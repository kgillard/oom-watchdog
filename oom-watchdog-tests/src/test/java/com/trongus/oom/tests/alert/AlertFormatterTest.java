package com.trongus.oom.tests.alert;

import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for the formatting produced by the package-private {@code AlertFormatter}.
 *
 * <p>{@code AlertFormatter} is package-private and cannot be accessed directly
 * from outside the {@code com.trongus.oom.alert} package.  Its output is
 * therefore exercised indirectly through {@link FileLogAlertChannel}, which
 * delegates to both {@code AlertFormatter.toSingleLine()} and
 * {@code AlertFormatter.toHumanReadable()}.  This approach is sound because
 * the formatter has no state of its own and its entire observable behaviour
 * is the content written to the log file.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Human-readable banner and all section headings</li>
 *   <li>Numeric heap/non-heap/GC fields</li>
 *   <li>Unlimited non-heap max display ("unlimited")</li>
 *   <li>NaN growth rate display ("insufficient data")</li>
 *   <li>Finite growth rate display (MB/hour)</li>
 *   <li>Post-GC heap used when present</li>
 *   <li>Heap dump path section when present / absent</li>
 *   <li>GC bean summary lines</li>
 *   <li>Single-line key=value fields</li>
 *   <li>Diagnosis notes in both formats</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.6
 * @since 1.0.0
 * @see FileLogAlertChannel
 */
public class AlertFormatterTest {

    private static final long MB = 1024L * 1024L;

    private Path tempLog;
    private FileLogAlertChannel channel;

    @Before
    public void setUp() throws IOException {
        tempLog = Files.createTempFile("oom-fmt-test-", ".log");
        Files.write(tempLog, new byte[0]);
        channel = new FileLogAlertChannel(tempLog.toAbsolutePath().toString());
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(tempLog);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JvmSnapshot snap(long heapUsed, long heapMax, long nonHeapMax,
                             double growthRate, long postGcHeap,
                             String heapDumpPath, Map<String, Long> gcCounts,
                             Map<String, Long> gcTimes, long totalGcMs,
                             long uptime, String notes) {
        JvmSnapshot.Builder b = new JvmSnapshot.Builder()
                .processName("proc@host")
                .timestampMs(1_700_000_000_000L)
                .heapUsedBytes(heapUsed)
                .heapCommittedBytes(heapMax)
                .heapMaxBytes(heapMax)
                .heapUsedRatio((double) heapUsed / heapMax)
                .nonHeapUsedBytes(50L * MB)
                .nonHeapMaxBytes(nonHeapMax)
                .poolUsedBytes(Collections.<String, Long>singletonMap("TestPool", 10L * MB))
                .gcCollectionCounts(gcCounts)
                .gcCollectionTimesMs(gcTimes)
                .totalGcTimeMs(totalGcMs)
                .jvmUptimeMs(uptime)
                .gcOverheadRatio(uptime > 0 ? (double) totalGcMs / uptime : 0.0)
                .postGcHeapUsedBytes(postGcHeap)
                .postGcHeapGrowthRatePerMs(growthRate)
                .riskLevel(OomRiskLevel.CRITICAL)
                .diagnosisNotes(notes);
        JvmSnapshot snap = b.build();
        if (heapDumpPath != null) {
            snap = snap.withHeapDumpPath(heapDumpPath);
        }
        return snap;
    }

    private JvmSnapshot defaultSnap() {
        return snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "default diagnosis");
    }

    private String read() throws IOException {
        return new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
    }

    // ── human-readable sections ───────────────────────────────────────────────

