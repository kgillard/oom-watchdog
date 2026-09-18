package com.trongus.oom.alert;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers OOM Watchdog alerts into the WebSphere Application Server (traditional WAS)
 * log infrastructure.
 *
 * <h2>How WAS routes {@code java.util.logging} output</h2>
 * <p>WAS fully integrates with {@code java.util.logging} (JUL).  Log records written via
 * JUL are intercepted by WAS's own logging handler and written to two destinations:
 * <ul>
 *   <li><strong>SystemOut.log</strong> — receives {@code INFO} and below.</li>
 *   <li><strong>SystemErr.log</strong> — receives {@code WARNING} and above.</li>
 * </ul>
 * This channel emits at {@code WARNING} for {@code WARNING} risk level and at
 * {@code SEVERE} for {@code CRITICAL} and {@code OOM_FIRING}, so all alerts appear in
 * {@code SystemErr.log} and are visible without custom filter configuration.
 *
 * <h2>Configuring the logger level in the WAS Admin Console</h2>
 * <ol>
 *   <li>Open the WAS Integrated Solutions Console.</li>
 *   <li>Navigate to <strong>Servers → Server Types → WebSphere application servers →
 *       &lt;your server&gt; → Troubleshooting → Logging and Tracing → Change Log
 *       Detail Levels</strong>.</li>
 *   <li>Add the entry: {@code com.trongus.oom.*=ALL}</li>
 *   <li>Apply and save.  No server restart is required for runtime tracing changes.</li>
 * </ol>
 *
 * <h2>FFDC incident ID format</h2>
 * <p>WAS FFDC correlates incidents across log files using a short incident ID.  This
 * channel derives a pseudo-incident ID from the snapshot timestamp and hash:
 * {@code <8-char-hex>}, e.g. {@code 3a7f1c2b}.  The ID appears in both the JUL log
 * record and the {@code System.err} structured line so that operators can search
 * {@code SystemErr.log} for the ID and cross-reference entries in the WAS FFDC directory
 * ({@code ${SERVER_LOG_ROOT}/ffdc/}).
 *
 * <h2>Enabling alongside FileLogAlertChannel</h2>
 * <pre>{@code
 * OomWatchdog watchdog = new OomWatchdog(config, collector, assessor,
 *     Arrays.asList(
 *         new WasAlertChannel(),
 *         new FileLogAlertChannel("/opt/IBM/WebSphere/AppServer/logs/myServer/oom-watchdog.log")
 *     ),
 *     dumpService);
 * }</pre>
 *
 * <h2>Security notes</h2>
 * <p>All free-text fields ({@code processName}, {@code diagnosisNotes},
 * {@code heapDumpPath}) are sanitised before being embedded in log messages.
 * Control characters (U+0000–U+001F, U+007F), pipe ({@code |}), and newlines are
 * replaced with a single space so that log-injection attacks cannot spoof additional
 * log lines or FFDC entries.  No user-supplied input is written to log messages without
 * prior sanitisation.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.6
 * @since 1.2.0
 * @see AlertChannel
 * @see FileLogAlertChannel
 */
public final class WasAlertChannel implements AlertChannel {

    /** JUL logger for WAS alert routing (intentionally uses the alert-routing name, not the diagnostic hierarchy). */
    private static final Logger LOG = Logger.getLogger("com.trongus.oom.WasAlert");

    /** Internal diagnostics logger for watchdog operational messages. */
    private static final Logger DIAG = WatchdogLogger.forClass(WasAlertChannel.class);

    /** Prefix written to {@code System.err} so WAS captures it in {@code SystemErr.log}. */
    private static final String STDERR_PREFIX = "[OomWatchdog][WAS]";

    /**
     * Creates a {@code WasAlertChannel} using the default logger name
     * {@code com.trongus.oom.WasAlert}.
     */
    public WasAlertChannel() {
        // no configuration required; WAS routes JUL automatically
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes a {@code java.util.logging} record at the appropriate level and a
     * structured single-line entry to {@code System.err}.  Both messages include an
     * FFDC-style incident ID derived from the snapshot timestamp for cross-referencing
     * in WAS log files.
     *
     * <p>Never throws: all exceptions are caught internally and reported to
     * {@code System.err}.
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        try {
            Level   julLevel  = resolveLevel(snapshot.getRiskLevel());
            String  incidentId = incidentId(snapshot);
            String  timestamp  = iso8601(snapshot.getTimestampMs());
            long    mb         = 1024L * 1024L;

            String process   = sanitise(snapshot.getProcessName());
            String notes     = sanitise(snapshot.getDiagnosisNotes());
            String dumpPath  = snapshot.getHeapDumpPath() != null
                    ? sanitise(snapshot.getHeapDumpPath()) : "none";

            // JUL record — routed to SystemOut/SystemErr by WAS
            String julMsg = String.format(
                    "OOM_ALERT incidentId=%s riskLevel=%s process=%s "
                    + "heapUsedMB=%d heapMaxMB=%d heapPct=%.1f "
                    + "gcOverheadPct=%.1f notes=\"%s\" heapDump=%s",
                    incidentId,
                    snapshot.getRiskLevel(),
                    process,
                    snapshot.getHeapUsedBytes() / mb,
                    snapshot.getHeapMaxBytes()   / mb,
                    snapshot.getHeapUsedRatio()  * 100,
                    snapshot.getGcOverheadRatio() * 100,
                    notes,
                    dumpPath);

            LOG.log(julLevel, julMsg);

            // Structured SystemErr line — WAS captures this in SystemErr.log
            String errLine = String.format(
                    "%s %s severity=%s incidentId=%s process=%s heapPct=%.1f notes=\"%s\"",
                    STDERR_PREFIX, timestamp,
                    snapshot.getRiskLevel(), incidentId, process,
                    snapshot.getHeapUsedRatio() * 100,
                    notes);
            System.err.println(errLine);

        } catch (Exception ex) {
            WatchdogLogger.warning(DIAG, ex, "WAS alert() failed: {0}", ex.getMessage());
        }
    }

    /** {@inheritDoc} */
    @Override
    public String channelName() {
        return "WAS(com.trongus.oom.WasAlert)";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps an {@link OomRiskLevel} to a JUL log level.
     * WARNING → {@code Level.WARNING}; CRITICAL/OOM_FIRING → {@code Level.SEVERE}.
     */
    private static Level resolveLevel(OomRiskLevel risk) {
        switch (risk) {
            case OOM_FIRING:
            case CRITICAL:   return Level.SEVERE;
            default:         return Level.WARNING;
        }
    }

    /**
     * Derives a short hex incident ID from the snapshot timestamp and its hash code.
     * Format: 8 lowercase hex characters, e.g. {@code 3a7f1c2b}.
     *
     * <p>This is intentionally simple — it provides a correlation handle across log
     * entries in the same WAS server, not a globally unique identifier.
     */
    private static String incidentId(JvmSnapshot snapshot) {
        long seed = snapshot.getTimestampMs() ^ (long) snapshot.hashCode();
        return String.format("%08x", (int) (seed & 0xFFFFFFFFL));
    }

    /** Returns an ISO-8601 timestamp string for the given epoch millisecond value. */
    private static String iso8601(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date(epochMs));
    }

    /**
     * Strips control characters (U+0000–U+001F, U+007F), pipe, tab, and newline from
     * the input so that log-injection is not possible.
     *
     * @param input raw value; {@code null} returns {@code ""}
     * @return sanitised string
     */
    private static String sanitise(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\x00-\\x1F\\x7F|]", " ").trim();
    }
}
