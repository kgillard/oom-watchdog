package com.trongus.oom.config;

import com.trongus.oom.dump.DumpType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Immutable configuration for {@code OomWatchdog}.
 *
 * <p>Centralises all tuneable parameters so they can be injected or loaded
 * from properties without touching any monitoring logic (OCP / DIP).
 *
 * <p>Use {@link #defaults()} to obtain a pre-loaded builder, then call
 * {@link Builder#build()} to produce the validated, immutable config.
 *
 * <h2>Builder fields</h2>
 * <table border="1">
 *   <caption>WatchdogConfig builder fields and their defaults</caption>
 *   <tr><th>Field</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code warningHeapThreshold}</td><td>{@code 0.80}</td>
 *       <td>Heap-usage ratio (0–1) that triggers a {@code WARNING} alert.</td></tr>
 *   <tr><td>{@code criticalHeapThreshold}</td><td>{@code 0.90}</td>
 *       <td>Heap-usage ratio (0–1) that triggers a {@code CRITICAL} alert and diagnostic dumps.</td></tr>
 *   <tr><td>{@code gcOverheadThreshold}</td><td>{@code 0.50}</td>
 *       <td>Fraction of CPU time in GC (0–1) that triggers a {@code WARNING} alert.</td></tr>
 *   <tr><td>{@code gcDumpThreshold}</td><td>{@code -1} (disabled)</td>
 *       <td>GC overhead ratio (0–1) that independently triggers a dump, regardless of alert level.</td></tr>
 *   <tr><td>{@code heapDumpThreshold}</td><td>{@code -1} (disabled)</td>
 *       <td>Heap-usage ratio (0–1) that independently triggers a dump, regardless of alert level.</td></tr>
 *   <tr><td>{@code nurseryDumpThreshold}</td><td>{@code -1} (disabled)</td>
 *       <td>Nursery/young-gen used ratio (0–1) that independently triggers a dump.</td></tr>
 *   <tr><td>{@code leakDetectionWindowSize}</td><td>{@code 5}</td>
 *       <td>Number of consecutive post-GC samples that must show growth to flag a memory leak.</td></tr>
 *   <tr><td>{@code pollIntervalMs}</td><td>{@code 5000}</td>
 *       <td>Polling interval between successive JVM diagnostics snapshots, in milliseconds.</td></tr>
 *   <tr><td>{@code heapDumpDirectory}</td><td>{@code "./dumps"}</td>
 *       <td>Directory path where diagnostic dump files are written.</td></tr>
 *   <tr><td>{@code dumpTypes}</td><td>(empty)</td>
 *       <td>Set of {@link com.trongus.oom.dump.DumpType} values to produce at {@code CRITICAL}.</td></tr>
 *   <tr><td>{@code qradarHost}</td><td>{@code ""} (disabled)</td>
 *       <td>QRadar SIEM syslog receiver host; empty string disables QRadar forwarding.</td></tr>
 *   <tr><td>{@code qradarPort}</td><td>{@code 514}</td>
 *       <td>Syslog port for the QRadar event destination.</td></tr>
 *   <tr><td>{@code locale}</td><td>{@link java.util.Locale#getDefault()}</td>
 *       <td>Locale used for alert messages and diagnosis strings.</td></tr>
 *   <tr><td>{@code logLevel}</td><td>{@link java.util.logging.Level#INFO}</td>
 *       <td>Minimum JUL log level for internal watchdog diagnostic output.</td></tr>
 * </table>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.0
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

    // Minimum log level for internal watchdog diagnostics
    private final Level logLevel;                // default Level.INFO

    // Per-metric dump thresholds (-1 = disabled)
    private final double gcDumpThreshold;        // default -1 (disabled)
    private final double heapDumpThreshold;      // default -1 (disabled)
    private final double nurseryDumpThreshold;   // default -1 (disabled)

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
        this.logLevel                = b.logLevel;
        this.gcDumpThreshold         = b.gcDumpThreshold;
        this.heapDumpThreshold       = b.heapDumpThreshold;
        this.nurseryDumpThreshold    = b.nurseryDumpThreshold;
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

    /**
     * Returns the minimum log level for internal watchdog diagnostic messages.
     *
     * <p>This level is applied to the root {@code "com.trongus.oom"} JUL logger
     * when {@link com.trongus.oom.logging.WatchdogLogger#initialise(Level)} is called
     * with this value.  The default is {@link Level#INFO}.
     *
     * @return the configured internal log level; never {@code null}
     */
    public Level        getLogLevel()                { return logLevel; }

    /**
     * Returns the GC overhead ratio that triggers an immediate dump, or {@code -1} when disabled.
     *
     * @return gc dump threshold (0–1), or {@code -1} if disabled
     */
    public double       getGcDumpThreshold()         { return gcDumpThreshold; }

    /**
     * Returns the heap used ratio that triggers an immediate dump (independent of the CRITICAL dump),
     * or {@code -1} when disabled.
     *
     * @return heap dump threshold (0–1), or {@code -1} if disabled
     */
    public double       getHeapDumpThreshold()       { return heapDumpThreshold; }

    /**
     * Returns the nursery/young-gen ratio that triggers an immediate dump, or {@code -1} when disabled.
     *
     * @return nursery dump threshold (0–1), or {@code -1} if disabled
     */
    public double       getNurseryDumpThreshold()    { return nurseryDumpThreshold; }

    /**
     * Returns a new {@link Builder} pre-populated with the settings from this configuration.
     *
     * @return a builder copy initialized from this instance
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.warningHeapThreshold    = this.warningHeapThreshold;
        b.criticalHeapThreshold   = this.criticalHeapThreshold;
        b.gcOverheadThreshold     = this.gcOverheadThreshold;
        b.leakDetectionWindowSize = this.leakDetectionWindowSize;
        b.pollIntervalMs          = this.pollIntervalMs;
        b.heapDumpDirectory       = this.heapDumpDirectory;
        b.dumpTypes               = this.dumpTypes.isEmpty()
                ? EnumSet.noneOf(DumpType.class)
                : EnumSet.copyOf(this.dumpTypes);
        b.qradarHost              = this.qradarHost;
        b.qradarPort              = this.qradarPort;
        b.locale                  = this.locale;
        b.logLevel                = this.logLevel;
        b.gcDumpThreshold         = this.gcDumpThreshold;
        b.heapDumpThreshold       = this.heapDumpThreshold;
        b.nurseryDumpThreshold    = this.nurseryDumpThreshold;
        return b;
    }

    /**
     * Returns a new {@link Builder} pre-loaded with safe defaults.
     *
     * @return a fresh builder with all fields set to their documented defaults
     */
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
     * Call {@link #build()} to produce the validated, immutable {@link WatchdogConfig}.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private double          warningHeapThreshold    = 0.80;
        private double          criticalHeapThreshold   = 0.90;
        private double          gcOverheadThreshold     = 0.50;
        private int             leakDetectionWindowSize = 5;
        private long            pollIntervalMs          = 5_000L;
        private String          heapDumpDirectory       = "./oom-watchdog";
        private Set<DumpType>   dumpTypes               = EnumSet.noneOf(DumpType.class);
        private String          qradarHost              = "";
        private int             qradarPort              = 514;
        private Locale          locale                  = Locale.getDefault();
        private Level           logLevel                = Level.INFO;
        /** -1 = disabled */
        private double          gcDumpThreshold         = -1.0;
        /** -1 = disabled */
        private double          heapDumpThreshold       = -1.0;
        /** -1 = disabled */
        private double          nurseryDumpThreshold    = -1.0;

        private Builder() {}

        /**
         * Sets the heap-usage ratio that triggers a {@code WARNING} alert.
         *
         * @param v threshold value in (0.0, 1.0); must be less than {@code criticalHeapThreshold}
         * @return this builder (fluent API)
         */
        public Builder warningHeapThreshold(double v)    { this.warningHeapThreshold = v;    return this; }

        /**
         * Sets the heap-usage ratio that triggers a {@code CRITICAL} alert and diagnostic dumps.
         *
         * @param v threshold value in (0.0, 1.0); must be greater than {@code warningHeapThreshold}
         * @return this builder (fluent API)
         */
        public Builder criticalHeapThreshold(double v)   { this.criticalHeapThreshold = v;   return this; }

        /**
         * Sets the fraction of CPU time spent in GC that triggers a {@code WARNING} alert.
         *
         * @param v threshold value in (0.0, 1.0)
         * @return this builder (fluent API)
         */
        public Builder gcOverheadThreshold(double v)     { this.gcOverheadThreshold = v;     return this; }

        /**
         * Sets the number of consecutive post-GC samples that must show heap growth
         * before a memory leak is flagged.
         *
         * @param v window size; must be &ge; 2
         * @return this builder (fluent API)
         */
        public Builder leakDetectionWindowSize(int v)    { this.leakDetectionWindowSize = v; return this; }

        /**
         * Sets the polling interval between successive JVM diagnostics snapshots.
         *
         * @param v interval in milliseconds; must be &ge; 100
         * @return this builder (fluent API)
         */
        public Builder pollIntervalMs(long v)            { this.pollIntervalMs = v;           return this; }

        /**
         * Sets the directory path where diagnostic dump files are written.
         *
         * <p>If the supplied path does not already end with {@code oom-watchdog}
         * as its last path segment, that subdirectory is appended automatically.
         * This ensures dump files are always isolated in a dedicated directory
         * (e.g. {@code /var/log} becomes {@code /var/log/oom-watchdog}) rather
         * than mixed in with other content in the configured parent.
         *
         * @param v non-null, non-blank directory path
         * @return this builder (fluent API)
         */
        public Builder heapDumpDirectory(String v) {
            this.heapDumpDirectory = appendOomSubdirIfNeeded(v);
            return this;
        }

        /**
         * Appends {@code /oom-watchdog} to {@code path} unless the last path
         * segment is already {@code oom-watchdog} (case-insensitive).
         */
        private static String appendOomSubdirIfNeeded(String path) {
            if (path == null || path.trim().isEmpty()) return path;
            String p = path.trim();
            // Normalise separators for comparison only; keep originals for the result
            String normalised = p.replace('\\', '/').replaceAll("/+$", "");
            int lastSlash = normalised.lastIndexOf('/');
            String lastSegment = lastSlash >= 0 ? normalised.substring(lastSlash + 1) : normalised;
            if ("oom-watchdog".equalsIgnoreCase(lastSegment)) {
                return p;   // already ends with oom-watchdog — leave as-is
            }
            return p + java.io.File.separator + "oom-watchdog";
        }

        /**
         * Sets the dump types to produce when a {@code CRITICAL} threshold is reached.
         *
         * @param v non-null set of {@link DumpType} values
         * @return this builder (fluent API)
         */
        public Builder dumpTypes(Set<DumpType> v)        { this.dumpTypes = v;                return this; }

        /**
         * Sets the QRadar SIEM syslog receiver host.
         * An empty string disables QRadar forwarding.
         *
         * @param v hostname or IP address; use {@code ""} to disable
         * @return this builder (fluent API)
         */
        public Builder qradarHost(String v)              { this.qradarHost = v;               return this; }

        /**
         * Sets the syslog port for the QRadar event destination.
         *
         * @param v port number in [1, 65535]
         * @return this builder (fluent API)
         */
        public Builder qradarPort(int v)                 { this.qradarPort = v;               return this; }

        /**
         * Sets the GC overhead ratio that independently triggers a dump, regardless of alert level.
         * Use {@code -1} to disable.
         *
         * @param v GC overhead ratio in (0.0, 1.0), or {@code -1} to disable
         * @return this builder (fluent API)
         */
        public Builder gcDumpThreshold(double v)         { this.gcDumpThreshold = v;          return this; }

        /**
         * Sets the heap-usage ratio that independently triggers a dump, regardless of alert level.
         * Use {@code -1} to disable.
         *
         * @param v heap ratio in (0.0, 1.0), or {@code -1} to disable
         * @return this builder (fluent API)
         */
        public Builder heapDumpThreshold(double v)       { this.heapDumpThreshold = v;        return this; }

        /**
         * Sets the nursery/young-gen used ratio that independently triggers a dump.
         * Use {@code -1} to disable.
         *
         * @param v nursery ratio in (0.0, 1.0), or {@code -1} to disable
         * @return this builder (fluent API)
         */
        public Builder nurseryDumpThreshold(double v)    { this.nurseryDumpThreshold = v;     return this; }

        /**
         * Sets the locale used for alert messages, diagnosis strings, and cause explanations.
         * Defaults to {@link Locale#getDefault()} if never called.
         *
         * @param v the desired locale; must not be {@code null}
         * @return this builder (fluent API)
         */
        public Builder locale(Locale v)                  { this.locale = v;                   return this; }

        /**
         * Sets the minimum log level for internal watchdog diagnostics.
         *
         * <p>This level is passed to
         * {@link com.trongus.oom.logging.WatchdogLogger#initialise(Level)} at startup.
         * Defaults to {@link Level#INFO}.
         *
         * @param v the desired log level; must not be {@code null}
         * @return this builder (fluent API)
         */
        public Builder logLevel(Level v)                 { this.logLevel = v;                 return this; }

        /**
         * Convenience setter that parses a comma-separated string of {@link DumpType} names
         * and sets the {@code dumpTypes} field accordingly.
         *
         * @param csv comma-separated dump-type identifiers (e.g. {@code "HEAP,THREAD"})
         * @return this builder (fluent API)
         */
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

        /**
         * Validates all fields and builds the immutable {@link WatchdogConfig}.
         *
         * @return a validated, immutable {@link WatchdogConfig} instance
         * @throws IllegalArgumentException if any field fails validation
         */
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
            // Only validate qradarPort when a QRadar host is actually configured;
            // when QRadar is disabled the port value is irrelevant.
            if (qradarHost != null && !qradarHost.trim().isEmpty()
                    && (qradarPort < 1 || qradarPort > 65535)) {
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
