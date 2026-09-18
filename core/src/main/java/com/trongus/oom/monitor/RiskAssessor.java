package com.trongus.oom.monitor;

import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

/**
 * Single Responsibility / Interface Segregation: the sole concern is classifying
 * a raw {@link JvmSnapshot} and returning an enriched copy with the appropriate
 * {@link OomRiskLevel} and updated diagnosis notes.
 *
 * <p>Implementations must be pure functions with no observable side effects beyond
 * constructing the returned snapshot.  The input snapshot is never modified;
 * a new instance is always returned via
 * {@link JvmSnapshot#toBuilder()}.
 *
 * <p>Implementations are called from the watchdog's scheduler thread on every poll
 * cycle and must complete quickly.  Long-running analysis (e.g. heap inspection)
 * belongs in a separate post-alert hook, not here.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.3
 * @since 1.0.0
 * @see com.trongus.oom.monitor.ThresholdRiskAssessor
 * @see com.trongus.oom.monitor.OomWatchdog
 */
public interface RiskAssessor {

    /**
     * Evaluates the supplied snapshot against the configured thresholds or other
     * heuristics, and returns a new snapshot carrying the assessed
     * {@link OomRiskLevel} and enriched diagnosis notes.
     *
     * <p>The returned snapshot must not be {@code null}.  The returned
     * {@link OomRiskLevel} must be one of the four defined constants; returning
     * {@code null} for the risk level is a contract violation.
     *
     * @param snapshot a freshly collected snapshot; {@code riskLevel} is typically
     *                 {@link OomRiskLevel#OK} as set by the collector
     * @return a new {@link JvmSnapshot} with the assessed risk level and updated
     *         diagnosis notes; never {@code null}
     */
    JvmSnapshot assess(JvmSnapshot snapshot);
}
