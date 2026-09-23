package com.trongus.oom.monitor;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Disk-backed ring-buffer that persists per-target GC and memory metrics as
 * newline-delimited JSON ({@code .jsonl}) for historical replay in the dashboard.
 *
 * <h2>Storage layout</h2>
 * <pre>
 *   ./oom-gc-history/
 *     &lt;target-slug&gt;.jsonl    ← one JSON object per line, newest last
 * </pre>
 *
 * <p>Each line is a compact JSON object with the fields needed by the GC
 * Analysis dashboard panel:
 * <pre>
 * {"ts":1718000000000,"heap":33.6,"gc":0.02,"nursery":19.9,"nonHeap":169.2,
 *  "totalGcMs":103637,"uptime":442584000,"postGcHeap":1083113472,"growthRate":3.47,
 *  "gcCounts":{"G1 Young Generation":4210,"G1 Old Generation":3},
 *  "gcTimes":{"G1 Young Generation":12503,"G1 Old Generation":867}}
 * </pre>
 *
 * <h2>Ring-buffer cap</h2>
 * <p>After each {@link #record(String, JvmSnapshot)} call the file is checked.
 * When it exceeds {@link #maxLines} entries the oldest lines are discarded in a
 * single atomic rewrite so the file always contains at most {@code maxLines} entries.
 * At the default cap of {@code 8640} (one poll per 10 s for 24 h) the file is
 * typically a few megabytes per target.
 *
 * <h2>Thread safety</h2>
 * <p>Each target's file is protected by a per-target {@link Object} monitor
 * stored in {@link #locks}.  Concurrent writes to different targets are fully
 * parallel; concurrent writes to the same target are serialised.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.28
 * @since 1.7.13.2
 * @see MetricsHttpServer
 */
public final class GcHistoryStore {

    private static final Logger LOG = WatchdogLogger.forClass(GcHistoryStore.class);

    /** Default maximum number of history records retained per target. */
    public static final int DEFAULT_MAX_LINES = 8640;   // 24 h at 10-s polls

    private final Path   baseDir;
    private final int    maxLines;

    /** Per-target write locks — created lazily and cached. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    // ── constructors ──────────────────────────────────────────────────────────

    /**
     * Creates a {@code GcHistoryStore} that writes to {@code ./oom-gc-history/}
     * with the default ring-buffer cap of {@value #DEFAULT_MAX_LINES} entries.
     */
    public GcHistoryStore() {
        this(Paths.get(".", "oom-gc-history"), DEFAULT_MAX_LINES);
    }

    /**
     * Creates a {@code GcHistoryStore} that writes to the given directory.
     *
     * @param baseDir  directory under which per-target {@code .jsonl} files are stored
     * @param maxLines maximum number of lines (polls) retained per target; older lines
     *                 are evicted when the cap is exceeded.  Must be &ge; 1.
     * @throws IllegalArgumentException if {@code maxLines &lt; 1}
     * @throws NullPointerException     if {@code baseDir} is {@code null}
     */
    public GcHistoryStore(Path baseDir, int maxLines) {
        if (baseDir == null) throw new NullPointerException("baseDir");
        if (maxLines < 1)   throw new IllegalArgumentException("maxLines must be >= 1");
        this.baseDir  = baseDir;
        this.maxLines = maxLines;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Appends one GC/memory record to the target's history file and trims the
     * file to at most {@link #maxLines} entries.
     *
     * <p>If the storage directory cannot be created or the file cannot be written
     * the error is logged and the call returns silently — history persistence is
     * best-effort and must never disrupt normal watchdog operation.
     *
     * @param targetName logical name of the monitored target (e.g. {@code "tomcat"})
     * @param snap       the assessed snapshot to record; must not be {@code null}
     */
    public void record(String targetName, JvmSnapshot snap) {
        if (snap == null) throw new NullPointerException("snap");
        String slug = slugify(targetName);
        Object lock = locks.computeIfAbsent(slug, k -> new Object());
        synchronized (lock) {
            try {
                ensureDir();
                Path file = baseDir.resolve(slug + ".jsonl");
                appendLine(file, buildLine(snap));
                trimIfNeeded(file);
            } catch (Exception e) {
                WatchdogLogger.warning(LOG,
                        "GcHistoryStore: failed to record history for target [{0}]: {1}",
                        targetName, e.getMessage());
            }
        }
    }

    /**
     * Reads up to {@code limit} most-recent history records for {@code targetName}
     * and returns them as a JSON array string (newest-last order).
     *
     * <p>Returns {@code "[]"} when no history is available or the file cannot be read.
     *
     * @param targetName logical name of the monitored target
     * @param limit      maximum number of records to return; values &lt; 1 mean
     *                   "return all records without any limit"
     * @return JSON array of history record objects
     */
    public String readHistory(String targetName, int limit) {
        if (limit < 1) limit = Integer.MAX_VALUE;   // 0 or negative = read everything
        String slug = slugify(targetName);
        Path file = baseDir.resolve(slug + ".jsonl");
        Object lock = locks.computeIfAbsent(slug, k -> new Object());
        synchronized (lock) {
            if (!Files.exists(file)) {
                return "[]";
            }
            try {
                List<String> lines = readTail(file, limit);
                if (lines.isEmpty()) return "[]";
                StringBuilder sb = new StringBuilder(lines.size() * 120 + 4);
                sb.append("[\n");
                for (int i = 0; i < lines.size(); i++) {
                    sb.append(lines.get(i));
                    if (i < lines.size() - 1) sb.append(",\n");
                }
                sb.append("\n]");
                return sb.toString();
            } catch (Exception e) {
                WatchdogLogger.warning(LOG,
                        "GcHistoryStore: failed to read history for target [{0}]: {1}",
                        targetName, e.getMessage());
                return "[]";
            }
        }
    }

    /**
     * Returns the names of all targets that have recorded history in this store.
     * Names are derived from the {@code .jsonl} file names in the base directory.
     *
     * @return unmodifiable list of target names (slugified)
     */
    public List<String> availableTargets() {
        try {
            ensureDir();
            File[] files = baseDir.toFile().listFiles(
                    f -> f.isFile() && f.getName().endsWith(".jsonl"));
            if (files == null || files.length == 0) return Collections.emptyList();
            List<String> names = new ArrayList<>(files.length);
            for (File f : files) {
                String n = f.getName();
                names.add(n.substring(0, n.length() - ".jsonl".length()));
            }
            Collections.sort(names);
            return Collections.unmodifiableList(names);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Builds a compact JSON object line for a single poll snapshot.
     * Only the fields consumed by the GC Analysis dashboard panel are included;
     * the full snapshot is not serialised to keep the history file small.
     *
     * @param snap the assessed snapshot to serialise; must not be {@code null}
     * @return a single-line compact JSON object string, never {@code null}
     */
    public static String buildLine(JvmSnapshot snap) {
        final double MB = 1024.0 * 1024.0;
        long   heapMax = snap.getHeapMaxBytes();
        double heapPct = heapMax > 0 ? snap.getHeapUsedBytes() * 100.0 / heapMax : 0.0;
        double nurPct  = Double.isNaN(snap.getNurseryUsedRatio())
                         ? 0.0 : snap.getNurseryUsedRatio() * 100.0;
        double gcPct   = snap.getGcOverheadRatio() * 100.0;
        double growthMbHr = Double.isNaN(snap.getPostGcHeapGrowthRatePerMs())
                         ? 0.0
                         : snap.getPostGcHeapGrowthRatePerMs() * 3_600_000.0 / MB;

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendLong  (sb, "ts",         snap.getTimestampMs(),  true);
        appendDouble(sb, "heap",       heapPct,                false);
        appendDouble(sb, "gc",         gcPct,                  false);
        appendDouble(sb, "nursery",    nurPct,                 false);
        appendDouble(sb, "nonHeapMB",  snap.getNonHeapUsedBytes() / MB, false);
        appendLong  (sb, "totalGcMs",  snap.getTotalGcTimeMs(),false);
        appendLong  (sb, "uptime",     snap.getJvmUptimeMs(),  false);
        appendDouble(sb, "heapMB",     snap.getHeapUsedBytes() / MB, false);
        appendDouble(sb, "heapMaxMB",  heapMax / MB,           false);
        appendDouble(sb, "growthMbHr", growthMbHr,             false);
        appendDouble(sb, "postGcHeapMB",
                snap.getPostGcHeapUsedBytes() > 0 ? snap.getPostGcHeapUsedBytes() / MB : -1.0,
                false);
        appendString(sb, "risk", snap.getRiskLevel().name(), false);

        // per-collector breakdown
        sb.append(",\"gcCounts\":{");
        appendMap(sb, snap.getGcCollectionCounts());
        sb.append("},\"gcTimes\":{");
        appendMap(sb, snap.getGcCollectionTimesMs());
        sb.append("}}");

        return sb.toString();
    }

    private static void appendLong(StringBuilder sb, String k, long v, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(k).append("\":").append(v);
    }

    private static void appendDouble(StringBuilder sb, String k, double v, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(k).append("\":");
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("0.0");
        } else {
            sb.append(String.format(Locale.US, "%.4f", v));
        }
    }

    private static void appendString(StringBuilder sb, String k, String v, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(k).append("\":\"").append(escapeJson(v)).append('"');
    }

    private static void appendMap(StringBuilder sb, Map<String, Long> map) {
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"')       sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else                sb.append(c);
        }
        return sb.toString();
    }

    private void ensureDir() throws IOException {
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
        }
    }

    private static void appendLine(Path file, String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(file.toFile(), true)))) {
            pw.println(line);
        }
    }

    /**
     * Trims the file in-place so it contains at most {@link #maxLines} lines.
     * Reads all lines in one pass, discards the oldest, and rewrites the file only
     * when the cap is actually exceeded.
     */
    private void trimIfNeeded(Path file) throws IOException {
        List<String> lines = new ArrayList<>(maxLines + 16);
        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) lines.add(line);
            }
        }
        int drop = lines.size() - maxLines;
        if (drop <= 0) return;

        List<String> trimmed = lines.subList(drop, lines.size());
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new FileWriter(file.toFile(), false)))) {  // overwrite
            for (String l : trimmed) pw.println(l);
        }
    }

    /**
     * Reads the last {@code n} non-empty lines from a file without loading the whole file.
     */
    private static List<String> readTail(Path file, int n) throws IOException {
        // n == Integer.MAX_VALUE means "read everything" — avoid overflow on capacity hint
        List<String> all = new ArrayList<>(n < 65_536 ? n + 100 : 256);
        try (BufferedReader br = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) all.add(line);
            }
        }
        if (n == Integer.MAX_VALUE || all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    /**
     * Converts a target name to a safe file-system slug: only ASCII alphanumerics,
     * dots, hyphens, and underscores are retained; everything else becomes {@code _}.
     *
     * @param name target name to slugify; {@code null} or empty returns {@code "_"}
     * @return safe filesystem slug, never {@code null}
     */
    public static String slugify(String name) {
        if (name == null || name.isEmpty()) return "_";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
