package com.trongus.oom.model;

/**
 * Enumerates the severity levels that the OOM Watchdog assigns to a JVM memory
 * snapshot, ordered from least to most severe.
 *
 * <p>The enum is ordered by increasing urgency so comparisons such as
 * {@code level.ordinal() >= WARNING.ordinal()} reliably test for "at least WARNING".
 *
 * <h2>Level descriptions</h2>
 * <dl>
 *   <dt>{@link #OK}</dt>
 *   <dd>All heap and GC metrics are within safe bounds. No action needed.</dd>
 *
 *   <dt>{@link #WARNING}</dt>
 *   <dd>One or more leading indicators have exceeded the configured warning
 *       thresholds (heap usage ≥ {@code warn-threshold}, GC overhead ≥
 *       {@code gc-threshold}, or a positive post-GC growth trend is detected).
 *       Alerts are dispatched to all registered {@code AlertChannel}s; no dump
 *       is triggered yet.</dd>
 *
 *   <dt>{@link #CRITICAL}</dt>
 *   <dd>The heap is at or above the critical threshold <em>or</em> both the GC
 *       overhead threshold and the warning heap threshold are exceeded
 *       simultaneously (runaway GC + near-full heap).  An OOM crash is
 *       considered imminent.  Alerts are dispatched <em>and</em> all configured
 *       dump types are triggered (once per episode to avoid dump storms).</dd>
 *
 *   <dt>{@link #OOM_FIRING}</dt>
 *   <dd>An {@link OutOfMemoryError} is actively occurring or has just been thrown.
 *       This level is reserved for future use by native OOME hooks; the
 *       threshold-based assessor never produces it during normal polling.</dd>
 * </dl>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11
 * @since 1.0.0
 * @see com.trongus.oom.monitor.RiskAssessor
 * @see com.trongus.oom.monitor.ThresholdRiskAssessor
 */
public enum OomRiskLevel {

    /**
     * All monitored metrics are within normal bounds.
     * The watchdog continues polling but takes no alerting or dump action.
     */
    OK,

    /**
     * One or more leading OOM indicators have crossed the warning threshold.
     * Alerts are fired; no diagnostic dump is taken.
     *
     * <p>Typical causes:
     * <ul>
     *   <li>Heap utilisation ≥ 80% (default {@code warn-threshold})</li>
     *   <li>GC overhead ≥ 50% of JVM uptime (default {@code gc-threshold})</li>
     *   <li>Post-GC heap showing a positive growth trend (memory leak signal)</li>
     * </ul>
     */
    WARNING,

    /**
     * The heap is critically close to exhaustion or a runaway-GC+high-heap
     * condition is detected.  An OOM crash is imminent.
     *
     * <p>In addition to firing all alert channels, the watchdog triggers all
     * configured diagnostic dumps ({@link com.trongus.oom.dump.DumpType} values)
     * once per sustained episode.
     */
    CRITICAL,

    /**
     * An {@link OutOfMemoryError} has already been thrown or is actively
     * occurring in the monitored JVM.  Reserved for future integration with
     * OOME uncaught-exception hooks or native-memory exhaustion detection.
     */
    OOM_FIRING
}
