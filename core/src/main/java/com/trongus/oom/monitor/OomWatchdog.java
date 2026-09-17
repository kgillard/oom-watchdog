package com.trongus.oom.monitor;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

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
 *
 * <h3>Thread safety</h3>
 * <p>{@code start()} and {@code stop()} are {@code synchronized} on the instance
 * to serialise lifecycle transitions.  The poll cycle runs on a single-threaded
 * {@link ScheduledExecutorService}, but the episode-dump guard uses an
 * {@link AtomicBoolean} with {@code compareAndSet} to eliminate any check-then-act
 * race should the scheduler ever be replaced with a multi-threaded one.
 * {@link #lastLevel} is an {@link AtomicReference} for consistent memory visibility.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.6.0
 * @since 1.0.0
 * @see JvmDiagnosticsCollector
 * @see RiskAssessor
 * @see AlertChannel
 * @see HeapDumpService
 */
public final class OomWatchdog {

    private static final Logger LOG = WatchdogLogger.forClass(OomWatchdog.class);

    private final WatchdogConfig          config;
    private final JvmDiagnosticsCollector collector;
    private final RiskAssessor            assessor;
    private final List<AlertChannel>      alertChannels;
    private final HeapDumpService         dumpService;

    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?>    task;

    /**
     * Guards against triggering multiple dumps within a single sustained-critical
     * episode.  Uses {@link AtomicBoolean#compareAndSet} so the check-and-set is
     * atomic even if the scheduler is ever made multi-threaded.
     */
    private final AtomicBoolean           dumpTakenThisEpisode = new AtomicBoolean(false);

    /**
     * Tracks the risk level assessed in the most recent poll cycle.
     * {@link AtomicReference} provides consistent visibility across threads
     * (e.g. {@link #getLastRiskLevel()} callers on the calling thread).
     */
    private final AtomicReference<OomRiskLevel> lastLevel =
            new AtomicReference<>(OomRiskLevel.OK);

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
        WatchdogLogger.info(LOG, "Started \u2013 polling every {0} ms.", config.getPollIntervalMs());
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
        WatchdogLogger.info(LOG, "Stopped.");
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
                dumpTakenThisEpisode.set(false);
            }

            // 4. Alert all channels when risk is elevated
            if (level.ordinal() >= OomRiskLevel.WARNING.ordinal()) {
                JvmSnapshot toReport = assessed;

                // 5. Trigger selected dump types on the first CRITICAL / OOM_FIRING event
                //    in this episode.  compareAndSet(false, true) is atomic: only the first
                //    concurrent caller wins; subsequent ones skip the dump entirely.
                if (level.ordinal() >= OomRiskLevel.CRITICAL.ordinal()
                        && !config.getDumpTypes().isEmpty()
                        && dumpTakenThisEpisode.compareAndSet(false, true)) {
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
                        WatchdogLogger.warning(LOG, e,
                                "Alert channel {0} failed: {1}",
                                channel.channelName(), e.getMessage());
                    }
                }
            }

            lastLevel.set(level);

        } catch (Exception e) {
            // The watchdog must not crash the host process.
            WatchdogLogger.warning(LOG, e, "Poll cycle error: {0}", e.getMessage());
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

    /**
     * Returns the last assessed risk level (useful for tests / health-checks).
     *
     * @return the most recent {@link OomRiskLevel} recorded by the poll cycle;
     *         {@link OomRiskLevel#OK} if no poll has completed yet
     */
    public OomRiskLevel getLastRiskLevel() {
        return lastLevel.get();
    }
}
