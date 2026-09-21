package com.trongus.oom.tests.monitor;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.ThresholdRiskAssessor;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ThresholdRiskAssessor} covering every branch of the
 * three-level decision tree (OK / WARNING / CRITICAL) and each individual trigger
 * condition (heap ratio, GC overhead, post-GC growth trend).
 *
 * <p>All tests use a config with:
 * <ul>
 *   <li>Warning heap threshold: 0.80</li>
 *   <li>Critical heap threshold: 0.90</li>
 *   <li>GC overhead threshold: 0.50</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.7
 * @since 1.0.0
 * @see ThresholdRiskAssessor
 */
public class ThresholdRiskAssessorTest {

    /** Config with standard defaults for all tests. */
    private WatchdogConfig config;
    /** Assessor under test. */
    private ThresholdRiskAssessor assessor;

    /** 100 MB in bytes — convenient base for heap size calculations. */
    private static final long MB = 1024L * 1024L;
    private static final long MAX_HEAP = 100L * MB;

    @Before
    public void setUp() {
        config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .gcOverheadThreshold(0.50)
                .build();
        assessor = new ThresholdRiskAssessor(config);
    }

    // ── OK scenarios ──────────────────────────────────────────────────────────

    /**
     * A snapshot with heap well below the warning threshold, no GC overhead,
     * and no growth trend must be assessed as OK.
     */
    @Test
    public void testOkWhenAllMetricsHealthy() {
        JvmSnapshot snap = snapshotWith(0.50, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.OK, result.getRiskLevel());
    }

    /**
     * Exactly at the warning threshold boundary (below by one bit) must still be OK.
     * The threshold comparison is {@code >=} so just below must be OK.
     */
    @Test
    public void testOkJustBelowWarningThreshold() {
        // 0.799... < 0.80 → should be OK
        JvmSnapshot snap = snapshotWith(0.799, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.OK, result.getRiskLevel());
    }

    // ── WARNING scenarios ─────────────────────────────────────────────────────

    /**
     * Heap at exactly the warning threshold (0.80) must trigger WARNING.
     */
    @Test
    public void testWarningAtExactHeapThreshold() {
        JvmSnapshot snap = snapshotWith(0.80, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.WARNING, result.getRiskLevel());
    }

    /**
     * Heap above warning but below critical must trigger WARNING.
     */
    @Test
    public void testWarningBetweenWarningAndCriticalThresholds() {
        JvmSnapshot snap = snapshotWith(0.85, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.WARNING, result.getRiskLevel());
    }

    /**
     * GC overhead at or above the GC threshold alone must trigger WARNING,
     * even when heap is below the warning threshold.
     */
    @Test
    public void testWarningFromGcOverheadAloneHeapHealthy() {
        JvmSnapshot snap = snapshotWith(0.50, 0.50, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.WARNING, result.getRiskLevel());
    }

