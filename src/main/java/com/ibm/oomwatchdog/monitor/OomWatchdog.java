package com.ibm.oomwatchdog.monitor;

import com.ibm.oomwatchdog.alert.AlertChannel;
import com.ibm.oomwatchdog.collector.JvmDiagnosticsCollector;
import com.ibm.oomwatchdog.config.WatchdogConfig;
import com.ibm.oomwatchdog.dump.DumpType;
import com.ibm.oomwatchdog.dump.HeapDumpService;
import com.ibm.oomwatchdog.model.JvmSnapshot;
import com.ibm.oomwatchdog.model.OomRiskLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * OomWatchdog – the central orchestrator.
 *
 * <p>This class coordinates the four responsibilities it <em>does not</em>
 * own, following the Dependency-Inversion Principle:
 * <ul>
 *   <li>{@link JvmDiagnosticsCollector} – gathers raw JVM metrics</li>
 *   <li>{@link RiskAssessor}            – classifies the risk level</li>
 *   <li>{@link AlertChannel}s           – send notifications to one or more channels</li>
 *   <li>{@link HeapDumpService}         – captures a heap dump when appropriate</li>
 * </ul>
 *
 * <h3>Alert logic</h3>
 * <ul>
 *   <li>All registered channels are notified for {@code WARNING} and above.</li>
 *   <li>A heap dump is triggered on the first {@code CRITICAL} or
 *       {@code OOM_FIRING} event and not repeated until risk returns to
 *       {@code OK} (prevents dump storms).</li>
 * </ul>
 */
public final class OomWatchdog {

    private final WatchdogConfig          config;
    private final JvmDiagnosticsCollector collector;
    private final RiskAssessor            assessor;
    private final List<AlertChannel>      alertChannels;
    private final HeapDumpService         dumpService;

    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?>    task;

    // State: prevents repeated dump for the same sustained-critical episode
    private volatile boolean dumpTakenForCurrentEpisode = false;
    private volatile OomRiskLevel lastLevel = OomRiskLevel.OK;

    /**
     * @param config        tuning parameters
     * @param collector     collects raw JVM metrics
     * @param assessor      classifies risk level from a snapshot
     * @param alertChannels zero or more channels to notify (defensive copy made)
     * @param dumpService   service to capture a heap dump
     */
    public OomWatchdog(WatchdogConfig config,
                       JvmDiagnosticsCollector collector,
                       RiskAssessor assessor,
                       List<AlertChannel> alertChannels,
                       HeapDumpService dumpService) {
        this.config        = config;
        this.collector     = collector;
        this.assessor      = assessor;
        this.alertChannels = Collections.unmodifiableList(alertChannels);
        this.dumpService   = dumpService;
        this.scheduler     = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    /**
     * Starts the watchdog polling loop.  Safe to call multiple times (idempotent).
     */
    public synchronized void start() {
        if (task != null && !task.isDone()) {
            return; // already running
        }
        task = scheduler.scheduleAtFixedRate(
                this::poll,
                0L,
                config.getPollIntervalMs(),
                TimeUnit.MILLISECONDS);
        System.out.println("[OomWatchdog] Started – polling every "
                + config.getPollIntervalMs() + " ms.");
    }

    /**
     * Stops the watchdog.  Does not shut down the executor immediately;
     * the current poll cycle is allowed to complete.
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdown();
        System.out.println("[OomWatchdog] Stopped.");
    }

    // -------------------------------------------------------------------------
    // Core poll cycle
    // -------------------------------------------------------------------------

    private void poll() {
        try {
            // 1. Collect raw metrics
            JvmSnapshot raw = collector.collect();

            // 2. Classify risk
            JvmSnapshot assessed = assessor.assess(raw);

            OomRiskLevel level = assessed.getRiskLevel();

            // 3. Reset episode flag when risk returns to OK
            if (level == OomRiskLevel.OK) {
                dumpTakenForCurrentEpisode = false;
            }

            // 4. Alert all channels when risk is elevated
            if (level.ordinal() >= OomRiskLevel.WARNING.ordinal()) {
                JvmSnapshot toReport = assessed;

                // 5. Trigger selected dump types on first CRITICAL / OOM_FIRING event in episode
                if (!dumpTakenForCurrentEpisode
                        && level.ordinal() >= OomRiskLevel.CRITICAL.ordinal()
                        && !config.getDumpTypes().isEmpty()) {
                    dumpTakenForCurrentEpisode = true;
                    List<DumpType> types = new ArrayList<>(config.getDumpTypes());
                    List<String> dumpPaths = dumpService.dump(assessed, types);
                    if (!dumpPaths.isEmpty()) {
                        // Attach all paths as a semicolon-delimited string to the snapshot
                        toReport = assessed.withHeapDumpPath(String.join("; ", dumpPaths));
                    }
                }

                for (AlertChannel channel : alertChannels) {
                    try {
                        channel.alert(toReport);
                    } catch (Exception e) {
                        System.err.println("[OomWatchdog] Alert channel "
                                + channel.channelName() + " failed: " + e.getMessage());
                    }
                }
            }

            lastLevel = level;

        } catch (Exception e) {
            // The watchdog must not crash the host process.
            System.err.println("[OomWatchdog] Poll cycle error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "oom-watchdog");
            t.setDaemon(true); // does not prevent JVM shutdown
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        };
    }

    /** Returns the last assessed risk level (useful for tests / health-checks). */
    public OomRiskLevel getLastRiskLevel() {
        return lastLevel;
    }
}
