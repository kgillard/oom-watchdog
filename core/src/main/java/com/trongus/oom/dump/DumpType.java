package com.trongus.oom.dump;

/**
 * Enumerates the diagnostic artifact types that OOM Watchdog can produce when a
 * {@link com.trongus.oom.model.OomRiskLevel#CRITICAL CRITICAL} risk level is
 * detected.
 *
 * <p>Users select one or more types at launch via the {@code --dump-types} flag.
 * Multiple values are separated by commas, e.g. {@code --dump-types heap,thread}.
 * Type names are case-insensitive and hyphens may be used in place of underscores,
 * e.g. {@code class-histogram} is equivalent to {@code CLASS_HISTOGRAM}.
 *
 * <h2>Dump type summary</h2>
 * <table border="1" summary="dump type overview">
 *   <tr><th>Value</th><th>Output file</th><th>What it captures</th><th>JVM support</th></tr>
 *   <tr>
 *     <td>{@link #HEAP}</td>
 *     <td>{@code .hprof}</td>
 *     <td>Full heap object graph with every live instance and its reference chain.
 *         Analyse offline with Eclipse MAT, VisualVM, or {@code jhat}.</td>
 *     <td>HotSpot / OpenJDK 8+; IBM J9 / OpenJ9; GraalVM ≥ 23.1 native image.
 *         Falls back to a structured memory-pool summary on all other runtimes.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #CORE}</td>
 *     <td>{@code .core} / {@code .dmp}</td>
 *     <td>Full OS-level process core dump including native frames, JIT code,
 *         and heap memory.  Required for low-level native memory or JVM bug
 *         analysis.</td>
 *     <td>IBM J9 / OpenJ9 (SystemDump); Linux / macOS with {@code gcore} on PATH.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #THREAD}</td>
 *     <td>{@code _threads.txt}</td>
 *     <td>Stack traces for every live Java thread, including lock contention,
 *         deadlock detection, CPU time, and blocked/waited time.
 *         Equivalent to {@code jstack}.</td>
 *     <td><strong>Universal</strong> — all JVM vendors and all JDK versions 6–26+,
 *         including GraalVM Native Image (partial).</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #CLASS_HISTOGRAM}</td>
 *     <td>{@code _histogram.txt}</td>
 *     <td>Ranked table of live object classes with instance counts and total
 *         retained bytes.  Equivalent to {@code jmap -histo:live}.
 *         Ideal for identifying the dominant leak types without parsing a full
 *         heap dump.</td>
 *     <td>HotSpot DiagnosticCommand MBean (JDK 8u40+); IBM J9 JavaDump;
 *         pool-summary fallback on all other runtimes.</td>
 *   </tr>
 * </table>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.0
 * @since 1.0.0
 * @see com.trongus.oom.dump.strategy.DumpStrategy
 * @see com.trongus.oom.dump.CompositeDumpService
 */
public enum DumpType {

    /**
     * JVM heap object graph in HPROF binary format.
     *
     * <p><strong>Strategy chain</strong> (first success wins):
     * <ol>
     *   <li>HotSpot {@code HotSpotDiagnosticMXBean#dumpHeap} (live objects only)</li>
     *   <li>IBM J9 / OpenJ9 {@code com.ibm.jvm.Dump#HeapDump} → produces PHD file</li>
     *   <li>GraalVM Native Image {@code org.graalvm.nativeimage.VMRuntime#dumpHeap}
     *       (GraalVM SDK 23.1+)</li>
     *   <li>Structured memory-pool summary text file (universal fallback)</li>
     * </ol>
     */
    HEAP,

    /**
     * OS-level process core / system dump.
     *
     * <p><strong>Strategy chain</strong>:
     * <ol>
     *   <li>IBM J9 / OpenJ9 {@code com.ibm.jvm.Dump#SystemDump}</li>
     *   <li>{@code gcore -o &lt;path&gt; &lt;pid&gt;} on Linux / macOS
     *       (requires {@code gcore} on {@code PATH}, typically part of {@code gdb})</li>
     * </ol>
     * Returns {@code null} on Windows or when neither mechanism is available.
     */
    CORE,

    /**
     * Java thread stack traces for all live threads.
     *
     * <p>Produced entirely in-process via {@link java.lang.management.ThreadMXBean}.
     * No external tools or JVM-specific APIs are required.  Includes:
     * <ul>
     *   <li>Thread name, ID, and state</li>
     *   <li>Full stack trace</li>
     *   <li>Lock names and owning threads</li>
     *   <li>Monitor and synchronizer info</li>
     *   <li>CPU, blocked, and waited times</li>
     *   <li>Deadlock detection summary</li>
     * </ul>
     * Works on every JVM vendor and every JDK version from 6 through 26+.
     */
    THREAD,

    /**
     * Live object class histogram sorted by retained bytes.
     *
     * <p><strong>Strategy chain</strong>:
     * <ol>
     *   <li>HotSpot {@code DiagnosticCommand} MBean ({@code gcClassHistogram})
     *       available from JDK 8u40 and all JDK 9–26+ builds</li>
     *   <li>IBM J9 / OpenJ9 {@code com.ibm.jvm.Dump#JavaDump} (javacore
     *       includes a class histogram section)</li>
     *   <li>Memory-pool usage table (universal fallback — less granular but
     *       always available)</li>
     * </ol>
     */
    CLASS_HISTOGRAM;

    /**
     * Parses a dump type from a user-supplied string in a case-insensitive,
     * hyphen-tolerant manner.
     *
     * <p>Examples of accepted values: {@code "heap"}, {@code "HEAP"},
     * {@code "class_histogram"}, {@code "class-histogram"},
     * {@code "CLASS-HISTOGRAM"}.
     *
     * @param s the user-supplied string token (must not be {@code null})
     * @return the matching {@code DumpType} constant
     * @throws IllegalArgumentException if {@code s} does not match any constant
     */
    public static DumpType fromString(final String s) {
        return DumpType.valueOf(s.trim().toUpperCase().replace('-', '_'));
    }
}
