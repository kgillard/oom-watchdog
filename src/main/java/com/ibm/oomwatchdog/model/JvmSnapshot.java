package com.ibm.oomwatchdog.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fully populated, immutable snapshot of JVM memory and GC state at a
 * single point in time.  This is the central value object passed between
 * every layer of the system (collector → monitor → alerters / dumper).
 * <p>
 * All memory values are in bytes unless the field name says otherwise.
 */
public final class JvmSnapshot {

    // ── process identity ────────────────────────────────────────────────────
    private final String  processName;
    private final long    timestampMs;

    // ── heap ────────────────────────────────────────────────────────────────
    private final long    heapUsedBytes;
    private final long    heapCommittedBytes;
    private final long    heapMaxBytes;
    /** 0.0 – 1.0 */
    private final double  heapUsedRatio;

    // ── non-heap (Metaspace / Code Cache etc.) ──────────────────────────────
    private final long    nonHeapUsedBytes;
    private final long    nonHeapMaxBytes;

    // ── per-pool breakdown ──────────────────────────────────────────────────
    /** pool name → used bytes; unmodifiable */
    private final Map<String, Long> poolUsedBytes;

    // ── garbage collection ──────────────────────────────────────────────────
    /** collector name → cumulative collection count */
    private final Map<String, Long> gcCollectionCounts;
    /** collector name → cumulative collection time in ms */
    private final Map<String, Long> gcCollectionTimesMs;
    /** total GC time consumed since JVM start (ms) */
    private final long   totalGcTimeMs;
    /** wall-clock uptime of the JVM in ms */
    private final long   jvmUptimeMs;
    /** fraction of uptime spent in GC (0.0 – 1.0) */
    private final double gcOverheadRatio;

    // ── leak-trend signal ───────────────────────────────────────────────────
    /** bytes of heap still used *after* the most recent full GC (−1 if none yet) */
    private final long   postGcHeapUsedBytes;
    /** positive slope (bytes/ms) indicates a possible leak; NaN when insufficient data */
    private final double postGcHeapGrowthRatePerMs;

    // ── assessed risk ────────────────────────────────────────────────────────
    private final OomRiskLevel riskLevel;

    // ── free-form diagnosis notes ────────────────────────────────────────────
    private final String diagnosisNotes;

    // ── heap-dump path (null until a dump has been captured) ─────────────────
    private final String heapDumpPath;

    // -------------------------------------------------------------------------

    private JvmSnapshot(Builder b) {
        this.processName               = b.processName;
        this.timestampMs               = b.timestampMs;
        this.heapUsedBytes             = b.heapUsedBytes;
        this.heapCommittedBytes        = b.heapCommittedBytes;
        this.heapMaxBytes              = b.heapMaxBytes;
        this.heapUsedRatio             = b.heapUsedRatio;
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
    }

    // ── accessors ────────────────────────────────────────────────────────────
    public String     getProcessName()               { return processName; }
    public long       getTimestampMs()               { return timestampMs; }
    public long       getHeapUsedBytes()             { return heapUsedBytes; }
    public long       getHeapCommittedBytes()        { return heapCommittedBytes; }
    public long       getHeapMaxBytes()              { return heapMaxBytes; }
    public double     getHeapUsedRatio()             { return heapUsedRatio; }
    public long       getNonHeapUsedBytes()          { return nonHeapUsedBytes; }
    public long       getNonHeapMaxBytes()           { return nonHeapMaxBytes; }
    public Map<String, Long> getPoolUsedBytes()      { return poolUsedBytes; }
    public Map<String, Long> getGcCollectionCounts() { return gcCollectionCounts; }
    public Map<String, Long> getGcCollectionTimesMs(){ return gcCollectionTimesMs; }
    public long       getTotalGcTimeMs()             { return totalGcTimeMs; }
    public long       getJvmUptimeMs()               { return jvmUptimeMs; }
    public double     getGcOverheadRatio()           { return gcOverheadRatio; }
    public long       getPostGcHeapUsedBytes()       { return postGcHeapUsedBytes; }
    public double     getPostGcHeapGrowthRatePerMs() { return postGcHeapGrowthRatePerMs; }
    public OomRiskLevel getRiskLevel()               { return riskLevel; }
    public String     getDiagnosisNotes()            { return diagnosisNotes; }
    public String     getHeapDumpPath()              { return heapDumpPath; }

