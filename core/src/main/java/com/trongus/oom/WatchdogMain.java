package com.trongus.oom;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.monitor.ThresholdRiskAssessor;
import com.trongus.oom.monitor.MetricsHttpServer;
import com.trongus.oom.monitor.TlsConfig;
import com.trongus.oom.remote.TargetDescriptor;
import com.trongus.oom.remote.TargetRegistry;
import com.trongus.oom.remote.WatchdogDaemon;
import com.trongus.oom.test.OomSimulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application entry point and command-line driver for the OOM Watchdog agent.
 *
 * <h2>Design Rationale</h2>
 * <p>{@code WatchdogMain} acts as the primary runtime orchestrator. It parses CLI arguments,
 * initialises structured JUL logging via {@link WatchdogLogger},
 * builds an immutable {@link WatchdogConfig}, configures alert dispatching channels
 * (Console, File Logger, and optional QRadar Syslog), initialises diagnostics collection
 * and risk assessment engines, registers JVM shutdown hooks for graceful termination,
 * and launches background monitoring. Additionally, it supports an interactive test mode
 * utilizing {@link OomSimulator} to exercise alert escalation within a single JVM lifecycle.
 *
 * <h2>Supported Command-Line Arguments</h2>
 * <table border="1">
 *   <caption>Command-Line Arguments Reference</caption>
 *   <tr>
 *     <th>Option Flag</th>
 *     <th>Type / Format</th>
 *     <th>Default</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>{@code --warn-threshold}</td>
 *     <td>Double (0.0 &ndash; 1.0)</td>
 *     <td>{@code 0.80} (80%)</td>
 *     <td>Heap usage ratio required to trigger a {@code WARNING} alert level.
 *         In daemon mode, supplying this flag overrides the thresholds on
 *         <em>every</em> target (ignoring per-target values in
 *         {@code targets.properties}). Intended only for verifying LEEF syslog
 *         delivery — not for production use.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --crit-threshold}</td>
 *     <td>Double (0.0 &ndash; 1.0)</td>
 *     <td>{@code 0.90} (90%)</td>
 *     <td>Heap usage ratio required to trigger a {@code CRITICAL} alert and diagnostic dumps.
 *         In daemon mode, supplying this flag overrides the thresholds on
 *         <em>every</em> target (ignoring per-target values in
 *         {@code targets.properties}). Intended only for verifying LEEF syslog
 *         delivery — not for production use.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --gc-dump-threshold}</td>
 *     <td>Double (0.0 &ndash; 1.0)</td>
 *     <td>{@code -1} (disabled)</td>
 *     <td>GC overhead ratio that independently triggers a dump, regardless of heap alert level.
 *         Set to {@code -1} to disable.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --heap-dump-threshold}</td>
 *     <td>Double (0.0 &ndash; 1.0)</td>
 *     <td>{@code -1} (disabled)</td>
 *     <td>Heap usage ratio that independently triggers a dump, regardless of alert level.
 *         Set to {@code -1} to disable.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --gc-threshold}</td>
 *     <td>Double (0.0 &ndash; 1.0)</td>
 *     <td>{@code 0.50} (50%)</td>
 *     <td>Fraction of time spent in GC during a polling interval to trigger high GC overhead alert.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --poll-ms}</td>
 *     <td>Long (milliseconds)</td>
 *     <td>{@code 5000}</td>
 *     <td>Polling interval between successive JVM diagnostics snapshots.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --dump-dir}</td>
 *     <td>String (file path)</td>
 *     <td>{@code "./dumps"}</td>
 *     <td>Destination directory path where diagnostic dump files are written.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --dump-types}</td>
 *     <td>Comma-separated String</td>
 *     <td>(none)</td>
 *     <td>Comma-separated list of dump types: {@code HEAP}, {@code CORE}, {@code THREAD}, {@code CLASS_HISTOGRAM}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --log-file}</td>
 *     <td>String (file path)</td>
 *     <td>{@code "./oom-watchdog.log"}</td>
 *     <td>Output file path for local structured alert logging.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --log-level}</td>
 *     <td>{@code FINEST|FINE|CONFIG|INFO|WARNING|SEVERE}</td>
 *     <td>{@code INFO}</td>
 *     <td>Minimum log level for internal watchdog diagnostic output.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --qradar-host}</td>
 *     <td>String (hostname/IP)</td>
 *     <td>(empty / disabled)</td>
 *     <td>QRadar SIEM syslog receiver host. If omitted, QRadar forwarding is disabled.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --qradar-port}</td>
 *     <td>Integer (port number)</td>
 *     <td>{@code 514}</td>
 *     <td>Syslog port for QRadar event destination.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --qradar-tcp}</td>
 *     <td>Flag (boolean)</td>
 *     <td>{@code false} (UDP)</td>
 *     <td>When specified, uses TCP transport rather than UDP for QRadar syslog forwarding.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --daemon}</td>
 *     <td>Flag (boolean)</td>
 *     <td>{@code false}</td>
 *     <td>Enables daemon mode for monitoring multiple external JVM processes via JMX.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --targets-file}</td>
 *     <td>String (file path)</td>
 *     <td>{@code "./targets.properties"}</td>
 *     <td>Path to the properties file declaring external JVM targets in daemon mode.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --test-mode}</td>
 *     <td>Flag (boolean)</td>
 *     <td>{@code false}</td>
 *     <td>Launches the internal {@link OomSimulator} to intentionally induce an out-of-memory condition.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --test-leak-secs}</td>
 *     <td>Long (seconds)</td>
 *     <td>{@code 20}</td>
 *     <td>Duration of the slow-leak phase when executing under {@code --test-mode}.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code --help}</td>
 *     <td>Flag (boolean)</td>
 *     <td>{@code false}</td>
 *     <td>Displays the command-line usage manual and exits.</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 *   # Production monitoring with heap and thread dumps plus QRadar forwarding
 *   java -jar oom-watchdog.jar \
 *     --dump-types heap,thread \
 *     --qradar-host 192.168.1.100
 *
 *   # Run test simulator locally on a constrained heap
 *   java -Xmx64m -jar oom-watchdog.jar \
 *     --test-mode \
 *     --dump-types heap,thread,class_histogram \
 *     --warn-threshold 0.50 \
 *     --crit-threshold 0.70 \
 *     --poll-ms 1000
 *
 *   # Debug mode with fine-grained internal diagnostics
 *   java -jar oom-watchdog.jar --log-level FINE
 * }</pre>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.0
 * @since 1.0.0
 * @see com.trongus.oom.config.WatchdogConfig
 * @see com.trongus.oom.monitor.OomWatchdog
 * @see com.trongus.oom.remote.WatchdogDaemon
 * @see com.trongus.oom.remote.TargetRegistry
 * @see com.trongus.oom.test.OomSimulator
 * @see com.trongus.oom.logging.WatchdogLogger
 */
