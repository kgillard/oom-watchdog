package com.trongus.oom.remote;

import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.strategy.ProcessSignalDumper;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>If a connection cannot be established, a single bounded attempt is made (timeout:
 *       5 s) and the poll task returns immediately on failure.  The natural poll interval
 *       provides the back-off between reconnection attempts, so the poll thread is never
 *       blocked for more than one connect timeout per cycle.</li>
 *   <li>On failure, returns a degraded snapshot with {@link OomRiskLevel#OOM_FIRING}
 *       and {@code targetName} populated, ensuring that unreachable targets generate an
 *       immediate LEEF alert on every poll cycle without crashing the collector or
 *       blocking the monitoring scheduler.</li>
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
 * @version 1.7.13.26
 * @since 1.7.0
 * @see TargetDescriptor
 * @see JvmDiagnosticsCollector
 */
public final class JmxDiagnosticsCollector implements JvmDiagnosticsCollector, Closeable {

    private static final Logger LOG = WatchdogLogger.forClass(JmxDiagnosticsCollector.class);

    /** Connect timeout for each JMX RMI attempt (ms). Prevents blocking the poll thread
     *  indefinitely when a target is down. One attempt per poll cycle; the poll interval
     *  itself provides the back-off between reconnection tries. */
    private static final int JMX_CONNECT_TIMEOUT_MS = 5_000;

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
     * Cached same-host determination. {@code null} = not yet evaluated.
     * Evaluated lazily on the first {@link #triggerRemoteDump} call and cached
     * so repeated dump requests don't re-resolve DNS on every call.
     */
    private volatile Boolean sameHost = null;

    /**
     * Remote PID parsed from the most recent {@link #collect()} snapshot
     * ({@code RuntimeMXBean.getName()} format: {@code pid@host}).
     * {@code -1} until the first successful collection.
     */
    private volatile long remotePid = -1L;

    /**
     * Whether the remote JVM is IBM J9/OpenJ9, detected from the JVM name
     * property during the first successful collection.
     */
    private volatile boolean remoteIsJ9 = false;

    /**
     * The {@code java.home} of the remote JVM, collected via
     * {@link RuntimeMXBean#getSystemProperties()} and displayed on the dashboard.
     * Used to locate {@code jcmd}/{@code jmap} from the correct JDK installation.
     * Empty string until the first successful collection.
     */
    private volatile String remoteJavaHome = "";

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
     * <p>If the connection fails, a degraded snapshot with
     * {@link OomRiskLevel#OOM_FIRING} is returned (never {@code null}).
     *
     * @return a fully populated (or degraded-unreachable) {@link JvmSnapshot}; never {@code null}
     */
    @Override
    public synchronized JvmSnapshot collect() {
        long now = System.currentTimeMillis();

        if (!ensureConnected()) {
            return buildUnreachableSnapshot(now, "Failed to connect to target JMX endpoint");
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

            // Cache remote PID, J9 flag, and java.home for use by triggerRemoteDump()
            long parsedPid = parsePid(remoteProcessName);
            if (parsedPid > 0) remotePid = parsedPid;
            if (jvmName != null && (jvmName.contains("J9") || jvmName.contains("OpenJ9"))) {
                remoteIsJ9 = true;
            }
            if (javaHome != null && !javaHome.isEmpty()) {
                remoteJavaHome = javaHome;
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
     * Attempts a single JMX connection to the target.
     *
     * <p>One attempt is made per call with a bounded connect timeout
     * ({@value #JMX_CONNECT_TIMEOUT_MS} ms) so the poll thread is never
     * blocked indefinitely. The natural poll interval acts as the back-off
     * between successive reconnection attempts across poll cycles.
     *
     * @return {@code true} if already connected or connection succeeded;
     *         {@code false} if this attempt failed
     */
    private boolean ensureConnected() {
        if (mbsc != null) {
            return true;
        }

        try {
            WatchdogLogger.fine(LOG, "Connecting to JMX target [{0}] at {1}...",
                    descriptor.getName(), descriptor.getJmxUrl());

            JMXServiceURL url = new JMXServiceURL(descriptor.getJmxUrl());
            Map<String, Object> env = new HashMap<>();

            // Bound the RMI connect time so a down target doesn't block the poll thread.
            env.put("sun.rmi.transport.connectionTimeout",
                    String.valueOf(JMX_CONNECT_TIMEOUT_MS));
            env.put("sun.rmi.transport.tcp.responseTimeout",
                    String.valueOf(JMX_CONNECT_TIMEOUT_MS));

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
            WatchdogLogger.warning(LOG, "Failed to connect to JMX target [{0}]: {1}",
                    descriptor.getName(), e.getMessage());
            disconnect();
            return false;
        }
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

    // ── Remote dump ───────────────────────────────────────────────────────────

    /**
     * Triggers a diagnostic dump on the <strong>remote</strong> target JVM via JMX.
     *
     * <p>The dump mechanism depends on the requested type and the capabilities
     * exposed by the remote JVM:
     * <ul>
     *   <li><strong>HEAP</strong> — tried in order:
     *     <ol>
     *       <li>{@code com.sun.management:type=HotSpotDiagnostic dumpHeap} (HotSpot / OpenJDK)</li>
     *       <li>{@code com.ibm.jvm:type=Dump heapDump(String)} (IBM J9 / OpenJ9)</li>
     *       <li>{@code com.ibm.lang.management:type=JvmMemory createHeapDump()} (IBM J9 fallback)</li>
     *     </ol>
     *   </li>
     *   <li><strong>THREAD</strong> — tried in order:
     *     <ol>
     *       <li>{@code com.sun.management:type=DiagnosticCommand threadPrint}</li>
     *       <li>{@link ThreadMXBean#dumpAllThreads} (universal JMX fallback)</li>
     *     </ol>
     *   </li>
     *   <li><strong>CORE</strong> — tried in order:
     *     <ol>
     *       <li>{@code com.ibm.jvm:type=Dump systemDump(String)} (IBM J9 / OpenJ9)</li>
     *       <li>{@code com.sun.management:type=DiagnosticCommand systemDump} (some J9 builds)</li>
     *     </ol>
     *     HotSpot cannot produce a core dump remotely via JMX.
     *   </li>
     * </ul>
     *
     * <p>If the JMX connection is not currently established, this method attempts to connect
     * before proceeding.  If connection fails, {@code null} is returned.
     *
     * <p>This method is {@code synchronized} on the same monitor as {@link #collect()} to
     * prevent concurrent access to the shared {@link MBeanServerConnection}.
     *
     * @param type       the dump type to produce; must not be {@code null}
     * @param outputPath suggested absolute path for the dump file (used for HEAP and THREAD;
     *                   may be overridden by the remote JVM for CORE)
     * @return the absolute path of the file written, or {@code null} if the dump could not
     *         be triggered
     */
    public synchronized String triggerRemoteDump(DumpType type, String outputPath) {
        if (!ensureConnected()) {
            WatchdogLogger.warning(LOG, "Cannot trigger remote dump for [{0}]: JMX not connected",
                    descriptor.getName());
            return "ERROR: JMX not connected to target [" + descriptor.getName() + "]";
        }
        // Ensure the output directory exists before any strategy tries to write a file.
        java.nio.file.Path parentDir = java.nio.file.Paths.get(outputPath).getParent();
        if (parentDir != null) {
            try {
                java.nio.file.Files.createDirectories(parentDir);
            } catch (Exception e) {
                WatchdogLogger.warning(LOG, "Cannot create dump directory for [{0}]: {1}",
                        descriptor.getName(), e.getMessage());
                return "ERROR: Cannot create dump directory: " + e.getMessage();
            }
        }
        // Pre-check which dump MBeans are registered so strategies can skip
        // unregistered MBeans immediately rather than taking an InstanceNotFoundException.
        // The availability map is also embedded in any error string shown in the dashboard.
        Map<String, Boolean> mbeansPresent = probeDumpMBeans();
        String mbeanSummary = formatMBeanSummary(mbeansPresent);
        WatchdogLogger.info(LOG, "Dump MBean availability on [{0}]: {1}",
                descriptor.getName(), mbeanSummary);
        // Priority 0: Same-host fast path — use OS signals and jcmd/jmap.
        // This is more reliable than JMX MBeans: no MBean registration required,
        // works on locked-down JVMs, and avoids RMI serialisation issues for heap dumps.
        String signalResult = trySignalDump(type, outputPath);
        if (signalResult != null && !signalResult.startsWith("ERROR:")) {
            return signalResult;
        }

        // Do NOT disconnect on dump failure — the JMX connection is shared with the
        // health-monitoring collect() loop; a bad dump request should not break polling.
        switch (type) {
            case HEAP:            return remoteHeapDump(outputPath, mbeansPresent, mbeanSummary);
            case THREAD:          return remoteThreadDump(outputPath);
            case CORE:            return remoteCoreOrSystemDump(outputPath, mbeansPresent, mbeanSummary);
            case CLASS_HISTOGRAM: return remoteClassHistogram(outputPath);
            default:
                WatchdogLogger.warning(LOG, "Remote dump type [{0}] not supported", type);
                return "ERROR: dump type " + type + " not supported for remote targets";
        }
    }

    /**
     * Checks which dump-related MBeans are registered on the remote JVM.
     * Returns a map of ObjectName string → registered (true/false).
     * Also discovers all IBM-domain MBeans via a wildcard query and logs them
     * so we can see what is actually available on locked-down JVMs.
     */
    private Map<String, Boolean> probeDumpMBeans() {
        // Fixed candidates we know about
        String[] known = {
            "com.ibm.jvm:type=Dump",
            "com.ibm.lang.management:type=JvmMemory",
            "com.sun.management:type=HotSpotDiagnostic",
            "com.sun.management:type=DiagnosticCommand"
        };
        Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        for (String name : known) {
            try {
                result.put(name, mbsc.isRegistered(new ObjectName(name)));
            } catch (Exception e) {
                result.put(name, false);
            }
        }

        // Wildcard-discover all com.ibm.* and com.sun.management.* MBeans so
        // we can see exactly what is available on this JVM without guessing.
        try {
            Set<ObjectName> ibmBeans = mbsc.queryNames(
                    new ObjectName("com.ibm.*:*"), null);
            if (!ibmBeans.isEmpty()) {
                StringBuilder sb = new StringBuilder("Discovered com.ibm MBeans on [")
                        .append(descriptor.getName()).append("]: ");
                java.util.List<String> names = new ArrayList<>();
                for (ObjectName on : ibmBeans) names.add(on.getCanonicalName());
                java.util.Collections.sort(names);
                for (String n : names) sb.append(n).append("; ");
                WatchdogLogger.info(LOG, "{0}", sb.toString().trim());
                // Add any discovered IBM dump-capable MBeans to the result map
                for (ObjectName on : ibmBeans) {
                    String canonical = on.getCanonicalName();
                    if (!result.containsKey(canonical)) {
                        result.put(canonical, true);
                    }
                }
            } else {
                WatchdogLogger.info(LOG,
                        "No com.ibm.* MBeans found on [{0}] — target JVM is not IBM J9 or MBeans are not exposed",
                        descriptor.getName());
            }
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, "MBean discovery failed for [{0}]: {1}",
                    descriptor.getName(), e.getMessage());
        }
        return result;
    }

    private static String formatMBeanSummary(Map<String, Boolean> m) {
        // Show the four key candidates; then append any extra discovered beans
        String[] key = {
            "com.ibm.jvm:type=Dump",
            "com.ibm.lang.management:type=JvmMemory",
            "com.sun.management:type=HotSpotDiagnostic",
            "com.sun.management:type=DiagnosticCommand"
        };
        Set<String> keySet = new HashSet<>(Arrays.asList(key));
        StringBuilder sb = new StringBuilder();
        for (String k : key) {
            Boolean v = m.get(k);
            sb.append(k).append('=').append(Boolean.TRUE.equals(v) ? "YES" : "no").append("; ");
        }
        // Append any extra discovered beans (e.g. JvmCpuMonitor) so the user can see what IS present
        for (Map.Entry<String, Boolean> e : m.entrySet()) {
            if (!keySet.contains(e.getKey()) && Boolean.TRUE.equals(e.getValue())) {
                sb.append(e.getKey()).append("=discovered; ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Invokes a heap dump on the remote JVM, trying each strategy only when
     * the required MBean is actually registered on the remote server.
     *
     * <p>Strategy priority:
     * <ol>
     *   <li>HotSpot {@code HotSpotDiagnosticMXBean.dumpHeap} — HotSpot / OpenJDK / GraalVM</li>
     *   <li>IBM J9 {@code com.ibm.jvm:type=Dump heapDump(String)} — token format</li>
     *   <li>IBM J9 {@code com.ibm.jvm:type=Dump heapDump()} — no-arg fallback</li>
     *   <li>IBM J9 {@code com.ibm.lang.management:type=JvmMemory createHeapDump()}</li>
     *   <li>IBM J9 {@code com.ibm.jvm:type=Dump javaDump()} — javacore text fallback</li>
     * </ol>
     */
    private String remoteHeapDump(String outputPath, Map<String, Boolean> present, String mbeanSummary) {
        StringBuilder failures = new StringBuilder();
        String phdPath = outputPath.replaceAll("\\.[^./]+$", "") + ".phd";

        // Strategy 1: HotSpot HotSpotDiagnosticMXBean
        if (Boolean.TRUE.equals(present.get("com.sun.management:type=HotSpotDiagnostic"))) {
            try {
                ObjectName on = new ObjectName("com.sun.management:type=HotSpotDiagnostic");
                mbsc.invoke(on, "dumpHeap",
                        new Object[]{ outputPath, Boolean.TRUE },
                        new String[]{ String.class.getName(), boolean.class.getName() });
                WatchdogLogger.info(LOG, "Remote heap dump (HotSpot) written to [{0}] for target [{1}]",
                        outputPath, descriptor.getName());
                return outputPath;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "HotSpot dumpHeap failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[HotSpot] ").append(r).append("; ");
            }
        }

        // Strategies 2, 2b, 4: require com.ibm.jvm:type=Dump
        if (Boolean.TRUE.equals(present.get("com.ibm.jvm:type=Dump"))) {
            // 2: heapDump(String) — IBM token format "heap:file=<path>"
            try {
                ObjectName on = new ObjectName("com.ibm.jvm:type=Dump");
                Object result = mbsc.invoke(on, "heapDump",
                        new Object[]{ "heap:file=" + phdPath },
                        new String[]{ String.class.getName() });
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : phdPath;
                WatchdogLogger.info(LOG, "Remote heap dump (IBM J9 heapDump(String)) → [{0}] for [{1}]",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM J9 heapDump(String) failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-heap(path)] ").append(r).append("; ");
            }
            // 2b: heapDump() — no-arg
            try {
                ObjectName on = new ObjectName("com.ibm.jvm:type=Dump");
                Object result = mbsc.invoke(on, "heapDump", new Object[0], new String[0]);
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : phdPath;
                WatchdogLogger.info(LOG, "Remote heap dump (IBM J9 heapDump()) → [{0}] for [{1}]",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM J9 heapDump() failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-heap()] ").append(r).append("; ");
            }
        }

        // Strategy 3: com.ibm.lang.management:type=JvmMemory createHeapDump()
        // This MBean is registered by default on all IBM JDK 8 JVMs — no startup flags needed.
        if (Boolean.TRUE.equals(present.get("com.ibm.lang.management:type=JvmMemory"))) {
            try {
                ObjectName on = new ObjectName("com.ibm.lang.management:type=JvmMemory");
                Object result = mbsc.invoke(on, "createHeapDump", new Object[0], new String[0]);
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : phdPath;
                WatchdogLogger.info(LOG, "Remote heap dump (IBM JvmMemory createHeapDump()) → [{0}] for [{1}]",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM JvmMemory createHeapDump() failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-JvmMemory] ").append(r).append("; ");
            }
        }

        // Strategy 3b: scan any discovered com.ibm.lang.management MBean for createHeapDump()
        // Handles IBM JDK builds where the ObjectName type differs from the standard.
        // Guard: only invoke if the MBean's MBeanInfo actually declares createHeapDump —
        // other com.ibm.lang.management beans (e.g. JvmCpuMonitor) don't have that operation.
        for (Map.Entry<String, Boolean> entry : present.entrySet()) {
            String beanName = entry.getKey();
            if (Boolean.TRUE.equals(entry.getValue())
                    && beanName.startsWith("com.ibm.lang.management:")
                    && !beanName.equals("com.ibm.lang.management:type=JvmMemory")) {
                try {
                    ObjectName on = new ObjectName(beanName);
                    // Check that createHeapDump is actually declared before invoking
                    boolean hasOp = false;
                    try {
                        javax.management.MBeanInfo info = mbsc.getMBeanInfo(on);
                        for (javax.management.MBeanOperationInfo op : info.getOperations()) {
                            if ("createHeapDump".equals(op.getName())) { hasOp = true; break; }
                        }
                    } catch (Exception ignored) { /* treat as absent */ }
                    if (!hasOp) continue;
                    Object result = mbsc.invoke(on, "createHeapDump", new Object[0], new String[0]);
                    String path = result != null && !result.toString().trim().isEmpty()
                            ? result.toString().trim() : phdPath;
                    WatchdogLogger.info(LOG, "Remote heap dump ({0} createHeapDump()) → [{1}] for [{2}]",
                            beanName, path, descriptor.getName());
                    return path;
                } catch (Exception e) {
                    String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                    failures.append("[").append(beanName).append("] ").append(r).append("; ");
                }
            }
        }

        // Strategy 4 (last resort): javaDump() → javacore text file
        if (Boolean.TRUE.equals(present.get("com.ibm.jvm:type=Dump"))) {
            String javacorePath = outputPath.replaceAll("\\.[^./]+$", "") + "_javacore.txt";
            try {
                ObjectName on = new ObjectName("com.ibm.jvm:type=Dump");
                Object result = mbsc.invoke(on, "javaDump", new Object[0], new String[0]);
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : javacorePath;
                WatchdogLogger.info(LOG,
                        "Remote javacore (IBM J9 javaDump) → [{0}] for [{1}] (substitute — heap MBeans exhausted)",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM J9 javaDump() failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-javaDump] ").append(r).append("; ");
            }
        }

        // Nothing worked — build a diagnostic error showing exactly what is/isn't present
        if (failures.length() == 0) {
            failures.append("no supported dump MBeans registered on this JVM");
        }
        String msg = "Heap dump failed. MBeans: " + mbeanSummary + " | Errors: " + failures;
        WatchdogLogger.warning(LOG, "Remote heap dump exhausted for [{0}]: {1}", descriptor.getName(), msg);
        return "ERROR: " + msg;
    }

    /**
     * Captures a thread dump from the remote JVM and writes it to {@code outputPath}
     * on the watchdog server's filesystem.
     *
     * <p>Strategy (in order):
     * <ol>
     *   <li>Try {@code DiagnosticCommand.threadPrint} — available on HotSpot JDK 8+ when
     *       the {@code com.sun.management:type=DiagnosticCommand} MBean is registered.</li>
     *   <li>Fall back to {@link ThreadMXBean#dumpAllThreads} — always available over JMX
     *       on any compliant JVM; produces the same thread-stack information.</li>
     * </ol>
     */
    private String remoteThreadDump(String outputPath) {
        // Strategy 1: DiagnosticCommand.threadPrint (HotSpot JDK 8+, not always registered)
        try {
            ObjectName on = new ObjectName("com.sun.management:type=DiagnosticCommand");
            Object result = mbsc.invoke(on, "threadPrint",
                    new Object[]{ new String[0] },
                    new String[]{ String[].class.getName() });
            String text = result != null ? result.toString() : "(empty thread dump)";
            writeTextFile(outputPath, text);
            return outputPath;
        } catch (Exception ignored) {
            WatchdogLogger.fine(LOG,
                    "DiagnosticCommand.threadPrint unavailable for [{0}]; falling back to ThreadMXBean",
                    descriptor.getName());
        }
        // Strategy 2: ThreadMXBean.dumpAllThreads — always present on any JMX-enabled JVM
        try {
            ThreadMXBean threadMx = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            java.lang.management.ThreadInfo[] infos = threadMx.dumpAllThreads(true, true);
            StringBuilder sb = new StringBuilder();
            sb.append("Full thread dump via JMX ThreadMXBean — target: ")
              .append(descriptor.getName()).append("\n\n");
            for (java.lang.management.ThreadInfo ti : infos) {
                sb.append(ti.toString());
            }
            writeTextFile(outputPath, sb.toString());
            return outputPath;
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, "Remote thread dump failed for [{0}]: {1}",
                    descriptor.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Attempts a system/core dump on the remote JVM, skipping unregistered MBeans.
     *
     * <p>Requires {@code com.ibm.jvm:type=Dump} (IBM J9/OpenJ9) or
     * {@code com.sun.management:type=DiagnosticCommand} to be registered.
     * Returns a diagnostic error string if neither is available.
     */
    private String remoteCoreOrSystemDump(String outputPath, Map<String, Boolean> present, String mbeanSummary) {
        StringBuilder failures = new StringBuilder();
        String dmpPath = outputPath.replaceAll("\\.[^./]+$", "") + ".dmp";

        // Strategies 1 + 1b: require com.ibm.jvm:type=Dump
        if (Boolean.TRUE.equals(present.get("com.ibm.jvm:type=Dump"))) {
            // 1: systemDump(String) — IBM token format "system:file=<path>"
            try {
                ObjectName on = new ObjectName("com.ibm.jvm:type=Dump");
                Object result = mbsc.invoke(on, "systemDump",
                        new Object[]{ "system:file=" + dmpPath },
                        new String[]{ String.class.getName() });
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : dmpPath;
                path = extractLastToken(path, dmpPath);
                WatchdogLogger.info(LOG, "Remote system dump (IBM J9 systemDump(String)) → [{0}] for [{1}]",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM J9 systemDump(String) failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-system(path)] ").append(r).append("; ");
            }
            // 1b: systemDump() — no-arg
            try {
                ObjectName on = new ObjectName("com.ibm.jvm:type=Dump");
                Object result = mbsc.invoke(on, "systemDump", new Object[0], new String[0]);
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : dmpPath;
                path = extractLastToken(path, dmpPath);
                WatchdogLogger.info(LOG, "Remote system dump (IBM J9 systemDump()) → [{0}] for [{1}]",
                        path, descriptor.getName());
                return path;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "IBM J9 systemDump() failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[J9-system()] ").append(r).append("; ");
            }
        }

        // Strategy 2: DiagnosticCommand systemDump
        if (Boolean.TRUE.equals(present.get("com.sun.management:type=DiagnosticCommand"))) {
            try {
                ObjectName on = new ObjectName("com.sun.management:type=DiagnosticCommand");
                Object result = mbsc.invoke(on, "systemDump",
                        new Object[]{ new String[]{ "file=" + dmpPath } },
                        new String[]{ String[].class.getName() });
                String text = result != null ? result.toString().trim() : "";
                if (!text.isEmpty()) {
                    int idx = text.lastIndexOf(' ');
                    String candidate = idx >= 0 ? text.substring(idx + 1) : text;
                    if (new File(candidate).exists()) return candidate;
                }
                return dmpPath;
            } catch (Exception e) {
                String r = e.getClass().getSimpleName() + ": " + e.getMessage();
                WatchdogLogger.warning(LOG, "DiagnosticCommand systemDump failed for [{0}]: {1}", descriptor.getName(), r);
                failures.append("[DiagCmd] ").append(r);
            }
        }

        // Nothing worked — explain clearly what is available and what to do
        if (failures.length() == 0) {
            failures.append("no supported system-dump MBeans registered on this JVM. "
                    + "Core dumps require com.ibm.jvm:type=Dump (IBM J9) which is not exposed "
                    + "via this JMX connection. Enable the dump agent or use -Dcom.ibm.tools.attach "
                    + "on the target JVM.");
        }
        String msg = "Core dump failed. MBeans: " + mbeanSummary + " | " + failures;
        WatchdogLogger.warning(LOG, "Remote core dump exhausted for [{0}]: {1}", descriptor.getName(), msg);
        return "ERROR: " + msg;
    }

    /** Extracts the last space-delimited token from a J9 dump result string, if it looks like a path. */
    private static String extractLastToken(String result, String fallback) {
        if (result != null && result.contains(" ")) {
            String candidate = result.substring(result.lastIndexOf(' ') + 1);
            if (new File(candidate).exists()) return candidate;
        }
        return result != null && !result.trim().isEmpty() ? result.trim() : fallback;
    }

    /** Invokes DiagnosticCommand.gcClassHistogram on the remote JVM and writes output to file. */
    private String remoteClassHistogram(String outputPath) {
        try {
            ObjectName on = new ObjectName("com.sun.management:type=DiagnosticCommand");
            Object result = mbsc.invoke(on, "gcClassHistogram",
                    new Object[]{ new String[0] },
                    new String[]{ String[].class.getName() });
            String text = result != null ? result.toString() : "(empty histogram)";
            writeTextFile(outputPath, text);
            return outputPath;
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, "Remote class histogram unavailable for [{0}]: {1}",
                    descriptor.getName(), e.getMessage());
            return null;
        }
    }

    /** Writes {@code text} to {@code path} using UTF-8 encoding. */
    private static void writeTextFile(String path, String text) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8))) {
            pw.print(text);
        }
    }

    /**
     * Attempts a same-host process-signal or {@code jcmd}/{@code jmap} based dump.
     * Returns {@code null} if the target is not on the same host, the PID is unknown,
     * or the mechanism is not available — the caller then falls through to JMX MBeans.
     */
    private String trySignalDump(DumpType type, String outputPath) {
        // Evaluate same-host status once and cache
        if (sameHost == null) {
            String host = ProcessSignalDumper.hostFromJmxUrl(descriptor.getJmxUrl());
            sameHost = ProcessSignalDumper.isSameHost(host);
            WatchdogLogger.info(LOG,
                    "Target [{0}] same-host={1} (JMX host=''{2}'')",
                    descriptor.getName(), sameHost, host);
        }
        if (!sameHost) return null;
        if (remotePid <= 0) {
            WatchdogLogger.fine(LOG,
                    "Same-host dump for [{0}]: remote PID not yet known — "
                    + "waiting for first successful JMX poll", descriptor.getName());
            return null;
        }
        String signalDumpLog = descriptor.getSignalDumpLog();
        WatchdogLogger.info(LOG,
                "Same-host dump: type={0} pid={1} j9={2} javaHome=[{3}] target=[{4}]",
                type, remotePid, remoteIsJ9, remoteJavaHome, descriptor.getName());
        String result = ProcessSignalDumper.dump(type, remotePid, outputPath, remoteIsJ9,
                signalDumpLog, remoteJavaHome);
        if (result != null) {
            WatchdogLogger.info(LOG,
                    "Same-host {0} dump succeeded for [{1}]: {2}", type, descriptor.getName(), result);
        }
        return result;
    }

    /** Parses the numeric PID from a {@code RuntimeMXBean.getName()} string ({@code pid@host}). */
    private static long parsePid(String runtimeName) {
        if (runtimeName == null) return -1L;
        int at = runtimeName.indexOf('@');
        String pidStr = at > 0 ? runtimeName.substring(0, at) : runtimeName;
        try {
            return Long.parseLong(pidStr.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
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
