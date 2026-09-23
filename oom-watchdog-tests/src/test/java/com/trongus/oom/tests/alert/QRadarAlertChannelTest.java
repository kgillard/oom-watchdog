package com.trongus.oom.tests.alert;

import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QRadarAlertChannel} LEEF message construction.
 *
 * <p>Binds a real TCP server socket on localhost to capture the payload,
 * then asserts that the LEEF string contains the expected attributes.
 * TCP is used because {@link QRadarAlertChannel} automatically switches to
 * TCP when the destination resolves to a local interface address (loopback
 * or host NIC), since Linux routes same-host UDP through the kernel without
 * touching the physical NIC.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.25
 * @since 1.7.1
 */
public class QRadarAlertChannelTest {

    private static final long MB = 1024L * 1024L;

    /** Snapshot with critThreshold stamped and a known heapUsedRatio. */
    private JvmSnapshot criticalSnap;

    /** Snapshot without critThreshold stamped (pre-assessment path). */
    private JvmSnapshot unanassessedSnap;

    @Before
    public void setUp() {
        criticalSnap = new JvmSnapshot.Builder()
                .processName("1@testhost")
                .timestampMs(1_000_000L)
                .heapUsedBytes(92L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.92)
                .critThreshold(0.90)
                .riskLevel(OomRiskLevel.CRITICAL)
                .diagnosisNotes("test")
                .leefCategory("JVM_OOM_hostcontext")
                .leefTags("env=prod,team=platform")
                .build();

        unanassessedSnap = new JvmSnapshot.Builder()
                .processName("2@testhost")
                .timestampMs(1_000_000L)
                .heapUsedBytes(80L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.80)
                .riskLevel(OomRiskLevel.WARNING)
                .diagnosisNotes("test")
                .build();
    }

    // ── helper: capture one TCP syslog frame sent by the channel ─────────────
    // QRadarAlertChannel automatically uses TCP when the destination is a local
    // interface address (loopback or host NIC) because Linux routes same-host
    // UDP entirely through the kernel — tcpdump on the NIC sees nothing.

    private String captureLeef(JvmSnapshot snap) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3_000);
            int port = server.getLocalPort();

            QRadarAlertChannel channel = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP); // channel auto-upgrades to TCP for local dest

            Future<String> receiver = ex.submit(() -> {
                try (Socket conn = server.accept();
                     InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[65_536];
                    int n = in.read(buf);
                    return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
                }
            });

            channel.alert(snap);
            return receiver.get();
        } finally {
            ex.shutdownNow();
        }
    }

    // ── threshold context attributes ──────────────────────────────────────────

    @Test
    public void testCritThresholdPctPresent() throws Exception {
        String leef = captureLeef(criticalSnap);
        assertTrue("critThresholdPct must be present", leef.contains("critThresholdPct=90.0"));
    }

    @Test
    public void testHeapMarginPct_negativeWhenOver() throws Exception {
        // heap=92%, crit=90% → margin = 90 - 92 = -2.0
        String leef = captureLeef(criticalSnap);
        assertTrue("heapMarginPct must be -2.0 (heap exceeds critical threshold)",
                leef.contains("heapMarginPct=-2.0"));
    }

    @Test
    public void testThresholdAttributesAbsentWhenNotStamped() throws Exception {
        String leef = captureLeef(unanassessedSnap);
        assertFalse("critThresholdPct must be absent when critThreshold=-1",
                leef.contains("critThresholdPct="));
        assertFalse("heapMarginPct must be absent when critThreshold=-1",
                leef.contains("heapMarginPct="));
    }

    // ── dumpTaken flag ────────────────────────────────────────────────────────

    @Test
    public void testDumpTakenFalseWhenNoPath() throws Exception {
        String leef = captureLeef(criticalSnap);
        assertTrue("dumpTaken=false when no heap dump path", leef.contains("dumpTaken=false"));
    }

    @Test
    public void testDumpTakenTrueWhenPathSet() throws Exception {
        JvmSnapshot withDump = criticalSnap.withHeapDumpPath("/var/dumps/heap.hprof");
        String leef = captureLeef(withDump);
        assertTrue("dumpTaken=true when heap dump path is set", leef.contains("dumpTaken=true"));
        assertTrue("heapDumpPath attribute must be present", leef.contains("heapDumpPath="));
    }

    // ── leef-category and leef-tags ───────────────────────────────────────────

    @Test
    public void testLeefCategoryOverride() throws Exception {
        String leef = captureLeef(criticalSnap);
        assertTrue("cat must use leef-category override", leef.contains("cat=JVM_OOM_hostcontext"));
    }

    @Test
    public void testLeefTagsPresent() throws Exception {
        String leef = captureLeef(criticalSnap);
        assertTrue("tags attribute must be present", leef.contains("tags=env=prod,team=platform"));
    }

    @Test
    public void testDefaultCatWhenLeefCategoryNull() throws Exception {
        String leef = captureLeef(unanassessedSnap);
        assertTrue("cat must default to JVM_OOM_Risk", leef.contains("cat=JVM_OOM_Risk"));
    }

    @Test
    public void testTagsAbsentWhenLeefTagsNull() throws Exception {
        String leef = captureLeef(unanassessedSnap);
        assertFalse("tags attribute must be absent when leefTags is null", leef.contains("tags="));
    }

    // ── nursery attribute ─────────────────────────────────────────────────────

    @Test
    public void testNurseryPctPresentWhenSet() throws Exception {
        JvmSnapshot snap = criticalSnap.toBuilder()
                .nurseryUsedRatio(0.45)
                .build();
        String leef = captureLeef(snap);
        assertTrue("nurseryPct=45.0 must appear in LEEF output", leef.contains("nurseryPct=45.0"));
    }

    @Test
    public void testNurseryPctAbsentWhenNaN() throws Exception {
        String leef = captureLeef(criticalSnap); // criticalSnap has default nurseryUsedRatio = NaN
        assertFalse("nurseryPct= must be absent when nurseryUsedRatio is NaN", leef.contains("nurseryPct="));
    }

    // ── sev mapping ───────────────────────────────────────────────────────────

    @Test
    public void testSevNineForCritical() throws Exception {
        String leef = captureLeef(criticalSnap);
        assertTrue("sev=9 for CRITICAL", leef.contains("sev=9"));
    }

    @Test
    public void testSevFiveForWarning() throws Exception {
        String leef = captureLeef(unanassessedSnap);
        assertTrue("sev=5 for WARNING", leef.contains("sev=5"));
    }
}
