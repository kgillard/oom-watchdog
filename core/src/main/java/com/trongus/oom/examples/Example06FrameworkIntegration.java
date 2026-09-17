package com.trongus.oom.examples;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.io.Closeable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example 06 — Spring Boot and application framework integration patterns.
 *
 * <h2>What this example shows</h2>
 * <p>Real-world applications are managed by frameworks (Spring Boot, Quarkus, Micronaut,
 * Jakarta EE, etc.).  This example demonstrates:
 * <ul>
 *   <li>A {@code WatchdogLifecycle} wrapper that implements {@link Closeable} and
 *       integrates cleanly into any lifecycle management system (Spring {@code @Bean},
 *       Jakarta CDI {@code @Produces}, etc.).</li>
 *   <li>A {@code MetricsAlertChannel} that bridges OOM Watchdog alerts into a
 *       counter/gauge metric system (Micrometer, Prometheus, StatsD, custom).</li>
 *   <li>Wiring the watchdog from a properties object — useful when config comes
 *       from {@code application.properties} or environment variables.</li>
 *   <li>A programmatic health indicator that exposes the last-seen risk level so
 *       readiness probes (Kubernetes, load balancer) can react to memory pressure.</li>
 * </ul>
 *
 * <h2>Spring Boot integration snippet</h2>
 * <p>In a real Spring Boot application you would do:
 * <pre>{@code
 * @Configuration
 * public class OomWatchdogConfig {
 *
 *     @Bean(destroyMethod = "close")
 *     public WatchdogLifecycle oomWatchdog(WatchdogProperties props) {
 *         return WatchdogLifecycle.fromProperties(props, List.of(
 *                 new ConsoleAlertChannel(),
 *                 new FileLogAlertChannel(props.getLogFile()),
 *                 applicationContext.getBean(MetricsAlertChannel.class)
 *         ));
 *     }
 * }
 * }</pre>
 *
 * <p>Spring calls {@code close()} on {@code destroyMethod} during context shutdown,
 * which cleanly stops the watchdog.  No manual shutdown hook is needed.
 *
 * <h2>Kubernetes readiness probe integration</h2>
 * <p>Expose the last risk level as a health endpoint:
 * <pre>{@code
 * @Component
 * public class OomHealthIndicator implements HealthIndicator {
 *
 *     private final WatchdogLifecycle watchdog;
 *
 *     @Override
 *     public Health health() {
 *         OomRiskLevel level = watchdog.getLastRiskLevel();
 *         if (level.ordinal() >= OomRiskLevel.CRITICAL.ordinal()) {
 *             return Health.down().withDetail("oomRiskLevel", level).build();
 *         }
 *         return Health.up().withDetail("oomRiskLevel", level).build();
 *     }
 * }
 * }</pre>
 *
 * @author Trongus OOM Watchdog
 * @version 1.0.0
 * @since 1.0.0
 * @see OomWatchdog
 * @see WatchdogConfig
 */
public final class Example06FrameworkIntegration {

    /** Utility class — construction is not permitted. */
    private Example06FrameworkIntegration() {}

    // =========================================================================
    // WatchdogProperties — configuration POJO (maps to app.properties)
    // =========================================================================

    /**
     * Plain-old Java object holding watchdog configuration loaded from
     * {@code application.properties} or environment variables.
     *
     * <p>In Spring Boot this class would be annotated with
     * {@code @ConfigurationProperties("oom.watchdog")} and bound automatically.
     * In a plain Java app, populate it from {@code System.getenv()} or
     * {@code System.getProperty()}.
     */
    public static final class WatchdogProperties {

        /**
         * Heap usage fraction that triggers WARNING.
         * Mapped from: {@code oom.watchdog.warn-threshold} / {@code OOM_WARN_THRESHOLD}
         */
        private double warnThreshold = 0.80;

        /**
         * Heap usage fraction that triggers CRITICAL.
         * Mapped from: {@code oom.watchdog.crit-threshold} / {@code OOM_CRIT_THRESHOLD}
         */
        private double critThreshold = 0.90;

        /**
         * Poll interval in milliseconds.
         * Mapped from: {@code oom.watchdog.poll-ms} / {@code OOM_POLL_MS}
         */
        private long pollMs = 5_000L;

        /**
         * Output directory for diagnostic dump files.
         * Mapped from: {@code oom.watchdog.dump-dir} / {@code OOM_DUMP_DIR}
         */
        private String dumpDir = "./dumps";

        /**
         * Alert log file path.
         * Mapped from: {@code oom.watchdog.log-file} / {@code OOM_LOG_FILE}
         */
        private String logFile = "./oom-watchdog.log";

