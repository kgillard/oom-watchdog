package com.trongus.oom.tests.alert;

import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link QRadarAlertChannel} LEEF message construction.
 *
 * <p>Binds a real UDP socket on localhost to capture the datagram payload,
 * then asserts that the LEEF string contains the expected attributes.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.9
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

    // ── helper: capture one UDP datagram sent by the channel ──────────────────

    private String captureLeef(JvmSnapshot snap) throws IOException {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3_000);
            int port = server.getLocalPort();

            QRadarAlertChannel channel = new QRadarAlertChannel(
                    InetAddress.getLoopbackAddress().getHostAddress(),
                    port,
                    QRadarAlertChannel.Transport.UDP);
            channel.alert(snap);

            byte[] buf = new byte[65_536];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            server.receive(pkt);
            return new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
        }
    }

    // ── threshold context attributes ──────────────────────────────────────────

    @Test
    public void testCritThresholdPctPresent() throws IOException {
        String leef = captureLeef(criticalSnap);
        assertTrue("critThresholdPct must be present", leef.contains("critThresholdPct=90.0"));
    }

    @Test
    public void testHeapMarginPct_negativeWhenOver() throws IOException {
        // heap=92%, crit=90% → margin = 90 - 92 = -2.0
        String leef = captureLeef(criticalSnap);
        assertTrue("heapMarginPct must be -2.0 (heap exceeds critical threshold)",
                leef.contains("heapMarginPct=-2.0"));
    }

    @Test
    public void testThresholdAttributesAbsentWhenNotStamped() throws IOException {
        String leef = captureLeef(unanassessedSnap);
        assertFalse("critThresholdPct must be absent when critThreshold=-1",
                leef.contains("critThresholdPct="));
        assertFalse("heapMarginPct must be absent when critThreshold=-1",
                leef.contains("heapMarginPct="));
    }

    // ── dumpTaken flag ────────────────────────────────────────────────────────

    @Test
    public void testDumpTakenFalseWhenNoPath() throws IOException {
        String leef = captureLeef(criticalSnap);
        assertTrue("dumpTaken=false when no heap dump path", leef.contains("dumpTaken=false"));
    }

    @Test
    public void testDumpTakenTrueWhenPathSet() throws IOException {
        JvmSnapshot withDump = criticalSnap.withHeapDumpPath("/var/dumps/heap.hprof");
        String leef = captureLeef(withDump);
        assertTrue("dumpTaken=true when heap dump path is set", leef.contains("dumpTaken=true"));
        assertTrue("heapDumpPath attribute must be present", leef.contains("heapDumpPath="));
    }

    // ── leef-category and leef-tags ───────────────────────────────────────────

    @Test
    public void testLeefCategoryOverride() throws IOException {
        String leef = captureLeef(criticalSnap);
        assertTrue("cat must use leef-category override", leef.contains("cat=JVM_OOM_hostcontext"));
    }

    @Test
    public void testLeefTagsPresent() throws IOException {
        String leef = captureLeef(criticalSnap);
        assertTrue("tags attribute must be present", leef.contains("tags=env=prod,team=platform"));
    }

    @Test
    public void testDefaultCatWhenLeefCategoryNull() throws IOException {
        String leef = captureLeef(unanassessedSnap);
        assertTrue("cat must default to JVM_OOM_Risk", leef.contains("cat=JVM_OOM_Risk"));
    }

    @Test
    public void testTagsAbsentWhenLeefTagsNull() throws IOException {
        String leef = captureLeef(unanassessedSnap);
        assertFalse("tags attribute must be absent when leefTags is null", leef.contains("tags="));
    }

    // ── nursery attribute ─────────────────────────────────────────────────────

    @Test
    public void testNurseryPctPresentWhenSet() throws IOException {
        JvmSnapshot snap = criticalSnap.toBuilder()
                .nurseryUsedRatio(0.45)
                .build();
        String leef = captureLeef(snap);
        assertTrue("nurseryPct=45.0 must appear in LEEF output", leef.contains("nurseryPct=45.0"));
    }

    @Test
    public void testNurseryPctAbsentWhenNaN() throws IOException {
        String leef = captureLeef(criticalSnap); // criticalSnap has default nurseryUsedRatio = NaN
        assertFalse("nurseryPct= must be absent when nurseryUsedRatio is NaN", leef.contains("nurseryPct="));
    }

    // ── sev mapping ───────────────────────────────────────────────────────────

    @Test
    public void testSevNineForCritical() throws IOException {
        String leef = captureLeef(criticalSnap);
        assertTrue("sev=9 for CRITICAL", leef.contains("sev=9"));
    }

    @Test
    public void testSevFiveForWarning() throws IOException {
        String leef = captureLeef(unanassessedSnap);
        assertTrue("sev=5 for WARNING", leef.contains("sev=5"));
    }
}
