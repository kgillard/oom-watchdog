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
 * @version 1.7.10
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

    private final Map<String, OomWatchdog>           activeWatchdogs = new LinkedHashMap<>();
    private final Map<String, JmxDiagnosticsCollector> collectors    = new LinkedHashMap<>();

    private volatile boolean running = false;

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
        Objects.requireNonNull(targets, "targets must not be null");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets list must not be empty");
        }
        this.targets             = Collections.unmodifiableList(new ArrayList<>(targets));
        this.baseConfig          = Objects.requireNonNull(baseConfig, "baseConfig must not be null");
        this.sharedAlertChannels = sharedAlertChannels != null
                ? Collections.unmodifiableList(new ArrayList<>(sharedAlertChannels))
                : Collections.emptyList();
    }

    /**
     * Convenience constructor with no shared channels.
     *
     * @param targets    list of targets to monitor
     * @param baseConfig base configuration
     */
    public WatchdogDaemon(List<TargetDescriptor> targets, WatchdogConfig baseConfig) {
        this(targets, baseConfig, Collections.emptyList());
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
                WatchdogConfig.Builder targetCfgBuilder = baseConfig.toBuilder()
                        .warningHeapThreshold(target.getWarnThreshold())
                        .criticalHeapThreshold(target.getCritThreshold())
                        .gcOverheadThreshold(target.getGcThreshold())
                        .pollIntervalMs(target.getPollIntervalMs())
                        .gcDumpThreshold(target.getGcDumpThreshold())
                        .heapDumpThreshold(target.getHeapDumpThreshold())
                        .nurseryDumpThreshold(target.getNurseryDumpThreshold());
                if (target.getDumpDirectory() != null) {
                    targetCfgBuilder.heapDumpDirectory(target.getDumpDirectory());
                }

                if (!target.getDumpTypes().isEmpty()) {
                    targetCfgBuilder.dumpTypes(target.getDumpTypes());
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

                OomWatchdog watchdog = new OomWatchdog(targetConfig, collector, assessor, channels, dumper);

                collectors.put(target.getName(), collector);
                activeWatchdogs.put(target.getName(), watchdog);

                watchdog.start();
                WatchdogLogger.info(LOG, "Started watchdog for target [{0}] (JMX: {1})",
                        logSafeName, logSafeUrl);

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

        activeWatchdogs.clear();
        collectors.clear();
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

    @Override
    public void close() {
        stop();
    }
}