        /** @return the heap warning threshold */
        public double getWarnThreshold()  { return warnThreshold; }
        /** @return the heap critical threshold */
        public double getCritThreshold()  { return critThreshold; }
        /** @return the poll interval in ms */
        public long   getPollMs()         { return pollMs; }
        /** @return the dump output directory */
        public String getDumpDir()        { return dumpDir; }
        /** @return the alert log file path */
        public String getLogFile()        { return logFile; }

        /** @param v heap warning threshold; @return {@code this} for chaining */
        public WatchdogProperties warnThreshold(double v)  { this.warnThreshold = v; return this; }
        /** @param v heap critical threshold; @return {@code this} for chaining */
        public WatchdogProperties critThreshold(double v)  { this.critThreshold = v; return this; }
        /** @param v poll interval ms; @return {@code this} for chaining */
        public WatchdogProperties pollMs(long v)           { this.pollMs = v; return this; }
        /** @param v dump output directory; @return {@code this} for chaining */
        public WatchdogProperties dumpDir(String v)        { this.dumpDir = v; return this; }
        /** @param v alert log file path; @return {@code this} for chaining */
        public WatchdogProperties logFile(String v)        { this.logFile = v; return this; }
    }

    // =========================================================================
    // WatchdogLifecycle — framework-friendly wrapper
    // =========================================================================

    /**
     * Lifecycle-managed wrapper around {@link OomWatchdog}.
     *
     * <p>Implements {@link Closeable} so it integrates with:
     * <ul>
     *   <li>Spring {@code @Bean(destroyMethod = "close")}</li>
     *   <li>Jakarta CDI {@code @PreDestroy}</li>
     *   <li>try-with-resources in tests</li>
     *   <li>Any lifecycle callback that expects {@code close()} or a zero-arg shutdown</li>
     * </ul>
     *
     * <p>Thread safety: {@code start()}, {@code close()}, and {@code getLastRiskLevel()}
     * all delegate to the underlying {@link OomWatchdog}, which is itself thread-safe.
     */
    public static final class WatchdogLifecycle implements Closeable {

        /** The underlying watchdog instance. */
        private final OomWatchdog watchdog;

        /**
         * Creates and starts a watchdog from the given properties and channels.
         *
         * @param props    configuration POJO; must not be {@code null}
         * @param channels alert channels to use; must not be {@code null}
         * @return a started {@link WatchdogLifecycle}
         */
        public static WatchdogLifecycle fromProperties(
                WatchdogProperties props,
                java.util.List<AlertChannel> channels) {

            WatchdogConfig config = WatchdogConfig.defaults()
                    .warningHeapThreshold(props.getWarnThreshold())
                    .criticalHeapThreshold(props.getCritThreshold())
                    .pollIntervalMs(props.getPollMs())
                    .heapDumpDirectory(props.getDumpDir())
                    .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
                    .build();

            OomWatchdog wd = new OomWatchdog(
                    config,
                    new MxBeanDiagnosticsCollector(config),
                    new ThresholdRiskAssessor(config),
                    channels,
                    new CompositeDumpService(config));

            return new WatchdogLifecycle(wd);
        }

        /**
         * Creates a lifecycle wrapper around an already-configured watchdog.
         *
         * @param watchdog the watchdog to wrap; must not be {@code null}
         */
        public WatchdogLifecycle(OomWatchdog watchdog) {
            if (watchdog == null) {
                throw new IllegalArgumentException("watchdog must not be null");
            }
            this.watchdog = watchdog;
            this.watchdog.start();
        }

        /**
         * Returns the last risk level assessed by the watchdog.
         *
         * <p>Can be used in health checks: if the level is {@code CRITICAL} or
         * {@code OOM_FIRING}, mark the service as unhealthy so a load balancer or
         * Kubernetes readiness probe can react.
         *
         * @return the most recent {@link OomRiskLevel}; never {@code null}
         */
        public OomRiskLevel getLastRiskLevel() {
            return watchdog.getLastRiskLevel();
        }

        /**
         * Returns {@code true} if the service is healthy (risk level below CRITICAL).
         *
         * <p>Suitable as a Spring Boot {@code HealthIndicator} or Kubernetes
         * readiness probe endpoint.
         *
         * @return {@code true} when risk is OK or WARNING, {@code false} when CRITICAL or above
         */
        public boolean isHealthy() {
            return watchdog.getLastRiskLevel().ordinal() < OomRiskLevel.CRITICAL.ordinal();
        }

        /**
         * Stops the watchdog.  Called automatically by Spring, CDI, or try-with-resources.
         * Safe to call multiple times.
         */
        @Override
        public void close() {
            watchdog.stop();
        }
    }

