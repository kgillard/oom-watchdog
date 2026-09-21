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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end integration test: OomWatchdog → QRadarAlertChannel → UDP datagram.
 *
 * <p>Verifies that when {@link OomWatchdog} assesses a WARNING or CRITICAL snapshot
 * it actually transmits a well-formed LEEF 2.0 syslog event over UDP — the same
 * path events must travel to reach QRadar in production.
 *
 * <p>A real loopback UDP socket is bound to capture the datagram; no network
 * or QRadar installation is required.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.8
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

    // ── stubs ─────────────────────────────────────────────────────────────────

    private static JvmDiagnosticsCollector criticalCollector() {
        return new JvmDiagnosticsCollector() {
            @Override
            public JvmSnapshot collect() {
                return new JvmSnapshot.Builder()
                        .processName("test-jvm@localhost")
                        .targetName("test-target")
                        .timestampMs(System.currentTimeMillis())
                        .heapUsedBytes(93L * MB)
                        .heapMaxBytes(100L * MB)
                        .heapUsedRatio(0.93)
                        .heapCommittedBytes(100L * MB)
                        .nonHeapUsedBytes(64L * MB)
                        .nonHeapMaxBytes(-1L)
                        .totalGcTimeMs(500L)
                        .jvmUptimeMs(60_000L)
                        .gcOverheadRatio(0.02)
                        .postGcHeapUsedBytes(-1L)
                        .postGcHeapGrowthRatePerMs(Double.NaN)
                        .riskLevel(OomRiskLevel.CRITICAL)
                        .critThreshold(0.90)
                        .diagnosisNotes("[Assessment] CRITICAL – OOM imminent.")
                        .leefCategory("JVM_OOM_Test")
                        .leefTags("env=test,suite=integration")
                        .poolUsedBytes(Collections.<String, Long>emptyMap())
                        .gcCollectionCounts(Collections.<String, Long>emptyMap())
                        .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                        .build();
            }
        };
    }

    /** Pass-through assessor — snapshot already carries the desired risk level. */
    private static RiskAssessor passThroughAssessor() {
        return snap -> snap;
    }

    /** No-op dump service. */
    private static HeapDumpService noopDumpService() {
        return (snap, types) -> Collections.emptyList();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Full pipeline: OomWatchdog polls → CRITICAL snapshot → QRadarAlertChannel
     * transmits a UDP datagram → datagram received and parsed as valid LEEF 2.0.
     */
    @Test
    public void testCriticalEventReachesQRadarViaPipeline() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5_000);
            int port = server.getLocalPort();

            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP);

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

            // Receive the first datagram (must arrive within 5 s)
            byte[] buf = new byte[65_536];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            server.receive(pkt);    // throws SocketTimeoutException if nothing arrives
            watchdog.stop();

            String leef = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);

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
    }

    /**
     * A WARNING-level snapshot must also reach QRadar (alerts fire for WARNING and above).
     */
    @Test
    public void testWarningEventReachesQRadarViaPipeline() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5_000);
            int port = server.getLocalPort();

            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP);

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

            byte[] buf = new byte[65_536];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            server.receive(pkt);
            watchdog.stop();

            String leef = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);

            assertTrue("LEEF:2.0 header must be present", leef.contains("LEEF:2.0|IBM|OomWatchdog|"));
            assertTrue("EventID must reflect WARNING risk level", leef.contains("OOM_WARNING"));
            assertTrue("sev=5 must be present for WARNING", leef.contains("sev=5"));
            assertTrue("riskLevel=WARNING must be present",  leef.contains("riskLevel=WARNING"));
        }
    }

    /**
     * An OK-level snapshot must NOT produce a QRadar event — the watchdog must
     * remain silent when heap is within acceptable bounds.
     */
    @Test
    public void testOkLevelProducesNoQRadarEvent() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            // Short timeout — we expect nothing to arrive
            server.setSoTimeout(400);
            int port = server.getLocalPort();

            QRadarAlertChannel qradar = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP);

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

            // Attempt to receive — must time out (SocketTimeoutException expected)
            byte[] buf = new byte[65_536];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            boolean received = false;
            try {
                server.receive(pkt);
                received = true;
            } catch (java.net.SocketTimeoutException expected) {
                // correct: no datagram should arrive for an OK level
            }
            assertFalse("No QRadar event should be sent when risk level is OK", received);
        }
    }
}
