package com.trongus.oom.harness;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.monitor.ThresholdRiskAssessor;
import com.trongus.oom.platform.JvmPlatform;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Self-contained test harness and automated verification runner for {@link OomWatchdog}.
 *
 * <h2>Design Rationale</h2>
 * <p>{@code TestHarnessMain} coordinates an end-to-end integration test of the watchdog
 * subsystem. It inspects the host JVM environment via {@link JvmPlatform}, constructs an
 * aggressive in-memory testing configuration (low thresholds, short polling interval, multiple
 * dump types), initializes an in-memory alert observer ({@link HarnessAlertRecorder}) alongside
 * standard console and file loggers, and invokes runtime bytecode compilation via
 * {@link DynamicOomClassGenerator} (falling back to {@link BuiltInHeapExhauster} when a JDK
 * compiler is absent).
 *
 * <h3>Execution Flow</h3>
 * <ol>
 *   <li><b>Platform Diagnostics:</b> Prints a detailed summary of detected JVM vendor, runtime version,
 *       and execution platform capabilities (HotSpot, IBM OpenJ9, GraalVM Native Image).</li>
 *   <li><b>Test Configuration:</b> Instantiates a customized {@link WatchdogConfig} with aggressive
 *       thresholds (45% warn, 65% critical) to accelerate test execution.</li>
 *   <li><b>Watchdog Initialisation:</b> Wires diagnostic collectors, risk assessors, alert channels,
 *       and composite dumpers, starting the watchdog thread and registering a shutdown hook.</li>
 *   <li><b>Dynamic Code Generation &amp; Execution:</b> Compiles {@code HeapExhauster} dynamically
 *       at runtime or falls back to built-in allocation, driving heap memory usage past warning and
 *       critical thresholds until {@link OutOfMemoryError} occurs.</li>
 *   <li><b>Verification &amp; Reporting:</b> Inspects recorded alert metrics and diagnostic dump file
 *       paths via {@link HarnessAlertRecorder} to verify watchdog effectiveness before JVM termination.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>Run with a constrained maximum heap limit (e.g., 96&nbsp;MB) to cycle through all alert stages rapidly:
 * <pre>{@code
 *   java -Xmx96m -jar test-harness.jar
 * }</pre>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.6
 * @since 1.0.0
 * @see com.trongus.oom.monitor.OomWatchdog
 * @see com.trongus.oom.harness.HarnessAlertRecorder
 * @see com.trongus.oom.harness.DynamicOomClassGenerator
 * @see com.trongus.oom.harness.BuiltInHeapExhauster
 */
public final class TestHarnessMain {

    /**
     * Private constructor to prevent direct instantiation of this entry point class.
     */
    private TestHarnessMain() {}

