package com.ibm.oomwatchdog.dump;

/**
 * Enumerates the types of diagnostic dump that OomWatchdog can produce.
 *
 * <p>Users select one or more types at launch via {@code --dump-types}.
 * Multiple values are separated by commas (e.g. {@code heap,thread}).
 *
 * <table border="1">
 *   <tr><th>Value</th><th>Output file</th><th>What it captures</th></tr>
 *   <tr><td>HEAP</td><td>{@code .hprof}</td>
 *       <td>Full heap object graph – analyse with Eclipse MAT, VisualVM, jhat</td></tr>
 *   <tr><td>CORE</td><td>{@code .core} / process maps</td>
 *       <td>OS-level core/system dump; requires {@code gcore} or IBM J9 system dump</td></tr>
 *   <tr><td>THREAD</td><td>{@code _threads.txt}</td>
 *       <td>Java thread stack traces (equivalent to {@code jstack})</td></tr>
 *   <tr><td>CLASS_HISTOGRAM</td><td>{@code _histogram.txt}</td>
 *       <td>Live object class histogram (equivalent to {@code jmap -histo:live})</td></tr>
 * </table>
 */
public enum DumpType {

    /**
     * JVM heap object graph in HPROF binary format.
     * Works on HotSpot/OpenJDK via {@code HotSpotDiagnosticMXBean#dumpHeap}
     * and on IBM J9/OpenJ9 via {@code com.ibm.jvm.Dump#HeapDump}.
     */
    HEAP,

    /**
     * OS-level core / system dump.
     * On Linux/macOS uses {@code gcore <pid>} via a child process.
     * On IBM J9/OpenJ9 uses {@code com.ibm.jvm.Dump#SystemDump}.
     * Requires the OS {@code gcore} utility to be on {@code PATH} for HotSpot.
     */
    CORE,

    /**
     * Java thread stack traces for all live threads.
     * Produced in-process via {@link java.lang.management.ThreadMXBean} –
     * no external tooling needed; works on all JVM vendors.
     */
    THREAD,

    /**
     * Live object class histogram (class name → instance count → total bytes).
     * Produced in-process via {@link java.lang.management.MemoryPoolMXBean} and
     * HotSpot's {@code DiagnosticCommand} MXBean where available; falls back
     * to a JVM-managed histogram via reflection on older runtimes.
     */
    CLASS_HISTOGRAM;

    /** Case-insensitive parse; throws {@link IllegalArgumentException} on bad input. */
    public static DumpType fromString(String s) {
        return DumpType.valueOf(s.trim().toUpperCase().replace('-', '_'));
    }
}
