package com.trongus.oom.diagnosis;

import com.trongus.oom.i18n.Messages;

/**
 * Analyses live JVM signals — heap utilisation, GC overhead, and post-GC
 * heap growth trend — and produces an {@link OomCause} with a localised,
 * plain-language explanation of the most likely root cause.
 *
 * <h2>Design</h2>
 * <p>The analyser is stateless (no mutable fields); the same instance can be
 * called concurrently from multiple threads without synchronisation.
 *
 * <h2>Signal priority</h2>
 * <ol>
 *   <li><strong>RUNAWAY_GC_WITH_HIGH_HEAP</strong> — GC overhead ≥ threshold
 *       AND heap ≥ critical threshold.  Both conditions together indicate that
 *       the JVM cannot free enough memory despite spending most of its time in GC.</li>
 *   <li><strong>HEAP_EXHAUSTION</strong> — heap ≥ critical threshold alone.</li>
 *   <li><strong>GC_OVERHEAD_EXCEEDED</strong> — GC overhead ≥ threshold alone.</li>
 *   <li><strong>MEMORY_LEAK_TREND</strong> — positive post-GC growth rate.</li>
 *   <li><strong>NONE</strong> — no signals exceed their thresholds.</li>
 * </ol>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.3
 * @since 1.3.0
 * @see OomCause
 * @see OomCauseCategory
 */
public final class OomCauseAnalyser {

    private final double  criticalHeapThreshold;
    private final double  gcOverheadThreshold;
    private final Messages messages;

    /**
     * Constructs an {@code OomCauseAnalyser} with the given thresholds and
     * localisation source.
     *
     * @param criticalHeapThreshold fraction of max heap (0–1) that constitutes
     *                              a critical condition; must be in {@code (0, 1)}
     * @param gcOverheadThreshold   fraction of uptime spent in GC (0–1) that
     *                              constitutes excessive overhead; must be in {@code (0, 1)}
     * @param messages              locale-aware message source; must not be {@code null}
     * @throws NullPointerException     if {@code messages} is {@code null}
     * @throws IllegalArgumentException if either threshold is outside {@code (0, 1)}
     */
    public OomCauseAnalyser(double criticalHeapThreshold,
                            double gcOverheadThreshold,
                            Messages messages) {
        if (messages == null) throw new NullPointerException("messages");
        if (criticalHeapThreshold <= 0.0 || criticalHeapThreshold >= 1.0) {
            throw new IllegalArgumentException(
                "criticalHeapThreshold must be in (0.0, 1.0), got: " + criticalHeapThreshold);
        }
        if (gcOverheadThreshold <= 0.0 || gcOverheadThreshold >= 1.0) {
            throw new IllegalArgumentException(
                "gcOverheadThreshold must be in (0.0, 1.0), got: " + gcOverheadThreshold);
        }
        this.criticalHeapThreshold = criticalHeapThreshold;
        this.gcOverheadThreshold   = gcOverheadThreshold;
        this.messages              = messages;
    }

    /**
     * Analyses the supplied JVM signals and returns an {@link OomCause}
     * containing the most likely root-cause category and a localised
     * plain-language explanation.
     *
     * @param heapUsedRatio          fraction of max heap currently in use (0–1);
     *                               negative values are treated as zero
     * @param gcOverheadRatio        fraction of JVM uptime spent in GC (0–1);
     *                               negative values are treated as zero
     * @param postGcGrowthRatePerMs  post-GC heap growth rate in bytes/ms;
     *                               {@link Double#NaN} or ≤ 0 means no leak signal
     * @return an {@link OomCause} with category and localised explanation;
     *         never {@code null}
     */
    public OomCause analyse(double heapUsedRatio,
                            double gcOverheadRatio,
                            double postGcGrowthRatePerMs) {

        double heap = Math.max(0.0, heapUsedRatio);
        double gc   = Math.max(0.0, gcOverheadRatio);

        boolean heapCritical  = heap >= criticalHeapThreshold;
        boolean gcHigh        = gc   >= gcOverheadThreshold;
        boolean leaking       = !Double.isNaN(postGcGrowthRatePerMs)
                                && postGcGrowthRatePerMs > 0;

        if (heapCritical && gcHigh) {
            return cause(OomCauseCategory.RUNAWAY_GC_WITH_HIGH_HEAP,
                    messages.format("cause.runaway_gc",
                            pct(heap), pct(criticalHeapThreshold),
                            pct(gc),   pct(gcOverheadThreshold)));
        }
        if (heapCritical) {
            return cause(OomCauseCategory.HEAP_EXHAUSTION,
                    messages.format("cause.heap_exhaustion",
                            pct(heap), pct(criticalHeapThreshold)));
        }
        if (gcHigh) {
            return cause(OomCauseCategory.GC_OVERHEAD_EXCEEDED,
                    messages.format("cause.gc_overhead",
                            pct(gc), pct(gcOverheadThreshold)));
        }
        if (leaking) {
            double mbPerHour = postGcGrowthRatePerMs * 3_600_000.0 / (1024.0 * 1024.0);
            return cause(OomCauseCategory.MEMORY_LEAK_TREND,
                    messages.format("cause.memory_leak", mbPerHour));
        }
        return cause(OomCauseCategory.NONE,
                messages.get("cause.none"));
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static OomCause cause(OomCauseCategory cat, String explanation) {
        return new OomCause(cat, explanation);
    }

    /** Converts a 0–1 ratio to a percentage value for message formatting. */
    private static double pct(double ratio) {
        return ratio * 100.0;
    }
}
