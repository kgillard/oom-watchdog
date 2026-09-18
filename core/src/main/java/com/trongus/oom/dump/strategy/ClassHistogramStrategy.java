package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import javax.management.MBeanServer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Class histogram via multiple strategies, in priority order:
 *
 * <ol>
 *   <li><b>HotSpot DiagnosticCommand MBean</b> – {@code gcClassHistogram} via
 *       {@code MBeanServer.invoke} (HotSpot JDK 8u40+, JDK 9–26+).</li>
 *   <li><b>IBM J9 JavaDump</b> – triggers a javacore that includes a class
 *       histogram section (IBM J9 / OpenJ9 all versions).</li>
 *   <li><b>GraalVM Native Image</b> – per-pool summary, which is the best
 *       available equivalent in a native executable.</li>
 *   <li><b>Universal fallback</b> – ordered pool-usage table from
 *       {@link ManagementFactory#getMemoryPoolMXBeans()} – works on every JVM.</li>
 * </ol>
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.2
 * @since 1.7.0
 */
public final class ClassHistogramStrategy implements DumpStrategy {

    @Override public DumpType type() { return DumpType.CLASS_HISTOGRAM; }
    @Override public String  name() { return "Composite-ClassHistogram"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {

        // Strategy 1: HotSpot DiagnosticCommand (JDK 8u40+ / JDK 9–26+)
        if (!JvmPlatform.IS_GRAAL_NATIVE && !JvmPlatform.IS_J9) {
            String result = tryDiagnosticCommand(snapshot, outputPath);
            if (result != null) return result;
        }

        // Strategy 2: IBM J9 JavaDump
        if (JvmPlatform.IS_J9) {
            String result = tryJ9JavaDump(snapshot, outputPath);
            if (result != null) return result;
        }

        // Strategy 3 / Universal fallback: memory-pool summary
        return writePoolTable(snapshot, outputPath);
    }

    // -------------------------------------------------------------------------

    private static String tryDiagnosticCommand(JvmSnapshot snapshot, String path) {
        try {
            MBeanServer server   = ManagementFactory.getPlatformMBeanServer();
            javax.management.ObjectName diagName =
                    new javax.management.ObjectName(
                            "com.sun.management:type=DiagnosticCommand");
            if (!server.isRegistered(diagName)) return null;

            String histogram = (String) server.invoke(
                    diagName,
                    "gcClassHistogram",
                    new Object[]{ new String[0] },
                    new String[]{ String[].class.getName() });

            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(path), StandardCharsets.UTF_8))) {
                pw.println("=== Class Histogram (HotSpot DiagnosticCommand) ===");
                pw.println("Platform  : " + JvmPlatform.summary());
                pw.println("Process   : " + snapshot.getProcessName());
                pw.println("Time      : " + new Date(snapshot.getTimestampMs()));
                pw.println();
                if (histogram != null) pw.print(histogram);
            }
            return new File(path).getAbsolutePath();
        } catch (Exception e) {
            return null; // not available on this JVM
        }
    }

    private static String tryJ9JavaDump(JvmSnapshot snapshot, String path) {
        try {
            Class.forName("com.ibm.jvm.Dump")
                 .getMethod("JavaDump")
                 .invoke(null);
            System.out.println("[OomWatchdog][Dump] CLASS_HISTOGRAM (J9 JavaDump) triggered; expected ~ " + path);
            return path + "_j9javacore";
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] J9 JavaDump error: " + e.getMessage());
            return null;
        }
    }

    private static String writePoolTable(JvmSnapshot snapshot, String path) {
        // Rename to make it clear this is a fallback
        String fallbackPath = path.replace(".txt", "_poolsummary.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(fallbackPath), StandardCharsets.UTF_8))) {

            pw.println("=== Memory Pool Table (class histogram fallback) ===");
            pw.println("Platform  : " + JvmPlatform.summary());
            pw.println("Process   : " + snapshot.getProcessName());
            pw.println("Time      : " + new Date(snapshot.getTimestampMs()));
            pw.println();
            pw.printf("  %-40s  %10s  %10s  %10s%n", "Pool", "Used MB", "Max MB", "Init MB");
            pw.printf("  %-40s  %10s  %10s  %10s%n", "----", "-------", "------", "-------");

            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage u = pool.getUsage();
                if (u == null) continue;
                long mb = 1024L * 1024L;
                pw.printf("  %-40s  %10d  %10s  %10d%n",
                        pool.getName(),
                        u.getUsed() / mb,
                        u.getMax() < 0 ? "unlimited" : String.valueOf(u.getMax() / mb),
                        u.getInit() < 0 ? 0 : u.getInit() / mb);
            }
            pw.println();
            // Snapshot per-pool bytes already collected by the MxBeanDiagnosticsCollector
            pw.println("--- Snapshot pool data ---");
            for (Map.Entry<String, Long> e : snapshot.getPoolUsedBytes().entrySet()) {
                pw.printf("  %-40s  %,12d bytes%n", e.getKey(), e.getValue());
            }
        } catch (IOException e) {
            System.err.println("[OomWatchdog][Dump] PoolTable write error: " + e.getMessage());
            return null;
        }
        return new File(fallbackPath).getAbsolutePath();
    }
}
