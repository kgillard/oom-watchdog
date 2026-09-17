package com.ibm.oomwatchdog.dump;

import com.ibm.oomwatchdog.config.WatchdogConfig;
import com.ibm.oomwatchdog.dump.strategy.ClassHistogramStrategy;
import com.ibm.oomwatchdog.dump.strategy.CoreDumpStrategy;
import com.ibm.oomwatchdog.dump.strategy.DumpStrategy;
import com.ibm.oomwatchdog.dump.strategy.GraalNativeHeapDumpStrategy;
import com.ibm.oomwatchdog.dump.strategy.HotSpotHeapDumpStrategy;
import com.ibm.oomwatchdog.dump.strategy.J9HeapDumpStrategy;
import com.ibm.oomwatchdog.dump.strategy.ThreadDumpStrategy;
import com.ibm.oomwatchdog.model.JvmSnapshot;
import com.ibm.oomwatchdog.platform.JvmPlatform;

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

/**
 * {@link HeapDumpService} implementation that selects the correct
 * {@link DumpStrategy} for each requested {@link DumpType} at runtime,
 * in priority order, falling back gracefully on every JVM and JDK version.
 *
 * <h3>Strategy chains per dump type</h3>
 * <table border="1">
 *   <tr><th>Type</th><th>Priority order</th></tr>
 *   <tr><td>HEAP</td>
 *       <td>HotSpot MXBean → IBM J9 → GraalVM VMRuntime → memory-pool summary</td></tr>
 *   <tr><td>THREAD</td>
 *       <td>ThreadMXBean (universal)</td></tr>
 *   <tr><td>CLASS_HISTOGRAM</td>
 *       <td>DiagnosticCommand MBean → J9 JavaDump → pool table</td></tr>
 *   <tr><td>CORE</td>
 *       <td>J9 SystemDump → gcore (Linux/macOS)</td></tr>
 * </table>
 *
 * <p>Platform detected once at class-load time via {@link JvmPlatform}.
 * All strategies are tried in order; the first non-null path wins and is returned.
 */
public final class CompositeDumpService implements HeapDumpService {

    private final WatchdogConfig config;

    /** strategy chains: type → ordered list of strategies to try */
    private final Map<DumpType, List<DumpStrategy>> chains;

    public CompositeDumpService(WatchdogConfig config) {
        this.config = config;
        this.chains = buildChains();
        System.out.println("[OomWatchdog][Dump] Platform: " + JvmPlatform.summary());
    }

    @Override
    public List<String> dump(JvmSnapshot snapshot, List<DumpType> types) {
        List<String> results = new ArrayList<>();

        try {
            Files.createDirectories(Paths.get(config.getHeapDumpDirectory()));
        } catch (Exception e) {
            System.err.println("[OomWatchdog][Dump] Cannot create dump directory: " + e.getMessage());
            return results;
        }

        for (DumpType type : types) {
            String path = buildPath(snapshot, type);
            List<DumpStrategy> chain = chains.get(type);
            if (chain == null) {
                System.err.println("[OomWatchdog][Dump] No strategy chain for type: " + type);
                continue;
            }

            boolean succeeded = false;
            for (DumpStrategy strategy : chain) {
                String result = null;
                try {
                    result = strategy.attempt(snapshot, path);
                } catch (Exception e) {
                    System.err.println("[OomWatchdog][Dump] Strategy " + strategy.name()
                            + " threw: " + e.getMessage());
                }
                if (result != null) {
                    System.out.println("[OomWatchdog][Dump] " + type
                            + " succeeded via " + strategy.name() + " → " + result);
                    results.add(result);
                    succeeded = true;
                    break;
                }
            }
            if (!succeeded) {
                System.err.println("[OomWatchdog][Dump] " + type
                        + " – all strategies exhausted. No dump produced.");
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Strategy chains
    // -------------------------------------------------------------------------

    private static Map<DumpType, List<DumpStrategy>> buildChains() {
        Map<DumpType, List<DumpStrategy>> m = new LinkedHashMap<>();

        m.put(DumpType.HEAP, Arrays.asList(
                new HotSpotHeapDumpStrategy(),    // HotSpot / OpenJDK / Azul / GraalVM JVM
                new J9HeapDumpStrategy(),          // IBM J9 / OpenJ9
                new GraalNativeHeapDumpStrategy()  // GraalVM Native Image + universal fallback
        ));

        m.put(DumpType.THREAD, Arrays.asList(
                new ThreadDumpStrategy()           // universal – all JVMs JDK 6–26+
        ));

        m.put(DumpType.CLASS_HISTOGRAM, Arrays.asList(
                new ClassHistogramStrategy()       // composite internally
        ));

        m.put(DumpType.CORE, Arrays.asList(
                new CoreDumpStrategy()             // J9 SystemDump → gcore
        ));

        return m;
    }

    // -------------------------------------------------------------------------
    // Path construction
    // -------------------------------------------------------------------------

    private String buildPath(JvmSnapshot snapshot, DumpType type) {
        String ts   = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS")
                          .format(new Date(snapshot.getTimestampMs()));
        String proc = snapshot.getProcessName().replaceAll("[^A-Za-z0-9._-]", "_");
        String ext  = extensionFor(type);
        return config.getHeapDumpDirectory() + File.separator
                + "oom_" + type.name().toLowerCase() + "_" + proc + "_" + ts + ext;
    }

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