public final class WatchdogMain {

    private static final Logger LOG = WatchdogLogger.forClass(WatchdogMain.class);

    /**
     * Private constructor preventing instantiation of this static utility entry class.
     */
    private WatchdogMain() {}

    // =========================================================================
    // main
    // =========================================================================

    /**
     * Main entry point for the OOM Watchdog application.
     * <p>Parses command-line arguments, initialises structured logging, validates threshold
     * relationships, builds configuration, wires alert channels, instantiates the watchdog
     * engine, registers a JVM shutdown hook, and either initiates background monitoring or
     * launches the test simulator.
     *
     * @param args command-line arguments supplied to the JVM
     * @throws InterruptedException if the main thread is interrupted while waiting during execution
     */
    public static void main(String[] args) throws InterruptedException {

        // Parse CLI options
        CliArgs cli = CliArgs.parse(args);

        // Display help text and exit if requested
        if (cli.help) {
            printHelp();
            return;
        }

        // ── Logging ───────────────────────────────────────────────────────────
        // Initialise structured JUL logging before any other component is created
        WatchdogLogger.initialise(cli.logLevel);

        // ── Configuration ─────────────────────────────────────────────────────
        // Construct the immutable WatchdogConfig instance from parsed arguments
        WatchdogConfig.Builder cfgBuilder = WatchdogConfig.defaults()
            .warningHeapThreshold( cli.warnThreshold)
            .criticalHeapThreshold(cli.critThreshold)
            .gcOverheadThreshold(  cli.gcThreshold)
            .pollIntervalMs(       cli.pollMs)
            .heapDumpDirectory(    cli.dumpDir)
            .qradarHost(           cli.qradarHost)
            .qradarPort(           cli.qradarPort)
            .logLevel(             cli.logLevel)
            .gcDumpThreshold(      cli.gcDumpThreshold)
            .heapDumpThreshold(    cli.heapDumpThreshold);

        if (!cli.dumpTypes.isEmpty()) {
            cfgBuilder.dumpTypes(cli.dumpTypes);
        }

        WatchdogConfig config = cfgBuilder.build();

        // ── Shared QRadar Alert Channel (if configured) ───────────────────────
        List<AlertChannel> sharedChannels = new ArrayList<>();
        if (!config.getQradarHost().isEmpty()) {
            QRadarAlertChannel.Transport transport = cli.qradarTcp
                    ? QRadarAlertChannel.Transport.TCP
                    : QRadarAlertChannel.Transport.UDP;
            sharedChannels.add(new QRadarAlertChannel(config.getQradarHost(), config.getQradarPort(), transport));
            WatchdogLogger.config(LOG, "QRadar alerts \u2192 {0}:{1}/{2}",
                    config.getQradarHost(), config.getQradarPort(), transport);
        } else {
            WatchdogLogger.config(LOG, "QRadar disabled (use --qradar-host to enable).");
        }

        // ── Daemon Mode (Multi-Target JVM Monitoring) ─────────────────────────
        if (cli.daemon) {
            WatchdogLogger.info(LOG, "Launching in DAEMON mode using targets file: {0}", cli.targetsFile);

            // ── Threshold override guard ───────────────────────────────────────
            // When --warn-threshold or --crit-threshold is given on the CLI in daemon mode,
            // those values override EVERY target's per-target thresholds from targets.properties.
            // This exists solely to force an immediate alert/LEEF event for syslog testing
            // and must never be used in production.
            if (cli.thresholdsOverridden) {
                WatchdogLogger.warning(LOG,
                        "⚠ TEST OVERRIDE ACTIVE: --warn-threshold ({0,number,0.##}) and/or " +
                        "--crit-threshold ({1,number,0.##}) will override ALL target thresholds " +
                        "from {2}. Use this only to verify LEEF syslog delivery — not in production.",
                        cli.warnThreshold, cli.critThreshold, cli.targetsFile);
            }

            List<TargetDescriptor> targets;
            try {
                targets = TargetRegistry.loadFromFile(cli.targetsFile);
            } catch (Exception e) {
                WatchdogLogger.severe(LOG, e, "Failed to load targets file [{0}]: {1}",
                        cli.targetsFile, e.getMessage());
                System.err.println("[OomWatchdog] Error loading targets file: " + e.getMessage());
                System.exit(1);
                return;
            }

            if (targets.isEmpty()) {
                WatchdogLogger.severe(LOG, "No valid targets found in targets file: {0}", cli.targetsFile);
                System.err.println("[OomWatchdog] No targets found in " + cli.targetsFile);
                System.exit(1);
                return;
            }

            WatchdogDaemon daemon = new WatchdogDaemon(targets, config, sharedChannels, cli.thresholdsOverridden);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(daemon::stop, "oom-daemon-shutdown"));

            daemon.start();
            printDaemonSummary(targets, config, cli);

            // ── Metrics HTTPS server for daemon mode ──────────────────────────
            if (cli.metricsPort > 0) {
                try {
                    TlsConfig tls = buildTlsConfig(cli);
                    MetricsHttpServer metricsServer = new MetricsHttpServer(
                            null, daemon.getActiveWatchdogs(), daemon.getCollectors(),
                            daemon.getDumpApiUrls(),
                            cli.metricsPort, cli.metricsBindAll, tls);
                    metricsServer.start();
                    Runtime.getRuntime().addShutdownHook(
                            new Thread(metricsServer::stop, "oom-metrics-shutdown"));
                } catch (Exception e) {
                    WatchdogLogger.warning(LOG, e,
                            "Failed to start metrics HTTPS server on port {0}: {1}",
                            cli.metricsPort, e.getMessage());
                }
            }

            Thread.currentThread().join();
            return;
        }

