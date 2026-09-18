package com.trongus.oom.dump;

import com.trongus.oom.model.JvmSnapshot;

import java.util.List;

/**
 * Service-level abstraction for triggering diagnostic dumps against the current
 * JVM process and returning the file paths that were written.
 *
 * <h2>Design rationale (ISP / SRP)</h2>
 * <p>This interface deliberately exposes a single method so that callers need
 * only depend on the capability they use — producing dumps — without being
 * coupled to any particular JVM vendor, dump format, or fallback strategy.
 * Implementations are free to select the most appropriate mechanism for each
 * {@link DumpType} and the current JVM vendor at runtime.</p>
 *
 * <h2>Interface Segregation Principle</h2>
 * <p>The interface is kept intentionally narrow: one method, one concern.
 * Higher-level orchestration (scheduling, alerting, retry) belongs to separate
 * interfaces rather than being mixed into this contract.</p>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.9
 * @since 1.0.0
 * @see CompositeDumpService
 * @see DumpType
 */
public interface HeapDumpService {

    /**
     * Triggers every dump type in {@code types} for the current JVM process.
     *
     * <p>Implementations must be resilient: if one dump type fails, the
     * remaining types should still be attempted.  A partially populated result
     * list is always preferable to propagating an exception.
     *
     * <p>The returned paths are absolute and refer to files that exist on disk
     * at the moment of return.  Files written by JVM-internal mechanisms (e.g.
     * IBM J9 dumps) may land in a JVM-controlled directory rather than the
     * configured output directory; in that case the returned path is the best
     * known approximation.
     *
     * @param snapshot the assessed {@link JvmSnapshot} that caused the dump
     *                 request; used to derive human-readable file names and to
     *                 embed process context in text-format dumps
     * @param types    one or more {@link DumpType} values indicating which
     *                 diagnostic artefacts should be produced; the list must
     *                 not be {@code null} but may be empty
     * @return list of absolute paths to the files written; never {@code null},
     *         may be empty if no dump could be taken (e.g. unsupported JVM)
     */
    List<String> dump(JvmSnapshot snapshot, List<DumpType> types);
}
