package com.trongus.oom.collector;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.i18n.Messages;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.platform.JvmPlatform;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link JvmDiagnosticsCollector} backed entirely by the standard
 * {@code java.lang.management} MXBean API – no native or third-party
 * dependencies, compatible with JDK 8 through the latest LTS releases.
 *
 * <p>The collector is stateful in one narrow respect: it keeps a rolling
 * window of post-GC heap samples to calculate the leak-trend slope.
 * All other state lives in the returned {@link JvmSnapshot}.
 *
 * <p>Diagnosis note strings are produced in the locale declared on the
 * supplied {@link WatchdogConfig}.
 * <h2>Nursery Pool Collection</h2>
 * <p>During each {@link #collect()} invocation, all {@link MemoryPoolMXBean} entries
 * are iterated.  Pools whose name contains {@code "Eden"}, {@code "Nursery"}, or
 * {@code "Young"} (case-insensitive) contribute to the aggregate
 * {@link JvmSnapshot#getNurseryUsedBytes()} and
 * {@link JvmSnapshot#getNurseryUsedRatio()} fields.  This covers both HotSpot
 * ({@code Eden Space}, {@code G1 Eden Space}) and OpenJ9/IBM J9
 * ({@code nursery-allocate}, {@code nursery-survivor}) pool naming conventions.
 * Both fields default to {@code 0} / {@link Double#NaN} when no matching pools are found.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.10
 * @since 1.0.0
 * @see com.trongus.oom.remote.JmxDiagnosticsCollector
 */
public final class MxBeanDiagnosticsCollector implements JvmDiagnosticsCollector {

    // MXBeans are thread-safe singletons – cache the references.
    private static final MemoryMXBean          MEMORY_MX  = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean         RUNTIME_MX = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean OS_MX      = ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean          THREAD_MX  = ManagementFactory.getThreadMXBean();

    private final WatchdogConfig config;
    private final Messages       messages;

    // Rolling window for post-GC heap trend (timestampMs → heapUsedAfterGc bytes)
    private final Deque<long[]> postGcWindow; // each entry = {timestampMs, heapUsedBytes}
    private long prevTotalGcTime = 0L;

    /**
     * Constructs a collector with the given configuration.
     *
     * @param config the watchdog configuration; must not be {@code null}
     */
    public MxBeanDiagnosticsCollector(WatchdogConfig config) {
        this.config       = config;
        this.messages     = new Messages(config.getLocale());
        this.postGcWindow = new ArrayDeque<>(config.getLeakDetectionWindowSize() + 1);
    }

    /**
     * Collects a fresh {@link JvmSnapshot} from the in-process JVM using the standard
     * {@code java.lang.management} MXBeans.
     *
     * <p>The snapshot includes:
     * <ul>
     *   <li>Heap and non-heap memory usage.</li>
     *   <li>Per-pool breakdown including nursery/young-gen aggregates.</li>
     *   <li>Garbage collection counts and elapsed time per collector.</li>
     *   <li>Post-GC heap trend slope for memory-leak detection.</li>
     *   <li>JVM process detail: {@code java.home}, Java version, JVM name, OS, CPU count,
     *       process CPU load/time (via {@code com.sun.management.OperatingSystemMXBean}
     *       reflection — returns {@code -1} gracefully when unavailable), JVM input
     *       arguments, {@code sun.java.command}, live and peak thread counts.</li>
     * </ul>
     *
     * @return a fully populated {@link JvmSnapshot} with risk level initialised to
     *         {@link OomRiskLevel#OK}; never {@code null}
     */
    @Override
    public JvmSnapshot collect() {
        long now = System.currentTimeMillis();

        // ── heap ────────────────────────────────────────────────────────────
        MemoryUsage heap = MEMORY_MX.getHeapMemoryUsage();
        long heapUsed      = heap.getUsed();
        long heapCommitted = heap.getCommitted();
        long heapMax       = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        double heapRatio   = (heapMax > 0) ? (double) heapUsed / heapMax : 0.0;

        // ── non-heap ────────────────────────────────────────────────────────
        MemoryUsage nonHeap = MEMORY_MX.getNonHeapMemoryUsage();
        long nonHeapUsed = nonHeap.getUsed();
        long nonHeapMax  = nonHeap.getMax(); // −1 when unlimited (Metaspace)

        // ── memory pools (Eden, Old Gen, Metaspace, Code Cache, …) ──────────
        Map<String, Long> poolUsed = new LinkedHashMap<>();
        long nurseryUsed = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                poolUsed.put(pool.getName(), u.getUsed());
                String poolNameLower = pool.getName().toLowerCase(java.util.Locale.ROOT);
                if (poolNameLower.contains("eden") || poolNameLower.contains("nursery")
                        || poolNameLower.contains("young")) {
                    nurseryUsed += u.getUsed();
                }
            }
        }
        double nurseryRatio = (nurseryUsed > 0 && heapMax > 0)
                ? (double) nurseryUsed / heapMax : Double.NaN;

        // ── garbage collection ───────────────────────────────────────────────
        Map<String, Long> gcCounts = new LinkedHashMap<>();
        Map<String, Long> gcTimes  = new LinkedHashMap<>();
        long totalGcTime = 0L;

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time  = gc.getCollectionTime();
            gcCounts.put(gc.getName(), count < 0 ? 0L : count);
            gcTimes.put(gc.getName(),  time  < 0 ? 0L : time);
            totalGcTime += (time > 0 ? time : 0L);
        }

        long uptime        = RUNTIME_MX.getUptime(); // ms
        double gcOverhead  = (uptime > 0) ? (double) totalGcTime / uptime : 0.0;

        // ── post-GC leak trend ───────────────────────────────────────────────
        long   postGcSample = -1L;
        double growthRate   = Double.NaN;

        if (totalGcTime > prevTotalGcTime) {
            // A GC event has occurred since the last poll – record post-GC heap
            prevTotalGcTime = totalGcTime;
            postGcSample    = heapUsed;

            long[] entry = { now, heapUsed };
            postGcWindow.addLast(entry);
            if (postGcWindow.size() > config.getLeakDetectionWindowSize()) {
                postGcWindow.removeFirst();
            }
        }

        if (postGcWindow.size() >= 2) {
            growthRate = computeSlope(new ArrayList<>(postGcWindow));
        }

        // ── process name ─────────────────────────────────────────────────────
        String processName = RUNTIME_MX.getName(); // e.g. "12345@hostname"

        // ── JVM process detail ────────────────────────────────────────────────
        String javaHome    = System.getProperty("java.home", "");
        String javaVersion = System.getProperty("java.version", "") + " ("
                           + System.getProperty("java.vendor",  "") + ")";
        String jvmName     = System.getProperty("java.vm.name",    "") + " "
                           + System.getProperty("java.vm.version", "");
        String osName      = OS_MX.getName() + " " + OS_MX.getVersion()
                           + " (" + OS_MX.getArch() + ")";
        int    cpuCount    = OS_MX.getAvailableProcessors();
        // Read process CPU via JvmPlatform helpers — uses the public
        // com.sun.management.OperatingSystemMXBean interface, no setAccessible,
        // no illegal-access warnings on any JVM or JDK version.
        double cpuPct = JvmPlatform.processCpuPct();
        long   cpuMs  = JvmPlatform.processCpuMs();
        List<String> inputArgsList = RUNTIME_MX.getInputArguments();
        StringBuilder inputArgsSb  = new StringBuilder();
        for (int i = 0; i < inputArgsList.size(); i++) {
            if (i > 0) inputArgsSb.append(' ');
            inputArgsSb.append(inputArgsList.get(i));
        }
        String javaCommand   = System.getProperty("sun.java.command", "");
        int    threadCount   = THREAD_MX.getThreadCount();
        int    peakThreads   = THREAD_MX.getPeakThreadCount();

        // ── diagnosis notes ──────────────────────────────────────────────────
        String notes = buildDiagnosisNotes(heapRatio, gcOverhead, growthRate,
                                           totalGcTime, gcCounts.isEmpty());

        return new JvmSnapshot.Builder()
                .targetName(null)
                .processName(processName)
                .timestampMs(now)
                .heapUsedBytes(heapUsed)
                .heapCommittedBytes(heapCommitted)
                .heapMaxBytes(heapMax)
                .heapUsedRatio(heapRatio)
                .nurseryUsedBytes(nurseryUsed)
                .nurseryUsedRatio(nurseryRatio)
                .nonHeapUsedBytes(nonHeapUsed)
                .nonHeapMaxBytes(nonHeapMax)
                .poolUsedBytes(poolUsed)
                .gcCollectionCounts(gcCounts)
                .gcCollectionTimesMs(gcTimes)
                .totalGcTimeMs(totalGcTime)
                .jvmUptimeMs(uptime)
                .gcOverheadRatio(gcOverhead)
                .postGcHeapUsedBytes(postGcSample)
                .postGcHeapGrowthRatePerMs(growthRate)
                .riskLevel(OomRiskLevel.OK)   // risk classification is the monitor's job
                .diagnosisNotes(notes)
                .javaHome(javaHome)
                .javaVersion(javaVersion)
                .jvmName(jvmName)
                .osName(osName)
                .cpuCount(cpuCount)
                .processCpuPct(cpuPct)
                .processCpuMs(cpuMs)
                .jvmInputArgs(inputArgsSb.toString())
                .javaCommand(javaCommand)
                .threadCount(threadCount)
                .peakThreadCount(peakThreads)
                .build();
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    /**
     * Ordinary-least-squares slope over (timestampMs, heapUsedBytes) pairs.
     * Returns bytes/ms; positive slope = memory is growing after GC.
     */
    private static double computeSlope(List<long[]> points) {
        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (long[] p : points) {
            sumX  += p[0];
            sumY  += p[1];
            sumXY += (double) p[0] * p[1];
            sumX2 += (double) p[0] * p[0];
        }
        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0.0) return 0.0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    private String buildDiagnosisNotes(double heapRatio, double gcOverhead,
                                       double growthRate, long totalGcTime,
                                       boolean noGcBeans) {
        StringBuilder sb = new StringBuilder();

        if (noGcBeans || totalGcTime == 0L) {
            sb.append("[GC] ").append(messages.get("diag.gc.none")).append(" ");
        } else {
            sb.append("[GC] ")
              .append(messages.format("diag.gc.summary", totalGcTime, gcOverhead * 100))
              .append(" ");
            if (gcOverhead > config.getGcOverheadThreshold()) {
                sb.append(messages.format("diag.gc.warning",
                        gcOverhead * 100, config.getGcOverheadThreshold() * 100))
                  .append(" ");
            }
        }

        sb.append("[Heap] ")
          .append(messages.format("diag.heap", heapRatio * 100))
          .append(" ");

        if (!Double.isNaN(growthRate)) {
            double growthMbPerHour = growthRate * 3_600_000.0 / (1024.0 * 1024.0);
            if (growthRate > 0) {
                sb.append("[Leak] ")
                  .append(messages.format("diag.leak.growing", growthMbPerHour))
                  .append(" ");
            } else {
                sb.append("[Leak] ")
                  .append(messages.get("diag.leak.stable"))
                  .append(" ");
            }
        } else {
            sb.append("[Leak] ")
              .append(messages.get("diag.leak.insufficient"))
              .append(" ");
        }

        return sb.toString().trim();
    }
}