    // =========================================================================
    // MetricsAlertChannel — Micrometer / Prometheus bridge
    // =========================================================================

    /**
     * An {@link AlertChannel} that increments metric counters on every alert.
     *
     * <p>Bridges OOM Watchdog alerts into any counter/gauge system.  In production,
     * inject a real {@code MeterRegistry} (Micrometer) instead of the {@code AtomicInteger}
     * counters shown here.
     *
     * <p>This is useful for:
     * <ul>
     *   <li>Alerting dashboards in Grafana / Datadog via Prometheus scrape</li>
     *   <li>SLA tracking: how many WARNING events per day?</li>
     *   <li>Automation: trigger a scale-out when {@code oom.critical.total} &gt; threshold</li>
     * </ul>
     */
    public static final class MetricsAlertChannel implements AlertChannel {

        /** Running total of WARNING-level alerts since JVM start. */
        private final AtomicInteger warningCount  = new AtomicInteger(0);

        /** Running total of CRITICAL-level alerts since JVM start. */
        private final AtomicInteger criticalCount = new AtomicInteger(0);

        /**
         * Stores the most recent snapshot so the current heap ratio can be
         * exposed as a gauge.  Declared {@code volatile} so the main thread's
         * read of {@code getLastSnapshot()} always sees the latest value written
         * by the watchdog's scheduler thread.
         */
        private volatile JvmSnapshot lastSnapshot;

        /**
         * Increments the appropriate counter and records the snapshot.
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            // Record the most recent snapshot for gauge-style metrics
            this.lastSnapshot = snapshot;

            if (snapshot.getRiskLevel().ordinal() >= OomRiskLevel.CRITICAL.ordinal()) {
                int total = criticalCount.incrementAndGet();
                // In a real Micrometer app: registry.counter("oom.critical").increment();
                System.out.printf("[Metrics] oom.critical.total=%d heapPct=%.1f%%%n",
                        total, snapshot.getHeapUsedRatio() * 100);
            } else if (snapshot.getRiskLevel() == OomRiskLevel.WARNING) {
                int total = warningCount.incrementAndGet();
                // In a real Micrometer app: registry.counter("oom.warning").increment();
                System.out.printf("[Metrics] oom.warning.total=%d heapPct=%.1f%%%n",
                        total, snapshot.getHeapUsedRatio() * 100);
            }
        }

        /** @return total WARNING alerts since JVM start */
        public int getWarningCount()  { return warningCount.get(); }

        /** @return total CRITICAL alerts since JVM start */
        public int getCriticalCount() { return criticalCount.get(); }

        /**
         * Returns the most recent snapshot for gauge-style metric reads.
         * May return {@code null} if no alert has fired yet.
         *
         * @return last alert snapshot, or {@code null}
         */
        public JvmSnapshot getLastSnapshot() { return lastSnapshot; }

        /** @return channel identifier */
        @Override
        public String channelName() { return "Metrics"; }
    }

    // =========================================================================
    // main — demonstration of the full lifecycle pattern
    // =========================================================================

    /**
     * Demonstrates the framework integration lifecycle:
     * start → receive alerts → health check → clean shutdown.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        // Simulate properties loaded from application.properties / env vars
        WatchdogProperties props = new WatchdogProperties()
                .warnThreshold(0.80)
                .critThreshold(0.90)
                .pollMs(2_000L)
                .dumpDir("./dumps")
                .logFile("./oom-watchdog.log");

        MetricsAlertChannel metrics = new MetricsAlertChannel();

        // Create the lifecycle-managed watchdog (auto-started)
        WatchdogLifecycle lifecycle = WatchdogLifecycle.fromProperties(
                props,
                Arrays.asList(new ConsoleAlertChannel(), metrics));

        // Simulate a short observation period (e.g. serving requests)
        System.out.println("[Example06] Watchdog running. Simulating 10-second observation...");
        System.out.println("[Example06] Health check: " + (lifecycle.isHealthy() ? "UP" : "DOWN"));
        Thread.sleep(10_000L);

        // Health check — useful as a Kubernetes readiness probe
        OomRiskLevel level = lifecycle.getLastRiskLevel();
        System.out.printf("[Example06] After 10 s: riskLevel=%s warningAlerts=%d criticalAlerts=%d%n",
                level, metrics.getWarningCount(), metrics.getCriticalCount());
        System.out.println("[Example06] Health check: " + (lifecycle.isHealthy() ? "UP" : "DOWN"));

        // Clean shutdown — equivalent to Spring context close / CDI @PreDestroy
        lifecycle.close();
        System.out.println("[Example06] Watchdog stopped cleanly.");
    }
}
