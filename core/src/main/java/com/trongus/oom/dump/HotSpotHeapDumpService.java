package com.trongus.oom.dump;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import javax.management.MBeanServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cross-vendor, multi-type diagnostic dump service.
 *
 * <h3>Supported dump types</h3>
 * <dl>
 *   <dt>{@link DumpType#HEAP}</dt>
 *   <dd>HotSpot: {@code HotSpotDiagnosticMXBean#dumpHeap} (live objects, .hprof)<br>
 *       IBM J9/OpenJ9: {@code com.ibm.jvm.Dump#HeapDump}</dd>
 *
 *   <dt>{@link DumpType#CORE}</dt>
 *   <dd>HotSpot/Linux/macOS: {@code gcore <pid>} spawned as a child process<br>
 *       IBM J9/OpenJ9: {@code com.ibm.jvm.Dump#SystemDump}</dd>
 *
 *   <dt>{@link DumpType#THREAD}</dt>
 *   <dd>All vendors: {@link ThreadMXBean} — full stack traces with lock info</dd>
 *
 *   <dt>{@link DumpType#CLASS_HISTOGRAM}</dt>
 *   <dd>HotSpot: {@code DiagnosticCommand} MXBean ({@code gcClassHistogram})<br>
 *       Fallback: per-pool summary from {@code MemoryPoolMXBean}</dd>
 * </dl>
 *
 * <p>All output lands in {@link WatchdogConfig#getHeapDumpDirectory()}.
 * The file name always contains the process name, dump type, and a
 * millisecond-precision timestamp so successive dumps never overwrite each other.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.0.0
 * @see HeapDumpService
 * @see DumpType
 */
public final class HotSpotHeapDumpService implements HeapDumpService {

    private static final String HOTSPOT_MXBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private final WatchdogConfig config;

    public HotSpotHeapDumpService(WatchdogConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // HeapDumpService
    // -------------------------------------------------------------------------

    @Override
    public List<String> dump(JvmSnapshot snapshot, List<DumpType> types) {
        List<String> paths = new ArrayList<>();

        try {
            Files.createDirectories(Paths.get(config.getHeapDumpDirectory()));
        } catch (IOException e) {
            System.err.println("[OomWatchdog][Dump] Cannot create dump directory: " + e.getMessage());
            return paths;
        }

        for (DumpType type : types) {
            try {
                String path = dispatchDump(snapshot, type);
                if (path != null) {
                    paths.add(path);
                }
            } catch (Exception e) {
                System.err.println("[OomWatchdog][Dump] " + type + " dump failed: " + e.getMessage());
            }
        }
        return paths;
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private String dispatchDump(JvmSnapshot snapshot, DumpType type) {
        switch (type) {
            case HEAP:            return dumpHeap(snapshot);
            case CORE:            return dumpCore(snapshot);
            case THREAD:          return dumpThreads(snapshot);
            case CLASS_HISTOGRAM: return dumpClassHistogram(snapshot);
            default:
                System.err.println("[OomWatchdog][Dump] Unknown dump type: " + type);
                return null;
        }
    }

    // -------------------------------------------------------------------------
    // HEAP dump
    // -------------------------------------------------------------------------

    private String dumpHeap(JvmSnapshot snapshot) {
        String path = buildPath(snapshot, "heap", ".hprof");

        // Strategy 1: HotSpot / OpenJDK / GraalVM via reflection
        String result = tryHotSpotHeap(path);
        if (result != null) return result;

        // Strategy 2: IBM J9 / OpenJ9
        result = tryJ9Heap(path);
        if (result != null) return result;

        System.err.println("[OomWatchdog][Dump] HEAP: no supported mechanism found. "
                + "Start JVM with -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="
                + config.getHeapDumpDirectory());
        return null;
    }

    private String tryHotSpotHeap(String path) {
        try {
            MBeanServer server   = ManagementFactory.getPlatformMBeanServer();
            Class<?>    beanCls  = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object      bean     = ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_MXBEAN_NAME, beanCls);
            Method      dumpHeap = beanCls.getMethod("dumpHeap", String.class, boolean.class);
            dumpHeap.invoke(bean, path, true); // true = live objects only
            System.out.println("[OomWatchdog][Dump] HEAP (HotSpot): " + path);
            return new File(path).getAbsolutePath();
        } catch (ClassNotFoundException e) {
            return null; // not HotSpot
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] HEAP HotSpot error: " + e.getMessage());
            return null;
        }
    }

    private String tryJ9Heap(String path) {
        try {
            Class<?> dumpCls = Class.forName("com.ibm.jvm.Dump");
            dumpCls.getMethod("HeapDump").invoke(null);
            System.out.println("[OomWatchdog][Dump] HEAP (IBM J9) triggered; JVM path ~ " + path);
            return path + "_j9.phd";
        } catch (ClassNotFoundException e) {
            return null; // not J9
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] HEAP J9 error: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // CORE / system dump
    // -------------------------------------------------------------------------

    private String dumpCore(JvmSnapshot snapshot) {
        // Strategy 1: IBM J9 system dump
        String result = tryJ9SystemDump(snapshot);
        if (result != null) return result;

        // Strategy 2: gcore on Linux/macOS
        result = tryGcore(snapshot);
        if (result != null) return result;

        System.err.println("[OomWatchdog][Dump] CORE: gcore not found on PATH and not running on IBM J9. "
                + "Install gcore (usually part of gdb) or switch to OpenJ9.");
        return null;
    }

    private String tryJ9SystemDump(JvmSnapshot snapshot) {
        try {
            Class<?> dumpCls = Class.forName("com.ibm.jvm.Dump");
            dumpCls.getMethod("SystemDump").invoke(null);
            String path = buildPath(snapshot, "core", ".dmp");
            System.out.println("[OomWatchdog][Dump] CORE (IBM J9) triggered; JVM path ~ " + path);
            return path + "_j9";
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] CORE J9 error: " + e.getMessage());
            return null;
        }
    }

    private String tryGcore(JvmSnapshot snapshot) {
        // Use the reliable PID from JvmPlatform rather than parsing the MXBean name
        long pid = JvmPlatform.PID;
        if (pid < 0) {
            System.err.println("[OomWatchdog][Dump] CORE: cannot determine PID, skipping gcore.");
            return null;
        }
        String outPath = buildPath(snapshot, "core", ".core");

        // Canonicalize to prevent path-traversal via user-supplied dump directory
        String safePath;
        try {
            safePath = new File(outPath).getCanonicalPath();
        } catch (IOException e) {
            System.err.println("[OomWatchdog][Dump] CORE: invalid output path: " + e.getMessage());
            return null;
        }

        try {
            // Arguments as list — no shell interpolation, no injection risk
            ProcessBuilder pb = new ProcessBuilder("gcore", "-o", safePath, String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Drain output; cap at 4096 bytes to prevent unbounded accumulation
            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 4096) break;
                }
            }

            // 60-second hard timeout; destroyForcibly if hung
            boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                System.err.println("[OomWatchdog][Dump] gcore timed out after 60 s.");
                return null;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                System.out.println("[OomWatchdog][Dump] CORE (gcore): " + safePath);
                return new File(safePath).getAbsolutePath();
            } else {
                System.err.println("[OomWatchdog][Dump] CORE gcore exit " + exit + ": " + output);
                return null;
            }
        } catch (IOException e) {
            // gcore not on PATH — expected on many systems
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // THREAD dump
    // -------------------------------------------------------------------------

    private String dumpThreads(JvmSnapshot snapshot) {
        String path = buildPath(snapshot, "threads", ".txt");
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threads  = threadMx.dumpAllThreads(
                threadMx.isObjectMonitorUsageSupported(),
                threadMx.isSynchronizerUsageSupported());

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.write("=== Thread Dump ===\n");
            pw.write("Process : " + snapshot.getProcessName() + "\n");
            pw.write("Time    : " + new Date(snapshot.getTimestampMs()) + "\n");
            pw.write("Threads : " + threads.length + "\n\n");

            for (ThreadInfo ti : threads) {
                pw.write(formatThreadInfo(ti));
                pw.write("\n");
            }
        } catch (IOException e) {
            System.err.println("[OomWatchdog][Dump] THREAD write error: " + e.getMessage());
            return null;
        }
        System.out.println("[OomWatchdog][Dump] THREAD: " + path);
        return new File(path).getAbsolutePath();
    }

    private static String formatThreadInfo(ThreadInfo ti) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "\"%s\" id=%d state=%s%n",
            ti.getThreadName(), ti.getThreadId(), ti.getThreadState()));
        if (ti.getLockName() != null) {
            sb.append(String.format("  waiting on lock: %s%n", ti.getLockName()));
        }
        if (ti.getLockOwnerName() != null) {
            sb.append(String.format("  locked by: %s (id=%d)%n",
                    ti.getLockOwnerName(), ti.getLockOwnerId()));
        }
        for (StackTraceElement ste : ti.getStackTrace()) {
            sb.append("  at ").append(ste).append('\n');
        }
        MonitorInfo[] monitors = ti.getLockedMonitors();
        if (monitors.length > 0) {
            sb.append("  Locked monitors:\n");
            for (MonitorInfo mi : monitors) {
                sb.append(String.format("    - %s (depth=%d)%n", mi.getClassName(), mi.getLockedStackDepth()));
            }
        }
        LockInfo[] sync = ti.getLockedSynchronizers();
        if (sync.length > 0) {
            sb.append("  Locked synchronizers:\n");
            for (LockInfo li : sync) {
                sb.append("    - ").append(li.getClassName()).append('\n');
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // CLASS HISTOGRAM
    // -------------------------------------------------------------------------

    private String dumpClassHistogram(JvmSnapshot snapshot) {
        String path = buildPath(snapshot, "histogram", ".txt");

        // Strategy 1: HotSpot DiagnosticCommand MXBean (JDK 9+, or 8u40+)
        String result = tryHotSpotHistogram(path, snapshot);
        if (result != null) return result;

        // Strategy 2: IBM J9 class dump
        result = tryJ9ClassHistogram(path);
        if (result != null) return result;

        // Fallback: per-pool memory summary (always works, less granular)
        return writePoolSummary(path, snapshot);
    }

    private String tryHotSpotHistogram(String path, JvmSnapshot snapshot) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            // DiagnosticCommand MBean is available on HotSpot JDK 8u40+ and JDK 9+
            javax.management.ObjectName diagName =
                    new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");

            // Verify the MBean is actually registered before proceeding
            if (!server.isRegistered(diagName)) return null;

            // Invoke gcClassHistogram via MBeanServer.invoke() – no proxy required
            // Signature: gcClassHistogram(String[] args) → String
            String result = (String) server.invoke(
                    diagName,
                    "gcClassHistogram",
                    new Object[]{ new String[0] },
                    new String[]{ String[].class.getName() });

            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(path), StandardCharsets.UTF_8))) {
                pw.write("=== Class Histogram ===\n");
                pw.write("Process : " + snapshot.getProcessName() + "\n");
                pw.write("Time    : " + new Date(snapshot.getTimestampMs()) + "\n\n");
                if (result != null) pw.write(result);
            }
            System.out.println("[OomWatchdog][Dump] CLASS_HISTOGRAM (HotSpot DiagnosticCommand): " + path);
            return new File(path).getAbsolutePath();
        } catch (Exception e) {
            // Not available on this JVM / version
            return null;
        }
    }

    private String tryJ9ClassHistogram(String path) {
        try {
            Class<?> dumpCls = Class.forName("com.ibm.jvm.Dump");
            dumpCls.getMethod("JavaDump").invoke(null); // J9 javacore includes class histogram
            System.out.println("[OomWatchdog][Dump] CLASS_HISTOGRAM (IBM J9 JavaDump) triggered; path ~ " + path);
            return path + "_j9";
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] CLASS_HISTOGRAM J9 error: " + e.getMessage());
            return null;
        }
    }

    private String writePoolSummary(String path, JvmSnapshot snapshot) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.write("=== Memory Pool Summary (class histogram not available) ===\n");
            pw.write("Process : " + snapshot.getProcessName() + "\n");
            pw.write("Time    : " + new Date(snapshot.getTimestampMs()) + "\n\n");
            pw.write(String.format("%-45s  %12s%n", "Pool", "Used (bytes)"));
            pw.write(String.format("%-45s  %12s%n", "----", "------------"));
            for (java.util.Map.Entry<String, Long> e : snapshot.getPoolUsedBytes().entrySet()) {
                pw.write(String.format("%-45s  %,12d%n", e.getKey(), e.getValue()));
            }
        } catch (IOException e) {
            System.err.println("[OomWatchdog][Dump] HISTOGRAM write error: " + e.getMessage());
            return null;
        }
        System.out.println("[OomWatchdog][Dump] CLASS_HISTOGRAM (pool summary fallback): " + path);
        return new File(path).getAbsolutePath();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildPath(JvmSnapshot snapshot, String label, String ext) {
        String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date(snapshot.getTimestampMs()));
        String proc = snapshot.getProcessName().replaceAll("[^A-Za-z0-9._-]", "_");
        return config.getHeapDumpDirectory() + File.separator
                + "oom_" + label + "_" + proc + "_" + ts + ext;
    }
}
