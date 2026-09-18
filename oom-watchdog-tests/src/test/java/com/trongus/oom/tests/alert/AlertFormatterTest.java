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
 * @version 1.7.3
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
     * The human-readable block must contain the {@code === JVM OOM Alert ===} banner.
     */
    @Test
    public void testHumanReadableBanner() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("banner missing", read().contains("=== JVM OOM Alert ==="));
    }

    /**
     * The {@code -- Heap --} section heading must be present.
     */
    @Test
    public void testHeapSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Heap section missing", read().contains("-- Heap --"));
    }

    /**
     * The {@code -- Non-Heap} section heading must be present.
     */
    @Test
    public void testNonHeapSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Non-Heap section missing", read().contains("-- Non-Heap"));
    }

    /**
     * The {@code -- Memory Pools --} section heading must be present.
     */
    @Test
    public void testMemoryPoolsSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Memory Pools section missing", read().contains("-- Memory Pools --"));
    }

    /**
     * The {@code -- Garbage Collection --} section heading must be present.
     */
    @Test
    public void testGcSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("GC section missing", read().contains("-- Garbage Collection --"));
    }

    /**
     * The {@code -- Leak Trend --} section heading must be present.
     */
    @Test
    public void testLeakTrendSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Leak Trend section missing", read().contains("-- Leak Trend --"));
    }

    /**
     * The {@code -- Diagnosis --} section heading must be present.
     */
    @Test
    public void testDiagnosisSectionHeading() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("Diagnosis section missing", read().contains("-- Diagnosis --"));
    }

    // ── numeric heap fields ───────────────────────────────────────────────────

    /**
     * The heap used in MB (90 MB for 90*MB used of 100*MB max) must appear in the log.
     */
    @Test
    public void testHeapUsedMbInHumanReadable() throws IOException {
        channel.alert(defaultSnap());
        // 90*MB / MB = 90
        assertTrue("90 MB heap used missing", read().contains("90 MB"));
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
     * When non-heap max is −1 (unlimited Metaspace), the word "unlimited" must appear.
     */
    @Test
    public void testNonHeapUnlimitedWhenMaxIsNegOne() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("'unlimited' should appear for -1 nonHeapMax", read().contains("unlimited"));
    }

    /**
     * When non-heap max is a positive value, the MB figure must appear instead of "unlimited".
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
        assertFalse("'unlimited' should NOT appear when nonHeapMax is positive",
                content.contains("unlimited"));
        assertTrue("256 MB should appear for non-heap max", content.contains("256 MB"));
    }

    // ── GC section ────────────────────────────────────────────────────────────

    /**
     * When no GC beans are present, the log must contain "No GC beans available".
     */
    @Test
    public void testNoGcBeansMessage() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("'No GC beans available' missing",
                read().contains("No GC beans available"));
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
     * When growth rate is NaN, the log must contain "insufficient data".
     */
    @Test
    public void testNaNGrowthRateShowsInsufficientData() throws IOException {
        channel.alert(defaultSnap());
        assertTrue("'insufficient data' missing for NaN growth rate",
                read().contains("insufficient data"));
    }

    /**
     * When a finite positive growth rate is present, the log must show "MB/hour".
     */
    @Test
    public void testFiniteGrowthRateShowsMbPerHour() throws IOException {
        // 1 MB/hour = 1/(3_600_000) MB/ms
        double rate = (double) MB / 3_600_000.0;
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                rate, 85L * MB, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("MB/hour missing for finite growth rate", read().contains("MB/hour"));
    }

    /**
     * When post-GC heap used is set (>= 0), it must appear in the log.
     */
    @Test
    public void testPostGcHeapUsedPresent() throws IOException {
        JvmSnapshot snap = snap(90L * MB, 100L * MB, -1L,
                Double.NaN, 80L * MB, null,
                Collections.<String, Long>emptyMap(),
                Collections.<String, Long>emptyMap(),
                0L, 60_000L, "diag");
        channel.alert(snap);
        assertTrue("Post-GC heap line missing", read().contains("Post-GC heap"));
    }

    // ── heap dump section ─────────────────────────────────────────────────────

    /**
     * When a heap dump path is set on the snapshot, the {@code -- Heap Dump --}
     * section must appear and contain the path.
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
        assertTrue("-- Heap Dump -- section missing", content.contains("-- Heap Dump --"));
        assertTrue("Dump path missing", content.contains("/var/dumps/oom.hprof"));
    }

    /**
     * When no heap dump path is set, the {@code -- Heap Dump --} section must
     * NOT appear.
     */
    @Test
    public void testNoHeapDumpSectionWhenPathAbsent() throws IOException {
        channel.alert(defaultSnap());
        assertFalse("-- Heap Dump -- should be absent when no path is set",
                read().contains("-- Heap Dump --"));
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
