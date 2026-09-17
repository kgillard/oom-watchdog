package com.trongus.oom.examples;

import com.trongus.oom.alert.CognosAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example 10 — IBM Cognos Analytics full integration.
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Creating a {@link CognosAlertChannel} targeting the Cognos Application Tier
 *       Component (ATC) log file.</li>
 *   <li>Heap thresholds appropriate for Cognos Report Studio: large report datasets
 *       can consume hundreds of megabytes of heap in the ATC JVM.</li>
 *   <li>Running separate {@link OomWatchdog} instances per Cognos JVM process (ATC,
 *       Content Manager, Gateway) with different configurations.</li>
 *   <li>A {@link CognosStartupMonitor} inner class showing the startup pattern for
 *       each Cognos component.</li>
 * </ul>
 *
 * <h2>Cognos JVM architecture overview</h2>
 * <pre>
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │                  Cognos Analytics Deployment                │
 *   │                                                             │
 *   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
 *   │  │   ATC JVM    │  │   CM JVM     │  │  Gateway JVM     │  │
 *   │  │  -Xmx4g–8g   │  │  -Xmx2g–4g  │  │  -Xmx1g–2g       │  │
 *   │  │              │  │              │  │                  │  │
 *   │  │ Report Engine│  │Content Mgr   │  │HTTP Dispatcher   │  │
 *   │  │ Session Cache│  │JDBC Cache    │  │Session Routing   │  │
 *   │  │ PDF Renderer │  │Metadata Tree │  │Load Balancer     │  │
 *   │  │              │  │              │  │                  │  │
 *   │  │ OomWatchdog  │  │ OomWatchdog  │  │ OomWatchdog      │  │
 *   │  │ (ATC config) │  │ (CM config)  │  │ (GW config)      │  │
 *   │  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
 *   │         │                 │                    │            │
 *   └─────────┼─────────────────┼────────────────────┼────────────┘
 *             │                 │                    │
 *   CognosAlertChannel    CognosAlertChannel   CognosAlertChannel
 *   oom-watchdog-ATC.log  oom-watchdog-CM.log  oom-watchdog-GW.log
 * </pre>
 *
 * <h2>Report Studio heap sizing guidance</h2>
 * <p>IBM Cognos Report Studio generates PDF reports by materialising the full result
 * set in heap.  A 100 000-row report with 20 columns at 50 bytes per cell consumes
 * ~100 MB of raw data in the ATC heap before rendering overhead.  Configure:
 * <ul>
 *   <li>{@code -Xmx} at least 4× the expected maximum report size.</li>
 *   <li>{@code RSVP.maxConnections} in {@code cogstartup.xml} to limit concurrent
 *       report executions and cap peak heap usage.</li>
 *   <li>Report page limits in the Cognos administration portal to prevent runaway
 *       single reports from exhausting the heap.</li>
 * </ul>
 *
 * <h2>cognosservice.xml log file configuration</h2>
 * <pre>{@code
 * <!-- In cognosservice.xml, point the Cognos Log Server at the watchdog log: -->
 * <param name="Log.outputFile">
 *   /opt/IBM/cognos/analytics/logs/oom-watchdog-ATC.log
 * </param>
 * <param name="Log.localCaching">true</param>
 * <param name="Log.flushInterval">30</param>
 * }</pre>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.2.0
 * @since 1.2.0
 * @see CognosAlertChannel
 * @see WatchdogConfig
 */
public final class Example10CognosIntegration {

    /** Utility class — construction is not permitted. */
    private Example10CognosIntegration() {}

    // =========================================================================
    // CognosStartupMonitor — per-component watchdog manager
    // =========================================================================

    /**
     * Starts and manages an OOM Watchdog for a single Cognos JVM component.
     *
     * <p>Create one {@code CognosStartupMonitor} per Cognos JVM process.  The Cognos
     * service startup script ({@code cogserver.sh} or Windows service) should invoke
     * {@link #start()} on JVM initialisation and {@link #stop()} on JVM shutdown.
     */
    public static final class CognosStartupMonitor {

