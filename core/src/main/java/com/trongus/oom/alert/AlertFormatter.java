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
 * @version 1.3.0
 * @since 1.0.0
 * @see AlertChannel
 * @see FileLogAlertChannel
 * @see ConsoleAlertChannel
 * @see QRadarAlertChannel
 */
final class AlertFormatter {

    private AlertFormatter() {}

    private static final long MB = 1024L * 1024L;

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
        StringBuilder sb = new StringBuilder();
        sb.append(messages.get("section.banner")).append("\n");
        sb.append(String.format("  %-11s: %s%n",  messages.get("label.severity"),  snap.getRiskLevel()));
        if (snap.getTargetName() != null) {
            sb.append(String.format("  %-11s: %s%n",  "Target", sanitiseMultiLine(snap.getTargetName())));
        }
        sb.append(String.format("  %-11s: %s%n",  messages.get("label.process"),   sanitiseMultiLine(snap.getProcessName())));
        sb.append(String.format("  %-11s: %tc%n", messages.get("label.timestamp"), snap.getTimestampMs()));
        sb.append("\n").append(messages.get("section.heap")).append("\n");
        sb.append(String.format("  %-11s: %d %s%n", messages.get("label.used"),      snap.getHeapUsedBytes()      / MB, messages.get("label.unit.mb")));
        sb.append(String.format("  %-11s: %d %s%n", messages.get("label.committed"), snap.getHeapCommittedBytes() / MB, messages.get("label.unit.mb")));
        sb.append(String.format("  %-11s: %d %s%n", messages.get("label.max"),       snap.getHeapMaxBytes()       / MB, messages.get("label.unit.mb")));
        sb.append(String.format("  %-11s: %.1f%%%n", messages.get("label.usage"),    snap.getHeapUsedRatio() * 100));
        sb.append("\n").append(messages.get("section.nonheap")).append("\n");
        sb.append(String.format("  %-11s: %d %s%n", messages.get("label.used"), snap.getNonHeapUsedBytes() / MB, messages.get("label.unit.mb")));
        long nhMax = snap.getNonHeapMaxBytes();
        sb.append(String.format("  %-11s: %s%n", messages.get("label.max.plain"),
                nhMax < 0 ? messages.get("label.nonheap.unlimited") : (nhMax / MB) + " " + messages.get("label.unit.mb")));
        sb.append("\n").append(messages.get("section.pools")).append("\n");
        for (Map.Entry<String, Long> e : snap.getPoolUsedBytes().entrySet()) {
            sb.append(String.format("  %-40s : %d %s%n", e.getKey(), e.getValue() / MB, messages.get("label.unit.mb")));
        }
        sb.append("\n").append(messages.get("section.gc")).append("\n");
        if (snap.getGcCollectionCounts().isEmpty()) {
            sb.append("  ").append(messages.get("label.gc.none")).append("\n");
        } else {
            for (Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
                long time = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
                sb.append(String.format("  %-40s count=%-6d time=%d %s%n",
                        e.getKey(), e.getValue(), time, messages.get("label.unit.ms")));
            }
            sb.append(String.format("  %-13s: %d %s%n", messages.get("label.gc.total"),   snap.getTotalGcTimeMs(),              messages.get("label.unit.ms")));
            sb.append(String.format("  %-13s: %d %s%n", messages.get("label.gc.uptime"),   snap.getJvmUptimeMs(),                messages.get("label.unit.ms")));
            sb.append(String.format("  %-13s: %.1f%%%n", messages.get("label.gc.overhead"), snap.getGcOverheadRatio() * 100));
        }
        sb.append("\n").append(messages.get("section.leak")).append("\n");
        if (snap.getPostGcHeapUsedBytes() >= 0) {
            sb.append(String.format("  %-13s: %d %s%n", messages.get("label.leak.postgc"),
                    snap.getPostGcHeapUsedBytes() / MB, messages.get("label.unit.mb")));
        }
        double slope = snap.getPostGcHeapGrowthRatePerMs();
        if (Double.isNaN(slope)) {
            sb.append(String.format("  %-13s: %s%n", messages.get("label.leak.growth"), messages.get("label.leak.insufficient")));
        } else {
            double mbPerHour = slope * 3_600_000.0 / MB;
            sb.append(String.format("  %-13s: %.2f %s%n", messages.get("label.leak.growth"), mbPerHour, messages.get("label.leak.mphour")));
        }
        sb.append("\n").append(messages.get("section.diagnosis")).append("\n");
        sb.append("  ").append(sanitiseMultiLine(snap.getDiagnosisNotes())).append("\n");
        if (snap.getHeapDumpPath() != null) {
            sb.append("\n").append(messages.get("section.dump")).append("\n");
            sb.append("  ").append(messages.get("label.dump.path")).append(": ")
              .append(sanitiseMultiLine(snap.getHeapDumpPath())).append("\n");
        }
        sb.append(messages.get("section.footer")).append("\n");
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
