package com.trongus.oom.examples;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Example 04 — Custom implementations of all three extension points.
 *
 * <h2>What this example shows</h2>
 * <p>OOM Watchdog is designed around four narrow interfaces. You can replace any or
 * all of the built-in implementations with your own without touching any other class:
 *
 * <ul>
 *   <li>{@link JvmDiagnosticsCollector} — how JVM metrics are gathered (custom
 *       metric sources: cloud agents, APM SDKs, mock data for tests).</li>
 *   <li>{@link RiskAssessor} — how the risk level is classified (custom rules:
 *       ML-based thresholds, application-specific logic, environment-aware thresholds).</li>
 *   <li>{@link HeapDumpService} — what happens when a dump is triggered (custom
 *       destinations: S3, Artifactory, remote capture agent).</li>
 *   <li>{@link AlertChannel} — where alerts go (custom back-ends: Slack, Teams,
 *       PagerDuty, email, Prometheus metric counter).</li>
 * </ul>
 *
 * <h2>Open/Closed Principle</h2>
 * <p>The watchdog is <em>open for extension</em> (plug in any implementation) and
 * <em>closed for modification</em> (you never need to touch {@link OomWatchdog} itself).
 * The interfaces are narrow by design — each has exactly one method — making custom
 * implementations trivial to write and test in isolation.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.0.0
 * @see JvmDiagnosticsCollector
 * @see RiskAssessor
 * @see HeapDumpService
 * @see AlertChannel
 */
public final class Example04CustomImplementations {

    /** Utility class — construction is not permitted. */
    private Example04CustomImplementations() {}

    // =========================================================================
    // Custom JvmDiagnosticsCollector
    // =========================================================================

    /**
     * A synthetic collector that fabricates escalating heap pressure.
     *
     * <p>This is useful in unit tests, demos, and load-simulation harnesses where
     * you want the watchdog to fire alerts without actually exhausting heap.
     * Each call returns a snapshot with heap ratio {@code baseRatio + (calls * step)},
     * capped at {@code 1.0}.
     *
     * <p>Thread safety: {@code callCount} is accessed only from the single-threaded
     * watchdog scheduler; no synchronisation is needed.
     *
     * @see JvmDiagnosticsCollector
     */
    public static final class EscalatingHeapCollector implements JvmDiagnosticsCollector {

        private static final long MB = 1024L * 1024L;

        /** Simulated heap maximum (100 MB). */
        private static final long MAX_HEAP = 100L * MB;

        /** Starting heap utilisation fraction. */
        private final double baseRatio;

        /** Amount added to the heap ratio on each successive call. */
        private final double stepPerCall;

        /** Number of times {@code collect()} has been called. */
        private int callCount = 0;

        /**
         * Creates a new escalating collector.
         *
         * @param baseRatio   starting heap ratio (0.0–1.0)
         * @param stepPerCall increment added per call (0.0–1.0); must be positive
         * @throws IllegalArgumentException if either parameter is out of range
         */
        public EscalatingHeapCollector(double baseRatio, double stepPerCall) {
            if (baseRatio < 0.0 || baseRatio > 1.0) {
                throw new IllegalArgumentException("baseRatio must be in [0.0, 1.0]");
            }
            if (stepPerCall <= 0.0 || stepPerCall > 1.0) {
                throw new IllegalArgumentException("stepPerCall must be in (0.0, 1.0]");
            }
            this.baseRatio   = baseRatio;
            this.stepPerCall = stepPerCall;
        }

        /**
         * Returns a synthetic {@link JvmSnapshot} with a progressively higher heap ratio.
         *
         * <p>The ratio is clamped to {@code 1.0} so snapshots always represent a valid
         * heap state even after many calls.
         *
         * @return synthetic snapshot; never {@code null}
         */
        @Override
        public JvmSnapshot collect() {
            double ratio = Math.min(1.0, baseRatio + callCount * stepPerCall);
            callCount++;

            long used = (long) (MAX_HEAP * ratio);
            return new JvmSnapshot.Builder()
                    .processName("escalating-sim@localhost")
                    .timestampMs(System.currentTimeMillis())
                    .heapUsedBytes(used)
                    .heapCommittedBytes(MAX_HEAP)
                    .heapMaxBytes(MAX_HEAP)
                    .heapUsedRatio(ratio)
                    .nonHeapUsedBytes(30L * MB)
                    .nonHeapMaxBytes(-1L)                // unlimited Metaspace
                    .poolUsedBytes(Collections.emptyMap())
                    .gcCollectionCounts(Collections.emptyMap())
                    .gcCollectionTimesMs(Collections.emptyMap())
                    .totalGcTimeMs(0L)
                    .jvmUptimeMs(60_000L)
                    .gcOverheadRatio(0.05)
                    .postGcHeapUsedBytes(-1L)
                    .postGcHeapGrowthRatePerMs(Double.NaN)
                    .riskLevel(OomRiskLevel.OK)
                    .diagnosisNotes(String.format("Simulated heap at %.1f%%", ratio * 100))
                    .build();
        }
    }

