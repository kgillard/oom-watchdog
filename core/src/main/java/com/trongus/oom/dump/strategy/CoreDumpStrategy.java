package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.logging.Logger;

/**
 * Core / system dump strategy.
 *
 * <p>Strategy order:
 * <ol>
 *   <li>IBM J9 / OpenJ9 — calls {@code com.ibm.jvm.Dump#SystemDump(String)} with
 *       {@code "file=<path>"} so the {@code .dmp} is written to the watchdog's
 *       configured dump directory.  Falls back to the no-arg {@code SystemDump()}
 *       on older J9 builds.</li>
 *   <li>{@code gcore <pid>} on Linux / macOS (JDK 9+ uses {@code ProcessHandle}
 *       for a reliable PID; JDK 8 parses {@code RuntimeMXBean.getName()})</li>
 * </ol>
 *
 * <p>Returns {@code null} on Windows or when neither mechanism is available.
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.9
 * @since 1.7.0
 */
public final class CoreDumpStrategy implements DumpStrategy {

    private static final Logger LOG = WatchdogLogger.forClass(CoreDumpStrategy.class);

    @Override public DumpType type() { return DumpType.CORE; }
    @Override public String  name() { return "CoreDump-J9orGcore"; }

    @Override
    public String attempt(JvmSnapshot snapshot, String outputPath) {

        // Strategy 1: IBM J9 / OpenJ9 system dump
        if (JvmPlatform.IS_J9) {
            String result = tryJ9System(outputPath);
            if (result != null) return result;
        }

        // Strategy 2: gcore (Linux / macOS only; no-op on Windows)
        if (!isWindows()) {
            return tryGcore(outputPath);
        }
        return null;
    }

    // -------------------------------------------------------------------------

    private static String tryJ9System(String path) {
        // Replace the .core extension added by CompositeDumpService with .dmp,
        // which is the native extension for J9/OpenJ9 system dumps.
        String dmpPath = path.endsWith(".core")
                ? path.substring(0, path.length() - 5) + ".dmp"
                : path + ".dmp";
        try {
            Class<?> cls = Class.forName("com.ibm.jvm.Dump");
            // Prefer the SystemDump(String agentOptions) overload so J9 writes to
            // our chosen path.  The agent option string "file=<path>" is the
            // documented way to control the output location.
            try {
                cls.getMethod("SystemDump", String.class).invoke(null, "file=" + dmpPath);
            } catch (NoSuchMethodException e) {
                // Older J9 builds only have the no-arg variant — fall back to it.
                cls.getMethod("SystemDump").invoke(null);
            }
            WatchdogLogger.info(LOG, "CORE (J9 SystemDump) written: {0}", dmpPath);
            return new File(dmpPath).getAbsolutePath();
        } catch (ClassNotFoundException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            WatchdogLogger.warning(LOG, e, "J9 SystemDump failed: {0}", cause.toString());
            return null;
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "J9 SystemDump failed: {0}", e.getMessage());
            return null;
        }
    }

    private static String tryGcore(String outputPath) {
        long pid = JvmPlatform.PID;
        if (pid < 0) {
            WatchdogLogger.warning(LOG, "CORE dump skipped: cannot determine PID.");
            return null;
        }

        // Canonicalize the output path to prevent path-traversal attacks in the
        // dump directory when it is derived from user-supplied configuration.
        String safePath;
        try {
            safePath = new File(outputPath).getCanonicalPath();
        } catch (java.io.IOException e) {
            WatchdogLogger.warning(LOG, e, "CORE: invalid output path: {0}", e.getMessage());
            return null;
        }

        try {
            // Pass arguments as separate list elements — never interpolated into a shell string —
            // so there is no shell-injection risk regardless of path or PID content.
            ProcessBuilder pb = new ProcessBuilder(
                    "gcore", "-o", safePath, String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > 4096) break; // cap output to prevent unbounded accumulation
                }
            }
            // Apply a 60-second timeout; gcore on a large heap can be slow but should not hang forever.
            boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                WatchdogLogger.warning(LOG, "gcore timed out after 60 s for path: {0}", safePath);
                return null;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                // gcore appends the PID to the output file name: e.g. if -o is /var/dumps/oom_core_...core
                // the actual file written is /var/dumps/oom_core_...core.<pid>
                // Check for the PID-suffixed file first; fall back to the bare path.
                File withPid  = new File(safePath + "." + pid);
                File withoutPid = new File(safePath);
                File actual = withPid.exists() ? withPid : withoutPid;
                WatchdogLogger.info(LOG, "CORE dump written via gcore: {0}", actual.getAbsolutePath());
                return actual.getAbsolutePath();
            }
            WatchdogLogger.warning(LOG, "gcore exited {0} for path [{1}]: {2}", exit, safePath, out);
            return null;
        } catch (java.io.IOException e) {
            // gcore not on PATH — expected on many systems; not an error
            WatchdogLogger.warning(LOG, "CORE dump skipped: gcore not found on PATH ({0})", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