    /** Returns a copy of this snapshot with the heap-dump path set. */
    public JvmSnapshot withHeapDumpPath(String path) {
        return toBuilder().heapDumpPath(path).build();
    }

    /** Returns a builder initialised from this snapshot (useful for wither methods). */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.processName               = this.processName;
        b.timestampMs               = this.timestampMs;
        b.heapUsedBytes             = this.heapUsedBytes;
        b.heapCommittedBytes        = this.heapCommittedBytes;
        b.heapMaxBytes              = this.heapMaxBytes;
        b.heapUsedRatio             = this.heapUsedRatio;
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
        return b;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------
    public static final class Builder {

        private String  processName               = "unknown";
        private long    timestampMs               = System.currentTimeMillis();
        private long    heapUsedBytes;
        private long    heapCommittedBytes;
        private long    heapMaxBytes;
        private double  heapUsedRatio;
        private long    nonHeapUsedBytes;
        private long    nonHeapMaxBytes;
        private Map<String, Long> poolUsedBytes       = new LinkedHashMap<>();
        private Map<String, Long> gcCollectionCounts  = new LinkedHashMap<>();
        private Map<String, Long> gcCollectionTimesMs = new LinkedHashMap<>();
        private long    totalGcTimeMs;
        private long    jvmUptimeMs;
        private double  gcOverheadRatio;
        private long    postGcHeapUsedBytes       = -1L;
        private double  postGcHeapGrowthRatePerMs = Double.NaN;
        private OomRiskLevel riskLevel            = OomRiskLevel.OK;
        private String  diagnosisNotes            = "";
        private String  heapDumpPath;

        public Builder processName(String v)               { this.processName = v; return this; }
        public Builder timestampMs(long v)                 { this.timestampMs = v; return this; }
        public Builder heapUsedBytes(long v)               { this.heapUsedBytes = v; return this; }
        public Builder heapCommittedBytes(long v)          { this.heapCommittedBytes = v; return this; }
        public Builder heapMaxBytes(long v)                { this.heapMaxBytes = v; return this; }
        public Builder heapUsedRatio(double v)             { this.heapUsedRatio = v; return this; }
        public Builder nonHeapUsedBytes(long v)            { this.nonHeapUsedBytes = v; return this; }
        public Builder nonHeapMaxBytes(long v)             { this.nonHeapMaxBytes = v; return this; }
        public Builder poolUsedBytes(Map<String,Long> v)   { this.poolUsedBytes = v; return this; }
        public Builder gcCollectionCounts(Map<String,Long> v)  { this.gcCollectionCounts = v; return this; }
        public Builder gcCollectionTimesMs(Map<String,Long> v) { this.gcCollectionTimesMs = v; return this; }
        public Builder totalGcTimeMs(long v)               { this.totalGcTimeMs = v; return this; }
        public Builder jvmUptimeMs(long v)                 { this.jvmUptimeMs = v; return this; }
        public Builder gcOverheadRatio(double v)           { this.gcOverheadRatio = v; return this; }
        public Builder postGcHeapUsedBytes(long v)         { this.postGcHeapUsedBytes = v; return this; }
        public Builder postGcHeapGrowthRatePerMs(double v) { this.postGcHeapGrowthRatePerMs = v; return this; }
        public Builder riskLevel(OomRiskLevel v)           { this.riskLevel = v; return this; }
        public Builder diagnosisNotes(String v)            { this.diagnosisNotes = v; return this; }
        public Builder heapDumpPath(String v)              { this.heapDumpPath = v; return this; }

        public JvmSnapshot build() {
            return new JvmSnapshot(this);
        }
    }
}