    // =========================================================================
    // Custom RiskAssessor
    // =========================================================================

    /**
     * An environment-aware risk assessor that applies stricter thresholds during
     * business hours (09:00–17:00 local time) and relaxed thresholds off-hours.
     *
     * <p>Real use-cases for custom assessors include:
     * <ul>
     *   <li>Tighter thresholds during peak load windows</li>
     *   <li>Machine-learning-based anomaly detection using historical baselines</li>
     *   <li>Suppression of alerts during planned maintenance windows</li>
     *   <li>Composite logic that incorporates CPU, thread count, or GC pause times</li>
     * </ul>
     *
     * <p>The built-in {@link ThresholdRiskAssessor} is still used internally for its
     * diagnosis-notes enrichment logic; this class composes it rather than duplicating
     * the threshold comparison.
     *
     * @see RiskAssessor
     * @see ThresholdRiskAssessor
     */
    public static final class BusinessHoursRiskAssessor implements RiskAssessor {

        /** Assessor for peak (business) hours — aggressive thresholds. */
        private final RiskAssessor peakAssessor;

        /** Assessor for off-peak hours — relaxed thresholds. */
        private final RiskAssessor offPeakAssessor;

        /**
         * Creates a new {@code BusinessHoursRiskAssessor}.
         *
         * <p>Two inner {@link ThresholdRiskAssessor}s are pre-built with different
         * thresholds.  Delegation is decided per-call based on the current clock time.
         */
        public BusinessHoursRiskAssessor() {
            // Business hours: warn at 70%, critical at 80% (tighter — peak traffic)
            WatchdogConfig peakConfig = WatchdogConfig.defaults()
                    .warningHeapThreshold(0.70)
                    .criticalHeapThreshold(0.80)
                    .build();
            this.peakAssessor = new ThresholdRiskAssessor(peakConfig);

            // Off-peak: warn at 85%, critical at 95% (relaxed — batch jobs may use more memory)
            WatchdogConfig offPeakConfig = WatchdogConfig.defaults()
                    .warningHeapThreshold(0.85)
                    .criticalHeapThreshold(0.95)
                    .build();
            this.offPeakAssessor = new ThresholdRiskAssessor(offPeakConfig);
        }

