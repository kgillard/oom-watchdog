package com.trongus.oom.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java fallback memory exhauster used when dynamic compilation is unavailable.
 *
 * <h2>Design Rationale</h2>
 * <p>Certain target runtime environments (such as GraalVM Native Image builds, JRE-only runtime
 * distributions lacking {@code tools.jar} or {@code java.compiler}, or constrained cloud containers)
 * do not have access to {@code javax.tools.JavaCompiler}. {@code BuiltInHeapExhauster} provides an
 * identical three-phase allocation sequence compiled ahead-of-time, guaranteeing that test harness
 * scenarios can deterministically exercise watchdog threshold escalations without requiring dynamic
 * bytecode emission.
 *
 * <h3>Exhaustion Sequence</h3>
 * <ol>
 *   <li><b>Phase 1 (Slow Leak):</b> Performs 12 consecutive 2&nbsp;MB byte array allocations retained in
 *       a local collection, issuing {@link System#gc()} hints every 3 allocations to provide the watchdog
 *       with clear post-GC trend data.</li>
 *   <li><b>Phase 2 (Burst):</b> Continuously appends 6&nbsp;MB chunks at 100&nbsp;ms intervals until available
 *       free heap drops below 4&nbsp;MB (2 &times; chunk size).</li>
 *   <li><b>Phase 3 (Terminal OOM):</b> Allocates a single {@link Integer#MAX_VALUE}-sized array, guaranteeing
 *       an immediate {@link OutOfMemoryError}.</li>
 * </ol>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.9
 * @since 1.0.0
 * @see com.trongus.oom.harness.DynamicOomClassGenerator
 * @see com.trongus.oom.harness.TestHarnessMain
 */
public final class BuiltInHeapExhauster {

    /** Size in bytes of each discrete allocation chunk during the leak phase (2 MB). */
    private static final long CHUNK_BYTES = 2L * 1024L * 1024L; // 2 MB

    /**
     * Private constructor to prevent direct instantiation of this static utility class.
     */
    private BuiltInHeapExhauster() {}

    /**
     * Executes the three-phase heap exhaustion routine on the current thread.
     * <p>Installs a thread-specific {@link Thread.UncaughtExceptionHandler} to confirm that the
     * watchdog had prior opportunity to fire alerts and dumps, then sequentially executes the
     * slow leak, burst, and terminal OOM phases.
     */
    public static void exhaust() {
        // Attach uncaught exception handler to verify watchdog warning before crash
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            if (e instanceof OutOfMemoryError) {
                System.err.println("[BuiltInExhauster] *** OutOfMemoryError: " + e.getMessage() + " ***");
                System.err.println("[BuiltInExhauster] The watchdog should have captured "
                        + "WARNING + CRITICAL alerts and dump files before this point.");
            }
        });

        List<byte[]> sink = new ArrayList<>();

        // Phase 1: Slow memory leak with periodic GC hints
        System.out.println("[BuiltInExhauster] Phase 1: slow leak (12 × 2 MB with GC hints)");
        for (int i = 0; i < 12; i++) {
            sink.add(new byte[(int) Math.min(CHUNK_BYTES, Integer.MAX_VALUE)]);
            if (i % 3 == 0) {
                System.gc();
            }
            sleep(300);
        }

        // Phase 2: Rapid burst allocations
        System.out.println("[BuiltInExhauster] Phase 2: burst");
        Runtime rt = Runtime.getRuntime();
        while (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()) > CHUNK_BYTES * 2) {
            sink.add(new byte[(int) Math.min(CHUNK_BYTES * 3, Integer.MAX_VALUE)]);
            sleep(100);
        }

        // Phase 3: Final terminal OOM allocation
        System.out.println("[BuiltInExhauster] Phase 3: OOM");
        // Force OOM – this will throw OutOfMemoryError
        byte[] finalBlow = new byte[Integer.MAX_VALUE];
        sink.add(finalBlow);
    }

    /**
     * Suspends execution on the current thread for the specified duration.
     *
     * @param ms duration to sleep in milliseconds
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Restore interrupted status for downstream cooperative cancellation
            Thread.currentThread().interrupt();
        }
    }
}
