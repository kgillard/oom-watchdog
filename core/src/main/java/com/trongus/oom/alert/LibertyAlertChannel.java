package com.trongus.oom.alert;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers OOM Watchdog alerts into the WebSphere Liberty / Open Liberty log
 * infrastructure and optionally exposes a health indicator for MicroProfile Health.
 *
 * <h2>Liberty logging architecture</h2>
 * <p>Liberty routes all {@code java.util.logging} (JUL) records through its unified
 * logging pipeline.  Each log record appears in one or more of the following files
 * depending on the configured log format and level:
 * <ul>
 *   <li><strong>messages.log</strong> — contains {@code INFO} and above; the primary
 *       operator-facing log file, always present.</li>
 *   <li><strong>console.log</strong> — mirrors {@code messages.log} to the console
 *       output stream; present when Liberty is started as a foreground process.</li>
 *   <li><strong>trace.log</strong> — contains all levels including {@code FINE},
 *       {@code FINER}, {@code FINEST}; only created when tracing is enabled.</li>
 * </ul>
 *
 * <h2>Configuring {@code server.xml} for this channel</h2>
 * <p>Add the following to your Liberty {@code server.xml} to capture all
 * {@code com.trongus.oom} log output:
 * <pre>{@code
 * <logging traceSpecification="com.trongus.oom.*=all"
 *          messageFormat="ENHANCED"
 *          logDirectory="${server.output.dir}/logs" />
 * }</pre>
 * <p>For Liberty JSON logging (structured log ingestion by log aggregators):
 * <pre>{@code
 * <logging messageFormat="JSON"
 *          jsonFieldMappings="ibm_userDir:userDir,ibm_serverName:serverName"
 *          traceSpecification="com.trongus.oom.*=all" />
 * }</pre>
 *
 * <h2>Liberty JSON logging integration</h2>
 * <p>When Liberty is configured with {@code messageFormat="JSON"} or the environment
 * variable {@code WLP_LOGGING_MESSAGE_FORMAT=json}, each JUL record is emitted as a
 * single JSON object on one line in {@code messages.log}.  This channel embeds a
 * pre-formatted JSON fragment as the JUL log message text so that Liberty's JSON
 * logger captures all OOM Watchdog fields (heap %, risk level, GC overhead, etc.) as
 * top-level fields in the JSON output, making them directly queryable in Elastic,
 * Splunk, or IBM Log Analysis.
 *
 * <h2>MicroProfile Health integration</h2>
 * <p>The {@link #isHealthy()} method returns {@code true} when the last assessed risk
 * level is below {@code CRITICAL}.  Wire it into your MicroProfile Health check:
 * <pre>{@code
 * @Liveness
 * @ApplicationScoped
 * public class OomHealthCheck implements HealthCheck {
 *
 *     @Inject
 *     private LibertyAlertChannel oomChannel;
 *
 *     @Override
 *     public HealthCheckResponse call() {
 *         boolean healthy = oomChannel.isHealthy();
 *         return HealthCheckResponse.named("jvm-oom-risk")
 *                 .status(healthy)
 *                 .withData("riskLevel", oomChannel.getLastRiskLevel().name())
 *                 .build();
 *     }
 * }
 * }</pre>
 * <p>The Liberty {@code /health/live} endpoint will report {@code DOWN} automatically
 * when the JVM is at {@code CRITICAL} or {@code OOM_FIRING} risk level.
 *
 * <h2>Compatibility</h2>
 * <p>Compatible with WebSphere Liberty 8.5.5.x (minimum) and Open Liberty 22.x and
 * later.  No Liberty-specific classes are imported; integration relies solely on
 * standard {@code java.util.logging}, which Liberty intercepts at runtime.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.9
 * @since 1.2.0
 * @see AlertChannel
 * @see WasAlertChannel
 */
public final class LibertyAlertChannel implements AlertChannel {

    /** JUL logger for Liberty alert routing (intentionally uses the alert-routing name, not the diagnostic hierarchy). */
    private static final Logger LOG = Logger.getLogger("com.trongus.oom.LibertyAlert");

    /** Internal diagnostics logger for watchdog operational messages. */
    private static final Logger DIAG = WatchdogLogger.forClass(LibertyAlertChannel.class);

    /** Prefix for diagnostic {@code System.err} lines. */
    private static final String ERR_PREFIX = "[OomWatchdog][Liberty]";

    /**
     * Stores the most recent risk level so that {@link #isHealthy()} and
     * {@link #getLastRiskLevel()} are always consistent regardless of which thread
     * reads them.
     */
    private final AtomicReference<OomRiskLevel> lastRiskLevel =
            new AtomicReference<>(OomRiskLevel.OK);

    /**
     * Creates a {@code LibertyAlertChannel} using the default logger name
     * {@code com.trongus.oom.LibertyAlert}.
     */
    public LibertyAlertChannel() {
        // Liberty intercepts JUL at runtime; no explicit handler registration needed
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a structured JSON-fragment JUL log record at {@code Level.WARNING} or
     * {@code Level.SEVERE} and updates the internal health state.
     *
     * <p>Never throws: all exceptions are caught and reported to {@code System.err}.
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        try {
            lastRiskLevel.set(snapshot.getRiskLevel());

            Level  julLevel  = resolveLevel(snapshot.getRiskLevel());
            long   mb        = 1024L * 1024L;
            String timestamp = iso8601(snapshot.getTimestampMs());

            String process  = sanitise(snapshot.getProcessName());
            String notes    = sanitise(snapshot.getDiagnosisNotes());
            String dumpPath = snapshot.getHeapDumpPath() != null
                    ? sanitise(snapshot.getHeapDumpPath()) : "";

            // Build a JSON fragment as the log message text.
            // Liberty JSON logger lifts the "message" field verbatim — structured
            // key/value pairs inside the message are queryable in log aggregators.
            StringBuilder jsonMsg = new StringBuilder();
            jsonMsg.append("{");
            jsonMsg.append("\"oomRiskLevel\":\"").append(snapshot.getRiskLevel()).append("\",");
            jsonMsg.append("\"process\":\"").append(process).append("\",");
            jsonMsg.append("\"timestamp\":\"").append(timestamp).append("\",");
            jsonMsg.append("\"heapUsedMB\":").append(snapshot.getHeapUsedBytes() / mb).append(",");
            jsonMsg.append("\"heapMaxMB\":").append(snapshot.getHeapMaxBytes()   / mb).append(",");
            jsonMsg.append(String.format(Locale.US, "\"heapPct\":%.1f,", snapshot.getHeapUsedRatio() * 100));
            jsonMsg.append("\"nonHeapUsedMB\":").append(snapshot.getNonHeapUsedBytes() / mb).append(",");
            jsonMsg.append(String.format(Locale.US, "\"gcOverheadPct\":%.1f,", snapshot.getGcOverheadRatio() * 100));
            jsonMsg.append("\"totalGcTimeMs\":").append(snapshot.getTotalGcTimeMs()).append(",");
            jsonMsg.append("\"notes\":\"").append(notes).append("\"");
            if (!dumpPath.isEmpty()) {
                jsonMsg.append(",\"heapDump\":\"").append(dumpPath).append("\"");
            }
            jsonMsg.append("}");

            // Log record parameters enable Liberty's structured log fields
            LOG.log(julLevel, jsonMsg.toString(),
                    new Object[]{ snapshot.getHeapUsedRatio() * 100, snapshot.getRiskLevel().name() });

        } catch (Exception ex) {
            WatchdogLogger.warning(DIAG, ex, "Liberty alert() failed: {0}", ex.getMessage());
        }
    }

    /**
     * Returns {@code true} when the JVM is healthy — i.e. the last assessed risk level
     * is below {@code CRITICAL}.
     *
     * <p>Intended for use in a MicroProfile Health {@code HealthCheck} implementation
     * (see class-level Javadoc for a complete code snippet).
     *
     * @return {@code true} when risk is {@code OK} or {@code WARNING}
     */
    public boolean isHealthy() {
        return lastRiskLevel.get().ordinal() < OomRiskLevel.CRITICAL.ordinal();
    }

    /**
     * Returns the most recently assessed {@link OomRiskLevel}.
     *
     * <p>Returns {@code OomRiskLevel.OK} if {@link #alert(JvmSnapshot)} has not yet
     * been called.
     *
     * @return last risk level; never {@code null}
     */
    public OomRiskLevel getLastRiskLevel() {
        return lastRiskLevel.get();
    }

    /** {@inheritDoc} */
    @Override
    public String channelName() {
        return "Liberty(com.trongus.oom.LibertyAlert)";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Maps an {@link OomRiskLevel} to a JUL log level. */
    private static Level resolveLevel(OomRiskLevel risk) {
        switch (risk) {
            case OOM_FIRING:
            case CRITICAL: return Level.SEVERE;
            default:       return Level.WARNING;
        }
    }

    /** Returns an ISO-8601 timestamp string for the given epoch millisecond value. */
    private static String iso8601(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date(epochMs));
    }

    /**
     * Strips control characters (U+0000–U+001F, U+007F), pipe, and JSON-breaking
     * characters from free-text fields.
     *
     * @param input raw value; {@code null} returns {@code ""}
     * @return sanitised string safe for embedding inside a JSON string value
     */
    private static String sanitise(String input) {
        if (input == null) return "";
        // Replace control chars, pipe, and double-quote (which would break JSON)
        return input.replaceAll("[\\x00-\\x1F\\x7F|\"]", " ").trim();
    }
}
