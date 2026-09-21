package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Same-host JVM diagnostics using OS signals and {@code jcmd}/{@code jmap} —
 * no JMX MBean limitations, no extra ports, no code changes in the target JVM.
 *
 * <h2>Mechanism by dump type</h2>
 * <table border="1">
 *   <caption>Signal/tool mapping per dump type</caption>
 *   <tr><th>Type</th><th>HotSpot / OpenJDK / GraalVM</th><th>IBM J9 / OpenJ9</th></tr>
 *   <tr><td>THREAD</td>
 *       <td>{@code kill -3 <pid>} → reads target stdout log file</td>
 *       <td>{@code kill -3 <pid>} → same</td></tr>
 *   <tr><td>HEAP</td>
 *       <td>{@code jcmd <pid> GC.heap_dump <path>} or
 *           {@code jmap -dump:format=b,file=<path> <pid>}</td>
 *       <td>{@code kill -USR1 <pid>} (writes .phd to working dir)</td></tr>
 *   <tr><td>CORE</td>
 *       <td>{@code gcore -o <path> <pid>} (non-destructive, Linux/macOS)</td>
 *       <td>{@code kill -USR2 <pid>} (non-destructive system dump)</td></tr>
 *   <tr><td>CLASS_HISTOGRAM</td>
 *       <td>{@code jcmd <pid> GC.class_histogram}</td>
 *       <td>{@code jcmd <pid> GC.class_histogram}</td></tr>
 * </table>
 *
 * <h2>Requirements</h2>
 * <ul>
 *   <li>Target process must be on the <strong>same host</strong> as the watchdog.
 *       Use {@link #isSameHost(String)} to verify before calling.</li>
 *   <li>Watchdog must run as the <strong>same OS user</strong> as the target, or as root.</li>
 *   <li>{@code jcmd} and {@code jmap} are located from the target JVM's {@code java.home}
 *       (collected via JMX and shown on the dashboard).  This means the correct JDK tools
 *       for that specific JVM are always used, regardless of what is on {@code PATH}.
 *       Falls back to bare {@code jcmd}/{@code jmap} on {@code PATH} if {@code java.home}
 *       is unavailable or the tool is not present there.</li>
 *   <li>{@code gcore} must be on {@code PATH} for core dumps on HotSpot.</li>
 *   <li>For {@code kill -3} (thread dump), the target's stdout must be redirected
 *       to a known log file; set {@code signal-dump-log} in targets.properties to
 *       make the path available in the result.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>All methods are stateless and safe for concurrent calls.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.1
 * @since 1.7.12.9
 */
public final class ProcessSignalDumper {

    private static final Logger LOG = WatchdogLogger.forClass(ProcessSignalDumper.class);

    /** Timeout for {@code jcmd} / {@code jmap} / {@code gcore} subprocesses (seconds). */
    private static final int SUBPROCESS_TIMEOUT_SEC = 120;

    private ProcessSignalDumper() {}

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Determines whether {@code hostname} (from a JMX URL such as
     * {@code service:jmx:rmi:///jndi/rmi://hostname:port/jmxrmi}) resolves to the
     * local machine.
     *
     * <p>Returns {@code true} when:
     * <ul>
     *   <li>hostname is {@code localhost}, {@code 127.0.0.1}, or {@code ::1}</li>
     *   <li>hostname resolves to an IP bound to a local network interface</li>
     * </ul>
     *
     * @param hostname the host component extracted from the JMX URL
     * @return {@code true} if the host is the local machine
     */
    public static boolean isSameHost(String hostname) {
        if (hostname == null || hostname.isEmpty()) return false;
        String h = hostname.toLowerCase(Locale.ROOT).trim();
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) return true;
        try {
            InetAddress remote = InetAddress.getByName(h);
            if (remote.isLoopbackAddress()) return true;
            // Check if the resolved address matches any local interface
            return InetAddress.getLocalHost().getHostAddress().equals(remote.getHostAddress());
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Extracts the host component from a JMX service URL.
     *
     * <p>Handles the canonical form
     * {@code service:jmx:rmi:///jndi/rmi://host:port/jmxrmi} as well as
     * non-standard variants.
     *
     * @param jmxUrl the full JMX service URL string
     * @return the hostname portion, or {@code ""} if it cannot be extracted
     */
    public static String hostFromJmxUrl(String jmxUrl) {
        if (jmxUrl == null) return "";
        // Match //host:port/ or //host/
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("//([^/:]+)[:/]").matcher(jmxUrl);
        // Find the last match (the one after the second //)
        String host = "";
        while (m.find()) {
            host = m.group(1);
        }
        return host;
    }

    /**
     * Triggers a diagnostic dump on a same-host process using the most reliable
     * mechanism available for the given dump type.
     *
     * <p>Returns the absolute path of the written file, or {@code null} if the
     * dump could not be produced (caller should then fall back to JMX).
     *
     * @param type          the dump type
     * @param pid           the OS PID of the target JVM
     * @param outputPath    desired output file path (used for heap, histogram, core)
     * @param isJ9          {@code true} if the target JVM is IBM J9/OpenJ9
     * @param signalDumpLog optional path to the target's stdout log file;
     *                      used to record where the {@code kill -3} output landed
     *                      (may be {@code null})
     * @param javaHome      the target JVM's {@code java.home} as reported by
     *                      {@code RuntimeMXBean} and shown on the dashboard
     *                      (e.g. {@code /opt/ibm/ibm-semeru-certified-11-jdk}).
     *                      Used to locate {@code jcmd}/{@code jmap} from that
     *                      specific JDK installation rather than relying on {@code PATH}.
     *                      {@code null} or blank falls back to searching {@code PATH}.
     * @return absolute path of the produced file, or {@code null} on failure
     */
    public static String dump(DumpType type, long pid, String outputPath,
                               boolean isJ9, String signalDumpLog, String javaHome) {
        if (pid <= 0) {
            WatchdogLogger.warning(LOG, "ProcessSignalDumper: invalid PID {0} for {1}", pid, type);
            return null;
        }
        String jcmd = resolveJdkTool("jcmd", javaHome);
        String jmap = resolveJdkTool("jmap", javaHome);
        switch (type) {
            case THREAD:          return threadDump(pid, outputPath, signalDumpLog);
            case HEAP:            return isJ9 ? heapDumpJ9(pid, outputPath, jcmd)
                                               : heapDumpHotSpot(pid, outputPath, jcmd, jmap);
            case CORE:            return isJ9 ? coreDumpJ9(pid, outputPath)
                                               : coreDumpGcore(pid, outputPath);
            case CLASS_HISTOGRAM: return classHistogram(pid, outputPath, jcmd);
            default:
                WatchdogLogger.warning(LOG, "ProcessSignalDumper: unsupported type {0}", type);
                return null;
        }
    }

    /**
     * Resolves the absolute path of a JDK command-line tool ({@code jcmd}, {@code jmap}, etc.)
     * from the target JVM's {@code java.home} directory.
     *
     * <p>The {@code java.home} property points to the JRE root:
     * <ul>
     *   <li>JDK 9+: {@code /path/to/jdk} — tools are in {@code <java.home>/bin/}</li>
     *   <li>JDK 8:  {@code /path/to/jdk/jre} — tools are in {@code <java.home>/../bin/}</li>
     * </ul>
     * Both locations are probed; the first that contains an executable wins.
     * Falls back to the bare tool name (PATH lookup) if neither is found.
     *
     * @param toolName  the executable name without extension (e.g. {@code "jcmd"})
     * @param javaHome  the {@code java.home} value from the target JVM (may be {@code null})
     * @return absolute path if found in the JDK installation, otherwise just {@code toolName}
     */
    static String resolveJdkTool(String toolName, String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) return toolName;
        String exe = isWindows() ? toolName + ".exe" : toolName;

        // JDK 9+ layout: java.home/bin/jcmd
        File direct = new File(javaHome, "bin" + File.separator + exe);
        if (direct.canExecute()) {
            WatchdogLogger.fine(LOG, "Resolved {0} → [{1}]", toolName, direct.getAbsolutePath());
            return direct.getAbsolutePath();
        }

        // JDK 8 layout: java.home is .../jre, tools are in ../bin/jcmd
        File parent = new File(javaHome).getParentFile();
        if (parent != null) {
            File viaParent = new File(parent, "bin" + File.separator + exe);
            if (viaParent.canExecute()) {
                WatchdogLogger.fine(LOG, "Resolved {0} → [{1}] (JDK8 parent)", toolName, viaParent.getAbsolutePath());
                return viaParent.getAbsolutePath();
            }
        }

        // Not found in java.home — rely on PATH
        WatchdogLogger.fine(LOG,
                "{0} not found under java.home [{1}] — will search PATH", toolName, javaHome);
        return toolName;
    }

    // ── thread dump ───────────────────────────────────────────────────────────

    /**
     * Sends {@code SIGQUIT} ({@code kill -3}) to the target PID, causing the JVM
     * to write a full thread dump to its stdout.
     *
     * <p>If {@code signalDumpLog} is set and the file exists, the absolute path is
     * returned so the dashboard can display where to find the output.  Otherwise,
     * a synthetic marker file is written to {@code outputPath} containing a note
     * pointing the user to the target's stdout log.
     *
     * @param pid           OS PID of the target JVM
     * @param outputPath    path for the synthetic marker file if no log path is known
     * @param signalDumpLog optional path to the target JVM's stdout/stderr log file
     * @return path of the log file (or synthetic marker), or {@code null} on failure
     */
    private static String threadDump(long pid, String outputPath, String signalDumpLog) {
        if (isWindows()) {
            WatchdogLogger.warning(LOG,
                    "THREAD (kill -3): not supported on Windows for PID {0}", pid);
            return null;
        }
        try {
            int exit = runAndWait(new String[]{ "kill", "-3", String.valueOf(pid) }, 10);
            if (exit != 0) {
                WatchdogLogger.warning(LOG,
                        "THREAD (kill -3): exit code {0} for PID {1}", exit, pid);
                return null;
            }
            WatchdogLogger.info(LOG,
                    "THREAD (kill -3): SIGQUIT sent to PID {0}", pid);

            // If the user configured signal-dump-log, return that path directly
            if (signalDumpLog != null && !signalDumpLog.isEmpty()) {
                File logFile = new File(signalDumpLog);
                if (logFile.exists()) {
                    WatchdogLogger.info(LOG,
                            "THREAD: dump output is in target log [{0}]", logFile.getAbsolutePath());
                    return logFile.getAbsolutePath();
                }
            }

            // Write a marker file so the dashboard has something to show
            String marker = outputPath.endsWith("_threads.txt")
                    ? outputPath : outputPath + "_threads.txt";
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            String note = "SIGQUIT sent to PID " + pid + " at " + ts + "\n"
                    + "Thread dump written to the target JVM's stdout/stderr log.\n"
                    + "Configure 'signal-dump-log' in targets.properties to point to that file.\n";
            writeText(marker, note);
            return marker;
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "THREAD (kill -3): cannot run kill for PID {0}: {1}", pid, e.getMessage());
            return null;
        }
    }

    // ── heap dump — HotSpot / OpenJDK / GraalVM ───────────────────────────────

    /**
     * Writes an HPROF heap dump using {@code jcmd <pid> GC.heap_dump <path>},
     * falling back to {@code jmap -dump:format=b,file=<path> <pid>}.
     *
     * @param pid        OS PID of the target JVM
     * @param outputPath desired .hprof output path
     * @param jcmd       resolved path to jcmd (absolute or bare name for PATH lookup)
     * @param jmap       resolved path to jmap (absolute or bare name for PATH lookup)
     * @return absolute path of the written file, or {@code null} on failure
     */
    private static String heapDumpHotSpot(long pid, String outputPath, String jcmd, String jmap) {
        String path = outputPath.endsWith(".hprof") ? outputPath : outputPath + ".hprof";
        File out = new File(path);
        // Delete existing file — HotSpot refuses to overwrite
        if (out.exists()) out.delete();

        // Strategy 1: jcmd (JDK 7u40+, ships with all modern JDKs)
        try {
            int exit = runAndWait(new String[]{
                    jcmd, String.valueOf(pid), "GC.heap_dump", out.getAbsolutePath()
            }, SUBPROCESS_TIMEOUT_SEC);
            if (exit == 0 && out.exists() && out.length() > 0) {
                WatchdogLogger.info(LOG,
                        "HEAP (jcmd GC.heap_dump): written [{0}]", out.getAbsolutePath());
                return out.getAbsolutePath();
            }
            WatchdogLogger.fine(LOG,
                    "HEAP: jcmd [{0}] exited {1}, trying jmap for PID {2}", jcmd, exit, pid);
        } catch (IOException e) {
            WatchdogLogger.fine(LOG,
                    "HEAP: jcmd [{0}] failed ({1}), trying jmap for PID {2}",
                    jcmd, e.getMessage(), pid);
        }

        // Strategy 2: jmap (JDK 5+, available on all JDK installations)
        try {
            int exit = runAndWait(new String[]{
                    jmap, "-dump:format=b,file=" + out.getAbsolutePath(), String.valueOf(pid)
            }, SUBPROCESS_TIMEOUT_SEC);
            if (exit == 0 && out.exists() && out.length() > 0) {
                WatchdogLogger.info(LOG,
                        "HEAP (jmap): written [{0}]", out.getAbsolutePath());
                return out.getAbsolutePath();
            }
            WatchdogLogger.warning(LOG,
                    "HEAP: jmap [{0}] exited {1} for PID {2}", jmap, exit, pid);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "HEAP: jmap [{0}] failed ({1}) for PID {2}", jmap, e.getMessage(), pid);
        }
        return null;
    }

    // ── heap dump — IBM J9 / OpenJ9 ───────────────────────────────────────────

    /**
     * Triggers a heap dump on an IBM J9/OpenJ9 JVM by sending {@code SIGUSR1}.
     *
     * @param pid        OS PID of the target JVM
     * @param outputPath desired output path (used for jcmd; J9 may write elsewhere)
     * @param jcmd       resolved path to jcmd
     * @return absolute path if determinable, or the configured path as hint
     */
    private static String heapDumpJ9(long pid, String outputPath, String jcmd) {
        if (isWindows()) return null;

        // Try jcmd first (some OpenJ9 builds support it)
        String path = outputPath.endsWith(".phd") ? outputPath : outputPath + ".phd";
        try {
            int exit = runAndWait(new String[]{
                    jcmd, String.valueOf(pid), "GC.heap_dump", path
            }, SUBPROCESS_TIMEOUT_SEC);
            if (exit == 0 && new File(path).exists()) {
                WatchdogLogger.info(LOG, "HEAP (jcmd J9): written [{0}]", path);
                return path;
            }
        } catch (IOException ignored) { /* jcmd unavailable, fall through */ }

        // Send SIGUSR1 — J9 responds by writing a heap dump to its working directory
        try {
            int exit = runAndWait(new String[]{ "kill", "-USR1", String.valueOf(pid) }, 10);
            if (exit == 0) {
                WatchdogLogger.info(LOG,
                        "HEAP (kill -USR1): SIGUSR1 sent to IBM J9 PID {0}. "
                        + "Heap dump written to target JVM working directory.", pid);
                // We cannot know the exact path J9 chose; return the desired path as hint
                return path + " [J9 SIGUSR1 — check target JVM working directory]";
            }
            WatchdogLogger.warning(LOG,
                    "HEAP (kill -USR1): exit {0} for PID {1}", exit, pid);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "HEAP (kill -USR1): failed for PID {0}: {1}", pid, e.getMessage());
        }
        return null;
    }

    // ── core dump ─────────────────────────────────────────────────────────────

    /**
     * Non-destructive core dump on IBM J9/OpenJ9 via {@code SIGUSR2}.
     * J9 writes a system dump ({@code .dmp}) to its working directory without
     * killing the process.
     *
     * @param pid        OS PID of the target JVM
     * @param outputPath desired output path (J9 controls the actual path)
     * @return path hint, or {@code null} on failure
     */
    private static String coreDumpJ9(long pid, String outputPath) {
        if (isWindows()) return null;
        String path = outputPath.endsWith(".dmp") ? outputPath : outputPath + ".dmp";
        try {
            int exit = runAndWait(new String[]{ "kill", "-USR2", String.valueOf(pid) }, 10);
            if (exit == 0) {
                WatchdogLogger.info(LOG,
                        "CORE (kill -USR2): non-destructive system dump triggered on IBM J9 PID {0}. "
                        + "Dump written to target JVM working directory.", pid);
                return path + " [J9 SIGUSR2 — check target JVM working directory]";
            }
            WatchdogLogger.warning(LOG,
                    "CORE (kill -USR2): exit {0} for PID {1}", exit, pid);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "CORE (kill -USR2): failed for PID {0}: {1}", pid, e.getMessage());
        }
        return null;
    }

    /**
     * Non-destructive core dump on HotSpot/OpenJDK via {@code gcore}.
     * {@code gcore} uses ptrace to snapshot process memory without stopping or
     * killing the target.
     *
     * @param pid        OS PID of the target JVM
     * @param outputPath desired output path prefix ({@code gcore} appends the PID)
     * @return absolute path of the written core file, or {@code null} on failure
     */
    private static String coreDumpGcore(long pid, String outputPath) {
        if (isWindows()) return null;
        String safePath;
        try {
            safePath = new File(outputPath).getCanonicalPath();
        } catch (IOException e) {
            WatchdogLogger.warning(LOG, "CORE: invalid output path: {0}", e.getMessage());
            return null;
        }
        try {
            int exit = runAndWait(new String[]{
                    "gcore", "-o", safePath, String.valueOf(pid)
            }, SUBPROCESS_TIMEOUT_SEC);
            if (exit == 0) {
                // gcore appends .<pid> to the output path
                File withPid = new File(safePath + "." + pid);
                File bare    = new File(safePath);
                File actual  = withPid.exists() ? withPid : bare;
                WatchdogLogger.info(LOG, "CORE (gcore): written [{0}]", actual.getAbsolutePath());
                return actual.getAbsolutePath();
            }
            WatchdogLogger.warning(LOG,
                    "CORE (gcore): exit {0} for PID {1} path [{2}]", exit, pid, safePath);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "CORE (gcore): not on PATH ({0}) for PID {1}", e.getMessage(), pid);
        }
        return null;
    }

    // ── class histogram ───────────────────────────────────────────────────────

    /**
     * Captures a class histogram via {@code jcmd <pid> GC.class_histogram}
     * and writes it to {@code outputPath}.
     *
     * @param pid        OS PID of the target JVM
     * @param outputPath desired output text file path
     * @return absolute path of the written file, or {@code null} on failure
     */
    private static String classHistogram(long pid, String outputPath, String jcmd) {
        String path = outputPath.endsWith("_histogram.txt")
                ? outputPath : outputPath + "_histogram.txt";
        try {
            StringBuilder out = new StringBuilder();
            int exit = runCapture(
                    new String[]{ jcmd, String.valueOf(pid), "GC.class_histogram" },
                    SUBPROCESS_TIMEOUT_SEC,
                    out);
            if (exit == 0 && out.length() > 0) {
                writeText(path, out.toString());
                WatchdogLogger.info(LOG, "HISTOGRAM (jcmd [{0}]): written [{1}]", jcmd, path);
                return path;
            }
            WatchdogLogger.warning(LOG,
                    "HISTOGRAM (jcmd [{0}]): exit {1} for PID {2}", jcmd, exit, pid);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG,
                    "HISTOGRAM (jcmd [{0}]): failed ({1}) for PID {2}", jcmd, e.getMessage(), pid);
        }
        return null;
    }

    // ── subprocess helpers ────────────────────────────────────────────────────

    /**
     * Runs a command, waits for it to finish (up to {@code timeoutSec} seconds),
     * and returns the exit code.  Stdout/stderr output is discarded.
     */
    private static int runAndWait(String[] cmd, int timeoutSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // Drain stdout so the process doesn't block on a full pipe buffer
        Thread drainer = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                while (proc.getInputStream().read(buf) != -1) { /* discard */ }
            } catch (IOException ignored) {}
        }, "oom-drain");
        drainer.setDaemon(true);
        drainer.start();
        try {
            boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                WatchdogLogger.warning(LOG,
                        "Subprocess timed out after {0}s: {1}", timeoutSec, cmd[0]);
                return -1;
            }
            return proc.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return -1;
        }
    }

    /**
     * Runs a command, captures stdout+stderr into {@code out}, waits up to
     * {@code timeoutSec} seconds, and returns the exit code.
     */
    private static int runCapture(String[] cmd, int timeoutSec, StringBuilder out)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
                if (out.length() > 512 * 1024) break; // cap at 512 KB
            }
        }
        try {
            boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                WatchdogLogger.warning(LOG,
                        "Subprocess timed out after {0}s: {1}", timeoutSec, cmd[0]);
                return -1;
            }
            return proc.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return -1;
        }
    }

    private static void writeText(String path, String text) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.print(text);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
