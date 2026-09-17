package com.trongus.oom.examples;

import com.trongus.oom.alert.LibertyAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example 09 — WebSphere Liberty / Open Liberty integration.
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Embedding OOM Watchdog in a Liberty application using a CDI
 *       {@code @ApplicationScoped} lifecycle pattern (see the {@link LibertyAppBean}
 *       inner class and its Javadoc).</li>
 *   <li>Using {@link LibertyAlertChannel} so alerts appear in Liberty's
 *       {@code messages.log} and, when JSON logging is enabled, as structured JSON lines
 *       directly queryable in Elastic or IBM Log Analysis.</li>
 *   <li>A MicroProfile Health integration pattern via the {@link OomHealthCheck} inner
 *       class (the real {@code HealthCheck} interface is shown in Javadoc comments).</li>
 *   <li>{@link WatchdogConfig} tuned for Liberty microservices: small heap, aggressive
 *       thresholds, and a short poll interval appropriate for container deployments.</li>
 * </ul>
 *
 * <h2>Liberty {@code server.xml} configuration</h2>
 * <p>Add the following to your Liberty {@code server.xml} to enable tracing and JSON
 * logging for this watchdog:
 * <pre>{@code
 * <featureManager>
 *     <feature>mpHealth-4.0</feature>
 *     <feature>cdi-4.0</feature>
 * </featureManager>
 *
 * <logging traceSpecification="com.trongus.oom.*=all"
 *          messageFormat="JSON"
 *          logDirectory="${server.output.dir}/logs"
 *          maxFileSize="20"
 *          maxFiles="5" />
 * }</pre>
 *
 * <h2>Microservice heap sizing</h2>
 * <p>Liberty microservices running in containers typically have 256 MB – 512 MB heaps.
 * The watchdog thresholds below are tuned accordingly:
 * <ul>
 *   <li>{@code warningHeapThreshold=0.70} — warn at 70 % to allow early reaction in
 *       a container where {@code -Xmx256m} means only ~75 MB free at the threshold.</li>
 *   <li>{@code criticalHeapThreshold=0.85} — trigger CRITICAL before the container
 *       OOM-killer terminates the pod (at ~97 % usage).</li>
 *   <li>{@code pollIntervalMs=10_000} — 10-second polling is appropriate for containers
 *       where heap spikes can be rapid.</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.2.0
 * @since 1.2.0
 * @see LibertyAlertChannel
 * @see WatchdogConfig
 */
public final class Example09LibertyIntegration {

    /** Utility class — construction is not permitted. */
    private Example09LibertyIntegration() {}

    // =========================================================================
    // LibertyAppBean — CDI ApplicationScoped lifecycle (illustrative)
    // =========================================================================

    /**
     * CDI application-scoped bean that manages the OOM Watchdog lifecycle.
     *
     * <h2>Real Liberty CDI deployment</h2>
     * <p>In a real Liberty application, annotate this class with CDI and MicroProfile
     * annotations:
     * <pre>{@code
     * // import jakarta.enterprise.context.ApplicationScoped;
     * // import jakarta.annotation.PostConstruct;
     * // import jakarta.annotation.PreDestroy;
     * // import jakarta.inject.Named;
     *
     * @ApplicationScoped
     * @Named("oomWatchdog")
     * public static final class LibertyAppBean {
     *
     *     @PostConstruct
     *     public void start() { ... }
     *
     *     @PreDestroy
     *     public void stop() { ... }
     * }
     * }</pre>
     * <p>CDI will call {@code start()} after the bean is constructed and {@code stop()}
     * before the application context shuts down.  Inject the bean where you need
     * the health state:
     * <pre>{@code
     * @Inject
     * private LibertyAppBean oomBean;
     * }</pre>
     */
    public static final class LibertyAppBean {

        /** Shared channel instance exposed to {@link OomHealthCheck}. */
        private final LibertyAlertChannel alertChannel = new LibertyAlertChannel();

        /** Holds the running watchdog. */
        private final AtomicReference<OomWatchdog> watchdog = new AtomicReference<>();

        /**
         * Starts the OOM Watchdog.
         * In a Liberty CDI bean this would be annotated {@code @PostConstruct}.
         */
        public void start() {
            WatchdogConfig config = WatchdogConfig.defaults()
                    // Aggressive thresholds for container-sized heaps (256 – 512 MB)
                    .warningHeapThreshold(0.70)
                    .criticalHeapThreshold(0.85)
                    // 10-second polling for containerised microservices
                    .pollIntervalMs(10_000L)
                    // 3 × 10 s = 30 s sustained leak detection window
                    .leakDetectionWindowSize(3)
                    // Flag GC overhead at 35 % (lower than WAS because smaller heap = faster saturation)
                    .gcOverheadThreshold(0.35)
                    // Write dumps to Liberty's server output directory
                    .heapDumpDirectory("${server.output.dir}/oom-dumps")
                    .dumpTypes(EnumSet.of(DumpType.THREAD))
                    .build();

            OomWatchdog wd = new OomWatchdog(
                    config,
                    new MxBeanDiagnosticsCollector(config),
                    new ThresholdRiskAssessor(config),
                    // Liberty routes JUL to messages.log and JSON log
                    Collections.singletonList(alertChannel),
                    new CompositeDumpService(config));

            wd.start();
            watchdog.set(wd);
            System.out.println("[Example09] Liberty OOM Watchdog started.");
        }

