package com.trongus.oom.diagnosis;

/**
 * Enumeration of the root-cause categories that the
 * {@link OomCauseAnalyser} can identify from live JVM signals.
 *
 * <p>Each constant represents a distinct failure mode that is observable
 * through the standard {@code java.lang.management} MXBean API.
 * A given JVM snapshot may have more than one contributing cause; the
 * analyser selects the single highest-priority category for display.
 *
 * <p>Priority order (highest → lowest) matches the constants'
 * declaration order so that callers can rely on ordinal comparison when
 * they need to merge multiple signals.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.7
 * @since 1.3.0
 * @see OomCause
 * @see OomCauseAnalyser
 */
public enum OomCauseCategory {

    /**
     * Heap is critically full and GC overhead is also extremely high.
     * The JVM is spending more time collecting garbage than executing
     * application code — OOM is imminent within the next few GC cycles.
     */
    RUNAWAY_GC_WITH_HIGH_HEAP,

    /**
     * Heap utilisation is at or above the critical threshold.
     * Available free memory is too small to service typical allocation
     * requests; an {@link OutOfMemoryError} may be thrown on the next
     * large allocation.
     */
    HEAP_EXHAUSTION,

    /**
     * GC overhead alone (without critical heap usage) exceeds the
     * configured threshold.  The JVM is struggling to reclaim memory;
     * heap will likely fill up soon unless allocation slows.
     */
    GC_OVERHEAD_EXCEEDED,

    /**
     * Post-GC heap usage is trending upward across multiple GC cycles.
     * Live object retention is growing steadily — a memory leak is the
     * most probable cause.
     */
    MEMORY_LEAK_TREND,

    /**
     * All monitored metrics are within normal bounds.
     * No OOM risk is currently detected.
     */
    NONE
}
