package com.trongus.oom.remote;

import com.trongus.oom.dump.DumpType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable descriptor for an external JVM process monitored via remote JMX.
 *
 * <p>Encapsulates target connection parameters, risk thresholds, polling frequency,
 * diagnostic dump preferences, and optional credentials required to establish a remote
 * JMX connection to a target JVM (such as QRadar {@code hostcontext}, Tomcat, WebSphere/Liberty,
 * Cognos, or standalone microservices).
 *
 * <h2>Thread Safety</h2>
 * <p>Instances of this class are completely immutable and thread-safe.
 *
 * <h2>Security Note</h2>
 * <p>JMX credentials stored in this descriptor are masked in {@link #toString()} output
 * to prevent accidental exposure in log files or console transcripts.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.7
 * @since 1.7.0
 * @see TargetRegistry
 * @see JmxDiagnosticsCollector
 * @see WatchdogDaemon
 */
public final class TargetDescriptor {

    /** Default warning heap usage threshold (80%). */
    public static final double DEFAULT_WARN_THRESHOLD = 0.80;

    /** Default critical heap usage threshold (90%). */
    public static final double DEFAULT_CRIT_THRESHOLD = 0.90;

    /** Default GC overhead threshold (50%). */
    public static final double DEFAULT_GC_THRESHOLD   = 0.50;

    /** Default background polling interval in milliseconds (5,000 ms). */
    public static final long DEFAULT_POLL_INTERVAL_MS = 5_000L;

    /** Sentinel value meaning "dump threshold disabled". */
    public static final double DUMP_THRESHOLD_DISABLED = -1.0;

    private final String        name;
    private final String        jmxUrl;
    private final String        username;
    private final String        password;
    private final double        warnThreshold;
    private final double        critThreshold;
    private final double        gcThreshold;
    private final long          pollIntervalMs;
    private final Set<DumpType> dumpTypes;
    private final String        dumpDirectory;

    /**
     * Optional LEEF {@code cat} override sent in the QRadar syslog event for this target.
     * When {@code null} the channel uses its built-in default ({@code "JVM_OOM_Risk"}).
     */
    private final String        leefCategory;

    /**
     * Optional free-text tags included as the {@code tags} attribute in the LEEF event.
     * Intended for environment labels, team identifiers, or topology context
     * (e.g. {@code "env=prod,team=platform,region=us-east-1"}).
     * {@code null} when not configured.
     */
    private final String        leefTags;

    /**
     * GC overhead ratio (0.0–1.0) that triggers an immediate dump, or
     * {@link #DUMP_THRESHOLD_DISABLED} ({@code -1}) when disabled.
     */
    private final double        gcDumpThreshold;

    /**
     * Heap used ratio (0.0–1.0) that triggers an immediate dump (independent of
     * the CRITICAL-level dump), or {@link #DUMP_THRESHOLD_DISABLED} when disabled.
     */
    private final double        heapDumpThreshold;

    /**
     * Nursery/young-gen used ratio (0.0–1.0) that triggers an immediate dump, or
     * {@link #DUMP_THRESHOLD_DISABLED} when disabled.
     */
    private final double        nurseryDumpThreshold;

    private TargetDescriptor(Builder b) {
        this.name                 = b.name;
        this.jmxUrl               = b.jmxUrl;
        this.username             = b.username;
        this.password             = b.password;
        this.warnThreshold        = b.warnThreshold;
        this.critThreshold        = b.critThreshold;
        this.gcThreshold          = b.gcThreshold;
        this.pollIntervalMs       = b.pollIntervalMs;
        this.dumpTypes            = Collections.unmodifiableSet(EnumSet.copyOf(b.dumpTypes));
        this.dumpDirectory        = b.dumpDirectory != null ? b.dumpDirectory : "./dumps/" + b.name;
        this.leefCategory         = b.leefCategory;
        this.leefTags             = b.leefTags;
        this.gcDumpThreshold      = b.gcDumpThreshold;
        this.heapDumpThreshold    = b.heapDumpThreshold;
        this.nurseryDumpThreshold = b.nurseryDumpThreshold;
    }

    /**
     * Creates a new mutable {@link Builder} initialized with required target name and JMX URL.
     *
     * @param name   unique identifier or human-readable name of the target JVM; must not be blank
     * @param jmxUrl JMX Service URL (e.g. {@code service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi})
     * @return a new {@link Builder} instance
     */
    public static Builder builder(String name, String jmxUrl) {
        return new Builder(name, jmxUrl);
    }

    /**
     * Returns the human-readable target name (e.g. {@code "hostcontext"}, {@code "tomcat"}).
     *
     * @return non-blank target identifier
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the JMX Service URL string used to connect to the target JVM.
     *
     * @return non-blank JMX service URL
     */
    public String getJmxUrl() {
        return jmxUrl;
    }

    /**
     * Returns the optional JMX authentication username, or {@code null} if unauthenticated.
     *
     * @return username string or {@code null}
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the optional JMX authentication password, or {@code null} if unauthenticated.
     *
     * @return password string or {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * Returns the heap usage ratio (0.0 to 1.0) triggering a WARNING alert.
     *
     * @return warning threshold
     */
    public double getWarnThreshold() {
        return warnThreshold;
    }

    /**
     * Returns the heap usage ratio (0.0 to 1.0) triggering a CRITICAL alert.
     *
     * @return critical threshold
     */
    public double getCritThreshold() {
        return critThreshold;
    }

    /**
     * Returns the GC overhead fraction (0.0 to 1.0) triggering a WARNING alert.
     *
     * @return GC overhead threshold
     */
    public double getGcThreshold() {
        return gcThreshold;
    }

    /**
     * Returns the polling interval between diagnostic collections in milliseconds.
     *
     * @return poll interval in ms
     */
    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    /**
     * Returns an unmodifiable set of {@link DumpType} actions configured for CRITICAL risk.
     *
     * @return unmodifiable set of dump types; never {@code null}
     */
    public Set<DumpType> getDumpTypes() {
        return dumpTypes;
    }

    /**
     * Returns the filesystem directory where diagnostic dumps for this target are saved.
     *
     * @return directory path; never {@code null}
     */
    public String getDumpDirectory() {
        return dumpDirectory;
    }

    /**
     * Returns the optional LEEF {@code cat} attribute override for QRadar events from
     * this target, or {@code null} if the channel default ({@code "JVM_OOM_Risk"}) should
     * be used.
     *
     * @return leef-category string or {@code null}
     */
    public String getLeefCategory() {
        return leefCategory;
    }

    /**
     * Returns the optional LEEF {@code tags} attribute for QRadar events from this target,
     * or {@code null} if no tags are configured.
     *
     * @return leef-tags string or {@code null}
     */
    public String getLeefTags() {
        return leefTags;
    }

    /**
     * Returns the GC overhead ratio threshold (0.0–1.0) that triggers an immediate dump,
     * or {@link #DUMP_THRESHOLD_DISABLED} ({@code -1}) when disabled.
     *
     * @return gc dump threshold, or {@code -1} if disabled
     */
    public double getGcDumpThreshold() {
        return gcDumpThreshold;
    }

    /**
     * Returns the heap used ratio threshold (0.0–1.0) that triggers an immediate dump
     * (independent of the CRITICAL-level dump), or {@link #DUMP_THRESHOLD_DISABLED} when disabled.
     *
     * @return heap dump threshold, or {@code -1} if disabled
     */
    public double getHeapDumpThreshold() {
        return heapDumpThreshold;
    }

    /**
     * Returns the nursery/young-gen ratio threshold (0.0–1.0) that triggers an immediate dump,
     * or {@link #DUMP_THRESHOLD_DISABLED} when disabled.
     *
     * @return nursery dump threshold, or {@code -1} if disabled
     */
    public double getNurseryDumpThreshold() {
        return nurseryDumpThreshold;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetDescriptor)) return false;
        TargetDescriptor that = (TargetDescriptor) o;
        return Double.compare(that.warnThreshold, warnThreshold) == 0 &&
               Double.compare(that.critThreshold, critThreshold) == 0 &&
               Double.compare(that.gcThreshold, gcThreshold) == 0 &&
               Double.compare(that.gcDumpThreshold, gcDumpThreshold) == 0 &&
               Double.compare(that.heapDumpThreshold, heapDumpThreshold) == 0 &&
               Double.compare(that.nurseryDumpThreshold, nurseryDumpThreshold) == 0 &&
               pollIntervalMs == that.pollIntervalMs &&
               Objects.equals(name, that.name) &&
               Objects.equals(jmxUrl, that.jmxUrl) &&
               Objects.equals(username, that.username) &&
               Objects.equals(password, that.password) &&
               Objects.equals(dumpTypes, that.dumpTypes) &&
               Objects.equals(dumpDirectory, that.dumpDirectory) &&
               Objects.equals(leefCategory, that.leefCategory) &&
               Objects.equals(leefTags, that.leefTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, jmxUrl, username, password, warnThreshold,
                            critThreshold, gcThreshold, pollIntervalMs, dumpTypes,
                            dumpDirectory, leefCategory, leefTags,
                            gcDumpThreshold, heapDumpThreshold, nurseryDumpThreshold);
    }

    @Override
    public String toString() {
        return "TargetDescriptor{" +
                "name='" + name + '\'' +
                ", jmxUrl='" + jmxUrl + '\'' +
                ", username=" + (username != null ? "'" + username + "'" : "null") +
                ", password=" + (password != null ? "'***'" : "null") +
                ", warnThreshold=" + warnThreshold +
                ", critThreshold=" + critThreshold +
                ", gcThreshold=" + gcThreshold +
                ", pollIntervalMs=" + pollIntervalMs +
                ", dumpTypes=" + dumpTypes +
                ", dumpDirectory='" + dumpDirectory + '\'' +
                ", leefCategory=" + (leefCategory != null ? "'" + leefCategory + "'" : "null") +
                ", leefTags=" + (leefTags != null ? "'" + leefTags + "'" : "null") +
                ", gcDumpThreshold=" + gcDumpThreshold +
                ", heapDumpThreshold=" + heapDumpThreshold +
                ", nurseryDumpThreshold=" + nurseryDumpThreshold +
                '}';
    }

    /**
     * Builder for constructing immutable {@link TargetDescriptor} instances.
     */
    public static final class Builder {

        private final String  name;
        private final String  jmxUrl;
        private String        username;
        private String        password;
        private double        warnThreshold      = DEFAULT_WARN_THRESHOLD;
        private double        critThreshold      = DEFAULT_CRIT_THRESHOLD;
        private double        gcThreshold        = DEFAULT_GC_THRESHOLD;
        private long          pollIntervalMs     = DEFAULT_POLL_INTERVAL_MS;
        private Set<DumpType> dumpTypes          = EnumSet.noneOf(DumpType.class);
        private String        dumpDirectory;
        private String        leefCategory;
        private String        leefTags;
        private double        gcDumpThreshold      = DUMP_THRESHOLD_DISABLED;
        private double        heapDumpThreshold    = DUMP_THRESHOLD_DISABLED;
        private double        nurseryDumpThreshold = DUMP_THRESHOLD_DISABLED;

        /**
         * Initialises builder with mandatory target name and JMX service URL.
         *
         * @param name   target name; must not be null or blank
         * @param jmxUrl JMX Service URL; must not be null or blank
         * @throws IllegalArgumentException if either parameter is null or blank
         */
        public Builder(String name, String jmxUrl) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Target name must not be null or blank");
            }
            if (jmxUrl == null || jmxUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("Target jmxUrl must not be null or blank");
            }
            this.name   = name.trim();
            this.jmxUrl = jmxUrl.trim();
        }

        /**
         * Sets optional authentication credentials for JMX connections.
         *
         * @param username JMX username (nullable)
         * @param password JMX password (nullable)
         * @return {@code this}
         */
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * Sets the warning heap usage threshold.
         *
         * @param warn heap ratio (0.0 to 1.0)
         * @return {@code this}
         * @throws IllegalArgumentException if out of (0.0, 1.0) range
         */
        public Builder warnThreshold(double warn) {
            if (warn <= 0.0 || warn >= 1.0) {
                throw new IllegalArgumentException("warnThreshold must be strictly between 0.0 and 1.0, got: " + warn);
            }
            this.warnThreshold = warn;
            return this;
        }

        /**
         * Sets the critical heap usage threshold.
         *
         * @param crit heap ratio (0.0 to 1.0)
         * @return {@code this}
         * @throws IllegalArgumentException if out of (0.0, 1.0) range
         */
        public Builder critThreshold(double crit) {
            if (crit <= 0.0 || crit >= 1.0) {
                throw new IllegalArgumentException("critThreshold must be strictly between 0.0 and 1.0, got: " + crit);
            }
            this.critThreshold = crit;
            return this;
        }

        /**
         * Sets the GC overhead ratio threshold.
         *
         * @param gc fraction (0.0 to 1.0)
         * @return {@code this}
         * @throws IllegalArgumentException if out of (0.0, 1.0) range
         */
        public Builder gcThreshold(double gc) {
            if (gc <= 0.0 || gc >= 1.0) {
                throw new IllegalArgumentException("gcThreshold must be strictly between 0.0 and 1.0, got: " + gc);
            }
            this.gcThreshold = gc;
            return this;
        }

        /**
         * Sets the polling interval in milliseconds.
         *
         * @param ms interval in ms (minimum 100 ms)
         * @return {@code this}
         * @throws IllegalArgumentException if ms &lt; 100
         */
        public Builder pollIntervalMs(long ms) {
            if (ms < 100L) {
                throw new IllegalArgumentException("pollIntervalMs must be at least 100 ms, got: " + ms);
            }
            this.pollIntervalMs = ms;
            return this;
        }

        /**
         * Sets the diagnostic dump types to execute when this target reaches CRITICAL risk.
         *
         * @param types collection of dump types
         * @return {@code this}
         */
        public Builder dumpTypes(Set<DumpType> types) {
            if (types == null || types.isEmpty()) {
                this.dumpTypes = EnumSet.noneOf(DumpType.class);
            } else {
                this.dumpTypes = EnumSet.copyOf(types);
            }
            return this;
        }

        /**
         * Sets custom destination directory for dumps generated for this target.
         *
         * @param dir directory path (if null or blank, defaults to {@code ./dumps/<name>})
         * @return {@code this}
         */
        public Builder dumpDirectory(String dir) {
            this.dumpDirectory = (dir != null && !dir.trim().isEmpty()) ? dir.trim() : null;
            return this;
        }

        /**
         * Sets the LEEF {@code cat} attribute override emitted in QRadar syslog events
         * for this target.  When omitted (or set to {@code null}/blank) the channel
         * uses its built-in default value ({@code "JVM_OOM_Risk"}).
         *
         * <p>Example: {@code leefCategory("JVM_OOM_QRadar_hostcontext")}
         *
         * @param category free-text category string; {@code null} or blank = use default
         * @return {@code this}
         */
        public Builder leefCategory(String category) {
            this.leefCategory = (category != null && !category.trim().isEmpty()) ? category.trim() : null;
            return this;
        }

        /**
         * Sets the LEEF {@code tags} attribute emitted in QRadar syslog events for this
         * target.  Intended for environment labels, team names, or topology context.
         *
         * <p>Example: {@code leefTags("env=prod,team=platform,region=us-east-1")}
         *
         * @param tags free-text tags string; {@code null} or blank = attribute omitted
         * @return {@code this}
         */
        public Builder leefTags(String tags) {
            this.leefTags = (tags != null && !tags.trim().isEmpty()) ? tags.trim() : null;
            return this;
        }

        /**
         * Sets the GC overhead ratio threshold (0.0–1.0) that triggers an immediate dump.
         * Use {@link TargetDescriptor#DUMP_THRESHOLD_DISABLED} ({@code -1}) to disable.
         *
         * @param threshold fraction in (0.0, 1.0) or {@code -1} to disable
         * @return {@code this}
         * @throws IllegalArgumentException if threshold is not -1.0 and outside (0.0, 1.0)
         */
        public Builder gcDumpThreshold(double threshold) {
            if (Double.compare(threshold, DUMP_THRESHOLD_DISABLED) != 0 && (threshold <= 0.0 || threshold >= 1.0)) {
                throw new IllegalArgumentException("gcDumpThreshold must be strictly between 0.0 and 1.0, or -1 to disable; got: " + threshold);
            }
            this.gcDumpThreshold = threshold;
            return this;
        }

        /**
         * Sets the heap used ratio threshold (0.0–1.0) that triggers an immediate dump,
         * independent of the existing CRITICAL-level dump trigger.
         * Use {@link TargetDescriptor#DUMP_THRESHOLD_DISABLED} ({@code -1}) to disable.
         *
         * @param threshold fraction in (0.0, 1.0) or {@code -1} to disable
         * @return {@code this}
         * @throws IllegalArgumentException if threshold is not -1.0 and outside (0.0, 1.0)
         */
        public Builder heapDumpThreshold(double threshold) {
            if (Double.compare(threshold, DUMP_THRESHOLD_DISABLED) != 0 && (threshold <= 0.0 || threshold >= 1.0)) {
                throw new IllegalArgumentException("heapDumpThreshold must be strictly between 0.0 and 1.0, or -1 to disable; got: " + threshold);
            }
            this.heapDumpThreshold = threshold;
            return this;
        }

        /**
         * Sets the nursery/young-gen ratio threshold (0.0–1.0) that triggers an immediate dump.
         * Use {@link TargetDescriptor#DUMP_THRESHOLD_DISABLED} ({@code -1}) to disable.
         *
         * @param threshold fraction in (0.0, 1.0) or {@code -1} to disable
         * @return {@code this}
         * @throws IllegalArgumentException if threshold is not -1.0 and outside (0.0, 1.0)
         */
        public Builder nurseryDumpThreshold(double threshold) {
            if (Double.compare(threshold, DUMP_THRESHOLD_DISABLED) != 0 && (threshold <= 0.0 || threshold >= 1.0)) {
                throw new IllegalArgumentException("nurseryDumpThreshold must be strictly between 0.0 and 1.0, or -1 to disable; got: " + threshold);
            }
            this.nurseryDumpThreshold = threshold;
            return this;
        }

        /**
         * Builds and validates the immutable {@link TargetDescriptor}.
         *
         * @return new {@link TargetDescriptor} instance
         * @throws IllegalStateException if warnThreshold &gt;= critThreshold
         */
        public TargetDescriptor build() {
            if (warnThreshold >= critThreshold) {
                throw new IllegalStateException(String.format(
                        "warnThreshold (%.2f) must be strictly less than critThreshold (%.2f) for target '%s'",
                        warnThreshold, critThreshold, name));
            }
            return new TargetDescriptor(this);
        }
    }
}
