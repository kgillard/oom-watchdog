package com.trongus.oom.examples;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Arrays;
import java.util.List;

/**
 * Example 02 — Multiple alert channels: Console + File log + custom in-memory channel.
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Configuring several {@link AlertChannel}s simultaneously so every
 *       alert is dispatched to all destinations in parallel.</li>
 *   <li>Writing a <strong>custom {@code AlertChannel}</strong> implementation
 *       — the {@code PagerDutyAlertChannel} stub demonstrates how little code
 *       is required to integrate with any alerting back-end (PagerDuty, Slack,
 *       email, etc.).</li>
 *   <li>How {@link FileLogAlertChannel} creates or appends to a structured
 *       log file containing both a machine-readable single-line entry and a
 *       human-readable block for each alert.</li>
 *   <li>Using {@link QRadarAlertChannel} for LEEF 2.0 syslog forwarding,
 *       with the TCP transport chosen for guaranteed delivery.</li>
 * </ul>
 *
 * <h2>Alert channel contract</h2>
 * <p>Any class can become an alert channel by implementing the two-method
 * {@link AlertChannel} interface:
 * <pre>{@code
 * public void alert(JvmSnapshot snapshot)   // called on every WARNING / CRITICAL
 * public String channelName()               // human-readable identifier for logs
 * }</pre>
 *
 * <p>Implementations <strong>must not throw</strong> checked or unchecked exceptions
 * from {@code alert()}.  The watchdog catches exceptions per-channel so that a failure
 * in one channel (e.g. network timeout) never prevents other channels from receiving
 * the alert.
 *
 * <h2>Thread safety</h2>
 * <p>{@code alert()} may be called from the watchdog's internal scheduler thread
 * concurrently with your application code.  Each built-in channel is thread-safe.
 * Custom implementations must also be thread-safe (e.g. use {@code synchronized}
 * on shared mutable state or prefer immutable / volatile fields).
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.0.0
 * @since 1.0.0
 * @see AlertChannel
 * @see ConsoleAlertChannel
 * @see FileLogAlertChannel
 * @see QRadarAlertChannel
 */
public final class Example02AlertChannels {

    /** Utility class — construction is not permitted. */
    private Example02AlertChannels() {}

    // =========================================================================
    // Custom AlertChannel implementation
    // =========================================================================

    /**
     * Stub implementation of a PagerDuty-style alert channel.
     *
     * <p>In a production integration you would replace the {@code System.out}
     * calls with an HTTPS POST to the PagerDuty Events API v2.  The structure
     * and contract shown here — field extraction from {@link JvmSnapshot},
     * severity mapping, no exception propagation — are exactly what any real
     * implementation would look like.
     *
     * <p>Key implementation notes:
     * <ul>
     *   <li>The {@code routingKey} is injected at construction time so the channel
     *       is fully stateless after initialisation (safe for concurrent use).</li>
     *   <li>Only {@code CRITICAL} and above pages the on-call engineer; lower
     *       severity just sends an informational event.  This avoids alert fatigue.</li>
     *   <li>All free-text content ({@code diagnosisNotes}) is truncated to a safe
     *       length before being included in the outgoing payload to prevent
     *       oversized requests.</li>
     * </ul>
     *
     * @see AlertChannel
     */
    public static final class PagerDutyAlertChannel implements AlertChannel {

        /**
         * Maximum number of characters from {@code diagnosisNotes} included in the
         * PagerDuty event payload.  Keeps the payload within PagerDuty's documented
         * 512 KB limit and prevents runaway allocation from pathologically long notes.
         */
        private static final int MAX_NOTES_LENGTH = 1024;

        /**
         * PagerDuty service integration routing key (32-character hex string).
         * Never {@code null}; set once at construction time and never changed.
         */
        private final String routingKey;

        /**
         * Creates a new {@code PagerDutyAlertChannel} bound to the given routing key.
         *
         * @param routingKey the PagerDuty service integration routing key;
         *                   must be a non-null, non-blank 32-character string
         * @throws IllegalArgumentException if {@code routingKey} is null or blank
         */
        public PagerDutyAlertChannel(String routingKey) {
            if (routingKey == null || routingKey.trim().isEmpty()) {
                throw new IllegalArgumentException("routingKey must not be null or blank");
            }
            this.routingKey = routingKey;
        }

