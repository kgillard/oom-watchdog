package com.ibm.oomwatchdog.collector;

import com.ibm.oomwatchdog.config.WatchdogConfig;
import com.ibm.oomwatchdog.model.JvmSnapshot;
import com.ibm.oomwatchdog.model.OomRiskLevel;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
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
 */
public final class MxBeanDiagnosticsCollector implements JvmDiagnosticsCollector {

    // MXBeans are thread-safe singletons – cache the references.
    private static final MemoryMXBean  MEMORY_MX  = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean RUNTIME_MX = ManagementFactory.getRuntimeMXBean();

    private final WatchdogConfig config;

    // Rolling window for post-GC heap trend (timestampMs → heapUsedAfterGc bytes)
    private final Deque<long[]> postGcWindow; // each entry = {timestampMs, heapUsedBytes}
    private long prevTotalGcTime = 0L;

    public MxBeanDiagnosticsCollector(WatchdogConfig config) {
        this.config = config;
        this.postGcWindow = new ArrayDeque<>(config.getLeakDetectionWindowSize() + 1);
    }

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
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                poolUsed.put(pool.getName(), u.getUsed());
            }
        }

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

        // ── diagnosis notes ──────────────────────────────────────────────────
        String notes = buildDiagnosisNotes(heapRatio, gcOverhead, growthRate,
                                           totalGcTime, gcCounts.isEmpty());

        return new JvmSnapshot.Builder()
                .processName(processName)
                .timestampMs(now)
                .heapUsedBytes(heapUsed)
                .heapCommittedBytes(heapCommitted)
                .heapMaxBytes(heapMax)
                .heapUsedRatio(heapRatio)
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
            sb.append("[GC] No GC activity recorded – GC may be disabled or not yet triggered. ");
        } else {
            sb.append(String.format("[GC] Total GC time: %d ms | Overhead: %.1f%%. ",
                    totalGcTime, gcOverhead * 100));
            if (gcOverhead > config.getGcOverheadThreshold()) {
                sb.append(String.format(
                    "WARNING: GC overhead %.1f%% exceeds threshold %.1f%%. ",
                    gcOverhead * 100, config.getGcOverheadThreshold() * 100));
            }
        }

        sb.append(String.format("[Heap] Used %.1f%% of max. ", heapRatio * 100));

        if (!Double.isNaN(growthRate)) {
            double growthMbPerHour = growthRate * 3_600_000.0 / (1024.0 * 1024.0);
            if (growthRate > 0) {
                sb.append(String.format(
                    "[Leak] Post-GC heap growing at %.2f MB/hour – possible memory leak. ",
                    growthMbPerHour));
            } else {
                sb.append("[Leak] Post-GC heap stable – no leak trend detected. ");
            }
        } else {
            sb.append("[Leak] Insufficient post-GC samples for trend analysis. ");
        }

        return sb.toString().trim();
    }
}
