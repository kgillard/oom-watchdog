package com.trongus.oom.examples;

import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Example 03 — Automatic diagnostic dump capture on CRITICAL events.
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Enabling one or more dump types so that a heap dump, thread dump, class
 *       histogram, and/or OS core dump are written automatically the first time
 *       the watchdog reaches {@code CRITICAL} risk.</li>
 *   <li>The <em>episode-deduplication</em> design: the dump is taken <strong>once</strong>
 *       per sustained critical episode, not on every poll cycle.  This prevents
 *       "dump storms" — hundreds of {@code .hprof} files being written while the
 *       heap is stuck near capacity.</li>
 *   <li>How to configure a custom output directory for dump files.</li>
 *   <li>How dump file paths are surfaced back through the alert snapshot's
 *       {@link com.trongus.oom.model.JvmSnapshot#getHeapDumpPath()} field so
 *       every alert channel can include the dump location in its notification.</li>
 * </ul>
 *
 * <h2>Episode deduplication</h2>
 * <p>OOM Watchdog uses a boolean flag called {@code dumpTakenForCurrentEpisode}.
 * Once a dump is triggered at {@code CRITICAL}, no further dumps are taken until
 * the risk level returns to {@code OK} (indicating the heap pressure has been
 * resolved).  At that point the flag is reset and the next critical episode will
 * again trigger one dump.  This is the safest policy for production: it gives
 * you the diagnostic artefact you need without filling the disk.
 *
 * <h2>Dump types and JVM support</h2>
 * <table border="1">
 *   <caption>Dump type availability by JVM</caption>
 *   <tr>
 *     <th>DumpType</th>
 *     <th>HotSpot</th>
 *     <th>OpenJ9 / IBM J9</th>
 *     <th>GraalVM</th>
 *   </tr>
 *   <tr>
 *     <td>{@code HEAP}</td>
 *     <td>✅ .hprof (HotSpotDiagnosticMXBean)</td>
 *     <td>✅ .phd (com.ibm.jvm.Dump)</td>
 *     <td>✅ .hprof</td>
 *   </tr>
 *   <tr>
 *     <td>{@code THREAD}</td>
 *     <td>✅ ThreadMXBean</td>
 *     <td>✅ ThreadMXBean</td>
 *     <td>✅ ThreadMXBean</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CLASS_HISTOGRAM}</td>
 *     <td>✅ DiagnosticCommand MBean (JDK 8u40+)</td>
 *     <td>✅ J9 JavaDump</td>
 *     <td>✅ pool summary fallback</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CORE}</td>
 *     <td>✅ gcore (Linux/macOS only)</td>
 *     <td>✅ SystemDump</td>
 *     <td>✅ gcore (Linux/macOS only)</td>
 *   </tr>
 * </table>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.0.0
 * @since 1.0.0
 * @see com.trongus.oom.dump.CompositeDumpService
 * @see DumpType
 */
public final class Example03DumpOnCritical {

    /** Utility class — construction is not permitted. */
    private Example03DumpOnCritical() {}

    /**
     * Starts monitoring with heap + thread dump capture on CRITICAL.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        // ── Configuration: low thresholds for easy testing on a constrained heap ─
        // Run with: java -Xmx128m -jar oom-watchdog.jar  (or your application jar)
        // to see the watchdog trigger at 80 MB / 115 MB heap.
        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.70)      // warn at 70 % (= 90 MB on -Xmx128m)
                .criticalHeapThreshold(0.85)      // critical at 85 % (= 109 MB on -Xmx128m)
                .pollIntervalMs(2_000L)           // poll every 2 s for responsive testing
                .heapDumpDirectory("./dumps")     // all dump files land here
                // Specify which dump types to capture.
                // HEAP + THREAD are the most commonly useful pair:
                //   HEAP  → .hprof for analysis in Eclipse MAT, JProfiler, VisualVM, etc.
                //   THREAD → human-readable stack traces showing what threads are doing
                // Add CLASS_HISTOGRAM for a class-by-class object count without a full hprof.
                // Add CORE only on Linux/macOS when gdb/gcore is available; it is large.
                .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD, DumpType.CLASS_HISTOGRAM))
                .build();

        // ── Wire and start ─────────────────────────────────────────────────────
        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config),
                new ThresholdRiskAssessor(config),
                Arrays.asList(
                        new ConsoleAlertChannel(),
                        new FileLogAlertChannel("./oom-watchdog.log")),
                new CompositeDumpService(config));  // ← uses config.getDumpTypes()

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();

        System.out.println("[Example03] Watching. Dump dir → ./dumps");
        System.out.println("[Example03] On first CRITICAL event, writes:");
        System.out.println("[Example03]   ./dumps/oom_heap_<proc>_<timestamp>.hprof");
        System.out.println("[Example03]   ./dumps/oom_thread_<proc>_<timestamp>_threads.txt");
        System.out.println("[Example03]   ./dumps/oom_histogram_<proc>_<timestamp>.txt");
        System.out.println("[Example03] Only ONE dump set per episode (no dump storms).");

        Thread.currentThread().join();
    }
}