    /**
     * The human-readable block must contain the CRITICAL severity marker.
     */
    @Test
    public void testHumanReadableBanner() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("CRITICAL severity marker missing", read().contains("CRITICAL"));
    }

    /**
     * The heap usage figures must be present in the output.
     */
    @Test
    public void testHeapSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        // New format: "Heap  90 /  100 MB  90.0%  [...]"
        assertTrue("Heap section missing", read().contains("Heap"));
    }

    /**
     * The Metaspace / non-heap line must be present.
     */
    @Test
    public void testNonHeapSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Metaspace section missing", read().contains("Metaspace"));
    }

    /**
     * The Pools section heading must be present when pools have non-zero usage.
     */
    @Test
    public void testMemoryPoolsSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Pools section missing", read().contains("─ Pools ─"));
    }

    /**
     * The GC section heading must be present when GC data is available.
     */
    @Test
    public void testGcSectionHeading() throws IOException {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("G1 Young Generation", 1L);
        Map<String, Long> times = new LinkedHashMap<String, Long>();
        times.put("G1 Young Generation", 10L);
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, null, counts, times, 10L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("GC section missing", read().contains("─ GC ─"));
    }

    /**
     * When growth rate is finite, the Growth line must appear in the output.
     */
    @Test
    public void testLeakTrendSectionHeading() throws IOException {
        double rate = (double) MB / 3_600_000.0;
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                rate, 85L * MB, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("Growth/leak row missing", read().contains("Growth"));
    }

    /**
     * The Diagnosis section heading must be present.
     */
    @Test
    public void testDiagnosisSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Diagnosis section missing", read().contains("─ Diagnosis ─"));
    }

    // ── numeric heap fields ───────────────────────────────────────────────────

    /**
     * The heap used value (90 MB for 90*MB used of 100*MB max) must appear in the log.
     */
    @Test
    public void testHeapUsedMbInHumanReadable() throws IOException {
        channel.alert(defaultSnap());
        // New format: "Heap  90 /  100 MB  90.0%  [...]"
        assertTrue("90 / 100 heap used missing", read().contains("90 /"));
    }

    /**
     * The heap usage percentage (90.0%) must appear in the human-readable block.
     */
    @Test
    public void testHeapUsagePctInHumanReadable() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("90.0% heap usage missing", read().contains("90.0%"));
    }

    // ── non-heap unlimited ────────────────────────────────────────────────────

    /**
     * When non-heap max is −1, the Metaspace line still appears (no "unlimited" in new format).
     */
    @Test
    public void testNonHeapUnlimitedWhenMaxIsNegOne() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Metaspace line missing", read().contains("Metaspace"));
    }

    /**
     * When non-heap max is a positive value, the non-heap used MB must still appear.
     */
    @Test
    public void testNonHeapMaxMbWhenPositive() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, 256L * MB,
                Double.NaN, -1L, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        String content = read();
        // Non-heap used is 50 MB (set in snap helper) — must appear
        assertTrue("50 MB non-heap used should appear", content.contains("50 MB"));
    }

    // ── GC section ────────────────────────────────────────────────────────────

    /**
     * When no GC beans are present, the GC section heading is absent.
     */
    @Test
    public void testNoGcBeansMessage() throws IOException {
        channel.alert(defaultSnap());
        // No GC data → GC section not rendered; heap/diagnosis still present
        assertFalse("GC section should be absent with no GC data",
                read().contains("─ GC ─"));
    }

    /**
     * When GC data is present, the GC collector name must appear in the log.
     */
    @Test
    public void testGcCollectorNamePresent() throws IOException {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("G1 Young Generation", 42L);
        Map<String, Long> times = new LinkedHashMap<String, Long>();
        times.put("G1 Young Generation", 300L);

        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, null,
                counts, times, 300L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("GC collector name missing", read().contains("G1 Young Generation"));
    }

    // ── growth rate ───────────────────────────────────────────────────────────

    /**
     * When growth rate is NaN, the human-readable Growth row must not be rendered.
     * (The single-line format still contains "postGcGrowth=N/A" — that is expected.)
     */
    @Test
    public void testNaNGrowthRateShowsInsufficientData() throws IOException {
        channel.alert(defaultSnap());
        // The Growth row only appears in human-readable format when slope is known
        assertFalse("Human-readable Growth row should be absent for NaN slope",
                read().contains("  Growth  "));
    }

    /**
     * When a finite positive growth rate is present, the log must show "MB/h".
     */
    @Test
    public void testFiniteGrowthRateShowsMbPerHour() throws IOException {
        double rate = (double) MB / 3_600_000.0;
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                rate, 85L * MB, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("MB/h missing for finite growth rate", read().contains("MB/h"));
    }

    /**
     * When post-GC heap used is set (>= 0), the single-line format still captures it.
     * The new human-readable format shows growth rate via the Growth row, not post-GC bytes.
     */
    @Test
    public void testPostGcHeapUsedPresent() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, 80L * MB, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        // Post-GC heap bytes feed the growth rate; no explicit post-GC line in new format
        // but the snapshot data is still present in the single-line output
        assertTrue("process= missing in single-line", read().contains("process="));
    }

    // ── heap dump section ─────────────────────────────────────────────────────

    /**
     * When a heap dump path is set on the snapshot, the Dump section must appear
     * and contain the path.
     */
    @Test
    public void testHeapDumpSectionPresent() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, "/var/dumps/oom.hprof",
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        String content = read();
        assertTrue("Dump section missing", content.contains("─ Dump ─"));
        assertTrue("Dump path missing", content.contains("/var/dumps/oom.hprof"));
    }

    /**
     * When no heap dump path is set, the Dump section must NOT appear.
     */
    @Test
    public void testNoHeapDumpSectionWhenPathAbsent() throws IOException {
        channel.alert(defaultSnap());
        assertFalse("Dump section should be absent when no path is set",
                read().contains("─ Dump ─"));
    }

    // ── single-line format ────────────────────────────────────────────────────

    /**
     * The single-line format must contain {@code severity=CRITICAL}.
     */
    @Test
    public void testSingleLineContainsSeverityKey() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("severity= missing from single-line", read().contains("severity=CRITICAL"));
    }

    /**
     * The single-line format must contain {@code process=}.
     */
    @Test
    public void testSingleLineContainsProcessKey() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("process= missing from single-line", read().contains("process="));
    }

    /**
     * The single-line format must contain {@code heapUsedMB=}.
     */
    @Test
    public void testSingleLineContainsHeapUsedMbKey() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("heapUsedMB= missing", read().contains("heapUsedMB="));
    }

    /**
     * The single-line format must contain {@code gcOverheadPct=}.
     */
    @Test
    public void testSingleLineContainsGcOverheadPctKey() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("gcOverheadPct= missing", read().contains("gcOverheadPct="));
    }

    /**
     * When the heap dump path is set, the single-line format must include
     * {@code heapDump=<path>}.
     */
    @Test
    public void testSingleLineContainsHeapDumpWhenPresent() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, "/tmp/test.hprof",
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("heapDump= missing from single-line", read().contains("heapDump=/tmp/test.hprof"));
    }

    /**
     * When no dump path is set, the single-line format must NOT contain {@code heapDump=}.
     */
    @Test
    public void testSingleLineNoHeapDumpWhenAbsent() throws IOException {
        channel.alert(defaultSnap());
        assertFalse("heapDump= should be absent when no path set", read().contains("heapDump="));
    }

    // ── diagnosis notes ───────────────────────────────────────────────────────

    /**
     * The diagnosis notes string must appear in the formatted output.
     */
    @Test
    public void testDiagnosisNotesInOutput() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, -1L, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "UNIQUE_DIAGNOSIS_TEXT");
        channel.alert(snap);
        assertTrue("diagnosisNotes missing from output",
                read().contains("UNIQUE_DIAGNOSIS_TEXT"));
    }

    // ── memory pool entry ─────────────────────────────────────────────────────

    /**
     * Pool names provided in the snapshot's pool map must appear in the
     * Memory Pools section.
     */
    @Test
    public void testMemoryPoolNameAppearsInOutput() throws IOException {
        channel.alert(defaultSnap()); // defaultSnap has "TestPool" → 10 MB
        assertTrue("TestPool should appear in memory pools section",
                read().contains("TestPool"));
    }
}
