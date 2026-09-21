package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import java.io.File;
import java.util.logging.Logger;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Thread dump via {@link ThreadMXBean#dumpAllThreads}.
 *
 * <p>Works on all JVM vendors and all versions JDK 6 through 26+,
 * including GraalVM Native Image (partial lock-info support).
 * This is the only dump type that is 100% portable.
 *
 * <p>Each thread entry includes: name, ID (using {@code threadId()} on JDK 19+
 * or {@code getThreadId()} on older versions), state, stack trace, held monitors,
 * and held synchronizers.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.4
 * @since 1.7.0
 */
public final class ThreadDumpStrategy implements DumpStrategy {

    private static final Logger LOG = WatchdogLogger.forClass(ThreadDumpStrategy.class);

    @Override public DumpType type() { return DumpType.THREAD; }
    @Override public String  name() { return "Universal-ThreadMXBean"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();

        boolean monitors      = mx.isObjectMonitorUsageSupported();
        boolean synchronizers = mx.isSynchronizerUsageSupported();

        ThreadInfo[] threads = mx.dumpAllThreads(monitors, synchronizers);
        // Pre-fetch CPU times while we still hold the MXBean reference
        final boolean cpuSupported = mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled();

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {

            pw.println("=== Thread Dump ===");
            pw.println("Platform  : " + JvmPlatform.summary());
            pw.println("Process   : " + snapshot.getProcessName());
            pw.println("Time      : " + new Date(snapshot.getTimestampMs()));
            pw.println("Threads   : " + threads.length);
            pw.println("RiskLevel : " + snapshot.getRiskLevel());
            pw.println();

            // Deadlock detection
            long[] deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                pw.println("*** DEADLOCK DETECTED – thread IDs: " + join(deadlocked) + " ***");
                pw.println();
            }

            for (ThreadInfo ti : threads) {
                long cpuNs = cpuSupported ? mx.getThreadCpuTime(getThreadId(ti)) : -1L;
                pw.println(formatThread(ti, cpuNs));
            }

        } catch (IOException e) {
            WatchdogLogger.warning(LOG, e, "Thread dump write error [{0}]: {1}", outputPath, e.getMessage());
            return null;
        }
        return new File(outputPath).getAbsolutePath();
    }

    // -------------------------------------------------------------------------

    private static String formatThread(ThreadInfo ti, long cpuNs) {
        StringBuilder sb = new StringBuilder();

        // Use threadId() on JDK 19+ to avoid deprecation warning; fall back to getThreadId()
        long tid = getThreadId(ti);
        sb.append(String.format("\"%s\" #%d [%s]%n",
                ti.getThreadName(), tid, ti.getThreadState()));
        sb.append(String.format("  cpu=%d ms  blocked=%d ms  waited=%d ms%n",
                cpuNs < 0 ? -1L : cpuNs / 1_000_000L,
                ti.getBlockedTime(),
                ti.getWaitedTime()));

        if (ti.getLockName() != null) {
            sb.append("  waiting on: ").append(ti.getLockName()).append('\n');
        }
        if (ti.getLockOwnerName() != null) {
            sb.append(String.format("  held by: \"%s\" #%d%n",
                    ti.getLockOwnerName(), ti.getLockOwnerId()));
        }

        for (StackTraceElement ste : ti.getStackTrace()) {
            sb.append("    at ").append(ste).append('\n');
        }

        MonitorInfo[] monitors = ti.getLockedMonitors();
        if (monitors.length > 0) {
            sb.append("  Locked monitors:\n");
            for (MonitorInfo mi : monitors) {
                sb.append(String.format("    - %s (depth %d)%n",
                        mi.getClassName(), mi.getLockedStackDepth()));
            }
        }

        LockInfo[] syncs = ti.getLockedSynchronizers();
        if (syncs.length > 0) {
            sb.append("  Locked synchronizers:\n");
            for (LockInfo li : syncs) {
                sb.append("    - ").append(li.getClassName()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Returns the thread ID, preferring the JDK 19+ {@code threadId()} method
     * (non-deprecated) and falling back to {@code getThreadId()} via reflection
     * for JDK 8–18 where {@code threadId()} doesn't exist.
     * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.4
 * @since 1.7.0
 */
    @SuppressWarnings("deprecation")
    private static long getThreadId(ThreadInfo ti) {
        if (JvmPlatform.JDK_VERSION >= 19) {
            try {
                return (Long) ThreadInfo.class.getMethod("threadId").invoke(ti);
            } catch (Exception ignored) {
                // fall through to legacy
            }
        }
        return ti.getThreadId();
    }

    private static String join(long[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids[i]);
        }
        return sb.toString();
    }
}
