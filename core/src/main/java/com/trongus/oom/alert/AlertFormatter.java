package com.trongus.oom.alert;

import com.trongus.oom.model.JvmSnapshot;

import java.util.Map;

/**
 * Shared, stateless formatting utilities for OOM alert messages.
 *
 * <p>Both {@link #toHumanReadable(JvmSnapshot)} and {@link #toSingleLine(JvmSnapshot)}
 * sanitise all free-text fields (diagnosis notes, process name, heap dump path) before
 * embedding them so that special characters cannot break the output format.
 *
 * <p>This class is package-private; only alert-channel implementations within this
 * package may use it directly.
 *
 * @author Trongus OOM Watchdog
 * @version 1.0.0
 * @since 1.0.0
 * @see AlertChannel
 * @see FileLogAlertChannel
 * @see ConsoleAlertChannel
 * @see QRadarAlertChannel
 */
final class AlertFormatter {

    private AlertFormatter() {}

    private static final long MB = 1024L * 1024L;

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

    /**
     * Returns a multi-line, human-readable summary of the snapshot suitable
     * for console output, log files, or email bodies.
     *
     * <p>All free-text fields (process name, diagnosis notes, heap dump path) are
     * sanitised via {@link #sanitiseMultiLine(String)} before embedding.
     *
     * @param snap the snapshot to format; must not be {@code null}
     * @return formatted multi-line string
     */
    static String toHumanReadable(JvmSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== JVM OOM Alert ===\n");
        sb.append(String.format("  Severity   : %s%n",  snap.getRiskLevel()));
        sb.append(String.format("  Process    : %s%n",  sanitiseMultiLine(snap.getProcessName())));
        sb.append(String.format("  Timestamp  : %tc%n", snap.getTimestampMs()));
        sb.append("\n-- Heap --\n");
        sb.append(String.format("  Used       : %d MB%n",      snap.getHeapUsedBytes()      / MB));
        sb.append(String.format("  Committed  : %d MB%n",      snap.getHeapCommittedBytes() / MB));
        sb.append(String.format("  Max (-Xmx) : %d MB%n",      snap.getHeapMaxBytes()       / MB));
        sb.append(String.format("  Usage      : %.1f%%%n",      snap.getHeapUsedRatio() * 100));
        sb.append("\n-- Non-Heap (Metaspace / Code Cache) --\n");
        sb.append(String.format("  Used       : %d MB%n",  snap.getNonHeapUsedBytes() / MB));
        long nhMax = snap.getNonHeapMaxBytes();
        sb.append(String.format("  Max        : %s%n",
                nhMax < 0 ? "unlimited" : (nhMax / MB) + " MB"));
        sb.append("\n-- Memory Pools --\n");
        for (Map.Entry<String, Long> e : snap.getPoolUsedBytes().entrySet()) {
            sb.append(String.format("  %-40s : %d MB%n", e.getKey(), e.getValue() / MB));
        }
        sb.append("\n-- Garbage Collection --\n");
        if (snap.getGcCollectionCounts().isEmpty()) {
            sb.append("  No GC beans available.\n");
        } else {
            for (Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
                long time = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
                sb.append(String.format("  %-40s count=%-6d time=%d ms%n",
                        e.getKey(), e.getValue(), time));
            }
            sb.append(String.format("  Total GC time  : %d ms%n",   snap.getTotalGcTimeMs()));
            sb.append(String.format("  JVM uptime     : %d ms%n",   snap.getJvmUptimeMs()));
            sb.append(String.format("  GC overhead    : %.1f%%%n",  snap.getGcOverheadRatio() * 100));
        }
        sb.append("\n-- Leak Trend --\n");
        if (snap.getPostGcHeapUsedBytes() >= 0) {
            sb.append(String.format("  Post-GC heap   : %d MB%n",
                    snap.getPostGcHeapUsedBytes() / MB));
        }
        double slope = snap.getPostGcHeapGrowthRatePerMs();
        if (Double.isNaN(slope)) {
            sb.append("  Growth rate    : insufficient data\n");
        } else {
            double mbPerHour = slope * 3_600_000.0 / MB;
            sb.append(String.format("  Growth rate    : %.2f MB/hour%n", mbPerHour));
        }
        sb.append("\n-- Diagnosis --\n");
        sb.append("  ").append(sanitiseMultiLine(snap.getDiagnosisNotes())).append("\n");
        if (snap.getHeapDumpPath() != null) {
            sb.append("\n-- Heap Dump --\n");
            sb.append("  Path: ").append(sanitiseMultiLine(snap.getHeapDumpPath())).append("\n");
        }
        sb.append("=====================\n");
        return sb.toString();
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

        return String.format(
            "severity=%s process=%s heapUsedMB=%d heapMaxMB=%d heapPct=%.1f "
          + "nonHeapUsedMB=%d gcOverheadPct=%.1f totalGcTimeMs=%d postGcGrowth=%s "
          + "diagnosis=\"%s\"%s",
            snap.getRiskLevel(),
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