        /**
         * Forwards an OOM alert to PagerDuty.
         *
         * <p>Only {@link OomRiskLevel#CRITICAL} and {@link OomRiskLevel#OOM_FIRING}
         * trigger a high-urgency page; {@link OomRiskLevel#WARNING} sends a
         * low-urgency informational event to avoid on-call fatigue.
         *
         * <p>This implementation never throws; all exceptions are caught and logged
         * to {@code System.err} so that other channels continue to receive the alert.
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            try {
                String pdSeverity = snapshot.getRiskLevel().ordinal()
                        >= OomRiskLevel.CRITICAL.ordinal() ? "critical" : "warning";

                // Truncate notes to MAX_NOTES_LENGTH to keep the payload bounded
                String notes = snapshot.getDiagnosisNotes();
                if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
                    notes = notes.substring(0, MAX_NOTES_LENGTH) + "…[truncated]";
                }

                // In a real implementation, POST this JSON to PagerDuty Events API v2:
                // https://events.pagerduty.com/v2/enqueue
                //
                // For this example, we print the payload to demonstrate the structure.
                System.out.printf(
                    "[PagerDuty] WOULD POST → routing_key=%s severity=%s "
                    + "summary=\"JVM OOM %s: heap %.1f%% used\" notes_len=%d%n",
                    routingKey,
                    pdSeverity,
                    snapshot.getRiskLevel(),
                    snapshot.getHeapUsedRatio() * 100.0,
                    notes == null ? 0 : notes.length());

            } catch (Exception e) {
                // Never propagate exceptions from alert()
                System.err.println("[PagerDutyAlertChannel] Failed to send alert: "
                        + e.getMessage());
            }
        }

        /**
         * Returns a descriptive name for log output, including the first 6 characters
         * of the routing key for easy identification in multi-service deployments.
         *
         * @return channel identifier string
         */
        @Override
        public String channelName() {
            return "PagerDuty(" + routingKey.substring(0, Math.min(6, routingKey.length())) + "…)";
        }
    }

    // =========================================================================
    // main
    // =========================================================================

    /**
     * Demonstrates configuring three alert channels simultaneously.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        // ── Configuration ─────────────────────────────────────────────────────
        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.75)
                .criticalHeapThreshold(0.90)
                .pollIntervalMs(3_000L)
                .build();

        // ── Channel 1: Console (stdout, always included for visibility) ────────
        AlertChannel console = new ConsoleAlertChannel();

        // ── Channel 2: File log (structured single-line + human-readable block) ─
        // The file is created if it does not exist; entries are appended.
        // All writes are explicit UTF-8 so the file is readable on any platform.
        AlertChannel fileLog = new FileLogAlertChannel("./oom-watchdog.log");

        // ── Channel 3: Custom PagerDuty stub channel ───────────────────────────
        // Replace the routing key with your real PagerDuty integration key.
        AlertChannel pagerDuty = new PagerDutyAlertChannel("EXAMPLE_ROUTING_KEY_32CH_HERE");

        // ── Channel 4: QRadar LEEF 2.0 over TCP (optional) ────────────────────
        // Omit this block if you do not have a QRadar instance.
        // Using TCP here provides delivery confirmation; use Transport.UDP for
        // lower-latency fire-and-forget semantics.
        //
        // AlertChannel qradar = new QRadarAlertChannel(
        //         "siem.corp.com", 514, QRadarAlertChannel.Transport.TCP);

        List<AlertChannel> channels = Arrays.asList(console, fileLog, pagerDuty);

        // ── Wire and start ─────────────────────────────────────────────────────
        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config),
                new ThresholdRiskAssessor(config),
                channels,
                new CompositeDumpService(config));

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();
        System.out.println("[Example02] Watching with " + channels.size()
                + " channels. Log → ./oom-watchdog.log");

        Thread.currentThread().join();
    }
}
