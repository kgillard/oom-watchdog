package com.ibm.oomwatchdog.dump.strategy;

import com.ibm.oomwatchdog.dump.DumpType;
import com.ibm.oomwatchdog.model.JvmSnapshot;

/**
 * SRP / ISP: one strategy = one dump type on one JVM vendor.
 * Implementations must never throw; they return {@code null} on failure.
 */
public interface DumpStrategy {

    /** The dump type this strategy produces. */
    DumpType type();

    /**
     * Attempt to produce the dump.
     *
     * @param snapshot assessed snapshot at the time of the dump request
     * @param outputPath suggested output path (may be ignored by some vendors)
     * @return absolute path of the written file, or {@code null} on failure / not applicable
     */
    String attempt(JvmSnapshot snapshot, String outputPath);

    /** Human-readable name shown in log messages. */
    default String name() { return getClass().getSimpleName(); }
}
