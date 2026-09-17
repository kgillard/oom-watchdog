package com.ibm.oomwatchdog;

import com.ibm.oomwatchdog.alert.AlertChannel;
import com.ibm.oomwatchdog.alert.ConsoleAlertChannel;
import com.ibm.oomwatchdog.alert.FileLogAlertChannel;
import com.ibm.oomwatchdog.alert.QRadarAlertChannel;
import com.ibm.oomwatchdog.collector.JvmDiagnosticsCollector;
import com.ibm.oomwatchdog.collector.MxBeanDiagnosticsCollector;
import com.ibm.oomwatchdog.config.WatchdogConfig;
import com.ibm.oomwatchdog.dump.DumpType;
import com.ibm.oomwatchdog.dump.CompositeDumpService;
import com.ibm.oomwatchdog.dump.HeapDumpService;
import com.ibm.oomwatchdog.monitor.OomWatchdog;
import com.ibm.oomwatchdog.monitor.RiskAssessor;
import com.ibm.oomwatchdog.monitor.ThresholdRiskAssessor;
import com.ibm.oomwatchdog.test.OomSimulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for OomWatchdog.
 *
 * <h2>Usage</h2>
 * <pre>
 * java [-Xmx&lt;n&gt;m] -jar oom-watchdog.jar [options]
 *
 * Options:
 *   --warn-threshold &lt;0.0-1.0&gt;   Heap-usage ratio for WARNING  (default: 0.80)
 *   --crit-threshold &lt;0.0-1.0&gt;   Heap-usage ratio for CRITICAL (default: 0.90)
 *   --gc-threshold   &lt;0.0-1.0&gt;   GC-overhead ratio for WARNING (default: 0.50)
 *   --poll-ms        &lt;ms&gt;         Poll interval in milliseconds (default: 5000)
 *   --dump-dir       &lt;path&gt;       Output directory for dump files (default: ./dumps)
 *   --dump-types     &lt;types&gt;      Comma-separated list of dump types to produce at
 *                                 CRITICAL threshold. Choices (case-insensitive):
 *                                   HEAP, CORE, THREAD, CLASS_HISTOGRAM
 *                                 Example: --dump-types heap,thread
 *                                 Default: none (no dumps unless specified)
 *   --log-file       &lt;path&gt;       Alert log file path (default: ./oom-watchdog.log)
 *   --qradar-host    &lt;host&gt;       QRadar syslog host (omit to disable QRadar alerts)
 *   --qradar-port    &lt;port&gt;       QRadar syslog port (default: 514)
 *   --qradar-tcp                  Use TCP instead of UDP for QRadar syslog
 *   --test-mode                   Run the OomSimulator to exercise all alert levels
 *                                 (combine with -Xmx64m for a quick test)
 *   --test-leak-secs &lt;s&gt;          Slow-leak phase duration in test mode (default: 20)
 *   --help                        Print this help message and exit
 * </pre>
 *
 * <h2>Examples</h2>
 * <pre>
 *   # Monitor with heap + thread dumps and QRadar alerts
 *   java -jar oom-watchdog.jar \
 *     --dump-types heap,thread \
 *     --qradar-host 192.168.1.100
 *
 *   # Run the OOM simulator locally to test the watchdog end-to-end
 *   java -Xmx64m -jar oom-watchdog.jar \
 *     --test-mode \
 *     --dump-types heap,thread,class_histogram \
 *     --warn-threshold 0.50 \
 *     --crit-threshold 0.70 \
 *     --poll-ms 1000
 * </pre>
 */
public final class WatchdogMain {

    private WatchdogMain() {}

    // =========================================================================
    // main
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {

        CliArgs cli = CliArgs.parse(args);

        if (cli.help) {
            printHelp();
            return;
        }

        // ── Configuration ─────────────────────────────────────────────────────
        WatchdogConfig.Builder cfgBuilder = WatchdogConfig.defaults()
            .warningHeapThreshold( cli.warnThreshold)
            .criticalHeapThreshold(cli.critThreshold)
            .gcOverheadThreshold(  cli.gcThreshold)
            .pollIntervalMs(       cli.pollMs)
            .heapDumpDirectory(    cli.dumpDir)
            .qradarHost(           cli.qradarHost)
            .qradarPort(           cli.qradarPort);

        if (!cli.dumpTypes.isEmpty()) {
            cfgBuilder.dumpTypes(cli.dumpTypes);
        }

        WatchdogConfig config = cfgBuilder.build();

        // ── Alert channels ────────────────────────────────────────────────────
        List<AlertChannel> channels = new ArrayList<>();

        channels.add(new ConsoleAlertChannel());
        channels.add(new FileLogAlertChannel(cli.logFile));

        if (!cli.qradarHost.isEmpty()) {
            QRadarAlertChannel.Transport transport = cli.qradarTcp
                    ? QRadarAlertChannel.Transport.TCP
                    : QRadarAlertChannel.Transport.UDP;
            channels.add(new QRadarAlertChannel(cli.qradarHost, cli.qradarPort, transport));
            System.out.println("[OomWatchdog] QRadar alerts → "
                    + cli.qradarHost + ":" + cli.qradarPort + "/" + transport);
        } else {
            System.out.println("[OomWatchdog] QRadar disabled (use --qradar-host to enable).");
        }

        // ── Wiring ────────────────────────────────────────────────────────────
        JvmDiagnosticsCollector collector = new MxBeanDiagnosticsCollector(config);
        RiskAssessor            assessor  = new ThresholdRiskAssessor(config);
        HeapDumpService         dumper    = new CompositeDumpService(config);

        OomWatchdog watchdog = new OomWatchdog(config, collector, assessor, channels, dumper);

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();
        printStartupSummary(config, cli);

        // ── Test mode ─────────────────────────────────────────────────────────
        if (cli.testMode) {
            runSimulator(cli.testLeakSecs);
            // The simulator's OOM will kill the JVM; we never reach Thread.join()
        }

        Thread.currentThread().join(); // block in production mode
    }