        // ── Single-JVM Self-Monitoring Mode ───────────────────────────────────
        List<AlertChannel> channels = new ArrayList<>();
        channels.add(new ConsoleAlertChannel());
        channels.add(new FileLogAlertChannel(cli.logFile));
        channels.addAll(sharedChannels);

        // ── Wiring ────────────────────────────────────────────────────────────
        // Assemble core monitoring dependencies and instantiate OomWatchdog
        JvmDiagnosticsCollector collector = new MxBeanDiagnosticsCollector(config);
        RiskAssessor            assessor  = new ThresholdRiskAssessor(config);
        HeapDumpService         dumper    = new CompositeDumpService(config);

        OomWatchdog watchdog = new OomWatchdog(config, collector, assessor, channels, dumper);

        // Attach shutdown hook to gracefully stop watchdog worker thread on JVM exit
        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        // Launch watchdog monitoring loop
        watchdog.start();

        // ── Metrics HTTPS server (optional dashboard support) ─────────────────
        if (cli.metricsPort > 0) {
            try {
                TlsConfig tls = buildTlsConfig(cli);
                MetricsHttpServer metricsServer = new MetricsHttpServer(
                        watchdog, cli.metricsPort, cli.metricsBindAll, tls);
                metricsServer.start();
                Runtime.getRuntime().addShutdownHook(
                        new Thread(metricsServer::stop, "oom-metrics-shutdown"));
            } catch (Exception e) {
                WatchdogLogger.warning(LOG, e,
                        "Failed to start metrics HTTPS server on port {0}: {1}",
                        cli.metricsPort, e.getMessage());
            }
        }

