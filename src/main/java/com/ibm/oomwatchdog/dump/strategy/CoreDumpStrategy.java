package com.ibm.oomwatchdog.dump.strategy;

import com.ibm.oomwatchdog.dump.DumpType;
import com.ibm.oomwatchdog.model.JvmSnapshot;
import com.ibm.oomwatchdog.platform.JvmPlatform;

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
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "gcore", "-o", outputPath, String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            int exit = proc.waitFor();
            if (exit == 0) {
                System.out.println("[OomWatchdog][Dump] CORE (gcore): " + outputPath);
                return new File(outputPath).getAbsolutePath();
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
