package com.trongus.oom.dump.strategy;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;

/**
 * Strategy contract for producing a single type of JVM diagnostic dump.
 *
 * <h2>Design rationale (SRP / ISP / Strategy pattern)</h2>
 * <p>Each implementation encapsulates exactly one dump mechanism on one JVM
 * vendor (or a closely related family of vendors).  This strict one-to-one
 * mapping makes it trivial to add new JVM targets, to unit-test each strategy
 * in isolation, and to build ordered fallback chains in
 * {@link com.trongus.oom.dump.CompositeDumpService} without those chains
 * knowing anything about how any individual dump works.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Implementations <em>must never throw</em>; any exception must be
 *       caught internally and result in a {@code null} return value.</li>
 *   <li>A {@code null} return value means "this strategy is not applicable
 *       on the current JVM" or "the attempt failed"; the caller will try the
 *       next strategy in the chain.</li>
 *   <li>A non-{@code null} return value is an absolute path to a file that
 *       exists on disk (or the best known approximation for JVM-controlled
 *       output locations).</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.4
 * @since 1.0.0
 * @see com.trongus.oom.dump.CompositeDumpService
 * @see HotSpotHeapDumpStrategy
 * @see J9HeapDumpStrategy
 * @see GraalNativeHeapDumpStrategy
 * @see ThreadDumpStrategy
 * @see ClassHistogramStrategy
 * @see CoreDumpStrategy
 */
public interface DumpStrategy {

    /**
     * Returns the {@link DumpType} that this strategy produces.
     *
     * <p>Used by {@link com.trongus.oom.dump.CompositeDumpService} to route
     * requests to the correct strategy chain.
     *
     * @return the dump type; never {@code null}
     */
    DumpType type();

    /**
     * Attempts to produce the dump and write the result to {@code outputPath}
     * (or a JVM-controlled location when the vendor ignores the supplied path).
     *
     * @param snapshot    the {@link JvmSnapshot} assessed at the time of the
     *                    dump request; may be used to embed process context
     *                    inside text-format dumps
     * @param outputPath  the suggested absolute output file path, including
     *                    extension; some JVM mechanisms (e.g. IBM J9) ignore
     *                    this and choose their own path
     * @return absolute path of the written file, or {@code null} if this
     *         strategy is not applicable on the current JVM or the attempt
     *         failed; must not throw
     */
    String attempt(JvmSnapshot snapshot, String outputPath);

    /**
     * Returns a short human-readable name used in log messages to identify
     * which strategy succeeded or failed.
     *
     * <p>The default implementation returns the simple class name, which is
     * sufficient for most strategies.  Override to provide a more descriptive
     * name that includes the underlying API or JVM vendor.
     *
     * @return non-{@code null} strategy name
     */
    default String name() { return getClass().getSimpleName(); }
}
