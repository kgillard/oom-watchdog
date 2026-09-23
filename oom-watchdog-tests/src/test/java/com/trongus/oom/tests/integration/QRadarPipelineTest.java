package com.trongus.oom.tests.integration;

import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.JvmDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.dump.HeapDumpService;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.RiskAssessor;
import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end integration test: OomWatchdog → QRadarAlertChannel → TCP syslog frame.
 *
 * <p>Verifies that when {@link OomWatchdog} assesses a WARNING or CRITICAL snapshot
 * it actually transmits a well-formed LEEF 2.0 syslog event — the same path events
 * travel to reach QRadar in production.
 *
 * <p>{@link QRadarAlertChannel} automatically switches from UDP to TCP when the
 * destination resolves to a local interface address (loopback or host NIC), because
 * Linux routes same-host UDP through the kernel loopback path without touching the
 * physical NIC. A real loopback TCP server socket is bound to capture the frame;
 * no network or QRadar installation is required.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.28
 * @since 1.7.10
 */
public class QRadarPipelineTest {

    private static final long MB = 1024L * 1024L;

    private OomWatchdog watchdog;

    @After
    public void tearDown() {
        if (watchdog != null) {
            watchdog.stop();
        }
    }

    // ── collectors / assessors / services ────────────────────────────────────

    /** Returns a CRITICAL snapshot on every collect(). */
    private JvmDiagnosticsCollector criticalCollector() {
        return () -> new JvmSnapshot.Builder()
                .targetName("test-target")
                .processName("test-jvm@localhost")
                .timestampMs(System.currentTimeMillis())
                .heapUsedBytes(93L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.93)
                .critThreshold(0.90)
                .heapCommittedBytes(100L * MB)
                .nonHeapUsedBytes(64L * MB)
                .nonHeapMaxBytes(-1L)
                .totalGcTimeMs(500L)
                .jvmUptimeMs(25_000L)
                .gcOverheadRatio(0.02)
                .postGcHeapUsedBytes(-1L)
                .postGcHeapGrowthRatePerMs(Double.NaN)
                .riskLevel(OomRiskLevel.CRITICAL)
                .diagnosisNotes("[Assessment] CRITICAL – OOM imminent.")
                .leefCategory("JVM_OOM_Test")
                .leefTags("env=test,suite=integration")
                .poolUsedBytes(Collections.<String, Long>emptyMap())
                .gcCollectionCounts(Collections.<String, Long>emptyMap())
                .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                .build();
    }

    /** Pass-through assessor: returns the snapshot unchanged. */
    private RiskAssessor passThroughAssessor() {
        return snap -> snap;
    }

    /** No-op dump service. */
    private static HeapDumpService noopDumpService() {
        return (snap, types) -> Collections.emptyList();
    }

    // ── TCP capture helper ────────────────────────────────────────────────────

