package com.trongus.oom.examples;

import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.alert.WasAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Example 08 — WebSphere Application Server (traditional WAS) integration.
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Embedding OOM Watchdog inside a WAS-hosted application using a
 *       {@link WasContextListener} POJO (see the class note below for the real
 *       {@code javax.servlet.ServletContextListener} pattern).</li>
 *   <li>Using {@link WasAlertChannel} alongside {@link FileLogAlertChannel} so that
 *       alerts reach both WAS {@code SystemErr.log} and a dedicated file log.</li>
 *   <li>The WAS-specific shutdown hook pattern via
 *       {@code Runtime.getRuntime().addShutdownHook()} as a fallback for environments
 *       where {@code contextDestroyed} is not guaranteed to fire (e.g. forced JVM
 *       termination).</li>
 *   <li>Tuned {@link WatchdogConfig} parameters appropriate for production WAS
 *       deployments with heap sizes in the 256 MB – 2 GB range.</li>
 * </ul>
 *
 * <h2>WAS thread-pool context</h2>
 * <p>WAS uses a work manager with a bounded thread pool for web-container and EJB
 * invocations.  The OOM Watchdog creates its own single-threaded scheduler that is
 * fully independent of the WAS thread pool and does not consume a web-container thread.
 * A poll interval of {@code 30 000 ms} (30 seconds) is appropriate for production WAS
 * because:
 * <ul>
 *   <li>Heap growth from report or transaction processing is typically gradual;
 *       sub-minute resolution is sufficient for early warning.</li>
 *   <li>Frequent JMX bean reads ({@code MemoryMXBean}, {@code GarbageCollectorMXBean})
 *       add negligible CPU overhead but contribute to I/O scheduling jitter under high
 *       WAS load; 30 s keeps the noise floor low.</li>
 *   <li>WAS PMI sampling period is typically 10–60 s, so aligning the watchdog at 30 s
 *       keeps the two subsystems coherent for correlation in the Admin Console.</li>
 * </ul>
 *
 * <h2>WAS JVM heap-sizing guide</h2>
 * <pre>
 * JVM argument             Recommended value for WAS production
 * ─────────────────────────────────────────────────────────────
 * -Xms                     512m (or equal to -Xmx to avoid GC storms on resize)
 * -Xmx                     1g – 4g depending on workload
 * -Xmn                     25–33% of -Xmx (nursery for IBM J9)
 * -Xgcpolicy               gencon (generational + concurrent — WAS default)
 * -Xdump:heap              enabled for OOM capture
 * -verbose:gc              route to ${LOG_ROOT}/verbosegc.log
 * </pre>
 *
 * <h2>Deploying in a real WAS application</h2>
 * <p>In a real WAS EAR/WAR, make {@link WasContextListener} implement
 * {@code javax.servlet.ServletContextListener} and annotate it with
 * {@code @WebListener}.  No {@code web.xml} entry is needed when servlet 3.0+ annotations
 * are enabled (WAS 8.5+).
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.2.0
 * @see WasAlertChannel
 * @see WatchdogConfig
 */
public final class Example08WasIntegration {

    /** Utility class — construction is not permitted. */
    private Example08WasIntegration() {}

    // =========================================================================
    // WasContextListener
    // =========================================================================

    /**
     * Application lifecycle manager that starts and stops the OOM Watchdog alongside
     * the web application context.
     *
     * <h2>Real WAS deployment pattern</h2>
     * <p>In a deployed WAS WAR, this class would be declared as:
     * <pre>{@code
     * // import javax.servlet.ServletContextEvent;
     * // import javax.servlet.ServletContextListener;
     * // import javax.servlet.annotation.WebListener;
     *
     * @WebListener
     * public static final class WasContextListener implements ServletContextListener {
     *
     *     private OomWatchdog watchdog;
     *
     *     @Override
     *     public void contextInitialized(ServletContextEvent sce) { startWatchdog(); }
     *
     *     @Override
     *     public void contextDestroyed(ServletContextEvent sce)   { stopWatchdog(); }
     * }
     * }</pre>
     * <p>Since {@code javax.servlet} is not on the module classpath of this library
     * (it is provided at runtime by WAS), the class below is a plain POJO showing the
     * identical method signatures.  The Javadoc above shows exactly how to convert it
     * when building a WAR for deployment.
     */
    public static final class WasContextListener {

