package com.trongus.oom.test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code OomSimulator} intentionally exhausts the JVM heap in controlled stages
 * to exercise every alert risk level ({@code WARNING} &rarr; {@code CRITICAL} &rarr; {@code OOM_FIRING})
 * defined by the OOM Watchdog subsystem.
 *
 * <h2>Design Rationale</h2>
 * <p>Testing out-of-memory watchdog behavior deterministically requires a structured,
 * multi-phase heap exhaustion mechanism rather than a single uncontrolled allocation burst.
 * This simulator executes three distinct phases:
 * <ol>
 *   <li><b>Slow-leak phase</b> (configurable duration): Allocates discrete 1&nbsp;MB byte arrays
 *       at fixed 500&nbsp;ms intervals while deliberately retaining references in a collection.
 *       Because the references remain reachable, garbage collection cannot reclaim them. This models
 *       a classic application memory leak, forcing a positive post-GC trend slope and provoking
 *       a {@code WARNING} risk level transition.</li>
 *   <li><b>Burst phase</b>: Rapidly allocates 5&nbsp;MB byte arrays at 200&nbsp;ms intervals until
 *       the remaining free heap space drops below a safety threshold (2 &times; burst chunk size).
 *       This escalates the watchdog to {@code CRITICAL} risk level and triggers automated diagnostic
 *       dump capture (heap dumps, thread dumps, class histograms, etc.).</li>
 *   <li><b>OOM phase</b>: Attempts to allocate a single oversized byte array ({@link Integer#MAX_VALUE}
 *       bytes) that the JVM heap cannot satisfy, guaranteeing an immediate {@link OutOfMemoryError}.
 *       The installed {@link java.lang.Thread.UncaughtExceptionHandler} intercepts the error to log
 *       confirmation that the watchdog was given opportunity to alert and dump diagnostics prior to death.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>Run in the same JVM as the watchdog with a constrained heap limit:
 * <pre>{@code
 *   java -Xmx64m -jar oom-watchdog.jar --test-mode
 * }</pre>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.6
 * @since 1.0.0
 * @see com.trongus.oom.monitor.OomWatchdog
 * @see com.trongus.oom.model.OomRiskLevel
 */
public final class OomSimulator implements Runnable {

    /**
     * Callback interface invoked whenever the simulation transitions between operational phases.
     * Enables callers (such as command-line entry points or test runners) to observe and log
     * progression through the leak, burst, and OOM stages.
     *
     * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
     * @version 1.7.12.6
     * @since 1.0.0
     */
    public interface PhaseListener {
        /**
         * Invoked when a new simulation phase begins.
         *
         * @param phaseName   short identifier for the phase (e.g., "LEAK", "BURST", "OOM")
         * @param description human-readable summary of the actions performed during this phase
         */
        void onPhase(String phaseName, String description);
    }

    // ── tuning ────────────────────────────────────────────────────────────────

    /** Size of each allocated byte array chunk during the slow-leak phase (1 MB). */
    private static final int LEAK_CHUNK_BYTES = 1 * 1024 * 1024; // 1 MB

    /** Pause duration in milliseconds between successive allocations during the slow-leak phase. */
    private static final long LEAK_PAUSE_MS = 500;

    /** Size of each allocated byte array chunk during the aggressive burst phase (5 MB). */
    private static final int BURST_CHUNK_BYTES = 5 * 1024 * 1024; // 5 MB

    /** Pause duration in milliseconds between successive allocations during the burst phase. */
    private static final long BURST_PAUSE_MS = 200;

    /** Total duration in milliseconds allocated to running the slow-leak phase before escalating. */
    private final long leakDurationMs;

    /** Optional listener notified when simulation phases transition; may be {@code null}. */
    private final PhaseListener listener;

    /**
     * Live heap references accumulator. Chunks added here are never cleared or dereferenced,
     * ensuring that garbage collector cycles cannot reclaim the underlying memory.
     */
    private final List<byte[]> retainedHeap = new ArrayList<>();

    /**
     * Constructs a new {@code OomSimulator} with the specified leak duration and optional listener.
     *
     * @param leakDurationMs how long to run the slow-leak phase in milliseconds before bursting
     * @param listener       optional phase-change callback listener; may be {@code null} if notification is not needed
     */
    public OomSimulator(long leakDurationMs, PhaseListener listener) {
        this.leakDurationMs = leakDurationMs;
        this.listener       = listener;
    }

    /**
     * Executes the simulation workflow on the calling thread.
     * <p>Installs a thread-specific {@link Thread.UncaughtExceptionHandler} to capture and report
     * the terminal {@link OutOfMemoryError}, prints a visual startup banner, and sequentially
     * executes the leak, burst, and terminal OOM phases.
     */
    @Override
    public void run() {
        // Install uncaught exception handler to confirm OOM trigger
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

        // Display test mode announcement banner
        printBanner();

        // Phase 1: Slow memory leak
        runLeakPhase();

        // Phase 2: Rapid memory burst
        runBurstPhase();

        // Phase 3: Final terminal OOM trigger
        runOomPhase();
    }

    // ── phases ────────────────────────────────────────────────────────────────

    /**
     * Executes Phase 1 (Slow Leak).
     * Allocates {@value #LEAK_CHUNK_BYTES} bytes every {@value #LEAK_PAUSE_MS} ms until
     * {@link #leakDurationMs} has elapsed, storing references in {@link #retainedHeap}
     * to prevent garbage collection reclamation.
     */
    private void runLeakPhase() {
        emit("LEAK",
             "Allocating " + (LEAK_CHUNK_BYTES / (1024 * 1024)) + " MB every "
             + LEAK_PAUSE_MS + " ms for " + (leakDurationMs / 1000) + " s "
             + "(references retained – simulates a memory leak)");

        long deadline = System.currentTimeMillis() + leakDurationMs;
        // Loop and retain 1 MB allocations until the configured duration expires
        while (System.currentTimeMillis() < deadline) {
            retainedHeap.add(new byte[LEAK_CHUNK_BYTES]);
            sleep(LEAK_PAUSE_MS);
        }
    }

    /**
     * Executes Phase 2 (Burst).
     * Continuously allocates {@value #BURST_CHUNK_BYTES} bytes every {@value #BURST_PAUSE_MS} ms
     * until the estimated free heap space drops below twice the burst chunk size.
     */
    private void runBurstPhase() {
        emit("BURST",
             "Allocating " + (BURST_CHUNK_BYTES / (1024 * 1024)) + " MB every "
             + BURST_PAUSE_MS + " ms until heap is exhausted");

        Runtime rt = Runtime.getRuntime();
        // Rapidly allocate until available free heap drops below safety margin
        while (true) {
            long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            if (free < BURST_CHUNK_BYTES * 2L) {
                break; // hand off to OOM phase
            }
            retainedHeap.add(new byte[BURST_CHUNK_BYTES]);
            sleep(BURST_PAUSE_MS);
        }
    }

    /**
     * Executes Phase 3 (OOM).
     * Attempts to allocate an array of size {@link Integer#MAX_VALUE} bytes, intentionally
     * throwing {@link OutOfMemoryError} to terminate the test and verify handler execution.
     */
    private void runOomPhase() {
        emit("OOM",
             "Allocating one final oversized array to trigger OutOfMemoryError");
        // This will throw OutOfMemoryError; the uncaught handler registered in run() will catch and report it
        @SuppressWarnings("unused")
        byte[] finalBlow = new byte[Integer.MAX_VALUE];
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Emits a phase transition notification to standard output and dispatches to
     * {@link #listener} if registered.
     *
     * @param phase       the identifier of the phase
     * @param description human-readable details about the phase
     */
    private void emit(String phase, String description) {
        String msg = "[OomSimulator] === Phase: " + phase + " === " + description;
        System.out.println(msg);
        if (listener != null) {
            try {
                listener.onPhase(phase, description);
            } catch (Exception ignored) {
                // Suppress listener exceptions to avoid interrupting simulation flow
            }
        }
    }

    /**
     * Pauses the current thread for the specified duration, restoring interrupted status if interrupted.
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

    /**
     * Prints the ASCII startup banner for the OOM test mode.
     */
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
