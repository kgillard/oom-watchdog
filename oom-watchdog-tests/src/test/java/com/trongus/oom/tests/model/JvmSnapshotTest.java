package com.trongus.oom.tests.model;

import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link JvmSnapshot} covering:
 * <ul>
 *   <li>Builder construction with defaults and explicit values</li>
 *   <li>Immutability guarantees</li>
 *   <li>{@link JvmSnapshot#withHeapDumpPath(String)} wither method</li>
 *   <li>{@link JvmSnapshot#toBuilder()} round-trip fidelity</li>
 *   <li>Map accessor unmodifiability</li>
 * </ul>
 *
 * @author Trongus OOM Watchdog
 * @version 1.0.0
 * @since 1.0.0
 * @see JvmSnapshot
 */
public class JvmSnapshotTest {

    /** A fully-populated snapshot shared across tests that only read state. */
    private JvmSnapshot snapshot;

    /** One megabyte in bytes — used to build realistic heap figures. */
    private static final long MB = 1024L * 1024L;

    /**
     * Builds a canonical snapshot with well-known values so every test can
     * assert predictable results without magic numbers.
     */
    @Before
    public void setUp() {
        Map<String, Long> pools = new LinkedHashMap<>();
        pools.put("G1 Eden Space", 10L * MB);
        pools.put("G1 Old Gen",    50L * MB);

        Map<String, Long> gcCounts = new LinkedHashMap<>();
        gcCounts.put("G1 Young Generation", 5L);
        gcCounts.put("G1 Old Generation",   1L);

        Map<String, Long> gcTimes = new LinkedHashMap<>();
        gcTimes.put("G1 Young Generation", 12L);
        gcTimes.put("G1 Old Generation",   45L);

        snapshot = new JvmSnapshot.Builder()
                .processName("99999@testhost")
                .timestampMs(1_000_000L)
                .heapUsedBytes(60L * MB)
                .heapCommittedBytes(80L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.60)
                .nonHeapUsedBytes(20L * MB)
                .nonHeapMaxBytes(-1L)
                .poolUsedBytes(pools)
                .gcCollectionCounts(gcCounts)
                .gcCollectionTimesMs(gcTimes)
                .totalGcTimeMs(57L)
                .jvmUptimeMs(10_000L)
                .gcOverheadRatio(0.0057)
                .postGcHeapUsedBytes(55L * MB)
                .postGcHeapGrowthRatePerMs(0.001)
                .riskLevel(OomRiskLevel.WARNING)
                .diagnosisNotes("test-diagnosis")
                .heapDumpPath(null)
                .build();
    }

    // ── process identity ──────────────────────────────────────────────────────

    /** The process name set via the builder must be retrievable unchanged. */
    @Test
    public void testProcessName() {
        assertEquals("99999@testhost", snapshot.getProcessName());
    }

    /** The timestamp set via the builder must be retrievable unchanged. */
    @Test
    public void testTimestamp() {
        assertEquals(1_000_000L, snapshot.getTimestampMs());
    }

    // ── heap accessors ────────────────────────────────────────────────────────

    /** Heap used bytes must match what was set. */
    @Test
    public void testHeapUsedBytes() {
        assertEquals(60L * MB, snapshot.getHeapUsedBytes());
    }

    /** Heap committed bytes must match what was set. */
    @Test
    public void testHeapCommittedBytes() {
        assertEquals(80L * MB, snapshot.getHeapCommittedBytes());
    }

    /** Max heap bytes must match what was set. */
    @Test
    public void testHeapMaxBytes() {
        assertEquals(100L * MB, snapshot.getHeapMaxBytes());
    }

    /** Heap ratio must match what was set (to within floating-point tolerance). */
    @Test
    public void testHeapUsedRatio() {
        assertEquals(0.60, snapshot.getHeapUsedRatio(), 1e-9);
    }

    // ── non-heap accessors ────────────────────────────────────────────────────

    /** Non-heap used bytes must match what was set. */
    @Test
    public void testNonHeapUsedBytes() {
        assertEquals(20L * MB, snapshot.getNonHeapUsedBytes());
    }

    /** Non-heap max of {@code -1} signals unlimited (Metaspace default). */
    @Test
    public void testNonHeapMaxBytesUnlimited() {
        assertEquals(-1L, snapshot.getNonHeapMaxBytes());
    }

    // ── pool map ──────────────────────────────────────────────────────────────

    /** Pool map must contain both pools that were added. */
    @Test
    public void testPoolMapSize() {
        assertEquals(2, snapshot.getPoolUsedBytes().size());
    }

    /** Eden pool value must match what was set. */
    @Test
    public void testPoolMapEdeneValue() {
        assertEquals(Long.valueOf(10L * MB), snapshot.getPoolUsedBytes().get("G1 Eden Space"));
    }

    /** The pool map returned by the accessor must be unmodifiable. */
    @Test(expected = UnsupportedOperationException.class)
    public void testPoolMapIsUnmodifiable() {
        snapshot.getPoolUsedBytes().put("new-pool", 1L);
    }

    // ── GC accessors ─────────────────────────────────────────────────────────

    /** GC count map size must match the number of collectors added. */
    @Test
    public void testGcCountMapSize() {
        assertEquals(2, snapshot.getGcCollectionCounts().size());
    }

    /** Young-generation count must match what was set. */
    @Test
    public void testGcCountYoung() {
        assertEquals(Long.valueOf(5L), snapshot.getGcCollectionCounts().get("G1 Young Generation"));
    }

    /** Total GC time must match what was set. */
    @Test
    public void testTotalGcTime() {
        assertEquals(57L, snapshot.getTotalGcTimeMs());
    }

    /** JVM uptime must match what was set. */
    @Test
    public void testJvmUptime() {
        assertEquals(10_000L, snapshot.getJvmUptimeMs());
    }

    /** GC overhead ratio must match what was set. */
    @Test
    public void testGcOverheadRatio() {
        assertEquals(0.0057, snapshot.getGcOverheadRatio(), 1e-9);
    }

    // ── leak trend ────────────────────────────────────────────────────────────

    /** Post-GC heap used bytes must match what was set. */
    @Test
    public void testPostGcHeapUsedBytes() {
        assertEquals(55L * MB, snapshot.getPostGcHeapUsedBytes());
    }

    /** Post-GC growth rate must match what was set. */
    @Test
    public void testPostGcGrowthRate() {
        assertEquals(0.001, snapshot.getPostGcHeapGrowthRatePerMs(), 1e-9);
    }

    /** NaN growth rate (default when insufficient data) is preserved. */
    @Test
    public void testNanGrowthRateDefault() {
        JvmSnapshot s = new JvmSnapshot.Builder().build();
        assertTrue("Default growth rate must be NaN", Double.isNaN(s.getPostGcHeapGrowthRatePerMs()));
    }

    /** Default post-GC heap value is -1 when no GC has occurred yet. */
    @Test
    public void testDefaultPostGcHeapIsNegativeOne() {
        JvmSnapshot s = new JvmSnapshot.Builder().build();
        assertEquals(-1L, s.getPostGcHeapUsedBytes());
    }

    // ── risk level and notes ──────────────────────────────────────────────────

    /** Risk level must match what was set. */
    @Test
    public void testRiskLevel() {
        assertSame(OomRiskLevel.WARNING, snapshot.getRiskLevel());
    }

    /** Default risk level for a newly-built snapshot must be OK. */
    @Test
    public void testDefaultRiskLevelIsOk() {
        JvmSnapshot s = new JvmSnapshot.Builder().build();
        assertSame(OomRiskLevel.OK, s.getRiskLevel());
    }

    /** Diagnosis notes must match what was set. */
    @Test
    public void testDiagnosisNotes() {
        assertEquals("test-diagnosis", snapshot.getDiagnosisNotes());
    }

    // ── heap dump path ────────────────────────────────────────────────────────

    /** Null dump path is the default — no dump taken yet. */
    @Test
    public void testHeapDumpPathNullByDefault() {
        assertNull(snapshot.getHeapDumpPath());
    }

    /** {@code withHeapDumpPath} must return a new snapshot with the path set. */
    @Test
    public void testWithHeapDumpPath() {
        JvmSnapshot enriched = snapshot.withHeapDumpPath("/tmp/heap.hprof");
        assertEquals("/tmp/heap.hprof", enriched.getHeapDumpPath());
    }

    /** {@code withHeapDumpPath} must NOT mutate the original snapshot. */
    @Test
    public void testWithHeapDumpPathDoesNotMutateOriginal() {
        snapshot.withHeapDumpPath("/tmp/heap.hprof");
        assertNull("Original snapshot must remain unchanged", snapshot.getHeapDumpPath());
    }

    /** The snapshot returned by {@code withHeapDumpPath} must be a different object. */
    @Test
    public void testWithHeapDumpPathReturnsNewInstance() {
        JvmSnapshot enriched = snapshot.withHeapDumpPath("/tmp/heap.hprof");
        assertNotSame(snapshot, enriched);
    }

    // ── toBuilder round-trip ─────────────────────────────────────────────────

    /**
     * A round-trip through {@code toBuilder().build()} must produce an equal-valued
     * (but distinct) snapshot preserving all fields.
     */
    @Test
    public void testToBuilderRoundTrip() {
        JvmSnapshot copy = snapshot.toBuilder().build();
        assertNotSame("round-trip must return a new instance", snapshot, copy);
        assertEquals(snapshot.getProcessName(),         copy.getProcessName());
        assertEquals(snapshot.getTimestampMs(),          copy.getTimestampMs());
        assertEquals(snapshot.getHeapUsedBytes(),        copy.getHeapUsedBytes());
        assertEquals(snapshot.getHeapMaxBytes(),         copy.getHeapMaxBytes());
        assertEquals(snapshot.getHeapUsedRatio(),        copy.getHeapUsedRatio(), 1e-9);
        assertEquals(snapshot.getTotalGcTimeMs(),        copy.getTotalGcTimeMs());
        assertEquals(snapshot.getRiskLevel(),            copy.getRiskLevel());
        assertEquals(snapshot.getDiagnosisNotes(),       copy.getDiagnosisNotes());
        assertEquals(snapshot.getPoolUsedBytes().size(), copy.getPoolUsedBytes().size());
    }

    /** Using toBuilder to override a single field must leave all others unchanged. */
    @Test
    public void testToBuilderPartialOverride() {
        JvmSnapshot modified = snapshot.toBuilder()
                .riskLevel(OomRiskLevel.CRITICAL)
                .build();
        assertSame(OomRiskLevel.CRITICAL, modified.getRiskLevel());
        // All other fields must be identical
        assertEquals(snapshot.getProcessName(), modified.getProcessName());
        assertEquals(snapshot.getHeapUsedBytes(), modified.getHeapUsedBytes());
    }
}