        /**
         * Stops the OOM Watchdog.
         * In a Liberty CDI bean this would be annotated {@code @PreDestroy}.
         */
        public void stop() {
            OomWatchdog wd = watchdog.getAndSet(null);
            if (wd != null) {
                wd.stop();
                System.out.println("[Example09] Liberty OOM Watchdog stopped.");
            }
        }

        /**
         * Returns the {@link LibertyAlertChannel} for injection into health checks.
         *
         * @return the alert channel; never {@code null}
         */
        public LibertyAlertChannel getAlertChannel() {
            return alertChannel;
        }
    }

    // =========================================================================
    // OomHealthCheck — MicroProfile Health integration (illustrative)
    // =========================================================================

    /**
     * MicroProfile Health check that reports JVM OOM risk as a Liberty health endpoint.
     *
     * <h2>Real Liberty MicroProfile Health deployment</h2>
     * <p>In a real Liberty application with the {@code mpHealth} feature enabled:
     * <pre>{@code
     * // import org.eclipse.microprofile.health.HealthCheck;
     * // import org.eclipse.microprofile.health.HealthCheckResponse;
     * // import org.eclipse.microprofile.health.Liveness;
     * // import jakarta.enterprise.context.ApplicationScoped;
     * // import jakarta.inject.Inject;
     *
     * @Liveness
     * @ApplicationScoped
     * public static final class OomHealthCheck implements HealthCheck {
     *
     *     @Inject
     *     private LibertyAppBean oomBean;
     *
     *     @Override
     *     public HealthCheckResponse call() {
     *         LibertyAlertChannel ch = oomBean.getAlertChannel();
     *         boolean healthy = ch.isHealthy();
     *         return HealthCheckResponse.named("jvm-oom-risk")
     *                 .status(healthy)
     *                 .withData("riskLevel", ch.getLastRiskLevel().name())
     *                 .build();
     *     }
     * }
     * }</pre>
     * <p>Liberty exposes this check at {@code GET /health/live}.  A Kubernetes
     * liveness probe pointing at that URL will restart the pod when the JVM is at
     * {@code CRITICAL} or {@code OOM_FIRING} risk level.
     *
     * <p>This POJO simulates the same logic without the MicroProfile dependency.
     */
    public static final class OomHealthCheck {

        private final LibertyAlertChannel channel;

        /**
         * @param channel the {@link LibertyAlertChannel} to query for health state;
         *                must not be {@code null}
         */
        public OomHealthCheck(LibertyAlertChannel channel) {
            if (channel == null) {
                throw new IllegalArgumentException("channel must not be null");
            }
            this.channel = channel;
        }

        /**
         * Returns a health response object equivalent to a MicroProfile
         * {@code HealthCheckResponse}.
         *
         * @return {@link HealthResponse} with status and risk level detail
         */
        public HealthResponse call() {
            boolean       healthy   = channel.isHealthy();
            OomRiskLevel  riskLevel = channel.getLastRiskLevel();
            return new HealthResponse("jvm-oom-risk", healthy, riskLevel.name());
        }
    }

    /**
     * Simplified stand-in for {@code org.eclipse.microprofile.health.HealthCheckResponse}.
     * In a real Liberty application, replace with the real MicroProfile type.
     */
    public static final class HealthResponse {
        private final String  name;
        private final boolean up;
        private final String  riskLevel;

        HealthResponse(String name, boolean up, String riskLevel) {
            this.name      = name;
            this.up        = up;
            this.riskLevel = riskLevel;
        }

        /** @return health check name */
        public String  getName()      { return name; }
        /** @return {@code true} if the JVM is healthy (risk below CRITICAL) */
        public boolean isUp()         { return up; }
        /** @return the last assessed OOM risk level name */
        public String  getRiskLevel() { return riskLevel; }

        @Override
        public String toString() {
            return String.format("HealthResponse{name='%s', status=%s, riskLevel=%s}",
                    name, up ? "UP" : "DOWN", riskLevel);
        }
    }

    // =========================================================================
    // main — demonstration
    // =========================================================================

    /**
     * Demonstrates the Liberty lifecycle: CDI start → health checks → CDI stop.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        LibertyAppBean bean = new LibertyAppBean();

        // Simulate CDI @PostConstruct
        bean.start();

        OomHealthCheck healthCheck = new OomHealthCheck(bean.getAlertChannel());

        System.out.println("[Example09] Monitoring Liberty microservice for 12 seconds...");
        for (int i = 0; i < 4; i++) {
            Thread.sleep(3_000L);
            HealthResponse health = healthCheck.call();
            System.out.printf("[Example09] /health/live → %s%n", health);
        }

        // Simulate CDI @PreDestroy (Liberty shutdown or application undeploy)
        bean.stop();
        System.out.println("[Example09] Done.");
    }
}