    /**
     * Main entry point for executing the OOM Watchdog test harness.
     *
     * @param args command-line arguments (unused)
     * @throws Exception if an error occurs during runtime compilation, class loading, reflection, or execution
     */
    public static void main(String[] args) throws Exception {
        // Display test harness visual header banner
        printBanner();

        // ── 1. Platform report ────────────────────────────────────────────────
        // Log environment capabilities and JVM platform attributes
        System.out.println("[Harness] Platform: " + JvmPlatform.summary());
        System.out.println("[Harness] JDK version: " + JvmPlatform.JDK_VERSION);
        System.out.println("[Harness] GraalVM native: " + JvmPlatform.IS_GRAAL_NATIVE);
        System.out.println("[Harness] IBM J9: " + JvmPlatform.IS_J9);
        System.out.println("[Harness] HotSpot: " + JvmPlatform.IS_HOTSPOT);
        System.out.println();

        // ── 2. Dummy configuration ────────────────────────────────────────────
        // Build aggressive configuration profile suitable for fast test execution
        WatchdogConfig config = buildDummyConfig();
        printConfig(config);

        // ── 3. Wire watchdog ──────────────────────────────────────────────────
        // Register in-memory recorder channel to collect generated alerts and dump paths
        HarnessAlertRecorder recorder = new HarnessAlertRecorder();
        List<AlertChannel>   channels = new ArrayList<>();
        channels.add(new ConsoleAlertChannel());
        channels.add(new FileLogAlertChannel("./harness-output/oom-harness.log"));
        channels.add(recorder);

        // Instantiate collector, assessor, and dumper implementations
        JvmDiagnosticsCollector collector = new MxBeanDiagnosticsCollector(config);
        RiskAssessor            assessor  = new ThresholdRiskAssessor(config);
        HeapDumpService         dumper    = new CompositeDumpService(config);

        OomWatchdog watchdog = new OomWatchdog(
                config, collector, assessor, channels, dumper);

        // Register shutdown hook for clean termination
        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "harness-shutdown"));

        // Start background watchdog polling thread
        watchdog.start();
        System.out.println("[Harness] Watchdog started.");

        // ── 4. Dynamic OOM class generation ──────────────────────────────────
        // Setup output directories for dynamic source generation and bytecode compilation
        System.out.println("[Harness] Generating and loading dynamic OOM class...");
        String dynamicClassName = "com.trongus.oom.harness.generated.HeapExhauster";
        File   sourceDir        = new File("./harness-output/generated-src");
        File   classDir         = new File("./harness-output/generated-classes");
        sourceDir.mkdirs();
        classDir.mkdirs();

        // Attempt runtime compilation of HeapExhauster
        DynamicOomClassGenerator generator = new DynamicOomClassGenerator(
                dynamicClassName, sourceDir, classDir);
        generator.generateAndCompile();

        // ── 5. Load + run the dynamic class to exhaust the heap ──────────────
        // Execute dynamic heap exhauster to induce WARNING, CRITICAL, and OOM conditions
        System.out.println("[Harness] Executing dynamic class to trigger OOM...");
        try {
            generator.execute();
        } catch (OutOfMemoryError oom) {
            System.err.println("[Harness] *** OutOfMemoryError caught: " + oom.getMessage() + " ***");
        } catch (Exception e) {
            // InvocationTargetException wraps the OOM when invoked via reflection
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof OutOfMemoryError) {
                System.err.println("[Harness] *** OutOfMemoryError (via reflection): "
                        + cause.getMessage() + " ***");
            } else {
                System.err.println("[Harness] Unexpected exception: " + cause);
            }
        }

        // Give the watchdog one extra poll cycle to capture the CRITICAL alert + dumps
        Thread.sleep(1500);

        // Print results
        printResults(recorder);
    }

    // -------------------------------------------------------------------------

    /**
     * Builds a test {@link WatchdogConfig} instance configured with low thresholds
     * and short polling intervals for fast validation.
     *
     * @return a fully populated {@link WatchdogConfig} configured for testing
     */
    static WatchdogConfig buildDummyConfig() {
        System.out.println("[Harness] Building dummy WatchdogConfig...");
        WatchdogConfig config = WatchdogConfig.defaults()
            .warningHeapThreshold(0.45)
            .criticalHeapThreshold(0.65)
            .gcOverheadThreshold(0.40)
            .pollIntervalMs(800)
            .heapDumpDirectory("./harness-output/dumps")
            .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD, DumpType.CLASS_HISTOGRAM))
            .qradarHost("")   // QRadar disabled in test
            .qradarPort(514)
            .leakDetectionWindowSize(3)
            .build();
        return config;
    }

    /**
     * Prints the active test configuration parameters to standard output.
     *
     * @param cfg the {@link WatchdogConfig} to display
     */
    private static void printConfig(WatchdogConfig cfg) {
        System.out.println("[Harness] === Dummy WatchdogConfig ===");
        System.out.printf("[Harness]   warn threshold  : %.0f%%%n", cfg.getWarningHeapThreshold()  * 100);
        System.out.printf("[Harness]   crit threshold  : %.0f%%%n", cfg.getCriticalHeapThreshold() * 100);
        System.out.printf("[Harness]   gc threshold    : %.0f%%%n", cfg.getGcOverheadThreshold()   * 100);
        System.out.printf("[Harness]   poll interval   : %d ms%n",  cfg.getPollIntervalMs());
        System.out.printf("[Harness]   dump directory  : %s%n",     cfg.getHeapDumpDirectory());
        System.out.printf("[Harness]   dump types      : %s%n",     cfg.getDumpTypes());
        System.out.printf("[Harness]   leak window     : %d%n",     cfg.getLeakDetectionWindowSize());
        System.out.println("[Harness] ===========================");
        System.out.println();
    }

    /**
     * Summarizes the test execution results captured by the {@link HarnessAlertRecorder}.
     *
     * @param recorder the in-memory recorder containing alert tallies and dump paths
     */
    private static void printResults(HarnessAlertRecorder recorder) {
        System.out.println();
        System.out.println("[Harness] === Test Results ===");
        System.out.printf("[Harness]   WARNING alerts fired   : %d%n", recorder.warnCount.get());
        System.out.printf("[Harness]   CRITICAL alerts fired  : %d%n", recorder.critCount.get());
        System.out.printf("[Harness]   Dump paths recorded    : %s%n", recorder.dumpPaths);
        System.out.println("[Harness] ====================");
    }

    /**
     * Prints the ASCII title banner for the test harness.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           OomWatchdog Test Harness                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Generates a dynamic Java class at runtime, compiles it,     ║");
        System.out.println("║  loads it, and runs it to exhaust the JVM heap.              ║");
        System.out.println("║                                                              ║");
        System.out.println("║  Recommended:  java -Xmx96m -jar test-harness.jar           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
