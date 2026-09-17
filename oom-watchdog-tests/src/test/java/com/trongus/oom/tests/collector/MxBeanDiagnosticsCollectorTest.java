package com.trongus.oom.tests.collector;

import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link MxBeanDiagnosticsCollector}.
 *
 * <p>The collector uses the live JVM MXBeans, so these are integration-style
 * unit tests: we verify the <em>shape</em> and <em>consistency</em> of the
 * returned {@link JvmSnapshot} rather than fixed numeric values that would
 * differ across JVM vendors, heap settings, and GC activity.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.0
 * @since 1.0.0
 * @see MxBeanDiagnosticsCollector
 */
public class MxBeanDiagnosticsCollectorTest {

    /** Collector under test, using default configuration. */
    private MxBeanDiagnosticsCollector collector;

    @Before
    public void setUp() {
        WatchdogConfig config = WatchdogConfig.defaults()
                .leakDetectionWindowSize(5)
                .build();
        collector = new MxBeanDiagnosticsCollector(config);
    }

    // ── basic structural assertions ───────────────────────────────────────────

    /**
     * {@code collect()} must return a non-null snapshot.
     */
    @Test
    public void testCollectReturnsNonNull() {
        assertNotNull("collect() must return a non-null snapshot", collector.collect());
    }

    /**
     * The process name must be non-null and non-empty (sourced from
     * {@code RuntimeMXBean.getName()}).
     */
    @Test
    public void testProcessNamePresent() {
        JvmSnapshot snap = collector.collect();
        assertNotNull("processName must not be null", snap.getProcessName());
        assertFalse("processName must not be empty", snap.getProcessName().isEmpty());
    }

    /**
     * The timestamp must be a recent epoch millisecond value —
     * at least 1 000 000 000 000 (after year 2001).
     */
    @Test
    public void testTimestampIsRecent() {
        JvmSnapshot snap = collector.collect();
        assertTrue("timestamp must be a reasonable epoch ms value",
                snap.getTimestampMs() > 1_000_000_000_000L);
    }

    // ── heap metrics ──────────────────────────────────────────────────────────

    /**
     * Heap used bytes must be positive.
     */
    @Test
    public void testHeapUsedPositive() {
        assertTrue("heapUsedBytes must be positive", collector.collect().getHeapUsedBytes() > 0);
    }

    /**
     * Heap committed must be at least as large as heap used.
     */
    @Test
    public void testHeapCommittedAtLeastUsed() {
        JvmSnapshot snap = collector.collect();
        assertTrue("heapCommittedBytes must be >= heapUsedBytes",
                snap.getHeapCommittedBytes() >= snap.getHeapUsedBytes());
    }

    /**
     * Heap max must be positive (we default to committed when -Xmx is not set).
     */
    @Test
    public void testHeapMaxPositive() {
        assertTrue("heapMaxBytes must be positive", collector.collect().getHeapMaxBytes() > 0);
    }

    /**
     * Heap used ratio must be between 0.0 and 1.0 (inclusive).
     */
    @Test
    public void testHeapUsedRatioInRange() {
        double ratio = collector.collect().getHeapUsedRatio();
        assertTrue("heapUsedRatio must be >= 0.0", ratio >= 0.0);
        assertTrue("heapUsedRatio must be <= 1.0", ratio <= 1.0);
    }

    /**
     * The ratio must be consistent with the raw used/max byte values.
     */
    @Test
    public void testHeapUsedRatioConsistentWithBytes() {
        JvmSnapshot snap = collector.collect();
        double expected = (double) snap.getHeapUsedBytes() / snap.getHeapMaxBytes();
        assertEquals("heapUsedRatio inconsistent with bytes", expected, snap.getHeapUsedRatio(), 0.01);
    }

    // ── non-heap metrics ──────────────────────────────────────────────────────

    /**
     * Non-heap used bytes must be positive (Metaspace / Code Cache always
     * have some allocation in any running JVM).
     */
    @Test
    public void testNonHeapUsedPositive() {
        assertTrue("nonHeapUsedBytes must be positive", collector.collect().getNonHeapUsedBytes() > 0);
    }

