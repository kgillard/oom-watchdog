package com.trongus.oom.tests.integration;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Integration tests for the full {@link OomWatchdog} poll/assess/alert pipeline.
 *
 * <p>These tests wire together real (non-mocked) implementations:
 * {@link OomWatchdog}, a stub {@link JvmDiagnosticsCollector}, a stub
 * {@link RiskAssessor}, and a stub {@link HeapDumpService}, with an in-memory
 * {@link AlertChannel} recorder.  No network or file I/O is performed.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.3
 * @since 1.0.0
 * @see OomWatchdog
 */
public class OomWatchdogIntegrationTest {

    private static final long MB = 1024L * 1024L;

    // ── in-memory stubs ───────────────────────────────────────────────────────

    /** Thread-safe list that records every snapshot passed to {@code alert()}. */
    private static class RecordingAlertChannel implements AlertChannel {
        final List<JvmSnapshot> received = new ArrayList<>();
        final CountDownLatch latch;

        RecordingAlertChannel(int expectedAlerts) {
            latch = new CountDownLatch(expectedAlerts);
        }

        @Override
        public synchronized void alert(JvmSnapshot snap) {
            received.add(snap);
            latch.countDown();
        }

        @Override
        public String channelName() { return "Recording"; }
    }

    /** Collector that always returns a snapshot at the configured risk level. */
    private static class FixedCollector implements JvmDiagnosticsCollector {
        private final OomRiskLevel level;
        private final AtomicInteger callCount = new AtomicInteger(0);

        FixedCollector(OomRiskLevel level) { this.level = level; }

        @Override
        public JvmSnapshot collect() {
            callCount.incrementAndGet();
            return new JvmSnapshot.Builder()
                    .processName("integration-test@host")
                    .timestampMs(System.currentTimeMillis())
                    .heapUsedBytes(90L * MB)
                    .heapCommittedBytes(100L * MB)
                    .heapMaxBytes(100L * MB)
                    .heapUsedRatio(0.90)
                    .nonHeapUsedBytes(50L * MB)
                    .nonHeapMaxBytes(-1L)
                    .poolUsedBytes(Collections.<String, Long>emptyMap())
                    .gcCollectionCounts(Collections.<String, Long>emptyMap())
                    .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                    .totalGcTimeMs(0L)
                    .jvmUptimeMs(60_000L)
                    .gcOverheadRatio(0.05)
                    .postGcHeapUsedBytes(-1L)
                    .postGcHeapGrowthRatePerMs(Double.NaN)
                    .riskLevel(level)
                    .diagnosisNotes("integration-test")
                    .build();
        }

        int getCallCount() { return callCount.get(); }
    }

    /** Pass-through assessor — returns the snapshot unchanged. */
    private static class PassThroughAssessor implements RiskAssessor {
        @Override
        public JvmSnapshot assess(JvmSnapshot snap) { return snap; }
    }

    /** Dump service that records which snapshots triggered a dump. */
    private static class RecordingDumpService implements HeapDumpService {
        final List<JvmSnapshot> dumped = new ArrayList<>();

        @Override
        public synchronized List<String> dump(JvmSnapshot snap, List<DumpType> types) {
            dumped.add(snap);
            return Collections.singletonList("/tmp/oom-integration-test.hprof");
        }
    }

    // ── test state ────────────────────────────────────────────────────────────

    private OomWatchdog watchdog;
    private RecordingAlertChannel alertChannel;
    private FixedCollector collector;
    private RecordingDumpService dumpService;
    private WatchdogConfig config;

    @Before
    public void setUp() {
        config = WatchdogConfig.defaults()
                .pollIntervalMs(100L)       // minimum allowed; fast enough for tests
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .dumpTypes(EnumSet.of(DumpType.THREAD))
                .build();
    }

