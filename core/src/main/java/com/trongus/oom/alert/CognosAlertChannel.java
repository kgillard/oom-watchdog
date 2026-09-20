package com.trongus.oom.alert;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers OOM Watchdog alerts to a dedicated Cognos Analytics-compatible log file
 * and to the JUL log infrastructure for the Application Tier Component (ATC),
 * Content Manager, or Gateway JVM being monitored.
 *
 * <h2>Cognos Analytics memory architecture</h2>
 * <p>A typical Cognos Analytics deployment runs three separate JVM processes, each of
 * which can exhaust heap independently:
 * <ul>
 *   <li><strong>Application Tier Component (ATC)</strong> — the report execution engine.
 *       Heap consumption is dominated by report dataset materialisation, PDF rendering,
 *       and active user sessions.  Large reports (&gt;100 MB result sets) frequently
 *       trigger OOM errors here.  Recommended heap: {@code -Xmx4g} to {@code -Xmx8g}.</li>
 *   <li><strong>Content Manager (CM)</strong> — manages report scheduling, security
 *       metadata, and the content store.  Heap is consumed by JDBC result caches and
 *       XML metadata trees.  Recommended heap: {@code -Xmx2g} to {@code -Xmx4g}.</li>
 *   <li><strong>Gateway / Dispatcher</strong> — HTTP request routing layer.  Heap
 *       consumption is normally low but can spike under session load.  Recommended heap:
 *       {@code -Xmx1g} to {@code -Xmx2g}.</li>
 * </ul>
 * <p>Run a separate {@code OomWatchdog} instance per JVM process, each configured with
 * a {@code CognosAlertChannel} using the appropriate {@code cognosComponent} name
 * ({@code "ATC"}, {@code "CM"}, {@code "Gateway"}).
 *
 * <h2>Common Cognos OOM causes</h2>
 * <ul>
 *   <li><strong>Large report datasets</strong> — Cognos materialises full result sets
 *       in heap before rendering.  Use report page limits and burst limits to constrain
 *       per-report memory consumption.</li>
 *   <li><strong>Session cache leaks</strong> — idle sessions are not always purged
 *       promptly.  Tune {@code sessionTimeout} and {@code maxActiveReportJobs} in
 *       {@code cogstartup.xml}.</li>
 *   <li><strong>Improper JDBC connection pooling</strong> — large prepared-statement
 *       caches retained per connection can exhaust Metaspace.  Limit pool sizes in
 *       {@code cogstartup.xml} under {@code &lt;param name="RSVP.maxConnections"&gt;}.</li>
 *   <li><strong>Log buffer accumulation</strong> — Cognos Log Server buffers log events
 *       in heap.  Flush intervals should be &lt;30 s in high-throughput deployments.</li>
 * </ul>
 *
 * <h2>Configuring the Cognos log file path</h2>
 * <p>Point the Cognos Log Server at the file written by this channel by adding a
 * {@code &lt;log&gt;} entry in {@code cognosservice.xml}:
 * <pre>{@code
 * <param name="Log.logServerPort">9300</param>
 * <param name="Log.logServerHost">localhost</param>
 * <param name="Log.localCaching">true</param>
 * <param name="Log.outputFile">/opt/IBM/cognos/analytics/logs/oom-watchdog-ATC.log</param>
 * }</pre>
 * <p>In {@code cogstartup.xml} for the ATC JVM, add the watchdog log directory under
 * {@code &lt;param name="Log.directory"&gt;} so the Cognos log rotation policy covers it.
 *
 * <h2>Cognos Audit logging integration</h2>
 * <p>The log entries written by this channel follow the Cognos structured log format
 * (pipe-delimited, ISO-8601 timestamp, component name, severity).  Cognos Audit DB
 * scripts that ingest {@code *.log} files will automatically pick up these entries
 * if the log file path is included in the Cognos Log Server scan directory.
 *
 * <h2>Recommended heap JVM arguments for Cognos components</h2>
 * <table border="1">
 *   <caption>Recommended heap JVM arguments for Cognos components</caption>
 *   <tr><th>Component</th><th>-Xms</th><th>-Xmx</th><th>-XX:MaxMetaspaceSize</th></tr>
 *   <tr><td>ATC</td>        <td>2g</td><td>8g</td><td>512m</td></tr>
 *   <tr><td>Content Mgr</td><td>1g</td><td>4g</td><td>256m</td></tr>
 *   <tr><td>Gateway</td>    <td>512m</td><td>2g</td><td>256m</td></tr>
 * </table>
 * <p>Add {@code -XX:+HeapDumpOnOutOfMemoryError} and
 * {@code -XX:HeapDumpPath=/opt/IBM/cognos/analytics/logs/heapdumps} to each JVM
 * argument list in the Cognos service configuration.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11
 * @since 1.2.0
 * @see AlertChannel
 * @see WasAlertChannel
 * @see LibertyAlertChannel
 */
public final class CognosAlertChannel implements AlertChannel {

    /** JUL logger for Cognos alert routing (intentionally uses the alert-routing name, not the diagnostic hierarchy). */
    private static final Logger LOG = Logger.getLogger("com.trongus.oom.CognosAlert");

    /** Internal diagnostics logger for watchdog operational messages. */
    private static final Logger DIAG = WatchdogLogger.forClass(CognosAlertChannel.class);

    /** Prefix for diagnostic {@code System.err} lines. */
    private static final String ERR_PREFIX = "[OomWatchdog][Cognos]";

    /** Pipe-delimiter used by Cognos structured log format. */
    private static final char DELIMITER = '|';

    private final String cognosComponent;
    private final String cognosServer;
    private final Path   logPath;

