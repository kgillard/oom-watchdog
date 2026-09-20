package com.trongus.oom.alert;

import com.trongus.oom.i18n.Messages;
import com.trongus.oom.model.JvmSnapshot;

import java.util.Locale;
import java.util.Map;

/**
 * Shared, stateless formatting utilities for OOM alert messages.
 *
 * <p>Both {@link #toHumanReadable(JvmSnapshot, Messages)} and
 * {@link #toSingleLine(JvmSnapshot)} sanitise all free-text fields
 * (diagnosis notes, process name, heap dump path) before embedding them
 * so that special characters cannot break the output format.
 *
 * <p>Section headings, labels, and status messages are resolved through a
 * {@link Messages} instance so that alert output is produced in the locale
 * configured on the watchdog.  When callers supply {@code null} for
 * {@code messages}, English defaults are used as a safe fallback.
 *
 * <p>This class is package-private; only alert-channel implementations within
 * this package may use it directly.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11
 * @since 1.0.0
 * @see AlertChannel
 * @see FileLogAlertChannel
 * @see ConsoleAlertChannel
 * @see QRadarAlertChannel
 */
final class AlertFormatter {

    private AlertFormatter() {}

    private static final long   MB   = 1024L * 1024L;
    private static final int    W    = 76;   // box interior width; full line = "│ " + W + " │" = W+4 chars

    /** English fallback — used when callers pass {@code null} for {@code messages}. */
    private static final Messages EN = new Messages(Locale.ENGLISH);

    /**
     * Sanitises a free-text field for safe embedding in human-readable multi-line output.
     * Strips ASCII control characters (except horizontal space) that could spoof
     * section boundaries or corrupt log parsing.
     *
     * @param input raw field value; {@code null} is treated as empty
     * @return sanitised string
     */
    private static String sanitiseMultiLine(String input) {
        if (input == null) return "";
        // Replace control chars (0x00-0x1F except 0x20 space) and DEL with space
        return input.replaceAll("[\\x00-\\x1F\\x7F]", " ");
    }

    /**
     * Sanitises a free-text field for safe embedding in a single-line structured record.
     * Removes newlines, carriage returns, and double-quote characters so that
     * key=value parsers are not confused.
     *
     * @param input raw field value; {@code null} is treated as empty
     * @return sanitised string (single-line, no quotes)
     */
    private static String sanitiseSingleLine(String input) {
        if (input == null) return "";
        return input.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }

    // ── overloads for back-compat with package-internal callers ──────────────

    /**
     * Returns a multi-line, human-readable summary using English defaults.
     *
     * @param snap the snapshot to format; must not be {@code null}
     * @return formatted multi-line string
     * @see #toHumanReadable(JvmSnapshot, Messages)
     */
    static String toHumanReadable(JvmSnapshot snap) {
        return toHumanReadable(snap, EN);
    }

