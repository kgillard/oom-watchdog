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
 * <h2>Alert logic</h2>
 * <ul>
 *   <li>All registered channels are notified for {@code WARNING} and above.</li>
 *   <li>A heap dump is triggered on the first {@code CRITICAL} or
 *       {@code OOM_FIRING} event within a sustained episode and not repeated
 *       until risk returns to {@code OK} (prevents dump storms).</li>
 *   <li>A dump may also be triggered independently on any poll cycle when any of
 *       the per-metric thresholds in {@link WatchdogConfig} ({@code gcDumpThreshold},
 *       {@code heapDumpThreshold}, {@code nurseryDumpThreshold}) is exceeded.
 *       The same episode guard ({@code dumpTakenThisEpisode}) is used, so at most
 *       one dump fires per elevated episode regardless of which trigger fires first.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@code start()} and {@code stop()} are {@code synchronized} on the instance
 * to serialise lifecycle transitions.  The poll cycle runs on a single-threaded
 * {@link ScheduledExecutorService}, but the episode-dump guard uses an
 * {@link AtomicBoolean} with {@code compareAndSet} to eliminate any check-then-act
 * race should the scheduler ever be replaced with a multi-threaded one.
 * {@link #lastLevel} is an {@link AtomicReference} for consistent memory visibility.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.10
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
     * The most recent fully-assessed {@link JvmSnapshot}, updated atomically
     * after every poll cycle.  {@code null} until the first poll completes.
     */
    private final AtomicReference<JvmSnapshot> lastSnapshot =
            new AtomicReference<>(null);

    /**
     * Constructs a fully wired {@code OomWatchdog} instance.
     *
     * @param config        tuning parameters controlling thresholds, dump behaviour, and polling rate
     * @param collector     collects raw JVM metrics each poll cycle
     * @param assessor      classifies the risk level from a raw snapshot
     * @param alertChannels zero or more channels to notify when risk is {@code WARNING} or above
     *                      (a defensive copy is made internally)
     * @param dumpService   service used to capture diagnostic dumps at {@code CRITICAL} level
     *                      or when a per-metric dump threshold is exceeded
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
     * Starts the watchdog polling loop on a single background daemon thread.
     * Safe to call multiple times — subsequent calls while the loop is running are no-ops.
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
     * Stops the watchdog, cancelling the scheduled polling task.
     * Does not interrupt the executor immediately; the current poll cycle
     * is allowed to complete before the scheduler shuts down.
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

            // 5b. Check dump thresholds every poll cycle (independent of risk level)
            checkDumpThresholds(assessed);

            lastLevel.set(level);
            lastSnapshot.set(assessed);

        } catch (Exception e) {
            // The watchdog must not crash the host process.
            WatchdogLogger.warning(LOG, e, "Poll cycle error: {0}", e.getMessage());
        }
    }

    /**
     * Checks whether any per-metric dump threshold is exceeded and, if so, triggers a dump
     * (using the same episode-guard AtomicBoolean to prevent dump storms).
     *
     * <p>A threshold of {@code -1} means disabled; never triggers on {@code -1}.
     * The comparison uses {@code compareAndSet(false, true)} so only one dump fires per episode.
     *
     * @param snap the assessed snapshot to evaluate
     */
    private void checkDumpThresholds(JvmSnapshot snap) {
        if (config.getDumpTypes().isEmpty()) {
            return;
        }
        double gcDump      = config.getGcDumpThreshold();
        double heapDump    = config.getHeapDumpThreshold();
        double nurseryDump = config.getNurseryDumpThreshold();

        boolean gcTriggered      = gcDump      > 0 && snap.getGcOverheadRatio()  >= gcDump;
        boolean heapTriggered    = heapDump    > 0 && snap.getHeapUsedRatio()     >= heapDump;
        boolean nurseryTriggered = nurseryDump > 0
                && !Double.isNaN(snap.getNurseryUsedRatio())
                && snap.getNurseryUsedRatio() >= nurseryDump;

        if ((gcTriggered || heapTriggered || nurseryTriggered)
                && dumpTakenThisEpisode.compareAndSet(false, true)) {
            List<DumpType> types = new ArrayList<>(config.getDumpTypes());
            List<String> dumpPaths = dumpService.dump(snap, types);
            if (!dumpPaths.isEmpty()) {
                WatchdogLogger.info(LOG, "Dump-threshold triggered dump for target [{0}]: {1}",
                        snap.getTargetName() != null ? snap.getTargetName() : "self",
                        String.join("; ", dumpPaths));
            }
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

    /**
     * Returns the most recent fully-assessed {@link JvmSnapshot}, or {@code null}
     * if no poll cycle has completed yet.
     *
     * @return latest snapshot, or {@code null}
     */
    public JvmSnapshot getLastSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * Triggers an on-demand diagnostic dump of the requested type against the
     * self-monitoring JVM.
     *
     * <p>This method is called by {@link MetricsHttpServer} when the dashboard
     * user clicks one of the <em>Thread Dump</em>, <em>Heap Dump</em>, or
     * <em>Core Dump</em> action buttons.  Unlike the automated dump path, this
     * call is <strong>not</strong> subject to the episode-guard
     * ({@code dumpTakenThisEpisode}) — the operator has explicitly requested a
     * dump regardless of the current risk level.
     *
     * <p>The dump is executed synchronously on the calling thread (an HTTP
     * worker thread).  If the dump succeeds the absolute file path is returned;
     * if no strategy in the chain succeeds an empty string is returned and a
     * warning is logged.
     *
     * @param type the dump type to produce; must not be {@code null}
     * @return the absolute path of the written dump file, or an empty string if
     *         the dump could not be produced
     */
    public String triggerDump(DumpType type) {
        JvmSnapshot snap = lastSnapshot.get();
        // If no snapshot yet, create a minimal synthetic one for path-building
        if (snap == null) {
            snap = new JvmSnapshot.Builder().targetName("self").build();
        }
        List<String> paths = dumpService.dump(snap, java.util.Collections.singletonList(type));
        return paths.isEmpty() ? "" : paths.get(0);
    }

    /**
     * Builds the output file path for a dump of the given type without executing the dump.
     *
     * <p>Used by {@link MetricsHttpServer} to obtain the target output path when routing
     * on-demand dumps to a remote JVM via {@link com.trongus.oom.remote.JmxDiagnosticsCollector}.
     * The path is built using the same logic as {@link #triggerDump(DumpType)} — from the
     * configured {@code dumpDirectory}, the current snapshot's process name, and a timestamp.
     *
     * @param type the dump type; determines the file extension
     * @return the suggested absolute output path, or {@code null} if path building fails
     */
    public String buildDumpPath(DumpType type) {
        if (!(dumpService instanceof com.trongus.oom.dump.CompositeDumpService)) {
            return null;
        }
        JvmSnapshot snap = lastSnapshot.get();
        if (snap == null) {
            snap = new JvmSnapshot.Builder().targetName("self").build();
        }
        return ((com.trongus.oom.dump.CompositeDumpService) dumpService).buildOutputPath(snap, type);
    }
}