    /**
     * Creates a {@code CognosAlertChannel} that writes to the specified log file.
     *
     * <p>The {@code cognosComponent} label appears in every log entry and should match
     * the Cognos component being monitored: {@code "ATC"}, {@code "CM"}, or
     * {@code "Gateway"}.
     *
     * <p>The {@code logFilePath} must be a writable path.  Parent directories are
     * created automatically if they do not exist.
     *
     * @param cognosComponent Cognos component name; must not be {@code null} or blank
     * @param logFilePath     absolute or relative path to the Cognos alert log file;
     *                        must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code cognosComponent} or {@code logFilePath}
     *                                  is {@code null} or blank
     */
    public CognosAlertChannel(String cognosComponent, String logFilePath) {
        if (cognosComponent == null || cognosComponent.trim().isEmpty()) {
            throw new IllegalArgumentException("cognosComponent must not be null or blank");
        }
        if (logFilePath == null || logFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("logFilePath must not be null or blank");
        }
        this.cognosComponent = cognosComponent.trim();
        this.cognosServer    = resolveHostname();
        // Canonicalise the path to prevent path-traversal attacks when logFilePath is
        // derived from user-supplied configuration (e.g. targets.properties).
        java.nio.file.Path raw = Paths.get(logFilePath);
        java.nio.file.Path canonical;
        try {
            canonical = raw.toAbsolutePath().normalize();
        } catch (Exception ex) {
            WatchdogLogger.warning(DIAG, ex, "Could not normalise Cognos log path [{0}]: {1}",
                    logFilePath, ex.getMessage());
            canonical = raw;
        }
        this.logPath = canonical;

        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
        } catch (IOException ex) {
            WatchdogLogger.warning(DIAG, ex, "Failed to create Cognos log directory: {0}", ex.getMessage());
        }
    }

    /**
     * Creates a {@code CognosAlertChannel} targeting the ATC component with the default
     * log file {@code ./cognos-oom-alert.log}.
     */
    public CognosAlertChannel() {
        this("ATC", "./cognos-oom-alert.log");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes a pipe-delimited Cognos structured log entry to the configured log file
     * and emits a JUL record at the appropriate level.
     *
     * <p>Never throws: all exceptions are caught internally and reported to
     * {@code System.err}.
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        try {
            Level  julLevel  = resolveLevel(snapshot.getRiskLevel());
            long   mb        = 1024L * 1024L;
            String timestamp = iso8601(snapshot.getTimestampMs());

            String process  = sanitise(snapshot.getProcessName());
            String notes    = sanitise(snapshot.getDiagnosisNotes());
            String dumpPath = snapshot.getHeapDumpPath() != null
                    ? sanitise(snapshot.getHeapDumpPath()) : "";

            long reportEngineHeapMb = snapshot.getHeapUsedBytes() / mb;

            // Cognos structured log format (pipe-delimited, one line per event):
            // timestamp|component|server|severity|heapUsedMB|heapMaxMB|heapPct|gcOverheadPct|process|notes[|heapDump]
            StringBuilder line = new StringBuilder();
            line.append(timestamp).append(DELIMITER);
            line.append(sanitise(cognosComponent)).append(DELIMITER);
            line.append(sanitise(cognosServer)).append(DELIMITER);
            line.append(snapshot.getRiskLevel()).append(DELIMITER);
            line.append(reportEngineHeapMb).append(DELIMITER);
            line.append(snapshot.getHeapMaxBytes() / mb).append(DELIMITER);
            line.append(String.format(Locale.US, "%.1f", snapshot.getHeapUsedRatio() * 100)).append(DELIMITER);
            line.append(String.format(Locale.US, "%.1f", snapshot.getGcOverheadRatio() * 100)).append(DELIMITER);
            line.append(process).append(DELIMITER);
            line.append(notes);
            if (!dumpPath.isEmpty()) {
                line.append(DELIMITER).append(dumpPath);
            }
            line.append(System.lineSeparator());

            Files.write(logPath,
                    line.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // JUL record for Liberty/WAS application server hosting Cognos
            String julMsg = String.format(
                    "COGNOS_OOM_ALERT component=%s server=%s riskLevel=%s "
                    + "reportEngineHeapMb=%d heapPct=%.1f gcOverheadPct=%.1f "
                    + "process=%s notes=\"%s\"%s",
                    sanitise(cognosComponent), sanitise(cognosServer),
                    snapshot.getRiskLevel(),
                    reportEngineHeapMb,
                    snapshot.getHeapUsedRatio()   * 100,
                    snapshot.getGcOverheadRatio()  * 100,
                    process, notes,
                    dumpPath.isEmpty() ? "" : " heapDump=" + dumpPath);

            LOG.log(julLevel, julMsg);

        } catch (Exception ex) {
            WatchdogLogger.warning(DIAG, ex, "Cognos alert() failed: {0}", ex.getMessage());
        }
    }

    /** {@inheritDoc} */
    @Override
    public String channelName() {
        return String.format("Cognos(%s@%s → %s)", cognosComponent, cognosServer,
                logPath.toAbsolutePath());
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

    /** Resolves the local hostname, falling back to {@code "localhost"}. */
    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "localhost";
        }
    }

    /**
     * Strips control characters (U+0000–U+001F, U+007F), pipe, tab, and newline from
     * free-text fields so that the pipe-delimited Cognos log format is never corrupted.
     *
     * @param input raw value; {@code null} returns {@code ""}
     * @return sanitised string safe for embedding in a Cognos pipe-delimited record
     */
    private static String sanitise(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\x00-\\x1F\\x7F|]", " ").trim();
    }
}
