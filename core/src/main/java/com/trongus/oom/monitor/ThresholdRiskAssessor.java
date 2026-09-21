package com.trongus.oom.monitor;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.diagnosis.OomCause;
import com.trongus.oom.diagnosis.OomCauseAnalyser;
import com.trongus.oom.i18n.Messages;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

/**
 * Threshold-based {@link RiskAssessor} with i18n-aware diagnosis output and
 * integrated OOM cause analysis.
 *
 * <h2>Decision rules (highest priority first)</h2>
 * <ol>
 *   <li>{@code CRITICAL} – heap used ≥ critical threshold, OR GC overhead ≥ GC
 *       threshold AND heap ≥ warning threshold (runaway GC + high heap = imminent OOM)</li>
 *   <li>{@code WARNING} – heap used ≥ warning threshold, OR GC overhead ≥ GC threshold
 *       alone, OR a positive post-GC growth trend is detected</li>
 *   <li>{@code OK} – none of the above</li>
 * </ol>
 *
 * <h2>OOM_FIRING pass-through</h2>
 * <p>Snapshots whose {@link com.trongus.oom.model.OomRiskLevel} is already
 * {@code OOM_FIRING} (stamped by a collector, e.g. an unreachable JMX target) are
 * returned unchanged.  The assessor does not downgrade or re-evaluate them.
 *
 * <h2>Threshold stamping</h2>
 * <p>Every outgoing snapshot (except {@code OOM_FIRING} pass-throughs) has the active
 * {@code critThreshold} value stamped on it via
 * {@link com.trongus.oom.model.JvmSnapshot.Builder#critThreshold(double)}.
 * This allows downstream consumers such as {@link com.trongus.oom.alert.QRadarAlertChannel}
 * to include margin-to-threshold information in alert events.
 *
 * <p>The returned snapshot's {@code diagnosisNotes} are prepended with a
 * localised assessment summary and enriched with an {@link OomCause} explanation
 * so that every alert channel has full context.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.4
 * @since 1.0.0
 * @see RiskAssessor
 * @see OomCauseAnalyser
 */
public final class ThresholdRiskAssessor implements RiskAssessor {

    private final WatchdogConfig  config;
    private final Messages        messages;
    private final OomCauseAnalyser causeAnalyser;

    /**
     * Constructs a {@code ThresholdRiskAssessor} using the locale declared
     * in the supplied config.
     *
     * @param config the watchdog configuration; must not be {@code null}
     * @throws NullPointerException if {@code config} is {@code null}
     */
    public ThresholdRiskAssessor(WatchdogConfig config) {
        if (config == null) throw new NullPointerException("config");
        this.config        = config;
        this.messages      = new Messages(config.getLocale());
        this.causeAnalyser = new OomCauseAnalyser(
                config.getCriticalHeapThreshold(),
                config.getGcOverheadThreshold(),
                messages);
    }

    /**
     * Classifies the risk level of the supplied snapshot and returns an enriched copy.
     *
     * <p>Snapshots already carrying {@link OomRiskLevel#OOM_FIRING} are returned
     * unchanged (pass-through).  All other snapshots are evaluated against configured
     * thresholds and receive:
     * <ul>
     *   <li>An updated {@link OomRiskLevel} ({@code OK}, {@code WARNING}, or {@code CRITICAL}).</li>
     *   <li>Localised assessment and cause-analysis text prepended to {@code diagnosisNotes}.</li>
     *   <li>The active {@code critThreshold} value stamped on the snapshot.</li>
     * </ul>
     *
     * @param snap the raw {@link JvmSnapshot} produced by the diagnostics collector;
     *             must not be {@code null}
     * @return an enriched, immutable {@link JvmSnapshot} with risk level and diagnosis notes set
     */
    @Override
    public JvmSnapshot assess(JvmSnapshot snap) {
        // Snapshots marked OOM_FIRING by the collector (e.g. unreachable JMX target)
        // must not be recalculated — the collector already determined the worst-case level.
        if (snap.getRiskLevel() == OomRiskLevel.OOM_FIRING) {
            return snap;
        }

        double heapRatio  = snap.getHeapUsedRatio();
        double gcOverhead = snap.getGcOverheadRatio();
        double growthRate = snap.getPostGcHeapGrowthRatePerMs();
        boolean leaking   = !Double.isNaN(growthRate) && growthRate > 0;

        OomRiskLevel level;
        StringBuilder summary = new StringBuilder("[Assessment] ");

        boolean heapCritical = heapRatio  >= config.getCriticalHeapThreshold();
        boolean heapWarning  = heapRatio  >= config.getWarningHeapThreshold();
        boolean gcHigh       = gcOverhead >= config.getGcOverheadThreshold();

        if (heapCritical || (gcHigh && heapWarning)) {
            level = OomRiskLevel.CRITICAL;
            summary.append(messages.get("assess.critical")).append(" ");
            if (heapCritical) {
                summary.append(messages.format("assess.critical.heap",
                        heapRatio * 100, config.getCriticalHeapThreshold() * 100))
                       .append(" ");
            }
            if (gcHigh) {
                summary.append(messages.format("assess.critical.gc",
                        gcOverhead * 100, config.getGcOverheadThreshold() * 100))
                       .append(" ");
            }
        } else if (heapWarning || gcHigh || leaking) {
            level = OomRiskLevel.WARNING;
            summary.append(messages.get("assess.warning")).append(" ");
            if (heapWarning) {
                summary.append(messages.format("assess.warning.heap",
                        heapRatio * 100, config.getWarningHeapThreshold() * 100))
                       .append(" ");
            }
            if (gcHigh) {
                summary.append(messages.format("assess.warning.gc",
                        gcOverhead * 100, config.getGcOverheadThreshold() * 100))
                       .append(" ");
            }
            if (leaking) {
                double mbPerHour = growthRate * 3_600_000.0 / (1024.0 * 1024.0);
                summary.append(messages.format("assess.warning.leak", mbPerHour))
                       .append(" ");
            }
        } else {
            level = OomRiskLevel.OK;
            summary.append(messages.get("assess.ok")).append(" ");
        }

        // Append OOM cause analysis
        OomCause cause = causeAnalyser.analyse(heapRatio, gcOverhead, growthRate);
        summary.append("[Cause] ").append(cause.getExplanation());

        String enrichedNotes = summary.toString().trim() + " " + snap.getDiagnosisNotes();

        return snap.toBuilder()
                .riskLevel(level)
                .diagnosisNotes(enrichedNotes)
                .critThreshold(config.getCriticalHeapThreshold())
                .build();
    }
}