    /**
     * Returns a multi-line, human-readable summary of the snapshot suitable
     * for console output, log files, or email bodies.
     *
     * <p>Section headings and labels are resolved from the supplied
     * {@link Messages} instance so that the output is produced in the
     * watchdog's configured locale.  All free-text fields (process name,
     * diagnosis notes, heap dump path) are sanitised via
     * {@link #sanitiseMultiLine(String)} before embedding.
     *
     * @param snap     the snapshot to format; must not be {@code null}
     * @param messages locale-aware message source; {@code null} falls back to English
     * @return formatted multi-line string
     */
    static String toHumanReadable(JvmSnapshot snap, Messages messages) {
        if (messages == null) messages = EN;

        String  risk      = snap.getRiskLevel().name();
        String  icon      = severityIcon(risk);
        String  process   = sanitiseMultiLine(snap.getProcessName());
        String  target    = snap.getTargetName() != null
                            ? "  target=" + sanitiseMultiLine(snap.getTargetName()) : "";
        long    heapUsed  = snap.getHeapUsedBytes()  / MB;
        long    heapMax   = snap.getHeapMaxBytes()   / MB;
        double  heapPct   = snap.getHeapUsedRatio()  * 100.0;
        long    nonHeap   = snap.getNonHeapUsedBytes() / MB;
        double  gcPct     = snap.getGcOverheadRatio() * 100.0;
        long    gcTotal   = snap.getTotalGcTimeMs();
        double  slope     = snap.getPostGcHeapGrowthRatePerMs();
        String  uptimeStr = formatUptime(snap.getJvmUptimeMs());

        // Width of the box interior.  Every content line is exactly COL chars
        // between the "| " and " |" delimiters.  The box itself is COL+4 wide.
        // We start at W but expand it if the data needs more room.
        int col = W;

        // -- pre-scan: compute dynamic column widths --------------------------
        // Heap/GC line: widen col if the rendered line would exceed current col.
        int usedW  = String.valueOf(heapUsed).length();
        int maxW   = String.valueOf(heapMax).length();
        int numW   = Math.max(usedW, maxW);           // common field width for both
        String heapBar  = heapBar((int) Math.round(heapPct));
        String heapPart = String.format("  Heap  %s / %s MB  %5.1f%%  %s",
                padLeft(String.valueOf(heapUsed), numW),
                padLeft(String.valueOf(heapMax),  numW),
                heapPct, heapBar);
        String gcPart   = String.format("GC  %.1f%%  total %d ms", gcPct, gcTotal);
        col = Math.max(col, heapPart.length() + 3 + gcPart.length());

        // Process/target/ts line
        String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(
                        new java.util.Date(snap.getTimestampMs()));
        String procLine = "  " + ts + "  " + process + target;
        col = Math.max(col, procLine.length());

        // Metaspace + uptime line
        String nhLine = String.format("  Metaspace  %d MB", nonHeap);
        String upLine = "Uptime  " + uptimeStr;
        col = Math.max(col, nhLine.length() + 4 + upLine.length());

        // Pools: compute max name width and max MB width from actual data
        int maxPoolNameW = 0;
        int maxPoolMbW   = 0;
        for (Map.Entry<String, Long> e : snap.getPoolUsedBytes().entrySet()) {
            long v = e.getValue() / MB;
            if (v <= 0) continue;
            maxPoolNameW = Math.max(maxPoolNameW, e.getKey().length());
            maxPoolMbW   = Math.max(maxPoolMbW,   String.valueOf(v).length());
        }
        if (maxPoolNameW > 0) {
            // "  " + name + "  " + mb + " MB"
            col = Math.max(col, 2 + maxPoolNameW + 2 + maxPoolMbW + 3);
        }

        // GC: compute max count width and max time width from actual data
        int maxGcNameW  = 0;
        int maxGcCntW   = 0;
        int maxGcTimeW  = 0;
        for (Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
            long t = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
            maxGcNameW  = Math.max(maxGcNameW,  e.getKey().length());
            maxGcCntW   = Math.max(maxGcCntW,   String.valueOf(e.getValue()).length());
            maxGcTimeW  = Math.max(maxGcTimeW,  String.valueOf(t).length());
        }
        if (maxGcNameW > 0) {
            // "  " + name + "  " + count + " x / " + time + " ms"
            col = Math.max(col, 2 + maxGcNameW + 2 + maxGcCntW + 7 + maxGcTimeW + 3);
        }

        // Diagnosis: wrap text to col-4 (2-space indent inside the box padding)
        String cleanNotes = sanitiseMultiLine(snap.getDiagnosisNotes())
                .replace('\u2013', '-').replace('\u2014', '-');
        java.util.List<String> diagLines = wrapDiagnosis(cleanNotes, col - 4);
        for (String line : diagLines) {
            col = Math.max(col, 2 + line.length());   // "  " + wrapped line
        }

        // Round col up to the nearest even number for visual symmetry
        if (col % 2 != 0) col++;

        // -- top border -------------------------------------------------------
        String header = String.format("[ %s  %s ]", icon, risk);
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(header)
          .append(repeat("-", Math.max(1, col + 2 - header.length()))).append("+\n");

        // -- process / target / timestamp -------------------------------------
        sb.append("| ").append(padTo(procLine, col)).append(" |\n");

        // -- heap bar ---------------------------------------------------------
        sb.append("| ").append(padTo(heapPart + "   " + gcPart, col)).append(" |\n");

        // -- non-heap + uptime ------------------------------------------------
        sb.append("| ").append(padTo(nhLine + "    " + upLine, col)).append(" |\n");

        // -- nursery row (only when data is available) -------------------------
        double nurseryPct = snap.getNurseryUsedRatio();
        if (!Double.isNaN(nurseryPct) && nurseryPct > 0.0) {
            long nurseryMB = snap.getNurseryUsedBytes() / MB;
            sb.append("| ").append(padTo(
                    String.format("  Young gen  %d MB  %.1f%%", nurseryMB, nurseryPct * 100.0), col))
              .append(" |\n");
        }

        // -- growth / leak row ------------------------------------------------
        if (!Double.isNaN(slope)) {
            double mbPerHour = slope * 3_600_000.0 / MB;
            String leakLine = mbPerHour > 0
                    ? String.format("  Growth  %+.1f MB/h  ^ possible leak", mbPerHour)
                    : String.format("  Growth  %.1f MB/h  (stable)", mbPerHour);
            sb.append("| ").append(padTo(leakLine, col)).append(" |\n");
        }

        // -- memory pools (only non-zero) -------------------------------------
        if (maxPoolNameW > 0) {
            sb.append(sectionBar("Pools", col));
            for (Map.Entry<String, Long> e : snap.getPoolUsedBytes().entrySet()) {
                long v = e.getValue() / MB;
                if (v <= 0) continue;
                // Left-align name, right-align MB value — both to computed widths
                String poolLine = "  " + padRight(e.getKey(), maxPoolNameW)
                        + "  " + padLeft(String.valueOf(v), maxPoolMbW) + " MB";
                sb.append("| ").append(padTo(poolLine, col)).append(" |\n");
            }
        }

        // -- GC collectors ----------------------------------------------------
        if (maxGcNameW > 0) {
            sb.append(sectionBar("GC", col));
            for (Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
                long t = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
                // Left-align name, right-align count and time to section-wide widths
                String gcLine = "  " + padRight(e.getKey(), maxGcNameW)
                        + "  " + padLeft(String.valueOf(e.getValue()), maxGcCntW)
                        + " x / " + padLeft(String.valueOf(t), maxGcTimeW) + " ms";
                sb.append("| ").append(padTo(gcLine, col)).append(" |\n");
            }
        }

        // -- diagnosis --------------------------------------------------------
        sb.append(sectionBar("Diagnosis", col));
        for (String line : diagLines) {
            sb.append("| ").append(padTo("  " + line, col)).append(" |\n");
        }

        // -- dump path (if present) -------------------------------------------
        if (snap.getHeapDumpPath() != null) {
            sb.append(sectionBar("Dump", col));
            sb.append("| ").append(padTo("  " + sanitiseMultiLine(snap.getHeapDumpPath()), col)).append(" |\n");
        }

        sb.append("+").append(repeat("-", col + 2)).append("+\n");
        return sb.toString();
    }

