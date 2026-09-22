package com.trongus.oom.remote;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.monitor.GcHistoryStore;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Multi-target JVM monitoring daemon and orchestrator.
 *
 * <h2>Architecture</h2>
 * <p>Spawns and manages dedicated {@link OomWatchdog} monitoring instances for each
 * configured {@link TargetDescriptor}.
 *
 * <pre>
 * WatchdogDaemon
 *   ├── Target: hostcontext ──▶ OomWatchdog (JmxCollector, RiskAssessor, AlertChannels)
 *   ├── Target: tomcat      ──▶ OomWatchdog (JmxCollector, RiskAssessor, AlertChannels)
 *   └── Target: liberty     ──▶ OomWatchdog (JmxCollector, RiskAssessor, AlertChannels)
 * </pre>
 *
 * <h2>Fault Tolerance</h2>
 * <p>During {@link #start()}, targets are initialized in isolation; an error or unreachable JMX
 * connection on one target does not prevent other targets from being monitored.
 *
 * <h2>Thread Safety</h2>
 * <p>All lifecycle operations ({@link #start()} and {@link #stop()}) are thread-safe and guarded.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.2
 * @since 1.7.0
 * @see TargetDescriptor
 * @see TargetRegistry
 * @see JmxDiagnosticsCollector
 * @see OomWatchdog
 */
public final class WatchdogDaemon implements Closeable {

    private static final Logger LOG = WatchdogLogger.forClass(WatchdogDaemon.class);

    private final List<TargetDescriptor> targets;
    private final WatchdogConfig         baseConfig;
    private final List<AlertChannel>     sharedAlertChannels;

    /**
     * When {@code true}, the base-config warning and critical heap thresholds
     * (supplied via {@code --warn-threshold} / {@code --crit-threshold} on the CLI)
     * override every target's per-target thresholds.  Intended only for testing
     * LEEF syslog delivery; must not be set in production.
     */
    private final boolean overrideThresholds;

    private final Map<String, OomWatchdog>             activeWatchdogs  = new LinkedHashMap<>();
    private final Map<String, JmxDiagnosticsCollector> collectors       = new LinkedHashMap<>();
    private final Map<String, String>                  dumpApiUrls      = new LinkedHashMap<>();
    private final Map<String, DumpApiServer>           autoDumpServers  = new LinkedHashMap<>();

    /**
     * Shared GC history store written to by every watchdog on each poll cycle.
     * All targets share one {@link GcHistoryStore} instance because it is
     * internally per-target keyed via file names.
     */
    private final GcHistoryStore gcHistoryStore = new GcHistoryStore();

    /**
     * Shared thread pool for all watchdog poll tasks.
     * Sized to the number of targets so every target can poll in parallel without
     * one slow JMX connection delaying another.  Uses daemon threads so the pool
     * does not prevent JVM shutdown.
     */
    private volatile ScheduledExecutorService sharedPool;

    private volatile boolean running = false;

    /**
     * Constructs a daemon instance with specified targets, base configuration, shared alert channels,
     * and an optional threshold-override flag for syslog testing.
     *
     * @param targets             list of targets to monitor; must not be null or empty
     * @param baseConfig          base watchdog configuration; must not be null
     * @param sharedAlertChannels shared alert channels (e.g. QRadar); nullable or empty
     * @param overrideThresholds  when {@code true}, the base-config thresholds override
     *                            every target's per-target thresholds — for testing only
     */
    public WatchdogDaemon(List<TargetDescriptor> targets,
                          WatchdogConfig baseConfig,
                          List<AlertChannel> sharedAlertChannels,
                          boolean overrideThresholds) {
        Objects.requireNonNull(targets, "targets must not be null");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets list must not be empty");
        }
        this.targets             = Collections.unmodifiableList(new ArrayList<>(targets));
        this.baseConfig          = Objects.requireNonNull(baseConfig, "baseConfig must not be null");
        this.sharedAlertChannels = sharedAlertChannels != null
                ? Collections.unmodifiableList(new ArrayList<>(sharedAlertChannels))
                : Collections.emptyList();
        this.overrideThresholds  = overrideThresholds;
    }

    /**
     * Constructs a daemon instance with specified targets, base configuration, and shared alert channels.
     *
     * @param targets             list of targets to monitor; must not be null or empty
     * @param baseConfig          base watchdog configuration; must not be null
     * @param sharedAlertChannels shared alert channels (e.g. QRadar); nullable or empty
     */
    public WatchdogDaemon(List<TargetDescriptor> targets,
                          WatchdogConfig baseConfig,
                          List<AlertChannel> sharedAlertChannels) {
        this(targets, baseConfig, sharedAlertChannels, false);
    }

    /**
     * Convenience constructor with no shared channels.
     *
     * @param targets    list of targets to monitor
     * @param baseConfig base configuration
     */
    public WatchdogDaemon(List<TargetDescriptor> targets, WatchdogConfig baseConfig) {
        this(targets, baseConfig, Collections.emptyList(), false);
    }

    /**
     * Starts monitoring all configured targets.
     */
    public synchronized void start() {
        if (running) {
            WatchdogLogger.warning(LOG, "WatchdogDaemon is already running.");
            return;
        }

        WatchdogLogger.info(LOG, "Starting WatchdogDaemon with {0} target(s)...", targets.size());

        // Create a shared pool sized to the number of targets so all can poll in parallel.
        // JMX polls are mostly blocked on I/O, so a pool equal to the target count is appropriate.
        int poolSize = Math.max(2, targets.size());
        sharedPool = Executors.newScheduledThreadPool(poolSize, sharedDaemonThreadFactory());
        WatchdogLogger.info(LOG,
                "Created shared poll thread pool with {0} threads for {1} targets",
                poolSize, targets.size());

        for (TargetDescriptor target : targets) {
            try {
                // Sanitise target name for logging to prevent log-injection (CWE-117).
                // Target name and JMX URL originate from user-controlled properties files.
                String logSafeName = target.getName()
                        .replaceAll("[\r\n\t]", " ").trim();
                String logSafeUrl = target.getJmxUrl()
                        .replaceAll("[\r\n\t]", " ").trim();

                // Per-target config derived from base config + target overrides.
                // heapDumpDirectory is only overridden when explicitly set in targets.properties;
                // otherwise the base config value (from --dump-dir) is inherited.
                //
                // When overrideThresholds is true (--warn-threshold / --crit-threshold supplied
                // on the CLI), the base-config thresholds are used for every target instead of
                // the per-target values from targets.properties.  This mode is intended only for
                // verifying LEEF syslog delivery and must never be used in production.
                WatchdogConfig.Builder targetCfgBuilder = baseConfig.toBuilder()
                        .pollIntervalMs(target.getPollIntervalMs())
                        .gcDumpThreshold(target.getGcDumpThreshold())
                        .heapDumpThreshold(target.getHeapDumpThreshold())
                        .nurseryDumpThreshold(target.getNurseryDumpThreshold());

                if (overrideThresholds) {
                    // Keep base-config warn/crit/gc thresholds — log the active values once.
                    // Disable ALL dump types so no heap/thread/core dumps fire during the
                    // syslog test.  Only LEEF alert events are sent; no files are written.
                    WatchdogLogger.warning(LOG,
                            "Target [{0}]: threshold override active — using warn={1,number,0.##} " +
                            "crit={2,number,0.##} (per-target values ignored); dumps suppressed for " +
                            "syslog test",
                            logSafeName,
                            baseConfig.getWarningHeapThreshold(),
                            baseConfig.getCriticalHeapThreshold());
                    targetCfgBuilder.dumpTypes(java.util.EnumSet.noneOf(
                            com.trongus.oom.dump.DumpType.class));
                } else {
                    targetCfgBuilder
                            .warningHeapThreshold(target.getWarnThreshold())
                            .criticalHeapThreshold(target.getCritThreshold())
                            .gcOverheadThreshold(target.getGcThreshold());
                    if (target.getDumpDirectory() != null) {
                        targetCfgBuilder.heapDumpDirectory(target.getDumpDirectory());
                    }
                    if (!target.getDumpTypes().isEmpty()) {
                        targetCfgBuilder.dumpTypes(target.getDumpTypes());
                    }
                }

                WatchdogConfig targetConfig = targetCfgBuilder.build();

                // Alert channels: console + per-target file log + shared channels (e.g. QRadar)
                // Sanitise target name for filesystem safety: allow only alphanumeric, dash, underscore
                String safeName = target.getName().replaceAll("[^A-Za-z0-9._-]", "_");
                List<AlertChannel> channels = new ArrayList<>();
                channels.add(new ConsoleAlertChannel());
                channels.add(new FileLogAlertChannel("./oom-watchdog-" + safeName + ".log"));
                channels.addAll(sharedAlertChannels);

                JmxDiagnosticsCollector collector = new JmxDiagnosticsCollector(target, targetConfig);
                RiskAssessor            assessor  = new ThresholdRiskAssessor(targetConfig);
                HeapDumpService         dumper    = new CompositeDumpService(targetConfig);

                OomWatchdog watchdog = new OomWatchdog(targetConfig, collector, assessor, channels, dumper,
                        sharedPool);

                collectors.put(target.getName(), collector);
                activeWatchdogs.put(target.getName(), watchdog);
                if (target.getDumpApiUrl() != null) {
                    dumpApiUrls.put(target.getName(), target.getDumpApiUrl());
                } else if (target.getDumpApiPort() > 0) {
                    // Auto-start a DumpApiServer inside the watchdog, delegating via JMX
                    String dumpDir = target.getDumpDirectory() != null
                            ? target.getDumpDirectory()
                            : baseConfig.getHeapDumpDirectory();
                    DumpApiServer apiServer = new DumpApiServer(
                            target.getDumpApiPort(), dumpDir, collector);
                    apiServer.start();
                    autoDumpServers.put(target.getName(), apiServer);
                    dumpApiUrls.put(target.getName(), "http://127.0.0.1:" + target.getDumpApiPort());
                    WatchdogLogger.info(LOG,
                            "Auto-started DumpApiServer for target [{0}] on port {1}",
                            logSafeName, target.getDumpApiPort());
                }

                // Attach the shared GC history store before starting so the first poll is recorded.
                watchdog.setGcHistoryStore(gcHistoryStore, target.getName());

                watchdog.start();
                WatchdogLogger.info(LOG,
                        "Started watchdog for target [{0}] (JMX: {1}) dump-dir=[{2}] dump-types={3}",
                        logSafeName, logSafeUrl,
                        targetConfig.getHeapDumpDirectory(),
                        targetConfig.getDumpTypes().isEmpty() ? "(none)" : targetConfig.getDumpTypes().toString());

            } catch (Exception e) {
                // Sanitise again in the catch block since logSafeName may not be in scope
                // if the exception was thrown before it was assigned.
                String safeName = target.getName().replaceAll("[\r\n\t]", " ").trim();
                WatchdogLogger.severe(LOG, e, "Failed to initialize watchdog for target [{0}]: {1}",
                        safeName, e.getMessage());
            }
        }

        running = true;
        WatchdogLogger.info(LOG, "WatchdogDaemon successfully initialized {0} active monitor(s).",
                activeWatchdogs.size());
    }

    /**
     * Stops monitoring all targets and closes active JMX connections.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        WatchdogLogger.info(LOG, "Stopping WatchdogDaemon...");

        for (Map.Entry<String, OomWatchdog> entry : activeWatchdogs.entrySet()) {
            try {
                entry.getValue().stop();
                WatchdogLogger.fine(LOG, "Stopped watchdog for target [{0}]", entry.getKey());
            } catch (Exception e) {
                WatchdogLogger.warning(LOG, e, "Error stopping watchdog for target [{0}]: {1}",
                        entry.getKey(), e.getMessage());
            }
        }

        for (Map.Entry<String, JmxDiagnosticsCollector> entry : collectors.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ignored) {
                // ignore
            }
        }

        // Shut down the shared poll pool after all watchdogs are stopped
        if (sharedPool != null) {
            sharedPool.shutdown();
            sharedPool = null;
        }

        for (Map.Entry<String, DumpApiServer> entry : autoDumpServers.entrySet()) {
            try {
                entry.getValue().close();
                WatchdogLogger.fine(LOG, "Stopped auto-started DumpApiServer for target [{0}]", entry.getKey());
            } catch (Exception e) {
                WatchdogLogger.warning(LOG, e, "Error stopping DumpApiServer for target [{0}]: {1}",
                        entry.getKey(), e.getMessage());
            }
        }

        activeWatchdogs.clear();
        collectors.clear();
        autoDumpServers.clear();
        dumpApiUrls.clear();
        running = false;
        WatchdogLogger.info(LOG, "WatchdogDaemon stopped.");
    }

    /**
     * Checks whether the daemon is currently active.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the unmodifiable list of configured target descriptors.
     *
     * @return list of targets
     */
    public List<TargetDescriptor> getTargets() {
        return targets;
    }

    /**
     * Returns the number of currently active watchdog instances.
     *
     * @return active watchdog count
     */
    public synchronized int getActiveWatchdogCount() {
        return activeWatchdogs.size();
    }

    /**
     * Returns a snapshot of the currently active watchdog instances keyed by target name.
     * Intended for use by the metrics HTTP server to serve per-target data.
     *
     * @return unmodifiable copy of the active watchdog map; never {@code null}
     */
    public synchronized Map<String, OomWatchdog> getActiveWatchdogs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(activeWatchdogs));
    }

    /**
     * Returns a snapshot of the active JMX collectors keyed by target name.
     * Used by {@link com.trongus.oom.monitor.MetricsHttpServer} to trigger
     * on-demand dumps directly on the remote target JVM.
     *
     * @return unmodifiable copy of the collectors map; never {@code null}
     */
    public synchronized Map<String, JmxDiagnosticsCollector> getCollectors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(collectors));
    }

    /**
     * Returns a map of target name → base URL for targets that have an embedded
     * {@link DumpApiServer} configured via {@code dump-api-url} in targets.properties.
     * Used by {@link com.trongus.oom.monitor.MetricsHttpServer} to forward dump requests
     * directly to the target JVM instead of routing them through JMX.
     *
     * @return unmodifiable copy of the dump API URL map; never {@code null}
     */
    public synchronized Map<String, String> getDumpApiUrls() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(dumpApiUrls));
    }

    @Override
    public void close() {
        stop();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a {@link ThreadFactory} producing named daemon threads for the shared poll pool.
     * Thread names include a sequential counter for easy identification in thread dumps:
     * {@code oom-poll-1}, {@code oom-poll-2}, etc.
     */
    private static ThreadFactory sharedDaemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread t = new Thread(runnable, "oom-poll-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        };
    }
}
