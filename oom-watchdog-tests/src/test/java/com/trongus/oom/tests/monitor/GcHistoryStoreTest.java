package com.trongus.oom.tests.monitor;

import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.GcHistoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link GcHistoryStore} covering:
 * <ul>
 *   <li>{@link GcHistoryStore#slugify(String)} — safe slug generation</li>
 *   <li>{@link GcHistoryStore#buildLine(JvmSnapshot)} — JSON line format and field values</li>
 *   <li>{@link GcHistoryStore#record(String, JvmSnapshot)} — append to disk</li>
 *   <li>{@link GcHistoryStore#readHistory(String, int)} — read back as JSON array</li>
 *   <li>{@link GcHistoryStore#availableTargets()} — directory listing</li>
 *   <li>Ring-buffer trimming — file is capped at {@code maxLines} entries</li>
 * </ul>
 *
 * <p>Each test creates its own temporary directory so tests are fully isolated.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.13
 * @since 1.7.13.12
 * @see GcHistoryStore
 */
public class GcHistoryStoreTest {

    /** One megabyte in bytes. */
    private static final long MB = 1024L * 1024L;

    /** Temporary directory created fresh for each test. */
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("gc-history-test-");
    }

    @After
    public void tearDown() throws IOException {
        // Delete all files in the temp directory, then the directory itself.
        if (tempDir != null && Files.exists(tempDir)) {
            for (Path p : Files.newDirectoryStream(tempDir)) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(tempDir);
        }
    }

    // ── slugify ───────────────────────────────────────────────────────────────

    /** ASCII alphanumerics, hyphens, underscores, and dots pass through unchanged. */
    @Test
    public void testSlugifySafeChars() {
        assertEquals("my-app_1.0", GcHistoryStore.slugify("my-app_1.0"));
    }

    /** Spaces and special characters are replaced by underscores. */
    @Test
    public void testSlugifyReplacesBadChars() {
        assertEquals("my_app_v1", GcHistoryStore.slugify("my app/v1"));
    }

    /** A null name produces a single underscore, not an exception. */
    @Test
    public void testSlugifyNull() {
        assertEquals("_", GcHistoryStore.slugify(null));
    }

    /** An empty name produces a single underscore. */
    @Test
    public void testSlugifyEmpty() {
        assertEquals("_", GcHistoryStore.slugify(""));
    }

    // ── buildLine ─────────────────────────────────────────────────────────────

    /**
     * {@code buildLine} must produce a valid JSON object with all expected keys
     * present and values that correspond to the snapshot's fields.
     */
    @Test
    public void testBuildLineContainsExpectedFields() {
        JvmSnapshot snap = baseSnapshot().build();
        String line = GcHistoryStore.buildLine(snap);

        assertTrue("must start with '{'", line.startsWith("{"));
        assertTrue("must end with '}'",   line.endsWith("}"));
        assertTrue("ts field",        line.contains("\"ts\":"));
        assertTrue("heap field",      line.contains("\"heap\":"));
        assertTrue("gc field",        line.contains("\"gc\":"));
        assertTrue("nursery field",   line.contains("\"nursery\":"));
        assertTrue("nonHeapMB field", line.contains("\"nonHeapMB\":"));
        assertTrue("totalGcMs field", line.contains("\"totalGcMs\":"));
        assertTrue("uptime field",    line.contains("\"uptime\":"));
        assertTrue("heapMB field",    line.contains("\"heapMB\":"));
        assertTrue("heapMaxMB field", line.contains("\"heapMaxMB\":"));
        assertTrue("growthMbHr",      line.contains("\"growthMbHr\":"));
        assertTrue("postGcHeapMB",    line.contains("\"postGcHeapMB\":"));
        assertTrue("risk field",      line.contains("\"risk\":"));
        assertTrue("gcCounts field",  line.contains("\"gcCounts\":"));
        assertTrue("gcTimes field",   line.contains("\"gcTimes\":"));
    }

    /** The {@code risk} field must reflect the snapshot's risk level. */
    @Test
    public void testBuildLineRiskLevel() {
        JvmSnapshot snap = baseSnapshot().riskLevel(OomRiskLevel.CRITICAL).build();
        String line = GcHistoryStore.buildLine(snap);
        assertTrue(line.contains("\"risk\":\"CRITICAL\""));
    }

    /** The {@code ts} field must equal the snapshot's timestamp. */
    @Test
    public void testBuildLineTimestamp() {
        JvmSnapshot snap = baseSnapshot().timestampMs(123_456_789L).build();
        String line = GcHistoryStore.buildLine(snap);
        assertTrue(line.contains("\"ts\":123456789"));
    }

    /** Per-collector counts and times must appear inside {@code gcCounts}/{@code gcTimes}. */
    @Test
    public void testBuildLineGcBreakdown() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("G1 Young Generation", 42L);
        Map<String, Long> times  = new LinkedHashMap<>();
        times.put("G1 Young Generation", 999L);
        JvmSnapshot snap = baseSnapshot()
                .gcCollectionCounts(counts)
                .gcCollectionTimesMs(times)
                .build();
        String line = GcHistoryStore.buildLine(snap);
        assertTrue(line.contains("\"G1 Young Generation\":42"));
        assertTrue(line.contains("\"G1 Young Generation\":999"));
    }

    /** A NaN growth-rate must be serialised as {@code 0.0}, not {@code NaN}. */
    @Test
    public void testBuildLineNaNGrowthRate() {
        JvmSnapshot snap = baseSnapshot().postGcHeapGrowthRatePerMs(Double.NaN).build();
        String line = GcHistoryStore.buildLine(snap);
        // must not contain the literal "NaN"
        assertFalse("NaN must not appear in output", line.contains("NaN"));
        assertTrue("growthMbHr must be 0.0", line.contains("\"growthMbHr\":0.0"));
    }

    // ── record + readHistory ──────────────────────────────────────────────────

    /** A single recorded snapshot must be readable back as a one-element JSON array. */
    @Test
    public void testRecordAndReadSingleEntry() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        store.record("tomcat", baseSnapshot().timestampMs(1_000L).build());

        String json = store.readHistory("tomcat", 100);
        assertTrue("must start with '['", json.startsWith("["));
        assertTrue("must end with ']'",   json.endsWith("]"));
        assertTrue("must contain ts",     json.contains("\"ts\":1000"));
    }

    /** Multiple recorded snapshots must all appear in the JSON array, newest last. */
    @Test
    public void testRecordMultipleEntriesPreservesOrder() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        store.record("app", baseSnapshot().timestampMs(100L).build());
        store.record("app", baseSnapshot().timestampMs(200L).build());
        store.record("app", baseSnapshot().timestampMs(300L).build());

        String json = store.readHistory("app", 100);
        int pos100 = json.indexOf("\"ts\":100");
        int pos200 = json.indexOf("\"ts\":200");
        int pos300 = json.indexOf("\"ts\":300");
        assertTrue("ts:100 must appear", pos100 >= 0);
        assertTrue("ts:200 must appear", pos200 >= 0);
        assertTrue("ts:300 must appear", pos300 >= 0);
        assertTrue("oldest first: 100 < 200", pos100 < pos200);
        assertTrue("oldest first: 200 < 300", pos200 < pos300);
    }

    /** Requesting more records than exist returns all available records. */
    @Test
    public void testReadHistoryLimitExceedsActual() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        store.record("x", baseSnapshot().timestampMs(1L).build());
        store.record("x", baseSnapshot().timestampMs(2L).build());

        String json = store.readHistory("x", 50);
        assertTrue(json.contains("\"ts\":1"));
        assertTrue(json.contains("\"ts\":2"));
    }

    /** When the {@code limit} parameter is honoured, only the last N records are returned. */
    @Test
    public void testReadHistoryLimit() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        for (int i = 1; i <= 10; i++) {
            store.record("limited", baseSnapshot().timestampMs((long) i * 1000).build());
        }

        // Request only the last 3
        String json = store.readHistory("limited", 3);
        // ts:8000 and earlier must NOT appear; ts:8000, 9000, 10000 must appear
        assertFalse("ts:7000 must not be in limit-3 result", json.contains("\"ts\":7000"));
        assertTrue("ts:8000 must be in limit-3 result",  json.contains("\"ts\":8000"));
        assertTrue("ts:9000 must be in limit-3 result",  json.contains("\"ts\":9000"));
        assertTrue("ts:10000 must be in limit-3 result", json.contains("\"ts\":10000"));
    }

    /**
     * {@code readHistory} must return the JSON string {@code "[]"} when no history
     * file exists for the requested target.
     */
    @Test
    public void testReadHistoryNoFile() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        assertEquals("[]", store.readHistory("nonexistent", 100));
    }

    /**
     * A zero or negative limit must not throw — it should default to returning all records.
     */
    @Test
    public void testReadHistoryZeroLimitUsesDefault() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        store.record("z", baseSnapshot().timestampMs(1L).build());
        // limit=0 should not throw and should return data
        String json = store.readHistory("z", 0);
        assertTrue("should return data with limit=0", json.contains("\"ts\":1"));
    }

    // ── ring-buffer trimming ──────────────────────────────────────────────────

    /**
     * After recording more entries than {@code maxLines}, the oldest entries must
     * be evicted and the file must contain exactly {@code maxLines} lines.
     */
    @Test
    public void testRingBufferTrimEvictsOldestEntries() throws IOException {
        int cap = 5;
        GcHistoryStore store = new GcHistoryStore(tempDir, cap);
        for (int i = 1; i <= 8; i++) {
            store.record("ring", baseSnapshot().timestampMs((long) i).build());
        }

        // Count lines in the resulting file
        Path file = tempDir.resolve("ring.jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        // Filter blank lines just in case
        long nonEmpty = lines.stream().filter(l -> !l.isEmpty()).count();
        assertEquals("file must be trimmed to cap", cap, nonEmpty);

        // The oldest 3 (ts:1, ts:2, ts:3) must be gone; newest 5 (ts:4..ts:8) must remain
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertFalse("ts:1 must be evicted",   content.contains("\"ts\":1,"));
        assertFalse("ts:2 must be evicted",   content.contains("\"ts\":2,"));
        assertFalse("ts:3 must be evicted",   content.contains("\"ts\":3,"));
        assertTrue("ts:4 must be retained",   content.contains("\"ts\":4,"));
        assertTrue("ts:8 must be retained",   content.contains("\"ts\":8,"));
    }

    /** Recording exactly {@code maxLines} entries must not trigger any eviction. */
    @Test
    public void testRingBufferExactCapNoTrim() throws IOException {
        int cap = 3;
        GcHistoryStore store = new GcHistoryStore(tempDir, cap);
        for (int i = 1; i <= cap; i++) {
            store.record("exact", baseSnapshot().timestampMs((long) i).build());
        }
        Path file = tempDir.resolve("exact.jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long nonEmpty = lines.stream().filter(l -> !l.isEmpty()).count();
        assertEquals("no trimming at exactly cap", cap, nonEmpty);
    }

    // ── availableTargets ──────────────────────────────────────────────────────

    /**
     * After recording for two distinct targets, {@code availableTargets()} must
     * return both slugified names, sorted alphabetically.
     */
    @Test
    public void testAvailableTargets() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        store.record("zebra", baseSnapshot().build());
        store.record("alpha", baseSnapshot().build());

        List<String> targets = store.availableTargets();
        assertEquals(2, targets.size());
        assertEquals("alpha", targets.get(0));
        assertEquals("zebra", targets.get(1));
    }

    /** An empty store directory must return an empty list, not throw. */
    @Test
    public void testAvailableTargetsEmpty() {
        GcHistoryStore store = new GcHistoryStore(tempDir, 100);
        List<String> targets = store.availableTargets();
        assertTrue("empty store must return empty list", targets.isEmpty());
    }

    // ── constructor validation ────────────────────────────────────────────────

    /** A null {@code baseDir} must throw {@link NullPointerException}. */
    @Test(expected = NullPointerException.class)
    public void testConstructorNullDir() {
        new GcHistoryStore(null, 10);
    }

    /** {@code maxLines < 1} must throw {@link IllegalArgumentException}. */
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorZeroMaxLines() {
        new GcHistoryStore(tempDir, 0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a {@link JvmSnapshot.Builder} pre-populated with harmless but
     * complete values so individual tests only need to override what they care about.
     */
    private static JvmSnapshot.Builder baseSnapshot() {
        Map<String, Long> gcCounts = new LinkedHashMap<>();
        gcCounts.put("G1 Young Generation", 5L);
        Map<String, Long> gcTimes = new LinkedHashMap<>();
        gcTimes.put("G1 Young Generation", 12L);

        return new JvmSnapshot.Builder()
                .processName("test@localhost")
                .timestampMs(System.currentTimeMillis())
                .heapUsedBytes(60L * MB)
                .heapCommittedBytes(80L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.60)
                .nonHeapUsedBytes(20L * MB)
                .nonHeapMaxBytes(-1L)
                .poolUsedBytes(Collections.emptyMap())
                .gcCollectionCounts(gcCounts)
                .gcCollectionTimesMs(gcTimes)
                .totalGcTimeMs(12L)
                .jvmUptimeMs(60_000L)
                .gcOverheadRatio(0.005)
                .postGcHeapUsedBytes(55L * MB)
                .postGcHeapGrowthRatePerMs(0.0)
                .nurseryUsedRatio(0.15)
                .critThreshold(0.90)
                .riskLevel(OomRiskLevel.OK)
                .diagnosisNotes("")
                .heapDumpPath(null);
    }
}