    /**
     * Builds a section-divider bar: "+-- LABEL ---...---+"
     * Total width is always {@code col + 4}.
     */
    private static String sectionBar(String label, int col) {
        String prefix = "+-- " + label + " ";
        return prefix + repeat("-", Math.max(1, col + 3 - prefix.length())) + "+\n";
    }

    /** Severity icon prefix for the box header — ASCII only for exact alignment. */
    private static String severityIcon(String risk) {
        switch (risk) {
            case "WARNING":    return "!";
            case "CRITICAL":   return "!!";
            case "OOM_FIRING": return "!!!";
            default:           return "OK";
        }
    }

    /** A simple 10-char heap usage bar using ASCII only. */
    private static String heapBar(int pct) {
        int filled = Math.min(10, (int) Math.round(pct / 10.0));
        return "[" + repeat("#", filled) + repeat(".", 10 - filled) + "]";
    }

    /** Formats JVM uptime ms into h/m/s string. */
    private static String formatUptime(long ms) {
        long s  = ms / 1000;
        long m  = s / 60;  s %= 60;
        long h  = m / 60;  m %= 60;
        if (h > 0)  return h + "h " + m + "m " + s + "s";
        if (m > 0)  return m + "m " + s + "s";
        return s + "s";
    }

    /**
     * Pads a string to exactly {@code width} characters, or truncates with ">"
     * if it is longer.  Used to fill the interior of a box column.
     */
    private static String padTo(String s, int width) {
        if (s.length() == width) return s;
        if (s.length() > width)  return s.substring(0, width - 1) + ">";
        return s + " ".repeat(width - s.length());
    }