    @After
    public void tearDown() {
        if (watchdog != null) {
            watchdog.stop();
        }
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void buildWatchdog(OomRiskLevel level, int latchCount) {
        collector    = new FixedCollector(level);
        alertChannel = new RecordingAlertChannel(latchCount);
        dumpService  = new RecordingDumpService();

        watchdog = new OomWatchdog(
                config,
                collector,
                new PassThroughAssessor(),
                Collections.<AlertChannel>singletonList(alertChannel),
                dumpService);
    }

    // ── start / stop ──────────────────────────────────────────────────────────

    /**
     * A watchdog started with an OK-level collector must not fire any alerts
     * within a short observation window.
     */
    @Test
    public void testNoAlertsWhenRiskIsOk() throws InterruptedException {
        buildWatchdog(OomRiskLevel.OK, 1 /* never expected to count down */);
        watchdog.start();
        // Let a few poll cycles pass — no alert should arrive
        Thread.sleep(250L);
        watchdog.stop();
        assertTrue("No alerts should fire for OK risk level",
                alertChannel.received.isEmpty());
    }

    /**
     * Starting the watchdog twice must be idempotent — no exception and only
     * one polling loop runs.
     */
    @Test
    public void testStartIsIdempotent() throws InterruptedException {
        buildWatchdog(OomRiskLevel.OK, 1);
        watchdog.start();
        watchdog.start(); // second call must not throw
        Thread.sleep(150L);
        watchdog.stop();
        // success = no exception thrown
    }

    // ── alert channel invocation ──────────────────────────────────────────────

    /**
     * When the collector returns WARNING, the alert channel must receive at
     * least one notification within a reasonable timeout.
     */
    @Test
    public void testAlertFiredForWarningLevel() throws InterruptedException {
        buildWatchdog(OomRiskLevel.WARNING, 1);
        watchdog.start();
        boolean arrived = alertChannel.latch.await(3, TimeUnit.SECONDS);
        watchdog.stop();
        assertTrue("Alert should fire for WARNING risk within 3 seconds", arrived);
        assertFalse("At least one alert should be recorded", alertChannel.received.isEmpty());
    }

    /**
     * When the collector returns CRITICAL, the alert channel must fire.
     */
    @Test
    public void testAlertFiredForCriticalLevel() throws InterruptedException {
        buildWatchdog(OomRiskLevel.CRITICAL, 1);
        watchdog.start();
        boolean arrived = alertChannel.latch.await(3, TimeUnit.SECONDS);
        watchdog.stop();
        assertTrue("Alert should fire for CRITICAL risk within 3 seconds", arrived);
    }

    // ── risk level tracking ───────────────────────────────────────────────────

    /**
     * {@code getLastRiskLevel()} must return the last level assessed by the
     * pass-through assessor.
     */
    @Test
    public void testGetLastRiskLevelReflectsAssessment() throws InterruptedException {
        buildWatchdog(OomRiskLevel.WARNING, 1);
        watchdog.start();
        alertChannel.latch.await(3, TimeUnit.SECONDS);
        watchdog.stop();
        assertSame("lastRiskLevel should be WARNING",
                OomRiskLevel.WARNING, watchdog.getLastRiskLevel());
    }

    // ── dump triggered at CRITICAL ────────────────────────────────────────────

    /**
     * The dump service must be invoked exactly once when the first CRITICAL event
     * fires in an episode (dump-storm prevention).
     */
    @Test
    public void testDumpTriggeredOnCritical() throws InterruptedException {
        buildWatchdog(OomRiskLevel.CRITICAL, 2);
        watchdog.start();
        // Wait for at least two alerts so the second poll has run
        alertChannel.latch.await(3, TimeUnit.SECONDS);
        watchdog.stop();

        assertEquals("Dump service should be called exactly once per episode",
                1, dumpService.dumped.size());
    }

    /**
     * When a dump is triggered, the alert snapshot must include the dump path
     * returned by the dump service.
     */
    @Test
    public void testAlertSnapshotContainsDumpPath() throws InterruptedException {
        buildWatchdog(OomRiskLevel.CRITICAL, 1);
        watchdog.start();
        alertChannel.latch.await(3, TimeUnit.SECONDS);
        watchdog.stop();

        assertFalse("Should have received at least one alert", alertChannel.received.isEmpty());
        // The alert that contains the dump path is the one fired after the dump
        boolean foundDumpPath = false;
        for (JvmSnapshot snap : alertChannel.received) {
            if (snap.getHeapDumpPath() != null && !snap.getHeapDumpPath().isEmpty()) {
                foundDumpPath = true;
                break;
            }
        }
        assertTrue("At least one alerted snapshot should carry the dump path", foundDumpPath);
    }

    // ── collector is polled ───────────────────────────────────────────────────

    /**
     * The collector must be called at least twice within the observation window,
     * confirming the scheduler is running repeatedly.
     */
    @Test
    public void testCollectorPolledMultipleTimes() throws InterruptedException {
        buildWatchdog(OomRiskLevel.OK, 1);
        watchdog.start();
        Thread.sleep(300L);  // 6+ poll cycles at 50 ms
        watchdog.stop();
        assertTrue("Collector should be polled at least twice",
                collector.getCallCount() >= 2);
    }
}
