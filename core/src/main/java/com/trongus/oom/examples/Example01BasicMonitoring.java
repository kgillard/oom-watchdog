package com.trongus.oom.examples;

import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Collections;

/**
 * Example 01 — Minimal production-ready monitoring with console alerts only.
 *
 * <h2>What this example shows</h2>
 * <p>The absolute minimum code required to embed OOM Watchdog in any JVM application.
 * Only a {@link ConsoleAlertChannel} is used — no file I/O, no network, no dump files.
 * This is the fastest way to get started and is useful in development or CI environments
 * where you want instant visibility into memory pressure without any configuration.
 *
 * <h2>Key concepts</h2>
 * <ul>
 *   <li>{@link WatchdogConfig} is <em>immutable</em>; all settings are fixed at build time
 *       and validated before the config object is returned.</li>
 *   <li>The watchdog runs a single <em>daemon</em> thread so it never prevents the JVM
 *       from shutting down normally.</li>
 *   <li>Calling {@code start()} is idempotent; calling it a second time is a safe no-op.</li>
 *   <li>Always call {@code stop()} — or register a shutdown hook — to release the
 *       internal {@link java.util.concurrent.ScheduledExecutorService} cleanly.</li>
 * </ul>
 *
 * <h2>Expected output</h2>
 * <p>Nothing is printed until heap usage exceeds 80 % of {@code -Xmx}.  At that point
 * you will see a multi-line {@code === JVM OOM Alert ===} block on stdout every 5 seconds.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.0.0
 * @see Example02AlertChannels
 * @see Example03DumpOnCritical
 */
public final class Example01BasicMonitoring {

    /** Utility class — construction is not permitted. */
    private Example01BasicMonitoring() {}

    /**
     * Entry point for the basic monitoring example.
     *
     * <p>The main thread blocks indefinitely.  Press {@code Ctrl-C} to exit;
     * the shutdown hook will stop the watchdog cleanly before the JVM exits.
     *
     * @param args command-line arguments (not used by this example)
     * @throws InterruptedException if the main thread is interrupted while waiting
     */
    public static void main(String[] args) throws InterruptedException {

        // ── Step 1: Build an immutable configuration ──────────────────────────
        //
        // WatchdogConfig.defaults() pre-loads sensible production values:
        //   warningHeapThreshold  = 0.80  (alert when heap > 80% of -Xmx)
        //   criticalHeapThreshold = 0.90  (escalate when heap > 90% of -Xmx)
        //   gcOverheadThreshold   = 0.50  (alert when >50% of uptime is GC time)
        //   pollIntervalMs        = 5_000 (check every 5 seconds)
        //
        // .build() validates all values and throws IllegalArgumentException for
        // anything out of range, so misconfiguration is caught immediately.
        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .pollIntervalMs(5_000L)
                .build();

        // ── Step 2: Wire the watchdog ──────────────────────────────────────────
        //
        // OomWatchdog accepts its four collaborators through constructor injection
        // (Dependency-Inversion Principle).  All wiring happens here; the watchdog
        // itself never creates any of these objects.
        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config), // reads JVM MXBeans
                new ThresholdRiskAssessor(config),       // three-level decision tree
                Collections.singletonList(new ConsoleAlertChannel()), // stdout alerts
                new CompositeDumpService(config)         // no dump types configured → no dumps
        );

        // ── Step 3: Register a shutdown hook for clean termination ─────────────
        //
        // The shutdown hook is called by the JVM on Ctrl-C, SIGTERM, or normal exit.
        // It cancels the scheduled poll task and shuts down the internal executor
        // so no threads are left dangling.
        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        // ── Step 4: Start monitoring ───────────────────────────────────────────
        //
        // start() schedules the poll cycle at a fixed rate on a daemon thread.
        // The first poll fires immediately (initialDelay = 0).
        watchdog.start();

        System.out.println("[Example01] OOM Watchdog running. Press Ctrl-C to stop.");
        System.out.println("[Example01] Heap warning at 80%, critical at 90%, polling every 5 s.");

        // Block the main thread so the example does not exit immediately.
        // In a real application the main thread is busy serving requests;
        // you do not need this join() call.
        Thread.currentThread().join();
    }
}