    /**
     * A positive post-GC growth trend (memory leak signal) must trigger WARNING
     * even when heap and GC are within normal bounds.
     */
    @Test
    public void testWarningFromPositivePostGcGrowthTrend() {
        JvmSnapshot snap = snapshotWith(0.50, 0.10, 0.001);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.WARNING, result.getRiskLevel());
    }

    /**
     * A zero growth trend (flat post-GC heap) must NOT trigger WARNING from the
     * growth rate signal alone.
     */
    @Test
    public void testNoWarningFromZeroGrowthRate() {
        JvmSnapshot snap = snapshotWith(0.50, 0.10, 0.0);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.OK, result.getRiskLevel());
    }

    /**
     * A negative growth trend (heap shrinking after GC) must NOT trigger WARNING.
     */
    @Test
    public void testNoWarningFromNegativeGrowthRate() {
        JvmSnapshot snap = snapshotWith(0.50, 0.10, -0.001);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.OK, result.getRiskLevel());
    }

    // ── CRITICAL scenarios ────────────────────────────────────────────────────

    /**
     * Heap at exactly the critical threshold (0.90) must trigger CRITICAL.
     */
    @Test
    public void testCriticalAtExactCriticalThreshold() {
        JvmSnapshot snap = snapshotWith(0.90, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.CRITICAL, result.getRiskLevel());
    }

    /**
     * Heap above the critical threshold must trigger CRITICAL.
     */
    @Test
    public void testCriticalAboveCriticalThreshold() {
        JvmSnapshot snap = snapshotWith(0.95, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.CRITICAL, result.getRiskLevel());
    }

    /**
     * GC overhead at threshold AND heap at warning threshold is the
     * "runaway GC + high heap" scenario that must produce CRITICAL.
     */
    @Test
    public void testCriticalFromRunawayGcPlusHighHeap() {
        // GC overhead >= 0.50 AND heap >= 0.80 → CRITICAL
        JvmSnapshot snap = snapshotWith(0.80, 0.50, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.CRITICAL, result.getRiskLevel());
    }

    /**
     * GC overhead above threshold with heap BELOW warning threshold must
     * produce WARNING (not CRITICAL), because the heap is still relatively
     * healthy.
     */
    @Test
    public void testGcHighAloneWithHealthyHeapIsWarning() {
        JvmSnapshot snap = snapshotWith(0.70, 0.55, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertSame(OomRiskLevel.WARNING, result.getRiskLevel());
    }

    // ── diagnosis notes enrichment ────────────────────────────────────────────

    /**
     * The assessed snapshot's diagnosis notes must be prefixed with the
     * {@code [Assessment]} tag.
     */
    @Test
    public void testDiagnosisNotesArePrefixed() {
        JvmSnapshot snap = snapshotWith(0.85, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertTrue("Notes must start with [Assessment]",
                result.getDiagnosisNotes().startsWith("[Assessment]"));
    }

    /**
     * The original collector diagnosis notes must be preserved in the
     * enriched notes (appended after the assessment prefix).
     */
    @Test
    public void testOriginalDiagnosisNotesPreserved() {
        JvmSnapshot snap = new JvmSnapshot.Builder()
                .heapUsedRatio(0.50)
                .gcOverheadRatio(0.10)
                .diagnosisNotes("original-collector-notes")
                .build();
        JvmSnapshot result = assessor.assess(snap);
        assertTrue("Original notes must appear in enriched notes",
                result.getDiagnosisNotes().contains("original-collector-notes"));
    }

    /**
     * A CRITICAL assessment must mention "CRITICAL" in the diagnosis notes.
     */
    @Test
    public void testCriticalLabelAppearsInNotes() {
        JvmSnapshot snap = snapshotWith(0.92, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertTrue(result.getDiagnosisNotes().contains("CRITICAL"));
    }

    /**
     * A WARNING assessment must mention "WARNING" in the diagnosis notes.
     */
    @Test
    public void testWarningLabelAppearsInNotes() {
        JvmSnapshot snap = snapshotWith(0.82, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertTrue(result.getDiagnosisNotes().contains("WARNING"));
    }

    // ── immutability ──────────────────────────────────────────────────────────

    /**
     * The assessor must not mutate the input snapshot; it must always return
     * a new instance.
     */
    @Test
    public void testAssessReturnsNewInstance() {
        JvmSnapshot snap = snapshotWith(0.85, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertNotSame(snap, result);
    }

    // ── critThreshold stamping & OOM_FIRING passthrough ───────────────────────

    /**
     * Assessed snapshots must have the active critical threshold stamped on them.
     */
    @Test
    public void testCritThresholdStampedOnAssessment() {
        JvmSnapshot snap = snapshotWith(0.50, 0.10, Double.NaN);
        JvmSnapshot result = assessor.assess(snap);
        assertEquals(config.getCriticalHeapThreshold(), result.getCritThreshold(), 1e-9);
    }

    /**
     * Snapshots already marked OOM_FIRING must pass through unchanged without re-evaluation.
     */
    @Test
    public void testOomFiringPassesThroughUnchanged() {
        JvmSnapshot firing = new JvmSnapshot.Builder()
                .processName("unreachable-target")
                .riskLevel(OomRiskLevel.OOM_FIRING)
                .diagnosisNotes("JMX connection failed")
                .critThreshold(-1.0)
                .build();
        JvmSnapshot result = assessor.assess(firing);
        assertSame("OOM_FIRING snapshot must be returned unchanged", firing, result);
        assertSame(OomRiskLevel.OOM_FIRING, result.getRiskLevel());
        assertEquals("JMX connection failed", result.getDiagnosisNotes());
        assertEquals(-1.0, result.getCritThreshold(), 1e-9);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Builds a minimal snapshot with the three metrics that drive risk assessment.
     *
     * @param heapRatio    fraction of max heap in use (0–1)
     * @param gcOverhead   fraction of uptime spent in GC (0–1)
     * @param growthRate   post-GC heap growth rate in bytes/ms ({@link Double#NaN} = unknown)
     * @return a JvmSnapshot configured with those metrics and empty defaults elsewhere
     */
    private static JvmSnapshot snapshotWith(double heapRatio, double gcOverhead, double growthRate) {
        return new JvmSnapshot.Builder()
                .heapUsedBytes((long) (MAX_HEAP * heapRatio))
                .heapMaxBytes(MAX_HEAP)
                .heapUsedRatio(heapRatio)
                .gcOverheadRatio(gcOverhead)
                .postGcHeapGrowthRatePerMs(growthRate)
                .diagnosisNotes("collector-notes")
                .build();
    }
}