    // =========================================================================
    // Simulator launcher
    // =========================================================================

    private static void runSimulator(long leakSecs) {
        OomSimulator simulator = new OomSimulator(
                leakSecs * 1000L,
                (phase, desc) -> System.out.println(
                        "[WatchdogMain] Simulator phase change: " + phase + " – " + desc));

        Thread simThread = new Thread(simulator, "oom-simulator");
        simThread.setDaemon(false); // keep JVM alive through OOM
        simThread.start();
        // main thread just waits – the simulator will cause the JVM to exit via OOM
    }

    // =========================================================================
    // Startup banner
    // =========================================================================

    private static void printStartupSummary(WatchdogConfig config, CliArgs cli) {
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
        System.out.printf( "║  Test mode               : %s%n",      cli.testMode ? "YES" : "no");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Press Ctrl-C to stop.");
    }

    // =========================================================================
    // Help text
    // =========================================================================

    private static void printHelp() {
        System.out.println(
            "OOM Watchdog – proactive JVM out-of-memory detection and diagnostic capture\n"
          + "\n"
          + "Usage:\n"
          + "  java [-Xmx<n>m] -jar oom-watchdog.jar [options]\n"
          + "\n"
          + "Options:\n"
          + "  --warn-threshold <0.0-1.0>   Heap-usage ratio for WARNING  (default: 0.80)\n"
          + "  --crit-threshold <0.0-1.0>   Heap-usage ratio for CRITICAL (default: 0.90)\n"
          + "  --gc-threshold   <0.0-1.0>   GC-overhead ratio for WARNING (default: 0.50)\n"
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
          + "  --qradar-host    <host>       QRadar syslog host (disables QRadar if omitted)\n"
          + "  --qradar-port    <port>       QRadar syslog port (default: 514)\n"
          + "  --qradar-tcp                  Use TCP instead of UDP for QRadar syslog\n"
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
        );
    }

    // =========================================================================
    // CLI argument parser
    // =========================================================================

    /**
     * Lightweight, zero-dependency argument parser.
     * Supports {@code --key value} and boolean flags {@code --flag}.
     */
    private static final class CliArgs {

        double        warnThreshold = 0.80;
        double        critThreshold = 0.90;
        double        gcThreshold   = 0.50;
        long          pollMs        = 5_000L;
        String        dumpDir       = "./dumps";
        Set<DumpType> dumpTypes     = EnumSet.noneOf(DumpType.class);
        String        logFile       = "./oom-watchdog.log";
        String        qradarHost    = "";
        int           qradarPort    = 514;
        boolean       qradarTcp     = false;
        boolean       testMode      = false;
        long          testLeakSecs  = 20L;
        boolean       help          = false;

        static CliArgs parse(String[] args) {
            CliArgs c = new CliArgs();
            List<String> list = Arrays.asList(args);

            for (int i = 0; i < list.size(); i++) {
                String arg = list.get(i);
                switch (arg) {
                    case "--help":          c.help = true;                                    break;
                    case "--test-mode":     c.testMode = true;                                break;
                    case "--qradar-tcp":    c.qradarTcp = true;                               break;
                    case "--warn-threshold":c.warnThreshold  = nextDouble(list, i++, arg);   break;
                    case "--crit-threshold":c.critThreshold  = nextDouble(list, i++, arg);   break;
                    case "--gc-threshold":  c.gcThreshold    = nextDouble(list, i++, arg);   break;
                    case "--poll-ms":       c.pollMs         = nextLong(list, i++, arg);     break;
                    case "--test-leak-secs":c.testLeakSecs   = nextLong(list, i++, arg);     break;
                    case "--qradar-port":   c.qradarPort     = nextInt(list, i++, arg);      break;
                    case "--dump-dir":      c.dumpDir        = nextStr(list, i++, arg);      break;
                    case "--log-file":      c.logFile        = nextStr(list, i++, arg);      break;
                    case "--qradar-host":   c.qradarHost     = nextStr(list, i++, arg);      break;
                    case "--dump-types":
                        c.dumpTypes = parseDumpTypes(nextStr(list, i++, arg));
                        break;
                    default:
                        if (arg.startsWith("--")) {
                            System.err.println("[OomWatchdog] Unknown argument: " + arg
                                    + "  (use --help for usage)");
                        }
                }
            }

            // Validate
            if (c.warnThreshold >= c.critThreshold) {
                System.err.println("[OomWatchdog] warn-threshold must be < crit-threshold; using defaults.");
                c.warnThreshold = 0.80;
                c.critThreshold = 0.90;
            }
            return c;
        }

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

        private static String nextStr(List<String> args, int currentIdx, String flag) {
            int nextIdx = currentIdx + 1;
            if (nextIdx >= args.size()) {
                die(flag + " requires a value");
            }
            return args.get(nextIdx);
        }

        private static double nextDouble(List<String> args, int currentIdx, String flag) {
            try { return Double.parseDouble(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires a decimal value"); return 0; }
        }

        private static long nextLong(List<String> args, int currentIdx, String flag) {
            try { return Long.parseLong(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires an integer value"); return 0; }
        }

        private static int nextInt(List<String> args, int currentIdx, String flag) {
            try { return Integer.parseInt(nextStr(args, currentIdx, flag)); }
            catch (NumberFormatException e) { die(flag + " requires an integer value"); return 0; }
        }

        private static void die(String msg) {
            System.err.println("[OomWatchdog] Argument error: " + msg);
            System.err.println("Use --help for usage.");
            System.exit(1);
        }
    }
}