        /** The Cognos component name, e.g. {@code "ATC"}, {@code "CM"}, {@code "Gateway"}. */
        private final String cognosComponent;

        /** The Cognos alert log file path for this component. */
        private final String logFilePath;

        /** Configuration tuned for this component. */
        private final WatchdogConfig config;

        /** The running watchdog, or {@code null} if not yet started. */
        private final AtomicReference<OomWatchdog> watchdog = new AtomicReference<>();

        /**
         * Creates a {@code CognosStartupMonitor} for a given Cognos component.
         *
         * @param cognosComponent component name ({@code "ATC"}, {@code "CM"},
         *                        {@code "Gateway"}); must not be null or blank
         * @param logFilePath     absolute path to the Cognos alert log file; must not be
         *                        null or blank
         * @param config          pre-built {@link WatchdogConfig} tuned for this component
         */
        public CognosStartupMonitor(String cognosComponent, String logFilePath,
                WatchdogConfig config) {
            if (cognosComponent == null || cognosComponent.trim().isEmpty()) {
                throw new IllegalArgumentException("cognosComponent must not be null or blank");
            }
            if (logFilePath == null || logFilePath.trim().isEmpty()) {
                throw new IllegalArgumentException("logFilePath must not be null or blank");
            }
            if (config == null) {
                throw new IllegalArgumentException("config must not be null");
            }
            this.cognosComponent = cognosComponent;
            this.logFilePath     = logFilePath;
            this.config          = config;
        }

