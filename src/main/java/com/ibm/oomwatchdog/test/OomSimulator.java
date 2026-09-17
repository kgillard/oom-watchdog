package com.ibm.oomwatchdog.test;

import java.util.ArrayList;
import java.util.List;

/**
 * OomSimulator – intentionally exhausts the JVM heap in stages to exercise
 * every OomWatchdog risk level (WARNING → CRITICAL → OOM_FIRING).
 *
 * <p>Run it in the same JVM as the watchdog with a small constrained heap:
 * <pre>
 *   java -Xmx64m -jar oom-watchdog.jar --test-mode
 * </pre>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li><b>Slow-leak phase</b> (0 – 60 s, configurable): allocates 1 MB chunks
 *       every 500 ms, intentionally retaining references so GC cannot reclaim
 *       them.  This is the "memory leak" scenario.  The post-GC trend slope
 *       will turn positive and the watchdog will escalate to WARNING.</li>
 *   <li><b>Burst phase</b>: allocates 5 MB chunks every 200 ms until the heap
 *       is near exhaustion.  This forces a CRITICAL alert and triggers
 *       the configured dumps.</li>
 *   <li><b>OOM phase</b>: allocates one final large array that cannot be
 *       satisfied, causing {@code OutOfMemoryError}.  The watchdog should
 *       detect this via an uncaught exception handler registered here.</li>
 * </ol>
 *
 * <p>The simulator installs a {@link Thread.UncaughtExceptionHandler} on its
 * own thread that prints a clear message when the OOM actually fires, so the
 * operator can confirm the watchdog detected and dumped it beforehand.
 */
public final class OomSimulator implements Runnable {

    /** Notified at each simulation phase transition so WatchdogMain can log it. */
    public interface PhaseListener {
        void onPhase(String phaseName, String description);
    }

    // ── tuning ────────────────────────────────────────────────────────────────
    private static final int  LEAK_CHUNK_BYTES     = 1 * 1024 * 1024;  // 1 MB
    private static final long LEAK_PAUSE_MS        = 500;
    private static final int  BURST_CHUNK_BYTES    = 5 * 1024 * 1024;  // 5 MB
    private static final long BURST_PAUSE_MS       = 200;

    private final long          leakDurationMs;
    private final PhaseListener listener;

    /** Live allocations – never cleared, so GC cannot reclaim them. */
    private final List<byte[]> retainedHeap = new ArrayList<>();

    /**
     * @param leakDurationMs how long to run the slow-leak phase (ms)
     * @param listener       optional phase-change callback; may be null
     */
    public OomSimulator(long leakDurationMs, PhaseListener listener) {
        this.leakDurationMs = leakDurationMs;
        this.listener       = listener;
    }

    @Override
    public void run() {
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            if (e instanceof OutOfMemoryError) {
                System.err.println("\n[OomSimulator] *** OutOfMemoryError FIRED ***");
                System.err.println("[OomSimulator] " + e.getMessage());
                System.err.println("[OomSimulator] The watchdog should have captured "
                        + "WARNING and CRITICAL alerts + any configured dumps before this point.");
            } else {
                System.err.println("[OomSimulator] Unexpected error: " + e);
            }
        });

        printBanner();
        runLeakPhase();
        runBurstPhase();
        runOomPhase();
    }

    // ── phases ────────────────────────────────────────────────────────────────

    private void runLeakPhase() {
        emit("LEAK",
             "Allocating " + (LEAK_CHUNK_BYTES / (1024 * 1024)) + " MB every "
             + LEAK_PAUSE_MS + " ms for " + (leakDurationMs / 1000) + " s "
             + "(references retained – simulates a memory leak)");

        long deadline = System.currentTimeMillis() + leakDurationMs;
        while (System.currentTimeMillis() < deadline) {
            retainedHeap.add(new byte[LEAK_CHUNK_BYTES]);
            sleep(LEAK_PAUSE_MS);
        }
    }

    private void runBurstPhase() {
        emit("BURST",
             "Allocating " + (BURST_CHUNK_BYTES / (1024 * 1024)) + " MB every "
             + BURST_PAUSE_MS + " ms until heap is exhausted");

        Runtime rt = Runtime.getRuntime();
        while (true) {
            long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            if (free < BURST_CHUNK_BYTES * 2L) {
                break; // hand off to OOM phase
            }
            retainedHeap.add(new byte[BURST_CHUNK_BYTES]);
            sleep(BURST_PAUSE_MS);
        }
    }

    private void runOomPhase() {
        emit("OOM",
             "Allocating one final oversized array to trigger OutOfMemoryError");
        // This will throw; the uncaught handler above will print the confirmation
        @SuppressWarnings("unused")
        byte[] finalBlow = new byte[Integer.MAX_VALUE];
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void emit(String phase, String description) {
        String msg = "[OomSimulator] === Phase: " + phase + " === " + description;
        System.out.println(msg);
        if (listener != null) {
            try {
                listener.onPhase(phase, description);
            } catch (Exception ignored) {}
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║              OOM Watchdog – Test Mode                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  This mode intentionally exhausts the heap to exercise   ║");
        System.out.println("║  WARNING → CRITICAL → OOM_FIRING alert escalation.       ║");
        System.out.println("║                                                          ║");
        System.out.println("║  Run with a small heap to see results quickly:           ║");
        System.out.println("║    java -Xmx64m -jar oom-watchdog.jar --test-mode        ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