        /** Holds the running watchdog so {@code contextDestroyed} can stop it. */
        private static final AtomicReference<OomWatchdog> WATCHDOG =
                new AtomicReference<>();

        /**
         * Called by the servlet container when the application context is initialised
         * (equivalent to {@code javax.servlet.ServletContextListener#contextInitialized}).
         *
         * <p>Creates and starts the watchdog with WAS-optimised configuration.
         * Also registers a JVM shutdown hook as a safety net in case
         * {@link #contextDestroyed()} is not called (e.g. forced kill).
         */
        public void contextInitialized(/* ServletContextEvent sce */) {
            WatchdogConfig config = WatchdogConfig.defaults()
                    // WAS production: warn at 75 % (typically 512 MB – 3 GB heaps)
                    .warningHeapThreshold(0.75)
                    // Alert CRITICAL at 88 % — below IBM J9 evacuation failure threshold
                    .criticalHeapThreshold(0.88)
                    // 30-second poll is appropriate for production WAS (see class Javadoc)
                    .pollIntervalMs(30_000L)
                    // Leak window: 5 × 30 s = 2.5 min of sustained growth before flagging
                    .leakDetectionWindowSize(5)
                    // GC overhead: flag when > 40 % CPU in GC (J9 gencon starts thrashing)
                    .gcOverheadThreshold(0.40)
                    // Write heap and thread dumps to the WAS server log directory
                    .heapDumpDirectory("${SERVER_LOG_ROOT}/oom-dumps")
                    .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
                    .build();

            OomWatchdog watchdog = new OomWatchdog(
                    config,
                    new MxBeanDiagnosticsCollector(config),
                    new ThresholdRiskAssessor(config),
                    Arrays.asList(
                            // WAS JUL → SystemErr.log + FFDC incident ID
                            new WasAlertChannel(),
                            // Dedicated file log for operator dashboards
                            new FileLogAlertChannel(
                                    "${SERVER_LOG_ROOT}/oom-watchdog/oom-watchdog.log")),
                    new CompositeDumpService(config));

            watchdog.start();
            WATCHDOG.set(watchdog);

            // Shutdown hook: WAS may terminate the JVM without calling contextDestroyed
            // in some error-recovery scenarios.  The hook ensures we always stop cleanly.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                OomWatchdog wd = WATCHDOG.get();
                if (wd != null) {
                    wd.stop();
                    System.out.println("[Example08] Watchdog stopped via shutdown hook.");
                }
            }, "OomWatchdog-WAS-Shutdown"));

            System.out.println("[Example08] WAS OOM Watchdog started.");
        }

        /**
         * Called by the servlet container when the application context is destroyed
         * (equivalent to {@code javax.servlet.ServletContextListener#contextDestroyed}).
         *
         * <p>Stops the watchdog and releases the scheduler thread.
         */
        public void contextDestroyed(/* ServletContextEvent sce */) {
            OomWatchdog wd = WATCHDOG.getAndSet(null);
            if (wd != null) {
                wd.stop();
                System.out.println("[Example08] WAS OOM Watchdog stopped.");
            }
        }
    }

    // =========================================================================
    // main — demonstration of the WAS lifecycle pattern
    // =========================================================================

    /**
     * Demonstrates the full WAS lifecycle: context initialisation → monitoring →
     * context destruction.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        WasContextListener listener = new WasContextListener();

        // Simulate WAS calling contextInitialized on application deployment
        System.out.println("[Example08] Simulating WAS contextInitialized...");
        listener.contextInitialized();

        // Simulate the application serving requests for 15 seconds
        System.out.println("[Example08] Application running — monitoring for 15 seconds...");
        System.out.println("[Example08] In a real WAS deployment, this thread is the main HTTP server loop.");
        Thread.sleep(15_000L);

        // Simulate WAS calling contextDestroyed on application undeploy
        System.out.println("[Example08] Simulating WAS contextDestroyed (undeploy / stop)...");
        listener.contextDestroyed();
        System.out.println("[Example08] Done.");
    }
}