        printStartupSummary(config, cli);

        // ── Test mode ─────────────────────────────────────────────────────────
        // If test mode is enabled, trigger OomSimulator
        if (cli.testMode) {
            runSimulator(cli.testLeakSecs);
            // The simulator's OOM will kill the JVM; we never reach Thread.join()
        }

        // Block main thread in production mode
        Thread.currentThread().join(); // block in production mode
    }

    // =========================================================================
    // TLS config builder
    // =========================================================================

    /**
     * Builds a {@link TlsConfig} from the parsed CLI arguments.
     *
     * <ul>
     *   <li>If {@code --metrics-no-tls} is set → {@link TlsConfig#disabled()}</li>
     *   <li>If {@code --metrics-cert} is set → {@link TlsConfig#fromKeystore(String, char[])}</li>
     *   <li>Otherwise → {@link TlsConfig#selfSigned()} (default, auto-generated cert)</li>
     * </ul>
     *
     * @param cli the parsed CLI arguments
     * @return the appropriate {@link TlsConfig}; never {@code null}
     */
    private static TlsConfig buildTlsConfig(CliArgs cli) {
        if (cli.metricsNoTls) {
            return TlsConfig.disabled();
        }
        if (cli.metricsCert != null && !cli.metricsCert.trim().isEmpty()) {
            char[] pwd = cli.metricsCertPassword != null
                    ? cli.metricsCertPassword.toCharArray()
                    : new char[0];
            return TlsConfig.fromKeystore(cli.metricsCert, pwd);
        }
        return TlsConfig.selfSigned();
    }

    // =========================================================================
    // Simulator launcher
    // =========================================================================

    /**
     * Launches the {@link OomSimulator} on a non-daemon worker thread to trigger memory exhaustion.
     *
     * @param leakSecs duration of the slow-leak phase in seconds
     */
    private static void runSimulator(long leakSecs) {
        OomSimulator simulator = new OomSimulator(
                leakSecs * 1000L,
                (phase, desc) -> WatchdogLogger.info(LOG,
                        "Simulator phase change: {0} \u2013 {1}", phase, desc));

        Thread simThread = new Thread(simulator, "oom-simulator");
        simThread.setDaemon(false); // keep JVM alive through OOM
        simThread.start();
        // main thread just waits – the simulator will cause the JVM to exit via OOM
    }

    // =========================================================================
    // Startup banner
    // =========================================================================

    /**
     * Logs summary table detailing active multi-target daemon configuration at startup.
     *
     * @param targets list of target descriptors
     * @param config  active base configuration
     * @param cli     parsed command-line arguments container
     */
    private static void printDaemonSummary(List<TargetDescriptor> targets, WatchdogConfig config, CliArgs cli) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║          OOM Watchdog Daemon – Multi-Target Mode         ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Targets file            : %s%n",      cli.targetsFile);
        System.out.printf( "║  Monitored targets count : %d%n",      targets.size());
        if (cli.thresholdsOverridden) {
            System.out.printf( "║  ⚠ TEST OVERRIDE          : warn=%.0f%% crit=%.0f%% applied to ALL targets%n",
                    config.getWarningHeapThreshold()  * 100,
                    config.getCriticalHeapThreshold() * 100);
            System.out.println("║    (per-target thresholds ignored; dumps suppressed)            ║");
            System.out.println("║    Use only to verify LEEF syslog delivery — NOT for production ║");
            for (TargetDescriptor t : targets) {
                System.out.printf( "║    \u2022 %-18s (warn=%.0f%% crit=%.0f%% OVERRIDDEN)%n",
                        t.getName(),
                        config.getWarningHeapThreshold()  * 100,
                        config.getCriticalHeapThreshold() * 100);
            }
        } else {
            for (TargetDescriptor t : targets) {
                System.out.printf( "║    \u2022 %-18s (warn=%.0f%% crit=%.0f%%)%n",
                        t.getName(), t.getWarnThreshold() * 100, t.getCritThreshold() * 100);
            }
        }
        System.out.printf( "║  Log level               : %s%n",      config.getLogLevel().getName());
        if (!config.getQradarHost().isEmpty()) {
            System.out.printf( "║  QRadar destination      : %s:%d/%s%n",
                    config.getQradarHost(), config.getQradarPort(), cli.qradarTcp ? "TCP" : "UDP");
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        if (cli.thresholdsOverridden) {
            System.out.println("⚠  WARNING: Threshold override is active — for LEEF syslog testing only.");
            System.out.println("   Remove --warn-threshold and --crit-threshold for normal monitoring.");
            System.out.println();
        }
        System.out.println("Press Ctrl-C to stop.");
    }

    /**
     * Logs a CONFIG-level summary table detailing active configuration options at startup.
     *
     * @param config active {@link WatchdogConfig} instance
     * @param cli    parsed command-line arguments container
     */
    private static void printStartupSummary(WatchdogConfig config, CliArgs cli) {
        // The startup banner is intentionally written to System.out (not the logger)
        // so it always appears regardless of the configured log level, mimicking the
        // conventional ASCII banner pattern for CLI tools.
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║               OOM Watchdog – Running                     ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Heap warning threshold  : %.0f%%%n",  config.getWarningHeapThreshold()  * 100);
        System.out.printf( "║  Heap critical threshold : %.0f%%%n",  config.getCriticalHeapThreshold() * 100);
        System.out.printf( "║  GC overhead threshold   : %.0f%%%n",  config.getGcOverheadThreshold()   * 100);
        System.out.printf( "║  Poll interval           : %d ms%n",   config.getPollIntervalMs());
        System.out.printf( "║  Dump directory          : %s%n",      config.getHeapDumpDirectory());
        System.out.printf( "║  Dump types              : %s%n",
                config.getDumpTypes().isEmpty() ? "(none)" : config.getDumpTypes().toString());
        System.out.printf( "║  Log file                : %s%n",      cli.logFile);
        System.out.printf( "║  Log level               : %s%n",      config.getLogLevel().getName());
        System.out.printf( "║  Test mode               : %s%n",      cli.testMode ? "YES" : "no");
        if (cli.metricsPort > 0) {
            String metricsHost = cli.metricsBindAll ? "0.0.0.0 (all interfaces)" : "127.0.0.1 (loopback only)";
            System.out.printf("║  Metrics bind address    : %s%n", metricsHost);
            System.out.printf("║  Metrics endpoint        : https://<host>:%d/metrics%n", cli.metricsPort);
            System.out.printf("║  Dashboard               : open dashboard.html, set URL to https://<host>:%d%n", cli.metricsPort);
        }
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Press Ctrl-C to stop.");
    }

    // =========================================================================
    // Help text
    // =========================================================================

    /**
     * Prints the complete command-line manual and option descriptions to standard output.
     */
    private static void printHelp() {
        System.out.println(
            "OOM Watchdog – proactive JVM out-of-memory detection and diagnostic capture\n"
          + "\n"
          + "Usage:\n"
          + "  java [-Xmx<n>m] -jar oom-watchdog.jar [options]\n"
          + "\n"
          + "Options:\n"
          + "  --warn-threshold <0.0-1.0>   Heap-usage ratio for WARNING  (default: 0.80)\n"
          + "                                In --daemon mode: overrides ALL targets, bypassing\n"
          + "                                per-target thresholds. Use only to test LEEF syslog.\n"
          + "  --crit-threshold <0.0-1.0>   Heap-usage ratio for CRITICAL (default: 0.90)\n"
          + "                                In --daemon mode: overrides ALL targets, bypassing\n"
          + "                                per-target thresholds. Use only to test LEEF syslog.\n"
          + "  --gc-threshold   <0.0-1.0>   GC-overhead ratio for WARNING (default: 0.50)\n"
          + "  --gc-dump-threshold <0.0-1.0> GC-overhead ratio that triggers an immediate dump (default: disabled)\n"
          + "  --heap-dump-threshold <0.0-1.0> Heap ratio that triggers an immediate dump (default: disabled)\n"
          + "  --poll-ms        <ms>         Poll interval in milliseconds (default: 5000)\n"
          + "  --dump-dir       <path>       Dump output directory (default: ./dumps)\n"
          + "  --dump-types     <types>      Comma-separated dump types at CRITICAL:\n"
          + "                                 HEAP           – .hprof heap snapshot\n"
          + "                                 CORE           – OS core/system dump (gcore)\n"
          + "                                 THREAD         – thread stack traces (.txt)\n"
          + "                                 CLASS_HISTOGRAM – class histogram (.txt)\n"
          + "                               Example: --dump-types heap,thread\n"
          + "                               Default: none\n"
          + "  --log-file       <path>       Alert log file (default: ./oom-watchdog.log)\n"
          + "  --log-level      <level>      Internal diagnostic log level:\n"
          + "                                 FINEST  – verbose trace output\n"
          + "                                 FINE    – debug-level events\n"
          + "                                 CONFIG  – startup and configuration info\n"
          + "                                 INFO    – normal operations (default)\n"
          + "                                 WARNING – degraded/recoverable only\n"
          + "                                 SEVERE  – fatal/unrecoverable only\n"
          + "  --daemon                      Run in daemon mode monitoring external JVMs via JMX\n"
          + "  --targets-file   <path>       Path to targets.properties in daemon mode (default: ./targets.properties)\n"
          + "  --qradar-host    <host>       QRadar syslog host (disables QRadar if omitted)\n"
          + "  --qradar-port    <port>       QRadar syslog port (default: 514)\n"
          + "  --qradar-tcp                  Use TCP instead of UDP for QRadar syslog\n"
          + "  --metrics-port   <port>       Expose JSON metrics endpoint for dashboard.html (disabled if omitted)\n"
          + "  --metrics-bind-all            Bind metrics server to 0.0.0.0 (all interfaces) so remote\n"
          + "                                browsers can reach it; default is loopback (127.0.0.1) only\n"
          + "  --test-mode                   Run OOM simulator to exercise all alert levels\n"
          + "  --test-leak-secs <s>          Slow-leak phase seconds in test mode (default: 20)\n"
          + "  --help                        Print this help and exit\n"
          + "\n"
          + "Quick test (triggers WARNING → CRITICAL → OOM_FIRING on a 64 MB heap):\n"
          + "  java -Xmx64m -jar oom-watchdog.jar \\\n"
          + "       --test-mode \\\n"
          + "       --dump-types heap,thread,class_histogram \\\n"
          + "       --warn-threshold 0.50 \\\n"
          + "       --crit-threshold 0.70 \\\n"
          + "       --poll-ms 1000 \\\n"
          + "       --test-leak-secs 10\n"
          + "\n"
          + "Verify LEEF syslog delivery (daemon mode — forces WARNING/CRITICAL on all targets):\n"
          + "  java -jar oom-watchdog.jar \\\n"
          + "       --daemon \\\n"
          + "       --targets-file /etc/oom-watchdog/targets.properties \\\n"
          + "       --qradar-host <syslog-host> \\\n"
          + "       --warn-threshold 0.01 \\\n"
          + "       --crit-threshold 0.02 \\\n"
          + "       --poll-ms 2000\n"
          + "  ⚠  This overrides ALL per-target thresholds. Remove these flags for normal monitoring.\n"
          + "\n"
          + "Dashboard (real-time browser UI — local):\n"
          + "  java -Xmx256m -jar oom-watchdog.jar --metrics-port 9090 --poll-ms 2000\n"
          + "  Then open dashboard.html and set server URL to https://localhost:9090\n"
          + "\n"
          + "Dashboard (remote host — accessible from another machine):\n"
          + "  java -Xmx256m -jar oom-watchdog.jar --metrics-port 9090 --metrics-bind-all --poll-ms 2000\n"
          + "  Then open dashboard.html and set server URL to https://<server-ip>:9090\n"
        );
    }

    // =========================================================================
    // CLI argument parser
    // =========================================================================

    /**
     * Lightweight, zero-dependency command-line argument parser and container.
     * <p>Parses key-value pairs ({@code --key value}) and boolean switches ({@code --flag}),
     * applying defaults and basic range validation.
     *
     * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
     * @version 1.7.13.0
     * @since 1.0.0
     * @see WatchdogMain
     */
    private static final class CliArgs {

        /** Heap usage ratio threshold for triggering a {@code WARNING} alert level. */
        double warnThreshold = 0.80;

        /** Heap usage ratio threshold for triggering a {@code CRITICAL} alert level. */
        double critThreshold = 0.90;

        /** GC overhead fraction threshold for triggering a {@code WARNING} alert level. */
        double gcThreshold = 0.50;

        /** GC overhead ratio that triggers an immediate dump; -1 = disabled. */
        double gcDumpThreshold = -1.0;

        /** Heap ratio that triggers an immediate dump (independent of crit); -1 = disabled. */
        double heapDumpThreshold = -1.0;

        /** Monitoring poll interval in milliseconds. */
        long pollMs = 5_000L;

        /** Target output directory path for diagnostic dump files. */
        String dumpDir = "./dumps";

        /** Set of diagnostic dump types enabled for execution at {@code CRITICAL} risk level. */
        Set<DumpType> dumpTypes = EnumSet.noneOf(DumpType.class);

        /** Destination file path for local alert logging. */
        String logFile = "./oom-watchdog.log";

        /** Minimum log level for internal watchdog diagnostic output. */
        Level logLevel = Level.INFO;

        /** QRadar syslog receiver hostname or IP address; empty if disabled. */
        String qradarHost = "";

        /** Syslog port for QRadar forwarding. */
        int qradarPort = 514;

        /** Whether to use TCP transport rather than UDP for QRadar syslog packets. */
        boolean qradarTcp = false;

        /** Whether to run in multi-target daemon mode. */
        boolean daemon = false;

        /** Path to targets.properties configuration file for daemon mode. */
        String targetsFile = "./targets.properties";

        /** Whether to run the test mode memory simulator instead of normal monitoring. */
        boolean testMode = false;

        /** Duration of the simulator slow-leak phase in seconds. */
        long testLeakSecs = 20L;

        /** TCP port for the metrics HTTP server; 0 = disabled. */
        int metricsPort = 0;

        /**
         * Path to a PKCS#12 or JKS keystore file for the metrics HTTPS server.
         * {@code null} = use auto-generated self-signed certificate (default).
         */
        String metricsCert = null;

        /**
         * Password for the {@link #metricsCert} keystore.
         * {@code null} when no keystore is configured.
         */
        String metricsCertPassword = null;

        /**
         * When {@code true}, the metrics server uses plain HTTP (TLS disabled).
         * Use only for loopback/internal-only deployments.
         */
        boolean metricsNoTls = false;

        /**
         * When {@code true}, the metrics server binds to all interfaces ({@code 0.0.0.0})
         * so it is reachable from remote hosts.  Defaults to {@code false} (loopback only).
         * Only set this when the metrics port is protected by a firewall or you are using TLS.
         */
        boolean metricsBindAll = false;

        /** Whether the help flag was requested. */
        boolean help = false;

        /**
         * {@code true} when {@code --warn-threshold} or {@code --crit-threshold} was explicitly
         * supplied on the command line.  In daemon mode this forces the CLI values onto
         * <em>every</em> target, overriding per-target thresholds from {@code targets.properties}.
         * Intended solely for testing LEEF syslog delivery — not for production use.
         */
        boolean thresholdsOverridden = false;

        /**
         * Parses command-line arguments into a populated {@code CliArgs} instance.
         *
         * @param args array of command-line argument strings
         * @return populated and validated {@code CliArgs} instance
         */
        static CliArgs parse(String[] args) {
            CliArgs c = new CliArgs();
            List<String> list = Arrays.asList(args);

            // Iterate through arguments and match recognized flags
            for (int i = 0; i < list.size(); i++) {
                String arg = list.get(i);
                switch (arg) {
                    case "--help":          c.help = true;                                    break;
                    case "--daemon":        c.daemon = true;                                  break;
                    case "--test-mode":     c.testMode = true;                                break;
                    case "--qradar-tcp":    c.qradarTcp = true;                               break;
                    case "--targets-file":  c.targetsFile    = nextStr(list, i++, arg);      break;
                    case "--warn-threshold":
                        c.warnThreshold = nextDouble(list, i++, arg);
                        c.thresholdsOverridden = true;
                        break;
                    case "--crit-threshold":
                        c.critThreshold = nextDouble(list, i++, arg);
                        c.thresholdsOverridden = true;
                        break;
                    case "--gc-threshold":      c.gcThreshold      = nextDouble(list, i++, arg); break;
                    case "--gc-dump-threshold": c.gcDumpThreshold  = nextDouble(list, i++, arg); break;
                    case "--heap-dump-threshold": c.heapDumpThreshold = nextDouble(list, i++, arg); break;
                    case "--poll-ms":       c.pollMs         = nextLong(list, i++, arg);     break;
                    case "--test-leak-secs":c.testLeakSecs   = nextLong(list, i++, arg);     break;
                    case "--qradar-port":   c.qradarPort     = nextInt(list, i++, arg);      break;
                    case "--metrics-port":  c.metricsPort    = nextInt(list, i++, arg);      break;
                    case "--metrics-cert":  c.metricsCert    = nextStr(list, i++, arg);      break;
                    case "--metrics-cert-password": c.metricsCertPassword = nextStr(list, i++, arg); break;
                    case "--metrics-no-tls":   c.metricsNoTls   = true;                      break;
                    case "--metrics-bind-all":  c.metricsBindAll = true;                      break;
                    case "--dump-dir":      c.dumpDir        = nextStr(list, i++, arg);      break;
                    case "--log-file":      c.logFile        = nextStr(list, i++, arg);      break;
                    case "--log-level":     c.logLevel       = parseLogLevel(nextStr(list, i++, arg)); break;
                    case "--qradar-host":   c.qradarHost     = nextStr(list, i++, arg);      break;
                    case "--dump-types":
                        c.dumpTypes = parseDumpTypes(nextStr(list, i++, arg));
                        break;
                    default:
                        if (arg.startsWith("--")) {
                            // Log to stderr before logger is initialised (it's init'd after parse)
                            System.err.println("[OomWatchdog] Unknown argument: " + arg
                                    + "  (use --help for usage)");
                        }
                }
            }

            // Validate threshold hierarchy (warnThreshold must be strictly lower than critThreshold)
            if (c.warnThreshold >= c.critThreshold) {
                System.err.println("[OomWatchdog] warn-threshold must be < crit-threshold; using defaults.");
                c.warnThreshold = 0.80;
                c.critThreshold = 0.90;
            }
            // Clamp poll interval to safe minimum
            if (c.pollMs < 100L) {
                System.err.println("[OomWatchdog] poll-ms must be >= 100; clamping to 100.");
                c.pollMs = 100L;
            }
            // Validate port range
            if (c.qradarPort < 1 || c.qradarPort > 65535) {
                System.err.println("[OomWatchdog] qradar-port must be 1–65535; using default 514.");
                c.qradarPort = 514;
            }
            // Validate threshold bounds (0.0, 1.0)
            if (c.warnThreshold <= 0.0 || c.warnThreshold >= 1.0) {
                System.err.println("[OomWatchdog] warn-threshold out of (0,1) range; using default 0.80.");
                c.warnThreshold = 0.80;
            }
            if (c.critThreshold <= 0.0 || c.critThreshold >= 1.0) {
                System.err.println("[OomWatchdog] crit-threshold out of (0,1) range; using default 0.90.");
                c.critThreshold = 0.90;
            }
            if (c.gcThreshold <= 0.0 || c.gcThreshold >= 1.0) {
                System.err.println("[OomWatchdog] gc-threshold out of (0,1) range; using default 0.50.");
                c.gcThreshold = 0.50;
            }
            // Validate dump directory is non-blank
            if (c.dumpDir == null || c.dumpDir.trim().isEmpty()) {
                System.err.println("[OomWatchdog] dump-dir must not be blank; using default ./dumps.");
                c.dumpDir = "./dumps";
            }
            return c;
        }

        /**
         * Parses a JUL log level name (case-insensitive).
         * Falls back to {@link Level#INFO} if the name is not recognised.
         *
         * @param name the level name string (e.g. {@code "FINE"}, {@code "info"})
         * @return the corresponding {@link Level}; never {@code null}
         */
        private static Level parseLogLevel(String name) {
            try {
                return Level.parse(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("[OomWatchdog] Unknown log-level '" + name
                        + "'; valid values: FINEST, FINE, CONFIG, INFO, WARNING, SEVERE. Using INFO.");
                return Level.INFO;
            }
        }

        /**
         * Parses a comma-separated string of dump type names into a set of {@link DumpType} enums.
         *
         * @param csv comma-separated string containing dump type identifiers
         * @return populated {@link Set} of parsed {@link DumpType} values
         */
        private static Set<DumpType> parseDumpTypes(String csv) {
            Set<DumpType> set = EnumSet.noneOf(DumpType.class);
            for (String token : csv.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                try {
                    set.add(DumpType.fromString(t));
                } catch (IllegalArgumentException e) {
                    System.err.println("[OomWatchdog] Unknown dump type '" + t
                            + "'. Valid types: HEAP, CORE, THREAD, CLASS_HISTOGRAM");
                }
            }
            return set;
        }

        // ── next-value helpers ─────────────────────────────────────────────────

        /**
         * Extracts the subsequent string argument following a key flag.
         *
         * @param args       list of argument strings
         * @param currentIdx index of the flag token
         * @param flag       name of the flag for error reporting
         * @return the argument value following the flag
         */
        private static String nextStr(List<String> args, int currentIdx, String flag) {
            int nextIdx = currentIdx + 1;
            if (nextIdx >= args.size()) {
                die(flag + " requires a value");
            }
            return args.get(nextIdx);
        }

        /**
         * Extracts and parses the subsequent argument as a double value.
         *
         * @param args       list of argument strings
         * @param currentIdx index of the flag token
         * @param flag       name of the flag for error reporting
         * @return parsed double value
         */
        private static double nextDouble(List<String> args, int currentIdx, String flag) {
            try { return Double.parseDouble(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires a decimal value"); return 0; }
        }

        /**
         * Extracts and parses the subsequent argument as a long value.
         *
         * @param args       list of argument strings
         * @param currentIdx index of the flag token
         * @param flag       name of the flag for error reporting
         * @return parsed long value
         */
        private static long nextLong(List<String> args, int currentIdx, String flag) {
            try { return Long.parseLong(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires an integer value"); return 0; }
        }

        /**
         * Extracts and parses the subsequent argument as an int value.
         *
         * @param args       list of argument strings
         * @param currentIdx index of the flag token
         * @param flag       name of the flag for error reporting
         * @return parsed int value
         */
        private static int nextInt(List<String> args, int currentIdx, String flag) {
            try { return Integer.parseInt(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires an integer value"); return 0; }
        }

        /**
         * Emits an error message and terminates the JVM due to invalid CLI arguments.
         *
         * @param msg description of the validation failure
         */
        private static void die(String msg) {
            System.err.println("[OomWatchdog] Argument error: " + msg);
            System.err.println("Use --help for usage.");
            System.exit(1);
        }
    }
}