        /**
         * Starts the OOM Watchdog for this Cognos component.
         *
         * <p>Call this from the Cognos service startup hook or from a
         * {@code ServletContextListener} if Cognos is deployed inside a WAS/Liberty
         * application server.
         */
        public void start() {
            CognosAlertChannel channel = new CognosAlertChannel(cognosComponent, logFilePath);

            OomWatchdog wd = new OomWatchdog(
                    config,
                    new MxBeanDiagnosticsCollector(config),
                    new ThresholdRiskAssessor(config),
                    Collections.singletonList(channel),
                    new CompositeDumpService(config));

            wd.start();
            watchdog.set(wd);

            // Shutdown hook ensures the watchdog stops even on SIGTERM
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                OomWatchdog w = watchdog.get();
                if (w != null) { w.stop(); }
            }, "OomWatchdog-Cognos-" + cognosComponent + "-Shutdown"));

            System.out.printf("[Example10] Cognos OOM Watchdog started for component=%s log=%s%n",
                    cognosComponent, logFilePath);
        }

        /**
         * Stops the OOM Watchdog for this Cognos component.
         *
         * <p>Call this from the Cognos service shutdown hook or application
         * {@code contextDestroyed}.
         */
        public void stop() {
            OomWatchdog wd = watchdog.getAndSet(null);
            if (wd != null) {
                wd.stop();
                System.out.printf("[Example10] Cognos OOM Watchdog stopped for component=%s%n",
                        cognosComponent);
            }
        }

        /** @return the Cognos component name */
        public String getCognosComponent() { return cognosComponent; }
    }

    // =========================================================================
    // Factory helpers — pre-configured monitors per Cognos component
    // =========================================================================

    /**
     * Creates a pre-configured {@link CognosStartupMonitor} for the ATC JVM.
     *
     * <p>ATC configuration uses aggressive heap thresholds because large report
     * executions can drive heap usage from 20 % to 95 % in seconds.  A shorter
     * poll interval (15 s) catches spikes before they become OOM events.
     *
     * @param logDir directory where Cognos log files are written (e.g.
     *               {@code /opt/IBM/cognos/analytics/logs})
     * @return configured monitor for ATC; never {@code null}
     */
    public static CognosStartupMonitor atcMonitor(String logDir) {
        WatchdogConfig config = WatchdogConfig.defaults()
                // ATC: warn at 70 % — large reports can grow heap quickly
                .warningHeapThreshold(0.70)
                // ATC: critical at 85 % — 15 % headroom before OOM
                .criticalHeapThreshold(0.85)
                // 15-second poll: catches rapid heap spikes from report execution
                .pollIntervalMs(15_000L)
                // 4 × 15 s = 1-minute sustained growth window for leak detection
                .leakDetectionWindowSize(4)
                // GC overhead: flag at 45 % — ATC GC pressure is normal, spikes mean trouble
                .gcOverheadThreshold(0.45)
                // Heap dumps for ATC: both heap (MAT analysis) and thread (for deadlocks)
                .heapDumpDirectory(logDir + "/heapdumps/atc")
                .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
                .build();
        return new CognosStartupMonitor("ATC",
                logDir + "/oom-watchdog-ATC.log", config);
    }

    /**
     * Creates a pre-configured {@link CognosStartupMonitor} for the Content Manager JVM.
     *
     * <p>CM has a more stable heap profile than ATC; gradual JDBC cache growth is the
     * primary OOM risk.  A 30-second poll and leak-detection window are sufficient.
     *
     * @param logDir directory where Cognos log files are written
     * @return configured monitor for CM; never {@code null}
     */
    public static CognosStartupMonitor contentManagerMonitor(String logDir) {
        WatchdogConfig config = WatchdogConfig.defaults()
                // CM: warn at 75 % — JDBC cache growth is gradual
                .warningHeapThreshold(0.75)
                // CM: critical at 88 %
                .criticalHeapThreshold(0.88)
                // 30-second poll is adequate for CM's gradual heap growth pattern
                .pollIntervalMs(30_000L)
                .leakDetectionWindowSize(5)
                .gcOverheadThreshold(0.40)
                // Thread dump only for CM; heap dump analysis on WAS/Liberty is expensive
                .heapDumpDirectory(logDir + "/heapdumps/cm")
                .dumpTypes(EnumSet.of(DumpType.THREAD))
                .build();
        return new CognosStartupMonitor("CM",
                logDir + "/oom-watchdog-CM.log", config);
    }

    /**
     * Creates a pre-configured {@link CognosStartupMonitor} for the Gateway / Dispatcher
     * JVM.
     *
     * <p>The Gateway JVM has the smallest heap and the lowest memory pressure.  Standard
     * defaults apply; the poll interval is set to 30 seconds.
     *
     * @param logDir directory where Cognos log files are written
     * @return configured monitor for Gateway; never {@code null}
     */
    public static CognosStartupMonitor gatewayMonitor(String logDir) {
        WatchdogConfig config = WatchdogConfig.defaults()
                // Gateway: standard thresholds
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .pollIntervalMs(30_000L)
                .leakDetectionWindowSize(5)
                .gcOverheadThreshold(0.50)
                .heapDumpDirectory(logDir + "/heapdumps/gateway")
                .dumpTypes(EnumSet.of(DumpType.THREAD))
                .build();
        return new CognosStartupMonitor("Gateway",
                logDir + "/oom-watchdog-Gateway.log", config);
    }

    // =========================================================================
    // main — demonstration
    // =========================================================================

    /**
     * Demonstrates starting separate OOM Watchdog monitors for all three Cognos JVM
     * components, simulating a short monitoring period, and stopping them cleanly.
     *
     * <p>In a real Cognos deployment, each monitor would run in the JVM of its
     * respective Cognos component — not all three in the same JVM as shown here.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        String logDir = "./cognos-logs";

        // Create one monitor per Cognos component
        CognosStartupMonitor atcMonitor = atcMonitor(logDir);
        CognosStartupMonitor cmMonitor  = contentManagerMonitor(logDir);
        CognosStartupMonitor gwMonitor  = gatewayMonitor(logDir);

        // Start all three (in a real deployment each runs in its own JVM)
        atcMonitor.start();
        cmMonitor.start();
        gwMonitor.start();

        System.out.println("[Example10] All three Cognos component watchdogs running.");
        System.out.println("[Example10] Monitoring for 15 seconds...");
        Thread.sleep(15_000L);

        // Stop all three cleanly
        atcMonitor.stop();
        cmMonitor.stop();
        gwMonitor.stop();

        System.out.println("[Example10] Done. Check " + logDir + " for alert log files.");
    }
}
