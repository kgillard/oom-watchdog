package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Core / system dump strategy.
 *
 * <p>Strategy order:
 * <ol>
 *   <li>IBM J9 / OpenJ9 {@code com.ibm.jvm.Dump#SystemDump}</li>
 *   <li>{@code gcore <pid>} on Linux / macOS (JDK 9+ uses {@code ProcessHandle}
 *       for a reliable PID; JDK 8 parses {@code RuntimeMXBean.getName()})</li>
 * </ol>
 *
 * <p>Returns {@code null} on Windows or when neither mechanism is available.
 */
public final class CoreDumpStrategy implements DumpStrategy {

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
        try {
            Class.forName("com.ibm.jvm.Dump")
                 .getMethod("SystemDump")
                 .invoke(null);
            System.out.println("[OomWatchdog][Dump] CORE (J9 SystemDump) triggered; expected ~ " + path);
            return path + "_j9.dmp";
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] J9 SystemDump error: " + e.getMessage());
            return null;
        }
    }

    private static String tryGcore(String outputPath) {
        long pid = JvmPlatform.PID;
        if (pid < 0) {
            System.err.println("[OomWatchdog][Dump] CORE: cannot determine PID, skipping gcore.");
            return null;
        }

        // Canonicalize the output path to prevent path-traversal attacks in the
        // dump directory when it is derived from user-supplied configuration.
        String safePath;
        try {
            safePath = new File(outputPath).getCanonicalPath();
        } catch (java.io.IOException e) {
            System.err.println("[OomWatchdog][Dump] CORE: invalid output path: " + e.getMessage());
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
                System.err.println("[OomWatchdog][Dump] gcore timed out after 60 s.");
                return null;
            }
            int exit = proc.exitValue();
            if (exit == 0) {
                System.out.println("[OomWatchdog][Dump] CORE (gcore): " + safePath);
                return new File(safePath).getAbsolutePath();
            }
            System.err.println("[OomWatchdog][Dump] gcore exit " + exit + ": " + out);
            return null;
        } catch (java.io.IOException e) {
            // gcore not on PATH – expected on many systems
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
