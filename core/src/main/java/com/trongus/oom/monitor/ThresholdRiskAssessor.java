package com.trongus.oom.monitor;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;

/**
 * Threshold-based {@link RiskAssessor}.
 *
 * <p>Rules (evaluated in priority order, highest first):
 * <ol>
 *   <li>{@code CRITICAL} – heap used ≥ critical threshold, OR GC overhead ≥ GC threshold
 *       AND heap ≥ warning threshold (runaway GC + high heap = imminent OOM)</li>
 *   <li>{@code WARNING} – heap used ≥ warning threshold, OR GC overhead ≥ GC threshold
 *       alone, OR a positive post-GC growth trend is detected</li>
 *   <li>{@code OK} – none of the above</li>
 * </ol>
 *
 * The returned snapshot's {@code diagnosisNotes} are prepended with an
 * assessment summary so every alert channel has full context.
 */
public final class ThresholdRiskAssessor implements RiskAssessor {

    private final WatchdogConfig config;

    public ThresholdRiskAssessor(WatchdogConfig config) {
        this.config = config;
    }

    @Override
    public JvmSnapshot assess(JvmSnapshot snap) {
        double heapRatio    = snap.getHeapUsedRatio();
        double gcOverhead   = snap.getGcOverheadRatio();
        double growthRate   = snap.getPostGcHeapGrowthRatePerMs();
        boolean leaking     = !Double.isNaN(growthRate) && growthRate > 0;

        OomRiskLevel level;
        StringBuilder summary = new StringBuilder("[Assessment] ");

        boolean heapCritical  = heapRatio  >= config.getCriticalHeapThreshold();
        boolean heapWarning   = heapRatio  >= config.getWarningHeapThreshold();
        boolean gcHigh        = gcOverhead >= config.getGcOverheadThreshold();

        if (heapCritical || (gcHigh && heapWarning)) {
            level = OomRiskLevel.CRITICAL;
            summary.append("CRITICAL – OOM imminent. ");
            if (heapCritical) {
                summary.append(String.format(
                    "Heap at %.1f%% (threshold %.1f%%). ",
                    heapRatio * 100, config.getCriticalHeapThreshold() * 100));
            }
            if (gcHigh) {
                summary.append(String.format(
                    "GC overhead %.1f%% (threshold %.1f%%). ",
                    gcOverhead * 100, config.getGcOverheadThreshold() * 100));
            }
        } else if (heapWarning || gcHigh || leaking) {
            level = OomRiskLevel.WARNING;
            summary.append("WARNING – OOM risk elevated. ");
            if (heapWarning) {
                summary.append(String.format(
                    "Heap at %.1f%% (threshold %.1f%%). ",
                    heapRatio * 100, config.getWarningHeapThreshold() * 100));
            }
            if (gcHigh) {
                summary.append(String.format(
                    "GC overhead %.1f%% (threshold %.1f%%). ",
                    gcOverhead * 100, config.getGcOverheadThreshold() * 100));
            }
            if (leaking) {
                double mbPerHour = growthRate * 3_600_000.0 / (1024.0 * 1024.0);
                summary.append(String.format(
                    "Post-GC heap growing at %.2f MB/hour. ", mbPerHour));
            }
        } else {
            level = OomRiskLevel.OK;
            summary.append("OK – all metrics within normal bounds. ");
        }

        String enrichedNotes = summary.toString().trim() + " | " + snap.getDiagnosisNotes();

        return snap.toBuilder()
                .riskLevel(level)
                .diagnosisNotes(enrichedNotes)
                .build();
    }
}
