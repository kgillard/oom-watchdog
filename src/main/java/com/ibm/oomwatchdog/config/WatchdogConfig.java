package com.ibm.oomwatchdog.config;

import com.ibm.oomwatchdog.dump.DumpType;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable configuration for OomWatchdog.
 * Centralises all tuneable parameters so they can be injected or loaded
 * from properties without touching any monitoring logic (OCP / DIP).
 */
public final class WatchdogConfig {

    // Heap-usage thresholds (0.0 – 1.0)
    private final double warningHeapThreshold;   // default 0.80
    private final double criticalHeapThreshold;  // default 0.90

    // GC-overhead threshold: fraction of CPU time spent in GC before raising a warning
    private final double gcOverheadThreshold;    // default 0.50 (50 %)

    // How many consecutive post-GC samples must show growth to flag a leak
    private final int leakDetectionWindowSize;   // default 5

    // Polling interval in milliseconds
    private final long pollIntervalMs;           // default 5 000

    // Dump output directory
    private final String heapDumpDirectory;      // default "./dumps"

    // Which dump types to produce when CRITICAL is reached (user-selectable)
    private final Set<DumpType> dumpTypes;

    // QRadar syslog target
    private final String qradarHost;
    private final int    qradarPort;             // default 514

    private WatchdogConfig(Builder b) {
        this.warningHeapThreshold   = b.warningHeapThreshold;
        this.criticalHeapThreshold  = b.criticalHeapThreshold;
        this.gcOverheadThreshold    = b.gcOverheadThreshold;
        this.leakDetectionWindowSize = b.leakDetectionWindowSize;
        this.pollIntervalMs         = b.pollIntervalMs;
        this.heapDumpDirectory      = b.heapDumpDirectory;
        this.dumpTypes              = Collections.unmodifiableSet(
                                          b.dumpTypes.isEmpty()
                                          ? EnumSet.noneOf(DumpType.class)
                                          : EnumSet.copyOf(b.dumpTypes));
        this.qradarHost             = b.qradarHost;
        this.qradarPort             = b.qradarPort;
    }

    public double       getWarningHeapThreshold()    { return warningHeapThreshold; }
    public double       getCriticalHeapThreshold()   { return criticalHeapThreshold; }
    public double       getGcOverheadThreshold()     { return gcOverheadThreshold; }
    public int          getLeakDetectionWindowSize() { return leakDetectionWindowSize; }
    public long         getPollIntervalMs()          { return pollIntervalMs; }
    public String       getHeapDumpDirectory()       { return heapDumpDirectory; }
    /** Unmodifiable set of dump types to produce at CRITICAL threshold. */
    public Set<DumpType> getDumpTypes()              { return dumpTypes; }
    public String       getQradarHost()              { return qradarHost; }
    public int          getQradarPort()              { return qradarPort; }

    /** Returns a builder pre-loaded with safe defaults. */
    public static Builder defaults() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------
    public static final class Builder {

        private double          warningHeapThreshold   = 0.80;
        private double          criticalHeapThreshold  = 0.90;
        private double          gcOverheadThreshold    = 0.50;
        private int             leakDetectionWindowSize = 5;
        private long            pollIntervalMs         = 5_000L;
        private String          heapDumpDirectory      = "./dumps";
        private Set<DumpType>   dumpTypes              = EnumSet.noneOf(DumpType.class);
        private String          qradarHost             = "localhost";
        private int             qradarPort             = 514;

        private Builder() {}

        public Builder warningHeapThreshold(double v)    { this.warningHeapThreshold = v;    return this; }
        public Builder criticalHeapThreshold(double v)   { this.criticalHeapThreshold = v;   return this; }
        public Builder gcOverheadThreshold(double v)     { this.gcOverheadThreshold = v;     return this; }
        public Builder leakDetectionWindowSize(int v)    { this.leakDetectionWindowSize = v; return this; }
        public Builder pollIntervalMs(long v)            { this.pollIntervalMs = v;           return this; }
        public Builder heapDumpDirectory(String v)       { this.heapDumpDirectory = v;        return this; }
        public Builder dumpTypes(Set<DumpType> v)        { this.dumpTypes = v;                return this; }
        public Builder qradarHost(String v)              { this.qradarHost = v;               return this; }
        public Builder qradarPort(int v)                 { this.qradarPort = v;               return this; }

        /** Convenience: parse a comma-separated string of DumpType names. */
        public Builder dumpTypesFromString(String csv) {
            Set<DumpType> set = EnumSet.noneOf(DumpType.class);
            for (String token : csv.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    set.add(DumpType.fromString(trimmed));
                }
            }
            this.dumpTypes = set;
            return this;
        }

        public WatchdogConfig build() {
            if (warningHeapThreshold >= criticalHeapThreshold) {
                throw new IllegalArgumentException(
                    "warningHeapThreshold must be < criticalHeapThreshold");
            }
            return new WatchdogConfig(this);
        }
    }
}