    // ── memory pool map ───────────────────────────────────────────────────────

    /**
     * The pool map must not be null; on any modern JVM there is at least one pool.
     */
    @Test
    public void testPoolMapNotNull() {
        assertNotNull("poolUsedBytes must not be null", collector.collect().getPoolUsedBytes());
    }

    /**
     * Every pool value in the map must be non-negative.
     */
    @Test
    public void testPoolMapValuesNonNegative() {
        for (long v : collector.collect().getPoolUsedBytes().values()) {
            assertTrue("pool value must be non-negative", v >= 0);
        }
    }

    // ── GC metrics ────────────────────────────────────────────────────────────

    /**
     * The GC count map must not be null.
     */
    @Test
    public void testGcCountMapNotNull() {
        assertNotNull("gcCollectionCounts must not be null",
                collector.collect().getGcCollectionCounts());
    }

    /**
     * The GC time map must not be null.
     */
    @Test
    public void testGcTimeMapNotNull() {
        assertNotNull("gcCollectionTimesMs must not be null",
                collector.collect().getGcCollectionTimesMs());
    }

    /**
     * Total GC time must be non-negative.
     */
    @Test
    public void testTotalGcTimeNonNegative() {
        assertTrue("totalGcTimeMs must be non-negative",
                collector.collect().getTotalGcTimeMs() >= 0);
    }

    /**
     * JVM uptime must be positive (it starts at launch, well before any test).
     */
    @Test
    public void testJvmUptimePositive() {
        assertTrue("jvmUptimeMs must be positive", collector.collect().getJvmUptimeMs() > 0);
    }

    /**
     * GC overhead ratio must be between 0.0 and 1.0.
     */
    @Test
    public void testGcOverheadRatioInRange() {
        double ratio = collector.collect().getGcOverheadRatio();
        assertTrue("gcOverheadRatio must be >= 0.0", ratio >= 0.0);
        assertTrue("gcOverheadRatio must be <= 1.0", ratio <= 1.0);
    }

    // ── risk level ────────────────────────────────────────────────────────────

    /**
     * The collector must set risk level to OK; classification is the
     * assessor's responsibility, not the collector's.
     */
    @Test
    public void testRiskLevelSetToOkByCollector() {
        assertSame("collector must return OK risk level",
                OomRiskLevel.OK, collector.collect().getRiskLevel());
    }

    // ── diagnosis notes ───────────────────────────────────────────────────────

    /**
     * Diagnosis notes must be non-null and non-empty.
     */
    @Test
    public void testDiagnosisNotesNonEmpty() {
        JvmSnapshot snap = collector.collect();
        assertNotNull("diagnosisNotes must not be null", snap.getDiagnosisNotes());
        assertFalse("diagnosisNotes must not be empty", snap.getDiagnosisNotes().isEmpty());
    }

    // ── post-GC trend (first call) ────────────────────────────────────────────

    /**
     * On the first call there will not yet be enough post-GC samples to compute
     * the growth rate, so it should be {@code NaN} unless a GC happened to fire
     * before the very first poll.
     *
     * <p>We merely assert it is either NaN or a finite double — never infinite.
     */
    @Test
    public void testGrowthRateIsNaNOrFiniteOnFirstCall() {
        double rate = collector.collect().getPostGcHeapGrowthRatePerMs();
        assertFalse("growth rate must not be infinite", Double.isInfinite(rate));
    }

    // ── multiple polls ────────────────────────────────────────────────────────

    /**
     * Calling {@code collect()} multiple times must always return non-null.
     */
    @Test
    public void testMultipleCollectsReturnNonNull() {
        for (int i = 0; i < 5; i++) {
            assertNotNull("collect() must be non-null on call " + i, collector.collect());
        }
    }

    /**
     * Each successive snapshot must have a timestamp >= the previous one.
     */
    @Test
    public void testTimestampsNonDecreasing() {
        JvmSnapshot first  = collector.collect();
        JvmSnapshot second = collector.collect();
        assertTrue("second timestamp must be >= first",
                second.getTimestampMs() >= first.getTimestampMs());
    }
}
