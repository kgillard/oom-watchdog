package com.trongus.oom.dump;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.strategy.ClassHistogramStrategy;
import com.trongus.oom.dump.strategy.CoreDumpStrategy;
import com.trongus.oom.dump.strategy.DumpStrategy;
import com.trongus.oom.dump.strategy.GraalNativeHeapDumpStrategy;
import com.trongus.oom.dump.strategy.HotSpotHeapDumpStrategy;
import com.trongus.oom.dump.strategy.J9HeapDumpStrategy;
import com.trongus.oom.dump.strategy.ThreadDumpStrategy;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.platform.JvmPlatform;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@link HeapDumpService} implementation that selects the correct
 * {@link DumpStrategy} for each requested {@link DumpType} at runtime,
 * in priority order, falling back gracefully across every JVM vendor and
 * JDK version (8 through 26+).
 *
 * <h3>Design rationale (Open/Closed &amp; Strategy pattern)</h3>
 * <p>Rather than encoding vendor-specific branches inside a single monolithic
 * method, this class delegates all dump mechanics to interchangeable
 * {@link DumpStrategy} implementations.  Adding support for a new JVM vendor
 * or dump mechanism requires only a new {@code DumpStrategy} class and a one-
 * line addition to the chain built by {@link #buildChains()} — no existing code
 * needs to change.</p>
 *
 * <h3>Strategy chains per dump type</h3>
 * <table border="1" summary="Strategy priority order per DumpType">
 *   <tr><th>Type</th><th>Priority order</th></tr>
 *   <tr><td>HEAP</td>
 *       <td>HotSpot MXBean → IBM J9 → GraalVM VMRuntime → memory-pool summary</td></tr>
 *   <tr><td>THREAD</td>
 *       <td>ThreadMXBean (universal — all JVMs JDK 6–26+)</td></tr>
 *   <tr><td>CLASS_HISTOGRAM</td>
 *       <td>DiagnosticCommand MBean → J9 JavaDump → pool table</td></tr>
 *   <tr><td>CORE</td>
 *       <td>J9 SystemDump → gcore (Linux/macOS)</td></tr>
 * </table>
 *
 * <p>Platform detection is performed once at class-load time via
 * {@link JvmPlatform}.  All strategies are tried in insertion order; the first
 * strategy that returns a non-{@code null} path wins and short-circuits the
 * rest of the chain for that dump type.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.3
 * @since 1.0.0
 * @see HeapDumpService
 * @see DumpStrategy
 * @see DumpType
 * @see JvmPlatform
 */
public final class CompositeDumpService implements HeapDumpService {

    private static final Logger LOG = WatchdogLogger.forClass(CompositeDumpService.class);

    /**
     * Watchdog configuration providing the output directory and other settings
     * used when building file paths for each dump artefact.
     */
    private final WatchdogConfig config;

    /**
     * Ordered map of strategy chains, keyed by {@link DumpType}.
     *
     * <p>Insertion order is significant: chains are built once in
     * {@link #buildChains()} and iterated in priority order for every dump
     * request.  A {@link java.util.LinkedHashMap} is used to preserve that
     * insertion order reliably across JVM versions.
     */
    private final Map<DumpType, List<DumpStrategy>> chains;

    /**
     * Creates a new {@code CompositeDumpService} bound to the supplied
     * configuration.
     *
     * <p>Strategy chains are built eagerly at construction time so that any
     * reflective class-loading errors surface during initialisation rather than
     * at the worst possible moment — during an OOM event.  The detected JVM
     * platform summary is logged to {@code stdout} for diagnosis.
     *
     * @param config watchdog configuration; must not be {@code null}
     */
    public CompositeDumpService(WatchdogConfig config) {
        this.config = config;
        this.chains = buildChains();
        WatchdogLogger.config(LOG, "Platform: {0}", JvmPlatform.summary());
    }

    // -------------------------------------------------------------------------
    // HeapDumpService
    // -------------------------------------------------------------------------

    /**
     * Triggers every requested {@link DumpType} in order, walking its strategy
     * chain until the first success, then collecting the resulting file paths.
     *
     * <p>The output directory ({@link WatchdogConfig#getHeapDumpDirectory()})
     * is created if it does not already exist.  If directory creation fails,
     * an empty list is returned immediately.  For each dump type, if all
     * strategies in the chain are exhausted without success, a warning is
     * logged but processing continues with the remaining types.
     *
     * @param snapshot the {@link JvmSnapshot} assessed at trigger time; used
     *                 to build the output file name and embed context in text
     *                 dumps
     * @param types    the dump types to produce; must not be {@code null}
     * @return list of absolute paths to files written; never {@code null},
     *         may be empty if the output directory cannot be created or all
     *         strategy chains are exhausted
     */
    @Override
    public List<String> dump(JvmSnapshot snapshot, List<DumpType> types) {
        List<String> results = new ArrayList<>();

        // Ensure the output directory exists before attempting any dump
        try {
            Files.createDirectories(Paths.get(config.getHeapDumpDirectory()));
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "Cannot create dump directory: {0}", e.getMessage());
            return results;
        }

        for (DumpType type : types) {
            // Build the target path for this dump type
            String path = buildPath(snapshot, type);
            List<DumpStrategy> chain = chains.get(type);
            if (chain == null) {
                WatchdogLogger.warning(LOG, "No strategy chain for type: {0}", type);
                continue;
            }

            // Walk the priority chain; stop at the first strategy that succeeds
            boolean succeeded = false;
            for (DumpStrategy strategy : chain) {
                String result = null;
                try {
                    result = strategy.attempt(snapshot, path);
                } catch (Exception e) {
                    // Strategies should not throw, but guard defensively
                    WatchdogLogger.warning(LOG, e, "Strategy {0} threw: {1}",
                            strategy.name(), e.getMessage());
                }
                if (result != null) {
                    WatchdogLogger.info(LOG, "{0} succeeded via {1} \u2192 {2}",
                            type, strategy.name(), result);
                    results.add(result);
                    succeeded = true;
                    break; // short-circuit: no need to try lower-priority strategies
                }
            }
            if (!succeeded) {
                WatchdogLogger.warning(LOG, "{0} \u2013 all strategies exhausted. No dump produced.", type);
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Strategy chains
    // -------------------------------------------------------------------------

    /**
     * Constructs the ordered strategy chains for each supported {@link DumpType}.
     *
     * <p>Each chain is a list of {@link DumpStrategy} instances in decreasing
     * priority.  {@link java.util.LinkedHashMap} preserves insertion order so
     * that the chains are iterated in the documented priority sequence.
     *
     * @return an unmodifiable-at-iteration-time map from dump type to strategy chain
     */
    private static Map<DumpType, List<DumpStrategy>> buildChains() {
        Map<DumpType, List<DumpStrategy>> m = new LinkedHashMap<>();

        // HEAP: try HotSpot first (widest coverage), then J9, then GraalVM / universal fallback
        m.put(DumpType.HEAP, Arrays.asList(
                new HotSpotHeapDumpStrategy(),    // HotSpot / OpenJDK / Azul / GraalVM JVM
                new J9HeapDumpStrategy(),          // IBM J9 / OpenJ9
                new GraalNativeHeapDumpStrategy()  // GraalVM Native Image + universal fallback
        ));

        // THREAD: ThreadMXBean is available on all JVMs — a single strategy suffices
        m.put(DumpType.THREAD, Arrays.asList(
                new ThreadDumpStrategy()           // universal – all JVMs JDK 6–26+
        ));

        // CLASS_HISTOGRAM: internally composite; the strategy handles its own sub-chain
        m.put(DumpType.CLASS_HISTOGRAM, Arrays.asList(
                new ClassHistogramStrategy()       // composite internally
        ));

        // CORE: J9 system dump preferred; gcore as fallback on Linux/macOS
        m.put(DumpType.CORE, Arrays.asList(
                new CoreDumpStrategy()             // J9 SystemDump → gcore
        ));

        return m;
    }

    // -------------------------------------------------------------------------
    // Path construction
    // -------------------------------------------------------------------------

    /**
     * Builds the suggested output file path for a dump of the given type.
     *
     * <p>The path pattern is:
     * {@code <dumpDirectory>/oom_<type>_<processName>_<yyyyMMdd_HHmmss_SSS><ext>}
     *
     * <p>The process name is sanitised by replacing all characters outside
     * {@code [A-Za-z0-9._-]} with underscores so the result is safe on all
     * operating systems.
     *
     * @param snapshot the snapshot whose timestamp and process name are embedded
     *                 in the file name
     * @param type     the dump type being produced; determines the file extension
     * @return suggested absolute file path (the receiving strategy may override it)
     */
    private String buildPath(JvmSnapshot snapshot, DumpType type) {
        // Format timestamp with millisecond precision to avoid name collisions on rapid retriggers
        String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS")
                          .format(new Date(snapshot.getTimestampMs()));
        // Sanitise process name for filesystem compatibility
        String proc = snapshot.getProcessName().replaceAll("[^A-Za-z0-9._-]", "_");
        String ext  = extensionFor(type);
        return config.getHeapDumpDirectory() + File.separator
                + "oom_" + type.name().toLowerCase() + "_" + proc + "_" + ts + ext;
    }

    /**
     * Returns the conventional file extension for the given {@link DumpType}.
     *
     * @param type the dump type
     * @return extension string including the leading dot; {@code ".dump"} for
     *         any unknown future type
     */
    private static String extensionFor(DumpType type) {
        switch (type) {
            case HEAP:            return ".hprof";
            case THREAD:          return "_threads.txt";
            case CLASS_HISTOGRAM: return "_histogram.txt";
            case CORE:            return ".core";
            default:              return ".dump";
        }
    }
}
