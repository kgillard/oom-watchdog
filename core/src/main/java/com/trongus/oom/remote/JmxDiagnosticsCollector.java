package com.trongus.oom.remote;

import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.i18n.Messages;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.Closeable;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Diagnostics collector that queries an external JVM process via standard JMX (JSR-160)
 * RMI connection.
 *
 * <h2>Design &amp; Fault Tolerance</h2>
 * <ul>
 *   <li>Establishes connection lazily on the first {@link #collect()} invocation.</li>
 *   <li>Caches remote MXBean proxies across collections while connection remains healthy.</li>
 *   <li>If a connection drops or cannot be established, performs up to 3 automatic retries
 *       with a 2-second back-off between attempts.</li>
 *   <li>If all retries fail, returns a degraded snapshot with {@link OomRiskLevel#OOM_FIRING}
 *       and {@code targetName} populated, ensuring that unreachable targets generate immediate
 *       operational alerts without crashing the collector or monitoring scheduler.</li>
 *   <li>Sets {@code processName} in the generated {@link JvmSnapshot} to
 *       {@code "<targetName> (<remotePid@host>)"}, or {@code targetName} alone when
 *       remote runtime info is unavailable.</li>
 * </ul>
 *
 * <h2>Nursery Pool Collection</h2>
 * <p>During each {@link #collect()} invocation, all remote {@link MemoryPoolMXBean} entries
 * are iterated.  Pools whose name contains {@code "Eden"}, {@code "Nursery"}, or
 * {@code "Young"} (case-insensitive) contribute to the aggregate
 * {@link JvmSnapshot#getNurseryUsedBytes()} and
 * {@link JvmSnapshot#getNurseryUsedRatio()} fields.  This covers both HotSpot
 * ({@code Eden Space}, {@code G1 Eden Space}) and OpenJ9/IBM J9
 * ({@code nursery-allocate}, {@code nursery-survivor}) pool naming conventions.
 *
 * <h2>LEEF Metadata Stamping</h2>
 * <p>Each snapshot produced by this collector has the target's
 * {@link TargetDescriptor#getLeefCategory()} and
 * {@link TargetDescriptor#getLeefTags()} values stamped onto it so that downstream
 * {@link com.trongus.oom.alert.QRadarAlertChannel} instances can emit per-target
 * {@code cat} and {@code tags} LEEF attributes without requiring awareness of the
 * {@code TargetDescriptor}.
 *
 * <h2>Remote JVM Detail Fields</h2>
 * <p>Since 1.7.10, each snapshot also carries the full set of JVM process-detail fields
 * populated directly from the remote JVM over JMX:
 * <ul>
 *   <li>{@code javaHome}, {@code jvmName}, {@code javaVersion} — from
 *       {@link RuntimeMXBean#getSystemProperties()}</li>
 *   <li>{@code osName}, {@code cpuCount} — from {@link OperatingSystemMXBean}</li>
 *   <li>{@code processCpuPct}, {@code processCpuMs} — from
 *       {@code com.sun.management.OperatingSystemMXBean} if available on the remote JVM;
 *       {@code -1.0} / {@code -1L} otherwise</li>
 *   <li>{@code jvmInputArgs} — from {@link RuntimeMXBean#getInputArguments()}</li>
 *   <li>{@code javaCommand} — from {@code sun.java.command} system property</li>
 *   <li>{@code threadCount}, {@code peakThreadCount} — from {@link ThreadMXBean}</li>
 * </ul>
 * These fields power the <em>JVM Detail</em> card in the monitoring dashboard.
 *
 * <h2>Security</h2>
 * <p>JMX credentials are never logged; connection errors are masked and sanitized.
 *
 * <h2>Thread Safety</h2>
 * <p>Designed to be invoked periodically from a single watchdog scheduler thread.
 * The {@link #collect()} and {@link #close()} methods are {@code synchronized} to
 * allow safe use from multiple threads if required.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.10
 * @since 1.7.0
 * @see TargetDescriptor
 * @see JvmDiagnosticsCollector
 */
public final class JmxDiagnosticsCollector implements JvmDiagnosticsCollector, Closeable {

    private static final Logger LOG = WatchdogLogger.forClass(JmxDiagnosticsCollector.class);

    private static final int MAX_CONNECT_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS   = 2_000L;

    private final TargetDescriptor descriptor;
    private final WatchdogConfig   config;
    private final Messages         messages;

    // Rolling window for post-GC heap trend
    private final Deque<long[]> postGcWindow;
    private long prevTotalGcTime = 0L;

    // Connection state
    private JMXConnector          connector;
    private MBeanServerConnection mbsc;

    /**
     * Constructs a remote JMX collector for the specified target and watchdog configuration.
     *
     * @param descriptor target connection descriptor; must not be null
     * @param config     watchdog configuration; must not be null
     */
    public JmxDiagnosticsCollector(TargetDescriptor descriptor, WatchdogConfig config) {
        this.descriptor   = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.config       = Objects.requireNonNull(config, "config must not be null");
        this.messages     = new Messages(config.getLocale());
        this.postGcWindow = new ArrayDeque<>(config.getLeakDetectionWindowSize() + 1);
    }

    /**
     * Convenience constructor using default WatchdogConfig.
     *
     * @param descriptor target descriptor; must not be null
     */
    public JmxDiagnosticsCollector(TargetDescriptor descriptor) {
        this(descriptor, WatchdogConfig.defaults().build());
    }

    /**
     * Collects a fresh {@link JvmSnapshot} from the remote JVM via JMX.
     *
     * <p>On the first call, a JMX connection is established using the target's
     * {@link TargetDescriptor#getJmxUrl()} and optional credentials.  Subsequent
     * calls reuse the cached connection.  If the connection is lost it is re-established
     * transparently on the next invocation.
     *
     * <p>Nursery/young-gen memory pools (Eden, Nursery, Young) are accumulated separately
     * and exposed as {@link JvmSnapshot#getNurseryUsedBytes()} and
     * {@link JvmSnapshot#getNurseryUsedRatio()}.
     *
     * <p>The target's {@code leefCategory} and {@code leefTags} are stamped onto the snapshot
     * so that alert channels can emit them without accessing the descriptor directly.
     *
     * <p>If connection fails after retries, a degraded snapshot with
     * {@link OomRiskLevel#OOM_FIRING} is returned (never {@code null}).
     *
     * @return a fully populated (or degraded-unreachable) {@link JvmSnapshot}; never {@code null}
     */
    @Override
    public synchronized JvmSnapshot collect() {
        long now = System.currentTimeMillis();

        if (!ensureConnected()) {
            return buildUnreachableSnapshot(now, "Failed to connect to target JMX endpoint after retries");
        }

        try {
            // ── Remote MemoryMXBean ──────────────────────────────────────────
            MemoryMXBean memoryMx = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);

            MemoryUsage heap = memoryMx.getHeapMemoryUsage();
            long heapUsed      = heap.getUsed();
            long heapCommitted = heap.getCommitted();
            long heapMax       = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            double heapRatio   = (heapMax > 0) ? (double) heapUsed / heapMax : 0.0;

            MemoryUsage nonHeap = memoryMx.getNonHeapMemoryUsage();
            long nonHeapUsed = nonHeap.getUsed();
            long nonHeapMax  = nonHeap.getMax();

            // ── Remote MemoryPoolMXBeans ─────────────────────────────────────
            Map<String, Long> poolUsed = new LinkedHashMap<>();
            long nurseryUsed = 0L;
            Set<ObjectName> poolNames = mbsc.queryNames(
                    new ObjectName(ManagementFactory.MEMORY_POOL_MXBEAN_DOMAIN_TYPE + ",*"), null);
            for (ObjectName on : poolNames) {
                MemoryPoolMXBean pool = ManagementFactory.newPlatformMXBeanProxy(
                        mbsc, on.getCanonicalName(), MemoryPoolMXBean.class);
                MemoryUsage u = pool.getUsage();
                if (u != null) {
                    poolUsed.put(pool.getName(), u.getUsed());
                    String poolNameLower = pool.getName().toLowerCase(Locale.ROOT);
                    if (poolNameLower.contains("eden") || poolNameLower.contains("nursery")
                            || poolNameLower.contains("young")) {
                        nurseryUsed += u.getUsed();
                    }
                }
            }
            double nurseryRatio = (nurseryUsed > 0 && heapMax > 0)
                    ? (double) nurseryUsed / heapMax : Double.NaN;

            // ── Remote GarbageCollectorMXBeans ───────────────────────────────
            Map<String, Long> gcCounts = new LinkedHashMap<>();
            Map<String, Long> gcTimes  = new LinkedHashMap<>();
            long totalGcTime = 0L;

            Set<ObjectName> gcNames = mbsc.queryNames(
                    new ObjectName(ManagementFactory.GARBAGE_COLLECTOR_MXBEAN_DOMAIN_TYPE + ",*"), null);
            for (ObjectName on : gcNames) {
                GarbageCollectorMXBean gc = ManagementFactory.newPlatformMXBeanProxy(
                        mbsc, on.getCanonicalName(), GarbageCollectorMXBean.class);
                long count = gc.getCollectionCount();
                long time  = gc.getCollectionTime();
                gcCounts.put(gc.getName(), count < 0 ? 0L : count);
                gcTimes.put(gc.getName(),  time  < 0 ? 0L : time);
                totalGcTime += (time > 0 ? time : 0L);
            }

            // ── Remote RuntimeMXBean ─────────────────────────────────────────
            RuntimeMXBean runtimeMx = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            long uptime = runtimeMx.getUptime();
            String remoteProcessName = runtimeMx.getName(); // e.g. "54321@remotehost"
            double gcOverhead = (uptime > 0) ? (double) totalGcTime / uptime : 0.0;

            // ── Remote JVM process detail ────────────────────────────────────
            // Read via standard MXBean proxies — same fields shown in the
            // JVM Process Detail card on the dashboard.
            String javaHome    = safeSystemProp(runtimeMx, "java.home");
            String javaVersion = safeSystemProp(runtimeMx, "java.version")
                               + " (" + safeSystemProp(runtimeMx, "java.vendor") + ")";
            String jvmName     = safeSystemProp(runtimeMx, "java.vm.name")
                               + " " + safeSystemProp(runtimeMx, "java.vm.version");
            String jvmInputArgs;
            try {
                List<String> args = runtimeMx.getInputArguments();
                StringBuilder sb2 = new StringBuilder();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb2.append(' ');
                    sb2.append(args.get(i));
                }
                jvmInputArgs = sb2.toString();
            } catch (Exception ignored) { jvmInputArgs = ""; }
            String javaCommand = safeSystemProp(runtimeMx, "sun.java.command");

            OperatingSystemMXBean osMx = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
            String osName   = osMx.getName() + " " + osMx.getVersion() + " (" + osMx.getArch() + ")";
            int    cpuCount = osMx.getAvailableProcessors();
            // Process CPU via com.sun.management extension (graceful -1 fallback)
            double cpuPct = -1.0;
            long   cpuMs  = -1L;
            try {
                Class<?> sunOs = Class.forName("com.sun.management.OperatingSystemMXBean");
                Object   sunBean = ManagementFactory.newPlatformMXBeanProxy(
                        mbsc, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                        (Class<OperatingSystemMXBean>) sunOs);
                java.lang.reflect.Method pcl = sunOs.getMethod("getProcessCpuLoad");
                Object v = pcl.invoke(sunBean);
                if (v instanceof Double && (Double) v >= 0) cpuPct = (Double) v * 100.0;
                java.lang.reflect.Method pct = sunOs.getMethod("getProcessCpuTime");
                Object t = pct.invoke(sunBean);
                if (t instanceof Long && (Long) t >= 0) cpuMs = (Long) t / 1_000_000L;
            } catch (Exception ignored) { /* extension unavailable on remote JVM */ }

            ThreadMXBean threadMx = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            int threadCount = threadMx.getThreadCount();
            int peakThreads = threadMx.getPeakThreadCount();

            // ── Post-GC Trend ────────────────────────────────────────────────
            long   postGcSample = -1L;
            double growthRate   = Double.NaN;

            if (totalGcTime > prevTotalGcTime) {
                prevTotalGcTime = totalGcTime;
                postGcSample    = heapUsed;

                long[] entry = { now, heapUsed };
                postGcWindow.addLast(entry);
                if (postGcWindow.size() > config.getLeakDetectionWindowSize()) {
                    postGcWindow.removeFirst();
                }
            }

            if (postGcWindow.size() >= 2) {
                growthRate = computeSlope(new ArrayList<>(postGcWindow));
            }

            String processDisplay = descriptor.getName() + " (" + remoteProcessName + ")";
            String notes = buildDiagnosisNotes(heapRatio, gcOverhead, growthRate, totalGcTime, gcCounts.isEmpty());

            return new JvmSnapshot.Builder()
                    .targetName(descriptor.getName())
                    .processName(processDisplay)
                    .timestampMs(now)
                    .heapUsedBytes(heapUsed)
                    .heapCommittedBytes(heapCommitted)
                    .heapMaxBytes(heapMax)
                    .heapUsedRatio(heapRatio)
                    .nurseryUsedBytes(nurseryUsed)
                    .nurseryUsedRatio(nurseryRatio)
                    .nonHeapUsedBytes(nonHeapUsed)
                    .nonHeapMaxBytes(nonHeapMax)
                    .poolUsedBytes(poolUsed)
                    .gcCollectionCounts(gcCounts)
                    .gcCollectionTimesMs(gcTimes)
                    .totalGcTimeMs(totalGcTime)
                    .jvmUptimeMs(uptime)
                    .gcOverheadRatio(gcOverhead)
                    .postGcHeapUsedBytes(postGcSample)
                    .postGcHeapGrowthRatePerMs(growthRate)
                    .riskLevel(OomRiskLevel.OK)
                    .diagnosisNotes(notes)
                    .leefCategory(descriptor.getLeefCategory())
                    .leefTags(descriptor.getLeefTags())
                    // JVM process detail — populated from remote MXBeans
                    .javaHome(javaHome)
                    .javaVersion(javaVersion)
                    .jvmName(jvmName)
                    .osName(osName)
                    .cpuCount(cpuCount)
                    .processCpuPct(cpuPct)
                    .processCpuMs(cpuMs)
                    .jvmInputArgs(jvmInputArgs)
                    .javaCommand(javaCommand)
                    .threadCount(threadCount)
                    .peakThreadCount(peakThreads)
                    .build();

        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "Error communicating with remote JMX target [{0}]: {1}",
                    descriptor.getName(), e.getMessage());
            disconnect();
            return buildUnreachableSnapshot(now, "JMX communication failure: " + e.getMessage());
        }
    }

    /**
     * Attempts connection establishment with retry logic.
     *
     * @return true if connected successfully, false if all retries failed
     */
    private boolean ensureConnected() {
        if (mbsc != null) {
            return true;
        }

        for (int attempt = 1; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                WatchdogLogger.fine(LOG, "Connecting to JMX target [{0}] at {1} (attempt {2}/{3})...",
                        descriptor.getName(), descriptor.getJmxUrl(), attempt, MAX_CONNECT_RETRIES);

                JMXServiceURL url = new JMXServiceURL(descriptor.getJmxUrl());
                Map<String, Object> env = new HashMap<>();

                if (descriptor.getUsername() != null && !descriptor.getUsername().isEmpty()) {
                    String[] credentials = new String[]{
                            descriptor.getUsername(),
                            descriptor.getPassword() != null ? descriptor.getPassword() : ""
                    };
                    env.put(JMXConnector.CREDENTIALS, credentials);
                }

                connector = JMXConnectorFactory.connect(url, env);
                mbsc = connector.getMBeanServerConnection();
                WatchdogLogger.info(LOG, "Connected successfully to remote JMX target [{0}] at {1}",
                        descriptor.getName(), descriptor.getJmxUrl());
                return true;

            } catch (Exception e) {
                WatchdogLogger.warning(LOG, "Failed to connect to JMX target [{0}] (attempt {1}/{2}): {3}",
                        descriptor.getName(), attempt, MAX_CONNECT_RETRIES, e.getMessage());
                disconnect();
                if (attempt < MAX_CONNECT_RETRIES) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private void disconnect() {
        if (connector != null) {
            try {
                connector.close();
            } catch (Exception ignored) {
                // ignore clean close failure
            }
            connector = null;
        }
        mbsc = null;
    }

    /**
     * Closes the underlying JMX connection and releases associated resources.
     * After calling this method, the next {@link #collect()} will re-establish the connection.
     */
    @Override
    public synchronized void close() {
        disconnect();
    }

    private JvmSnapshot buildUnreachableSnapshot(long timestampMs, String reason) {
        // Sanitise the reason to prevent log-injection; restrict to safe printable characters
        String safeReason = reason != null
                ? reason.replaceAll("[\\x00-\\x1F\\x7F]", " ").trim()
                : "unknown";
        return new JvmSnapshot.Builder()
                .targetName(descriptor.getName())
                .processName(descriptor.getName() + " [UNREACHABLE]")
                .timestampMs(timestampMs)
                .riskLevel(OomRiskLevel.OOM_FIRING)
                .diagnosisNotes("[UNREACHABLE] Target JVM '" + descriptor.getName()
                        + "' could not be reached via JMX: " + safeReason)
                .leefCategory(descriptor.getLeefCategory())
                .leefTags(descriptor.getLeefTags())
                .build();
    }

    /**
     * Reads a single system property from a remote {@link RuntimeMXBean}.
     * Returns an empty string if the property is absent or the call fails
     * (e.g. the remote JVM has restricted {@code java.management} access).
     */
    private static String safeSystemProp(RuntimeMXBean runtimeMx, String key) {
        try {
            Map<String, String> props = runtimeMx.getSystemProperties();
            String v = props.get(key);
            return v != null ? v : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static double computeSlope(List<long[]> points) {
        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (long[] p : points) {
            sumX  += p[0];
            sumY  += p[1];
            sumXY += (double) p[0] * p[1];
            sumX2 += (double) p[0] * p[0];
        }
        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0.0) return 0.0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    private String buildDiagnosisNotes(double heapRatio, double gcOverhead,
                                       double growthRate, long totalGcTime,
                                       boolean noGcBeans) {
        StringBuilder sb = new StringBuilder();

        if (noGcBeans || totalGcTime == 0L) {
            sb.append("[GC] ").append(messages.get("diag.gc.none")).append(" ");
        } else {
            sb.append("[GC] ")
              .append(messages.format("diag.gc.summary", totalGcTime, gcOverhead * 100))
              .append(" ");
            if (gcOverhead > descriptor.getGcThreshold()) {
                sb.append(messages.format("diag.gc.warning",
                        gcOverhead * 100, descriptor.getGcThreshold() * 100))
                  .append(" ");
            }
        }

        sb.append("[Heap] ")
          .append(messages.format("diag.heap", heapRatio * 100))
          .append(" ");

        if (!Double.isNaN(growthRate)) {
            double growthMbPerHour = growthRate * 3_600_000.0 / (1024.0 * 1024.0);
            if (growthRate > 0) {
                sb.append("[Leak] ")
                  .append(messages.format("diag.leak.growing", growthMbPerHour))
                  .append(" ");
            } else {
                sb.append("[Leak] ")
                  .append(messages.get("diag.leak.stable"))
                  .append(" ");
            }
        } else {
            sb.append("[Leak] ")
              .append(messages.get("diag.leak.insufficient"))
              .append(" ");
        }

        return sb.toString().trim();
    }
}
