package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * Heap dump for GraalVM Native Image and any JVM where HotSpot/J9 are absent.
 *
 * <h2>Strategy</h2>
 * <ol>
 *   <li>If running as a GraalVM Native Image and {@code VMRuntime.dumpHeap()} is
 *       available (GraalVM 23.1+), call it via reflection.</li>
 *   <li>Otherwise produce a structured memory-pool report file
 *       ({@code _heapsummary.txt}) that contains every pool's init/used/committed/max
 *       values, which is the best portable equivalent available.</li>
 * </ol>
 *
 * <p>This strategy is always the last in the chain so it is the guaranteed
 * fallback for every JVM and JDK version including JDK 26+.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.9
 * @since 1.7.0
 */
public final class GraalNativeHeapDumpStrategy implements DumpStrategy {

    private static final Logger LOG = WatchdogLogger.forClass(GraalNativeHeapDumpStrategy.class);

    @Override public DumpType type() { return DumpType.HEAP; }
    @Override public String  name() { return "GraalNative/Fallback-MemoryPoolSummary"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {

        // ── Strategy A: GraalVM Native Image VMRuntime.dumpHeap (23.1+) ──────
        if (JvmPlatform.IS_GRAAL_NATIVE) {
            String result = tryGraalVmDumpHeap(outputPath);
            if (result != null) return result;
        }

        // ── Strategy B: Structured memory-pool summary (universal fallback) ──
        return writeMemoryPoolSummary(snapshot, outputPath);
    }

    // -------------------------------------------------------------------------

    private static String tryGraalVmDumpHeap(String path) {
        try {
            // org.graalvm.nativeimage.VMRuntime is present in GraalVM SDK 23.1+
            Class<?> vmRuntime = Class.forName("org.graalvm.nativeimage.VMRuntime");
            // dumpHeap(String path, boolean gcBefore)
            vmRuntime.getMethod("dumpHeap", String.class, boolean.class)
                     .invoke(null, path, true);
            WatchdogLogger.info(LOG, "HEAP written via GraalVM VMRuntime.dumpHeap: {0}", path);
            return new File(path).getAbsolutePath();
        } catch (ClassNotFoundException e) {
            return null; // SDK not on classpath / older GraalVM
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "GraalVM dumpHeap failed: {0}", e.getMessage());
            return null;
        }
    }

    private static String writeMemoryPoolSummary(JvmSnapshot snapshot, String basePath) {
        String summaryPath = basePath.replace(".hprof", "_heapsummary.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(summaryPath), StandardCharsets.UTF_8))) {

            pw.println("=== Heap Memory Summary (portable fallback dump) ===");
            pw.println("Platform : " + JvmPlatform.summary());
            pw.println("Process  : " + snapshot.getProcessName());
            pw.println("Time     : " + new Date(snapshot.getTimestampMs()));
            pw.println();
            pw.printf("  %-12s  %10s%n", "Heap Used",
                    formatMb(snapshot.getHeapUsedBytes()));
            pw.printf("  %-12s  %10s%n", "Heap Max",
                    formatMb(snapshot.getHeapMaxBytes()));
            pw.printf("  %-12s  %10.1f%%%n", "Heap %",
                    snapshot.getHeapUsedRatio() * 100);
            pw.println();

            // Per-pool breakdown with init/used/committed/max
            List<MemoryPoolMXBean> pools = new ArrayList<>(
                    ManagementFactory.getMemoryPoolMXBeans());
            // Sort: heap pools first, then non-heap
            Collections.sort(pools, new Comparator<MemoryPoolMXBean>() {
                @Override
                public int compare(MemoryPoolMXBean a, MemoryPoolMXBean b) {
                    return a.getType().toString().compareTo(b.getType().toString());
                }
            });

            pw.printf("  %-40s  %-8s  %10s  %10s  %10s  %10s%n",
                    "Pool", "Type", "Used", "Committed", "Max", "Init");
            pw.printf("  %-40s  %-8s  %10s  %10s  %10s  %10s%n",
                    "----", "----", "----", "---------", "---", "----");

            for (MemoryPoolMXBean pool : pools) {
                MemoryUsage u = pool.getUsage();
                if (u == null) continue;
                pw.printf("  %-40s  %-8s  %10s  %10s  %10s  %10s%n",
                        pool.getName(),
                        pool.getType().toString().substring(0, Math.min(8, pool.getType().toString().length())),
                        formatMb(u.getUsed()),
                        formatMb(u.getCommitted()),
                        u.getMax() < 0 ? "unlimited" : formatMb(u.getMax()),
                        formatMb(u.getInit()));
            }

            pw.println();
            pw.println("GC Overhead : " + String.format("%.1f%%", snapshot.getGcOverheadRatio() * 100));
            pw.println("Diagnosis   : " + snapshot.getDiagnosisNotes());

        } catch (IOException e) {
            WatchdogLogger.warning(LOG, e, "Memory pool summary write error [{0}]: {1}", summaryPath, e.getMessage());
            return null;
        }
        WatchdogLogger.info(LOG, "HEAP written as memory pool summary: {0}", summaryPath);
        return new File(summaryPath).getAbsolutePath();
    }

    private static String formatMb(long bytes) {
        if (bytes < 0) return "N/A";
        return String.format("%,d MB", bytes / (1024 * 1024));
    }
}
