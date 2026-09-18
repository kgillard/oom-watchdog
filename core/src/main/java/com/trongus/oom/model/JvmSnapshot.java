package com.trongus.oom.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable, fully-populated snapshot of a JVM's memory and GC state captured
 * at a single point in time.
 *
 * <p>{@code JvmSnapshot} is the central value object that flows through every layer
 * of the OOM Watchdog pipeline:
 * <pre>
 *   MxBeanDiagnosticsCollector  ─(raw snapshot)──▶  ThresholdRiskAssessor
 *   ThresholdRiskAssessor        ─(assessed)──────▶  OomWatchdog
 *   OomWatchdog                  ─(enriched)──────▶  AlertChannel / HeapDumpService
 * </pre>
 *
 * <p>Because the object is immutable, every stage that needs to add information
 * (e.g. the risk assessor adding {@code riskLevel}, the dump service adding
 * {@code heapDumpPath}) creates a new instance via {@link #toBuilder()} and
 * returns the enriched copy.  The original is never modified.
 *
 * <h2>Memory units</h2>
 * All memory values are in <strong>bytes</strong> unless the field name says
 * otherwise (e.g. {@link #getHeapUsedRatio()} returns a 0–1 fraction).
 *
 * <h2>Thread safety</h2>
 * Instances are fully thread-safe because they are immutable.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.8
 * @since 1.0.0
 * @see com.trongus.oom.collector.JvmDiagnosticsCollector
 * @see com.trongus.oom.monitor.RiskAssessor
 * @see com.trongus.oom.remote.JmxDiagnosticsCollector
 */
public final class JvmSnapshot {

    // ── process identity ─────────────────────────────────────────────────────

    /** Name of the remote target being monitored (e.g. {@code "hostcontext"}), or {@code null} if self-monitoring. */
    private final String targetName;

    /** JVM process name as returned by {@code RuntimeMXBean.getName()}, e.g. {@code "12345@hostname"}. */
    private final String processName;

    /** Wall-clock epoch milliseconds at which this snapshot was taken. */
    private final long timestampMs;

    // ── heap ─────────────────────────────────────────────────────────────────

    /** Bytes of heap memory currently in use by live objects. */
    private final long heapUsedBytes;

    /** Bytes of heap memory currently committed (allocated from the OS). */
    private final long heapCommittedBytes;

    /**
     * Maximum heap size in bytes ({@code -Xmx}).
     * Used as the denominator for {@link #heapUsedRatio}.
     */
    private final long heapMaxBytes;

    /**
     * Fraction of the maximum heap currently in use: {@code heapUsedBytes / heapMaxBytes}.
     * Range: {@code 0.0} to {@code 1.0}.
     */
    private final double heapUsedRatio;

    // ── nursery / young-gen (J9/OpenJ9 and generic via pool names) ────────────

    /**
     * Sum of bytes currently used across all MemoryPoolMXBean entries whose name
     * contains "Eden", "Nursery", or "Young" (case-insensitive).
     * {@code 0} when no matching pools are found.
     */
    private final long nurseryUsedBytes;

    /**
     * Nursery used bytes expressed as a fraction of {@link #heapMaxBytes}:
     * {@code nurseryUsedBytes / heapMaxBytes}.
     * {@link Double#NaN} when no matching pools are found or {@code heapMaxBytes <= 0}.
     */
    private final double nurseryUsedRatio;

    // ── non-heap (Metaspace, Code Cache, Compressed Class Space) ─────────────

    /** Bytes of non-heap memory (Metaspace + Code Cache + other) currently in use. */
    private final long nonHeapUsedBytes;

    /**
     * Maximum non-heap memory in bytes, or {@code -1} when the limit is
     * effectively unlimited (typical for Metaspace without {@code -XX:MaxMetaspaceSize}).
     */
    private final long nonHeapMaxBytes;

    // ── per-pool breakdown ────────────────────────────────────────────────────

    /**
     * Map from memory-pool name to used bytes for every pool visible via
     * {@link java.lang.management.ManagementFactory#getMemoryPoolMXBeans()}.
     *
     * <p>Typical HotSpot G1 pools: {@code G1 Eden Space}, {@code G1 Old Gen},
     * {@code G1 Survivor Space}, {@code Metaspace}, {@code CodeHeap 'profiled nmethods'}, etc.
     * The map is unmodifiable and insertion-ordered.
     */
    private final Map<String, Long> poolUsedBytes;

    // ── garbage collection ────────────────────────────────────────────────────

    /**
     * Map from GC collector name to cumulative collection count since JVM start.
     * Collector names depend on the GC algorithm selected (e.g. {@code G1 Young Generation},
     * {@code G1 Old Generation}, {@code PS Scavenge}, {@code ConcurrentMarkSweep}).
     */
    private final Map<String, Long> gcCollectionCounts;

    /**
     * Map from GC collector name to cumulative collection time in milliseconds
     * since JVM start.
     */
    private final Map<String, Long> gcCollectionTimesMs;

    /** Sum of all {@link #gcCollectionTimesMs} values in milliseconds. */
    private final long totalGcTimeMs;

    /** JVM uptime in milliseconds since the process started ({@code RuntimeMXBean.getUptime()}). */
    private final long jvmUptimeMs;

    /**
     * Fraction of JVM uptime spent in garbage collection:
     * {@code totalGcTimeMs / jvmUptimeMs}.
     * Range: {@code 0.0} to {@code 1.0}.  A value above {@code 0.50} (50%)
     * strongly indicates GC overhead and may precede an OOM.
     */
    private final double gcOverheadRatio;

    // ── post-GC leak trend ────────────────────────────────────────────────────

    /**
     * Bytes of heap still in use immediately <em>after</em> the most recent
     * full GC cycle, or {@code -1} if no GC has occurred yet.
     *
     * <p>A consistently rising value here (the GC reclaims less and less each cycle)
     * is the canonical signature of a Java memory leak.
     */
    private final long postGcHeapUsedBytes;

    /**
     * OLS linear-regression slope over recent post-GC heap samples, expressed
     * in <em>bytes per millisecond</em>.
     *
     * <ul>
     *   <li>Positive value → heap is growing after GC → likely memory leak</li>
     *   <li>Zero or negative → GC is reclaiming memory normally</li>
     *   <li>{@link Double#NaN} → insufficient post-GC samples to compute a slope</li>
     * </ul>
     */
    private final double postGcHeapGrowthRatePerMs;

    // ── assessed risk ─────────────────────────────────────────────────────────

    /**
     * Risk level assigned to this snapshot by the {@link com.trongus.oom.monitor.RiskAssessor}.
     * Raw snapshots produced by the collector always carry {@link OomRiskLevel#OK};
     * the assessor replaces it with the appropriate level.
     */
    private final OomRiskLevel riskLevel;

    // ── diagnosis notes ───────────────────────────────────────────────────────

    /**
     * Human-readable diagnosis notes explaining which signals triggered the
     * current risk level.  Built by the collector and prepended with an
     * assessment summary by the risk assessor.
     *
     * <p>Example: {@code "[Assessment] CRITICAL – OOM imminent. Heap at 91.3% | [GC] ..."}
     */
    private final String diagnosisNotes;

    // ── heap dump path ────────────────────────────────────────────────────────

    /**
     * Semicolon-separated list of absolute paths to any dump files written
     * for this snapshot, or {@code null} if no dump has been captured yet.
     *
     * <p>Set by {@link com.trongus.oom.dump.CompositeDumpService} after a
     * successful dump and propagated back to the alert channels via
     * {@link #withHeapDumpPath(String)}.
     */
    private final String heapDumpPath;

    /**
     * Optional LEEF {@code cat} override sourced from the target's
     * {@code leef-category} property, or {@code null} to use the channel default.
     */
    private final String leefCategory;

    /**
     * Optional LEEF {@code tags} attribute sourced from the target's
     * {@code leef-tags} property, or {@code null} when not configured.
     */
    private final String leefTags;

    /**
     * The critical heap threshold (0.0–1.0) that was in effect when this snapshot
     * was assessed, stamped by {@link com.trongus.oom.monitor.ThresholdRiskAssessor}.
     * {@code -1} when not yet assessed.
     */
    private final double critThreshold;

    // ── JVM process detail ────────────────────────────────────────────────────

    /** {@code java.home} system property — the directory containing the JRE/JDK. */
    private final String javaHome;

    /** {@code java.version} + vendor string. */
    private final String javaVersion;

    /** {@code java.vm.name} + vm.version string. */
    private final String jvmName;

    /** OS name + version + arch from {@code OperatingSystemMXBean}. */
    private final String osName;

    /** Number of logical CPUs available to the JVM process. */
    private final int cpuCount;

    /**
     * Process CPU load as a percentage (0–100), or {@code -1} when
     * {@code com.sun.management.OperatingSystemMXBean} is not available.
     */
    private final double processCpuPct;

    /**
     * Cumulative process CPU time in milliseconds, or {@code -1} when unavailable.
     */
    private final long processCpuMs;

    /** Space-separated JVM input arguments ({@code -X}, {@code -D}, flags). */
    private final String jvmInputArgs;

    /** {@code sun.java.command} — main class and application arguments. */
    private final String javaCommand;

    /** Live thread count at snapshot time. */
    private final int threadCount;

    /** Peak thread count since JVM start. */
    private final int peakThreadCount;

    // ── constructor ───────────────────────────────────────────────────────────

    /**
     * Private constructor; use {@link Builder} to construct instances.
     *
     * @param b fully populated builder
     */
    private JvmSnapshot(final Builder b) {
        this.targetName                = b.targetName;
        this.processName               = b.processName;
        this.timestampMs               = b.timestampMs;
        this.heapUsedBytes             = b.heapUsedBytes;
        this.heapCommittedBytes        = b.heapCommittedBytes;
        this.heapMaxBytes              = b.heapMaxBytes;
        this.heapUsedRatio             = b.heapUsedRatio;
        this.nurseryUsedBytes          = b.nurseryUsedBytes;
        this.nurseryUsedRatio          = b.nurseryUsedRatio;
        this.nonHeapUsedBytes          = b.nonHeapUsedBytes;
        this.nonHeapMaxBytes           = b.nonHeapMaxBytes;
        this.poolUsedBytes             = Collections.unmodifiableMap(new LinkedHashMap<>(b.poolUsedBytes));
        this.gcCollectionCounts        = Collections.unmodifiableMap(new LinkedHashMap<>(b.gcCollectionCounts));
        this.gcCollectionTimesMs       = Collections.unmodifiableMap(new LinkedHashMap<>(b.gcCollectionTimesMs));
        this.totalGcTimeMs             = b.totalGcTimeMs;
        this.jvmUptimeMs               = b.jvmUptimeMs;
        this.gcOverheadRatio           = b.gcOverheadRatio;
        this.postGcHeapUsedBytes       = b.postGcHeapUsedBytes;
        this.postGcHeapGrowthRatePerMs = b.postGcHeapGrowthRatePerMs;
        this.riskLevel                 = b.riskLevel;
        this.diagnosisNotes            = b.diagnosisNotes;
        this.heapDumpPath              = b.heapDumpPath;
        this.leefCategory              = b.leefCategory;
        this.leefTags                  = b.leefTags;
        this.critThreshold             = b.critThreshold;
        this.javaHome                  = b.javaHome;
        this.javaVersion               = b.javaVersion;
        this.jvmName                   = b.jvmName;
        this.osName                    = b.osName;
        this.cpuCount                  = b.cpuCount;
        this.processCpuPct             = b.processCpuPct;
        this.processCpuMs              = b.processCpuMs;
        this.jvmInputArgs              = b.jvmInputArgs;
        this.javaCommand               = b.javaCommand;
        this.threadCount               = b.threadCount;
        this.peakThreadCount           = b.peakThreadCount;
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    /** @return target name if monitoring an external JVM via JMX, or {@code null} if self-monitoring */
    public String     getTargetName()                { return targetName; }

    /** @return process name from {@code RuntimeMXBean.getName()}, e.g. {@code "12345@host"} */
    public String     getProcessName()               { return processName; }

    /** @return epoch milliseconds when this snapshot was captured */
    public long       getTimestampMs()               { return timestampMs; }

    /** @return heap memory currently used by live objects, in bytes */
    public long       getHeapUsedBytes()             { return heapUsedBytes; }

    /** @return heap memory committed (allocated from OS), in bytes */
    public long       getHeapCommittedBytes()        { return heapCommittedBytes; }

    /** @return maximum heap size ({@code -Xmx}), in bytes */
    public long       getHeapMaxBytes()              { return heapMaxBytes; }

    /** @return heap utilisation fraction in range {@code 0.0–1.0} */
    public double     getHeapUsedRatio()             { return heapUsedRatio; }

    /** @return bytes used across nursery/young-gen pools (Eden+Nursery+Young); {@code 0} if none found */
    public long       getNurseryUsedBytes()          { return nurseryUsedBytes; }

    /**
     * @return nursery used as fraction of heap max; {@link Double#NaN} when no nursery pools found
     */
    public double     getNurseryUsedRatio()          { return nurseryUsedRatio; }

    /** @return non-heap (Metaspace + Code Cache) memory in use, in bytes */
    public long       getNonHeapUsedBytes()          { return nonHeapUsedBytes; }

    /** @return non-heap maximum in bytes, or {@code -1} if unlimited */
    public long       getNonHeapMaxBytes()           { return nonHeapMaxBytes; }

    /** @return unmodifiable map of memory-pool name → used bytes */
    public Map<String, Long> getPoolUsedBytes()      { return poolUsedBytes; }

    /** @return unmodifiable map of GC collector name → cumulative count */
    public Map<String, Long> getGcCollectionCounts() { return gcCollectionCounts; }

    /** @return unmodifiable map of GC collector name → cumulative time in ms */
    public Map<String, Long> getGcCollectionTimesMs(){ return gcCollectionTimesMs; }

    /** @return total cumulative GC time in milliseconds since JVM start */
    public long       getTotalGcTimeMs()             { return totalGcTimeMs; }

    /** @return JVM uptime in milliseconds */
    public long       getJvmUptimeMs()               { return jvmUptimeMs; }

    /** @return fraction of JVM uptime spent in GC (0.0–1.0) */
    public double     getGcOverheadRatio()           { return gcOverheadRatio; }

    /** @return heap in use immediately after most recent GC, in bytes, or {@code -1} */
    public long       getPostGcHeapUsedBytes()       { return postGcHeapUsedBytes; }

    /**
     * @return OLS slope of post-GC heap samples in bytes/ms; positive = growing leak;
     *         {@link Double#NaN} = insufficient data
     */
    public double     getPostGcHeapGrowthRatePerMs() { return postGcHeapGrowthRatePerMs; }

    /** @return the assessed {@link OomRiskLevel} for this snapshot */
    public OomRiskLevel getRiskLevel()               { return riskLevel; }

    /** @return human-readable assessment and diagnosis string */
    public String     getDiagnosisNotes()            { return diagnosisNotes; }

    /**
     * @return semicolon-separated paths to dump files written for this snapshot,
     *         or {@code null} if none have been taken
     */
    public String     getHeapDumpPath()              { return heapDumpPath; }

    /**
     * @return LEEF {@code cat} override from the target's {@code leef-category} property,
     *         or {@code null} to use the channel default ({@code "JVM_OOM_Risk"})
     */
    public String     getLeefCategory()              { return leefCategory; }

    /**
     * @return LEEF {@code tags} value from the target's {@code leef-tags} property,
     *         or {@code null} if not configured
     */
    public String     getLeefTags()                  { return leefTags; }

    /**
     * @return critical heap threshold (0.0–1.0) active when this snapshot was assessed;
     *         {@code -1} if assessment has not run yet
     */
    public double     getCritThreshold()             { return critThreshold; }

    /** @return {@code java.home} — directory containing the JRE/JDK, or empty string */
    public String     getJavaHome()                  { return javaHome != null ? javaHome : ""; }

    /** @return {@code java.version} and vendor string */
    public String     getJavaVersion()               { return javaVersion != null ? javaVersion : ""; }

    /** @return {@code java.vm.name} and vm version string */
    public String     getJvmName()                   { return jvmName != null ? jvmName : ""; }

    /** @return OS name, version and architecture */
    public String     getOsName()                    { return osName != null ? osName : ""; }

    /** @return number of logical CPUs available to the JVM */
    public int        getCpuCount()                  { return cpuCount; }

    /** @return process CPU load as a percentage (0–100), or {@code -1} if unavailable */
    public double     getProcessCpuPct()             { return processCpuPct; }

    /** @return cumulative process CPU time in milliseconds, or {@code -1} if unavailable */
    public long       getProcessCpuMs()              { return processCpuMs; }

    /** @return space-separated JVM input arguments */
    public String     getJvmInputArgs()              { return jvmInputArgs != null ? jvmInputArgs : ""; }

    /** @return {@code sun.java.command} — main class and application arguments */
    public String     getJavaCommand()               { return javaCommand != null ? javaCommand : ""; }

    /** @return live thread count at snapshot time */
    public int        getThreadCount()               { return threadCount; }

    /** @return peak thread count since JVM start */
    public int        getPeakThreadCount()           { return peakThreadCount; }

    // ── wither ────────────────────────────────────────────────────────────────

    /**
     * Returns a new {@code JvmSnapshot} identical to {@code this} except that the
     * {@link #heapDumpPath} field is set to {@code path}.
     *
     * <p>Used by the watchdog to attach dump paths returned by
     * {@link com.trongus.oom.dump.HeapDumpService} before forwarding the enriched
     * snapshot to alert channels.
     *
     * @param path semicolon-separated list of absolute dump-file paths (may be {@code null})
     * @return new snapshot with the given dump path
     */
    public JvmSnapshot withHeapDumpPath(final String path) {
        return toBuilder().heapDumpPath(path).build();
    }

    /**
     * Returns a mutable {@link Builder} pre-populated with every field of this snapshot.
     * Useful when an enrichment stage wants to change a small number of fields while
     * preserving all others.
     *
     * @return a new builder initialised from this snapshot
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.targetName                = this.targetName;
        b.processName               = this.processName;
        b.timestampMs               = this.timestampMs;
        b.heapUsedBytes             = this.heapUsedBytes;
        b.heapCommittedBytes        = this.heapCommittedBytes;
        b.heapMaxBytes              = this.heapMaxBytes;
        b.heapUsedRatio             = this.heapUsedRatio;
        b.nurseryUsedBytes          = this.nurseryUsedBytes;
        b.nurseryUsedRatio          = this.nurseryUsedRatio;
        b.nonHeapUsedBytes          = this.nonHeapUsedBytes;
        b.nonHeapMaxBytes           = this.nonHeapMaxBytes;
        b.poolUsedBytes             = new LinkedHashMap<>(this.poolUsedBytes);
        b.gcCollectionCounts        = new LinkedHashMap<>(this.gcCollectionCounts);
        b.gcCollectionTimesMs       = new LinkedHashMap<>(this.gcCollectionTimesMs);
        b.totalGcTimeMs             = this.totalGcTimeMs;
        b.jvmUptimeMs               = this.jvmUptimeMs;
        b.gcOverheadRatio           = this.gcOverheadRatio;
        b.postGcHeapUsedBytes       = this.postGcHeapUsedBytes;
        b.postGcHeapGrowthRatePerMs = this.postGcHeapGrowthRatePerMs;
        b.riskLevel                 = this.riskLevel;
        b.diagnosisNotes            = this.diagnosisNotes;
        b.heapDumpPath              = this.heapDumpPath;
        b.leefCategory              = this.leefCategory;
        b.leefTags                  = this.leefTags;
        b.critThreshold             = this.critThreshold;
        b.javaHome                  = this.javaHome;
        b.javaVersion               = this.javaVersion;
        b.jvmName                   = this.jvmName;
        b.osName                    = this.osName;
        b.cpuCount                  = this.cpuCount;
        b.processCpuPct             = this.processCpuPct;
        b.processCpuMs              = this.processCpuMs;
        b.jvmInputArgs              = this.jvmInputArgs;
        b.javaCommand               = this.javaCommand;
        b.threadCount               = this.threadCount;
        b.peakThreadCount           = this.peakThreadCount;
        return b;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Mutable builder for {@link JvmSnapshot}.
     *
     * <p>All fields are initialised to safe defaults so that partial snapshots
     * (used during early JVM start-up before GC data is available) do not
     * cause {@code NullPointerException}s downstream.
     */
    public static final class Builder {

        private String  targetName;
        private String  processName               = "unknown";
        private long    timestampMs               = System.currentTimeMillis();
        private long    heapUsedBytes;
        private long    heapCommittedBytes;
        private long    heapMaxBytes;
        private double  heapUsedRatio;
        /** 0 when no nursery/young-gen pools are found. */
        private long    nurseryUsedBytes   = 0L;
        /** NaN when no nursery/young-gen pools are found or heapMax <= 0. */
        private double  nurseryUsedRatio   = Double.NaN;
        private long    nonHeapUsedBytes;
        private long    nonHeapMaxBytes;
        private Map<String, Long> poolUsedBytes       = new LinkedHashMap<>();
        private Map<String, Long> gcCollectionCounts  = new LinkedHashMap<>();
        private Map<String, Long> gcCollectionTimesMs = new LinkedHashMap<>();
        private long    totalGcTimeMs;
        private long    jvmUptimeMs;
        private double  gcOverheadRatio;
        /** -1 indicates no post-GC sample available yet. */
        private long    postGcHeapUsedBytes       = -1L;
        /** NaN until at least two post-GC samples have been recorded. */
        private double  postGcHeapGrowthRatePerMs = Double.NaN;
        private OomRiskLevel riskLevel            = OomRiskLevel.OK;
        private String  diagnosisNotes            = "";
        private String  heapDumpPath;
        private String  leefCategory;
        private String  leefTags;
        /** -1 until stamped by ThresholdRiskAssessor. */
        private double  critThreshold             = -1.0;
        private String  javaHome                  = "";
        private String  javaVersion               = "";
        private String  jvmName                   = "";
        private String  osName                    = "";
        private int     cpuCount                  = 0;
        private double  processCpuPct             = -1.0;
        private long    processCpuMs              = -1L;
        private String  jvmInputArgs              = "";
        private String  javaCommand               = "";
        private int     threadCount               = 0;
        private int     peakThreadCount           = 0;

        /** @param v target name (e.g. "hostcontext"); @return {@code this} */
        public Builder targetName(String v)                { this.targetName = v; return this; }
        /** @param v JVM process name; @return {@code this} */
        public Builder processName(String v)               { this.processName = v; return this; }
        /** @param v epoch ms timestamp; @return {@code this} */
        public Builder timestampMs(long v)                 { this.timestampMs = v; return this; }
        /** @param v heap used bytes; @return {@code this} */
        public Builder heapUsedBytes(long v)               { this.heapUsedBytes = v; return this; }
        /** @param v heap committed bytes; @return {@code this} */
        public Builder heapCommittedBytes(long v)          { this.heapCommittedBytes = v; return this; }
        /** @param v max heap bytes ({@code -Xmx}); @return {@code this} */
        public Builder heapMaxBytes(long v)                { this.heapMaxBytes = v; return this; }
        /** @param v heap used ratio 0–1; @return {@code this} */
        public Builder heapUsedRatio(double v)             { this.heapUsedRatio = v; return this; }
        /** @param v nursery/young-gen used bytes; @return {@code this} */
        public Builder nurseryUsedBytes(long v)            { this.nurseryUsedBytes = v; return this; }
        /** @param v nursery used ratio (nurseryUsedBytes/heapMax); NaN if unavailable; @return {@code this} */
        public Builder nurseryUsedRatio(double v)          { this.nurseryUsedRatio = v; return this; }
        /** @param v non-heap used bytes; @return {@code this} */
        public Builder nonHeapUsedBytes(long v)            { this.nonHeapUsedBytes = v; return this; }
        /** @param v non-heap max bytes (−1 if unlimited); @return {@code this} */
        public Builder nonHeapMaxBytes(long v)             { this.nonHeapMaxBytes = v; return this; }
        /** @param v pool name → used bytes map (defensive copy taken); @return {@code this} */
        public Builder poolUsedBytes(Map<String,Long> v)   {
            this.poolUsedBytes = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }
        /** @param v GC name → count map (defensive copy taken); @return {@code this} */
        public Builder gcCollectionCounts(Map<String,Long> v) {
            this.gcCollectionCounts = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }
        /** @param v GC name → time ms map (defensive copy taken); @return {@code this} */
        public Builder gcCollectionTimesMs(Map<String,Long> v) {
            this.gcCollectionTimesMs = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }
        /** @param v total GC ms; @return {@code this} */
        public Builder totalGcTimeMs(long v)               { this.totalGcTimeMs = v; return this; }
        /** @param v JVM uptime ms; @return {@code this} */
        public Builder jvmUptimeMs(long v)                 { this.jvmUptimeMs = v; return this; }
        /** @param v GC overhead fraction 0–1; @return {@code this} */
        public Builder gcOverheadRatio(double v)           { this.gcOverheadRatio = v; return this; }
        /** @param v post-GC heap used bytes; @return {@code this} */
        public Builder postGcHeapUsedBytes(long v)         { this.postGcHeapUsedBytes = v; return this; }
        /** @param v growth rate bytes/ms; @return {@code this} */
        public Builder postGcHeapGrowthRatePerMs(double v) { this.postGcHeapGrowthRatePerMs = v; return this; }
        /** @param v assessed risk level; @return {@code this} */
        public Builder riskLevel(OomRiskLevel v)           { this.riskLevel = v; return this; }
        /** @param v human-readable diagnosis; @return {@code this} */
        public Builder diagnosisNotes(String v)            { this.diagnosisNotes = v; return this; }
        /** @param v semicolon-separated dump paths; @return {@code this} */
        public Builder heapDumpPath(String v)              { this.heapDumpPath = v; return this; }
        /** @param v LEEF cat override (null = use channel default); @return {@code this} */
        public Builder leefCategory(String v)              { this.leefCategory = v; return this; }
        /** @param v LEEF tags string (null = omit attribute); @return {@code this} */
        public Builder leefTags(String v)                  { this.leefTags = v; return this; }
        /** @param v critical heap threshold 0–1 stamped by assessor; @return {@code this} */
        public Builder critThreshold(double v)             { this.critThreshold = v; return this; }
        /** @param v java.home path; @return {@code this} */
        public Builder javaHome(String v)                  { this.javaHome = v != null ? v : ""; return this; }
        /** @param v java.version + vendor; @return {@code this} */
        public Builder javaVersion(String v)               { this.javaVersion = v != null ? v : ""; return this; }
        /** @param v java.vm.name + version; @return {@code this} */
        public Builder jvmName(String v)                   { this.jvmName = v != null ? v : ""; return this; }
        /** @param v OS name + version + arch; @return {@code this} */
        public Builder osName(String v)                    { this.osName = v != null ? v : ""; return this; }
        /** @param v logical CPU count; @return {@code this} */
        public Builder cpuCount(int v)                     { this.cpuCount = v; return this; }
        /** @param v process CPU % (0–100), or -1 if unavailable; @return {@code this} */
        public Builder processCpuPct(double v)             { this.processCpuPct = v; return this; }
        /** @param v process CPU time ms, or -1 if unavailable; @return {@code this} */
        public Builder processCpuMs(long v)                { this.processCpuMs = v; return this; }
        /** @param v space-separated JVM input args; @return {@code this} */
        public Builder jvmInputArgs(String v)              { this.jvmInputArgs = v != null ? v : ""; return this; }
        /** @param v sun.java.command value; @return {@code this} */
        public Builder javaCommand(String v)               { this.javaCommand = v != null ? v : ""; return this; }
        /** @param v live thread count; @return {@code this} */
        public Builder threadCount(int v)                  { this.threadCount = v; return this; }
        /** @param v peak thread count; @return {@code this} */
        public Builder peakThreadCount(int v)              { this.peakThreadCount = v; return this; }

        /**
         * Constructs and returns an immutable {@link JvmSnapshot} from this builder.
         *
         * @return new immutable snapshot
         */
        public JvmSnapshot build() {
            return new JvmSnapshot(this);
        }
    }
}