    /** Right-pads {@code s} to {@code width} characters (left-aligns text). */
    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /** Left-pads {@code s} to {@code width} characters (right-aligns numbers). */
    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /** Repeats character {@code c} exactly {@code n} times. */
    private static String repeat(String c, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /**
     * Splits the diagnosis string into readable lines no wider than {@code maxWidth},
     * breaking on sentence boundaries first, then on spaces.
     * Sections prefixed with {@code [Assessment]}, {@code [Cause]}, {@code [GC]},
     * {@code [Heap]}, {@code [Leak]} are each placed on their own line.
     */
    private static java.util.List<String> wrapDiagnosis(String notes, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (notes == null || notes.trim().isEmpty()) {
            lines.add("-");
            return lines;
        }
        // Split on known section tags
        String[] parts = notes.split("(?=\\[(?:Assessment|Cause|GC|Heap|Leak|UNREACHABLE)])");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // word-wrap each section to maxWidth
            while (p.length() > maxWidth) {
                int cut = p.lastIndexOf(' ', maxWidth);
                if (cut <= 0) cut = maxWidth;
                lines.add(p.substring(0, cut));
                p = p.substring(cut).trim();
            }
            if (!p.isEmpty()) lines.add(p);
        }
        return lines;
    }

    /**
     * Returns a compact single-line summary suitable for syslog payloads or
     * structured log entries.
     *
     * <p>All free-text fields are sanitised via {@link #sanitiseSingleLine(String)}:
     * newlines are replaced with spaces and double-quotes are replaced with single-quotes
     * so that the {@code diagnosis="..."} field is never broken by content.
     *
     * @param snap the snapshot to format; must not be {@code null}
     * @return single-line key=value string
     */
    static String toSingleLine(JvmSnapshot snap) {
        double slope  = snap.getPostGcHeapGrowthRatePerMs();
        String growth = Double.isNaN(slope)
                ? "N/A"
                : String.format("%.2f MB/h", slope * 3_600_000.0 / MB);

        String targetPart = snap.getTargetName() != null
                ? "target=" + sanitiseSingleLine(snap.getTargetName()) + " "
                : "";

        return String.format(
            "severity=%s %sprocess=%s heapUsedMB=%d heapMaxMB=%d heapPct=%.1f "
          + "nonHeapUsedMB=%d gcOverheadPct=%.1f totalGcTimeMs=%d postGcGrowth=%s "
          + "diagnosis=\"%s\"%s",
            snap.getRiskLevel(),
            targetPart,
            sanitiseSingleLine(snap.getProcessName()),
            snap.getHeapUsedBytes()     / MB,
            snap.getHeapMaxBytes()      / MB,
            snap.getHeapUsedRatio()     * 100,
            snap.getNonHeapUsedBytes()  / MB,
            snap.getGcOverheadRatio()   * 100,
            snap.getTotalGcTimeMs(),
            growth,
            sanitiseSingleLine(snap.getDiagnosisNotes()),
            snap.getHeapDumpPath() != null
                    ? " heapDump=" + sanitiseSingleLine(snap.getHeapDumpPath()) : "");
    }
}
