package com.trongus.oom.config;

import com.trongus.oom.dump.DumpType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable configuration for {@code OomWatchdog}.
 *
 * <p>Centralises all tuneable parameters so they can be injected or loaded
 * from properties without touching any monitoring logic (OCP / DIP).
 *
 * <p>Use {@link #defaults()} to obtain a pre-loaded builder, then call
 * {@link Builder#build()} to produce the validated, immutable config.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.3.0
 * @since 1.0.0
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

    // Locale for i18n alert messages and diagnosis strings
    private final Locale locale;                 // default Locale.getDefault()

    private WatchdogConfig(Builder b) {
        this.warningHeapThreshold    = b.warningHeapThreshold;
        this.criticalHeapThreshold   = b.criticalHeapThreshold;
        this.gcOverheadThreshold     = b.gcOverheadThreshold;
        this.leakDetectionWindowSize = b.leakDetectionWindowSize;
        this.pollIntervalMs          = b.pollIntervalMs;
        this.heapDumpDirectory       = b.heapDumpDirectory;
        this.dumpTypes               = Collections.unmodifiableSet(
                                           b.dumpTypes.isEmpty()
                                           ? EnumSet.noneOf(DumpType.class)
                                           : EnumSet.copyOf(b.dumpTypes));
        this.qradarHost              = b.qradarHost;
        this.qradarPort              = b.qradarPort;
        this.locale                  = b.locale;
    }

    /** @return warning heap threshold (0–1); default {@code 0.80} */
    public double       getWarningHeapThreshold()    { return warningHeapThreshold; }
    /** @return critical heap threshold (0–1); default {@code 0.90} */
    public double       getCriticalHeapThreshold()   { return criticalHeapThreshold; }
    /** @return GC overhead threshold (0–1); default {@code 0.50} */
    public double       getGcOverheadThreshold()     { return gcOverheadThreshold; }
    /** @return number of post-GC samples in the leak-detection rolling window */
    public int          getLeakDetectionWindowSize() { return leakDetectionWindowSize; }
    /** @return polling interval in milliseconds */
    public long         getPollIntervalMs()          { return pollIntervalMs; }
    /** @return directory path where heap dumps are written */
    public String       getHeapDumpDirectory()       { return heapDumpDirectory; }
    /** @return unmodifiable set of dump types to produce at CRITICAL threshold */
    public Set<DumpType> getDumpTypes()              { return dumpTypes; }
    /** @return QRadar syslog host name or IP */
    public String       getQradarHost()              { return qradarHost; }
    /** @return QRadar syslog port (1–65535) */
    public int          getQradarPort()              { return qradarPort; }
    /**
     * Returns the locale used for alert messages, diagnosis strings, and
     * cause explanations produced by this watchdog instance.
     *
     * @return the configured locale; defaults to {@link Locale#getDefault()}
     */
    public Locale       getLocale()                  { return locale; }

    /** Returns a builder pre-loaded with safe defaults. */
    public static Builder defaults() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------
    /**
     * Fluent builder for {@link WatchdogConfig}.
     *
     * <p>Obtain an instance via {@link WatchdogConfig#defaults()}.
     * Call {@link #build()} to produce the validated, immutable config.
     */
    public static final class Builder {

        private double          warningHeapThreshold    = 0.80;
        private double          criticalHeapThreshold   = 0.90;
        private double          gcOverheadThreshold     = 0.50;
        private int             leakDetectionWindowSize = 5;
        private long            pollIntervalMs          = 5_000L;
        private String          heapDumpDirectory       = "./dumps";
        private Set<DumpType>   dumpTypes               = EnumSet.noneOf(DumpType.class);
        private String          qradarHost              = "localhost";
        private int             qradarPort              = 514;
        private Locale          locale                  = Locale.getDefault();

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

        /**
         * Sets the locale used for alert messages, diagnosis strings, and
         * cause explanations.  Defaults to {@link Locale#getDefault()} if
         * this setter is never called.
         *
         * @param v the desired locale; must not be {@code null}
         * @return this builder (fluent API)
         */
        public Builder locale(Locale v)                  { this.locale = v;                   return this; }

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
            if (warningHeapThreshold <= 0.0 || warningHeapThreshold >= 1.0) {
                throw new IllegalArgumentException(
                    "warningHeapThreshold must be in (0.0, 1.0)");
            }
            if (criticalHeapThreshold <= 0.0 || criticalHeapThreshold >= 1.0) {
                throw new IllegalArgumentException(
                    "criticalHeapThreshold must be in (0.0, 1.0)");
            }
            if (warningHeapThreshold >= criticalHeapThreshold) {
                throw new IllegalArgumentException(
                    "warningHeapThreshold must be < criticalHeapThreshold");
            }
            if (gcOverheadThreshold <= 0.0 || gcOverheadThreshold >= 1.0) {
                throw new IllegalArgumentException(
                    "gcOverheadThreshold must be in (0.0, 1.0)");
            }
            if (pollIntervalMs < 100L) {
                throw new IllegalArgumentException(
                    "pollIntervalMs must be >= 100 ms");
            }
            if (leakDetectionWindowSize < 2) {
                throw new IllegalArgumentException(
                    "leakDetectionWindowSize must be >= 2");
            }
            if (qradarPort < 1 || qradarPort > 65535) {
                throw new IllegalArgumentException(
                    "qradarPort must be in [1, 65535]");
            }
            if (heapDumpDirectory == null || heapDumpDirectory.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "heapDumpDirectory must not be null or blank");
            }
            return new WatchdogConfig(this);
        }
    }
}