        /**
         * Classifies the snapshot using either peak or off-peak thresholds depending
         * on the current wall-clock hour.
         *
         * @param snapshot raw snapshot from the collector; never {@code null}
         * @return assessed snapshot with risk level and enriched diagnosis notes
         */
        @Override
        public JvmSnapshot assess(JvmSnapshot snapshot) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            boolean businessHours = hour >= 9 && hour < 17;
            RiskAssessor delegate = businessHours ? peakAssessor : offPeakAssessor;
            return delegate.assess(snapshot);
        }
    }

    // =========================================================================
    // Custom HeapDumpService
    // =========================================================================

    /**
     * A {@link HeapDumpService} implementation that uploads dump files to a remote
     * artefact store after triggering the local dump.
     *
     * <p>This stub demonstrates the pattern: delegate to {@link CompositeDumpService}
     * for the actual file capture, then post-process the resulting paths.  Replace
     * the {@code System.out.printf} call with your actual upload logic (AWS S3,
     * Artifactory, SFTP, etc.).
     *
     * <p>Security considerations:
     * <ul>
     *   <li>The destination URL is validated at construction time to prevent SSRF
     *       attacks from a misconfigured URL.</li>
     *   <li>The URL scheme is restricted to {@code https://} to prevent accidental
     *       plaintext transmission of heap dump data.</li>
     *   <li>Dump file names are taken from the path returned by the local service
     *       and are never derived from user input, preventing path-injection.</li>
     * </ul>
     *
     * @see HeapDumpService
     * @see CompositeDumpService
     */
    public static final class UploadingDumpService implements HeapDumpService {

        /** Allowed URI scheme — enforced to prevent accidental plaintext uploads. */
        private static final String REQUIRED_SCHEME = "https://";

        /** Local file dump service that writes .hprof/.txt files to disk. */
        private final HeapDumpService localService;

        /**
         * Remote upload base URL (validated at construction to start with {@code https://}).
         * Dump file names are appended to form the full upload target.
         */
        private final String uploadBaseUrl;

        /**
         * Creates a new {@code UploadingDumpService}.
         *
         * @param config        watchdog config (forwarded to {@link CompositeDumpService})
         * @param uploadBaseUrl HTTPS URL of the remote artefact store;
         *                      must start with {@code https://}
         * @throws IllegalArgumentException if {@code uploadBaseUrl} is null, blank,
         *                                  or does not start with {@code https://}
         */
        public UploadingDumpService(WatchdogConfig config, String uploadBaseUrl) {
            if (uploadBaseUrl == null || uploadBaseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("uploadBaseUrl must not be null or blank");
            }
            // Enforce HTTPS to prevent accidental plaintext transmission
            if (!uploadBaseUrl.startsWith(REQUIRED_SCHEME)) {
                throw new IllegalArgumentException(
                        "uploadBaseUrl must start with 'https://' (got: " + uploadBaseUrl + ")");
            }
            this.localService  = new CompositeDumpService(config);
            this.uploadBaseUrl = uploadBaseUrl;
        }

        /**
         * Writes dump files locally via {@link CompositeDumpService}, then uploads
         * each file to the configured remote URL.
         *
         * @param snapshot the critical snapshot that triggered the dump
         * @param types    the dump types to produce
         * @return list of local file paths that were written (and queued for upload)
         */
        @Override
        public List<String> dump(JvmSnapshot snapshot, List<DumpType> types) {
            // Write files locally first
            List<String> localPaths = localService.dump(snapshot, types);

            // Upload each file — in production, use an async upload to avoid
            // blocking the watchdog's poll thread.
            for (String path : localPaths) {
                try {
                    java.io.File file = new java.io.File(path);
                    String remoteName = file.getName();
                    // Validate that the file name contains only safe characters
                    // before constructing the upload URL (prevents path-traversal).
                    if (!remoteName.matches("[A-Za-z0-9._\\-]+")) {
                        System.err.println("[UploadingDumpService] Skipping unsafe file name: "
                                + remoteName);
                        continue;
                    }
                    String target = uploadBaseUrl + "/" + remoteName;
                    System.out.printf(
                        "[UploadingDumpService] WOULD UPLOAD %s → %s%n", path, target);
                    // Real implementation: new URL(target).openConnection(), PUT/POST the bytes
                } catch (Exception e) {
                    System.err.println("[UploadingDumpService] Upload failed for " + path
                            + ": " + e.getMessage());
                }
            }
            return localPaths;
        }
    }

    // =========================================================================
    // main
    // =========================================================================

    /**
     * Wires all three custom implementations together and starts the watchdog.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        WatchdogConfig config = WatchdogConfig.defaults()
                .heapDumpDirectory("./dumps")
                .pollIntervalMs(5_000L)
                .build();

        // Custom collector: simulates heap growing by 5% every poll
        JvmDiagnosticsCollector collector = new EscalatingHeapCollector(0.60, 0.05);

        // Custom assessor: tighter thresholds 09:00–17:00, relaxed outside
        RiskAssessor assessor = new BusinessHoursRiskAssessor();

        // Custom dump service: writes locally then uploads to S3
        HeapDumpService dumpService = new UploadingDumpService(
                config, "https://my-bucket.s3.amazonaws.com/oom-dumps");

        List<AlertChannel> channels = Collections.singletonList(new ConsoleAlertChannel());

        OomWatchdog watchdog = new OomWatchdog(config, collector, assessor, channels, dumpService);

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();
        System.out.println("[Example04] Custom implementations wired. Escalating heap simulation active.");

        Thread.currentThread().join();
    }
}