    /**
     * Binds a TCP server on all interfaces, creates the channel (which auto-upgrades to TCP
     * for local destinations and connects to the real NIC IP), runs the supplied action,
     * and returns the first frame received.
     */
    private String captureOneTcpFrame(ThrowingRunnable action) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        // Bind on 0.0.0.0 so the channel's TCP connect to the resolved real NIC address
        // (not loopback) is accepted by this test server socket.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))) {
            server.setSoTimeout(5_000);
            Future<String> receiver = ex.submit(() -> {
                try (Socket conn = server.accept();
                     InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[65_536];
                    int n = in.read(buf);
                    return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                }
            });
            action.run(server.getLocalPort());
            return receiver.get();
        } finally {
            ex.shutdownNow();
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run(int port) throws Exception;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Full pipeline: OomWatchdog polls → CRITICAL snapshot → QRadarAlertChannel
     * transmits a TCP syslog frame → frame received and parsed as valid LEEF 2.0.
     */
    @Test
    public void testCriticalEventReachesQRadarViaPipeline() throws Exception {
        String leef = captureOneTcpFrame(port -> {
            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP); // auto-upgrades to TCP for local dest

            WatchdogConfig config = WatchdogConfig.defaults()
                    .pollIntervalMs(100L)
                    .criticalHeapThreshold(0.90)
                    .warningHeapThreshold(0.80)
                    .dumpTypes(EnumSet.noneOf(DumpType.class))
                    .build();

            watchdog = new OomWatchdog(
                    config,
                    criticalCollector(),
                    passThroughAssessor(),
                    Collections.singletonList(qradar),
                    noopDumpService());
            watchdog.start();
        });
        if (watchdog != null) watchdog.stop();

        // ── LEEF 2.0 structure ────────────────────────────────────────────
        assertTrue("Payload must start with RFC 3164 syslog priority",
                leef.startsWith("<"));
        assertTrue("Payload must contain LEEF:2.0 header",
                leef.contains("LEEF:2.0|IBM|OomWatchdog|"));
        assertTrue("EventID must reflect CRITICAL risk level",
                leef.contains("OOM_CRITICAL"));

        // ── LEEF attributes ───────────────────────────────────────────────
        assertTrue("sev=9 must be present for CRITICAL",
                leef.contains("sev=9"));
        assertTrue("cat must use leef-category override",
                leef.contains("cat=JVM_OOM_Test"));
        assertTrue("tags attribute must be present",
                leef.contains("tags=env=test,suite=integration"));
        assertTrue("targetJvm attribute must be present",
                leef.contains("targetJvm=test-target"));
        assertTrue("heapPct must be present",
                leef.contains("heapPct="));
        assertTrue("critThresholdPct=90.0 must be present",
                leef.contains("critThresholdPct=90.0"));
        assertTrue("heapMarginPct must be present (heap 93% - crit 90% = -3.0%)",
                leef.contains("heapMarginPct=-3.0"));
        assertTrue("riskLevel=CRITICAL must be present",
                leef.contains("riskLevel=CRITICAL"));
        assertTrue("dumpTaken=false must be present (no dump configured)",
                leef.contains("dumpTaken=false"));
        assertTrue("msg= attribute must be present",
                leef.contains("msg="));
    }

    /**
     * A WARNING-level snapshot must also reach QRadar (alerts fire for WARNING and above).
     */
    @Test
    public void testWarningEventReachesQRadarViaPipeline() throws Exception {
        String leef = captureOneTcpFrame(port -> {
            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP); // auto-upgrades to TCP for local dest

            WatchdogConfig config = WatchdogConfig.defaults()
                    .pollIntervalMs(100L)
                    .criticalHeapThreshold(0.90)
                    .warningHeapThreshold(0.80)
                    .dumpTypes(EnumSet.noneOf(DumpType.class))
                    .build();

            JvmDiagnosticsCollector warnCollector = () ->
                    new JvmSnapshot.Builder()
                            .processName("test-jvm@localhost")
                            .timestampMs(System.currentTimeMillis())
                            .heapUsedBytes(85L * MB)
                            .heapMaxBytes(100L * MB)
                            .heapUsedRatio(0.85)
                            .heapCommittedBytes(100L * MB)
                            .nonHeapUsedBytes(32L * MB)
                            .nonHeapMaxBytes(-1L)
                            .totalGcTimeMs(100L)
                            .jvmUptimeMs(30_000L)
                            .gcOverheadRatio(0.01)
                            .postGcHeapUsedBytes(-1L)
                            .postGcHeapGrowthRatePerMs(Double.NaN)
                            .riskLevel(OomRiskLevel.WARNING)
                            .diagnosisNotes("[Assessment] WARNING – heap elevated.")
                            .poolUsedBytes(Collections.<String, Long>emptyMap())
                            .gcCollectionCounts(Collections.<String, Long>emptyMap())
                            .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                            .build();

            watchdog = new OomWatchdog(
                    config,
                    warnCollector,
                    passThroughAssessor(),
                    Collections.singletonList(qradar),
                    noopDumpService());
            watchdog.start();
        });
        if (watchdog != null) watchdog.stop();

        assertTrue("LEEF:2.0 header must be present", leef.contains("LEEF:2.0|IBM|OomWatchdog|"));
        assertTrue("EventID must reflect WARNING risk level", leef.contains("OOM_WARNING"));
        assertTrue("sev=5 must be present for WARNING", leef.contains("sev=5"));
        assertTrue("riskLevel=WARNING must be present",  leef.contains("riskLevel=WARNING"));
    }

    /**
     * An OK-level snapshot must NOT produce a QRadar event — the watchdog must
     * remain silent when heap is within acceptable bounds.
     */
    @Test
    public void testOkLevelProducesNoQRadarEvent() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))) {
            server.setSoTimeout(400);
            int port = server.getLocalPort();

            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP); // auto-upgrades to TCP for local dest

            WatchdogConfig config = WatchdogConfig.defaults()
                    .pollIntervalMs(100L)
                    .dumpTypes(EnumSet.noneOf(DumpType.class))
                    .build();

            JvmDiagnosticsCollector okCollector = () ->
                    new JvmSnapshot.Builder()
                            .processName("ok-jvm@localhost")
                            .timestampMs(System.currentTimeMillis())
                            .heapUsedBytes(40L * MB)
                            .heapMaxBytes(100L * MB)
                            .heapUsedRatio(0.40)
                            .heapCommittedBytes(100L * MB)
                            .nonHeapUsedBytes(32L * MB)
                            .nonHeapMaxBytes(-1L)
                            .totalGcTimeMs(50L)
                            .jvmUptimeMs(10_000L)
                            .gcOverheadRatio(0.005)
                            .postGcHeapUsedBytes(-1L)
                            .postGcHeapGrowthRatePerMs(Double.NaN)
                            .riskLevel(OomRiskLevel.OK)
                            .diagnosisNotes("")
                            .poolUsedBytes(Collections.<String, Long>emptyMap())
                            .gcCollectionCounts(Collections.<String, Long>emptyMap())
                            .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                            .build();

            watchdog = new OomWatchdog(
                    config,
                    okCollector,
                    passThroughAssessor(),
                    Collections.singletonList(qradar),
                    noopDumpService());
            watchdog.start();

            // Let a few poll cycles run
            TimeUnit.MILLISECONDS.sleep(300L);
            watchdog.stop();

            // Attempt to accept a connection — must time out
            boolean connected = false;
            try {
                Future<Boolean> f = ex.submit(() -> { server.accept(); return true; });
                f.get(400, TimeUnit.MILLISECONDS);
                connected = true;
            } catch (Exception expected) {
                // correct: no connection should arrive for an OK level
            }
            assertFalse("No QRadar event should be sent when risk level is OK", connected);
        } finally {
            ex.shutdownNow();
        }
    }
}
