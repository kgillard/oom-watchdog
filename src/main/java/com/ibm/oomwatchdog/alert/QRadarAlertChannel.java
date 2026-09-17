package com.ibm.oomwatchdog.alert;

import com.ibm.oomwatchdog.model.JvmSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Forwards OOM alerts to IBM QRadar as LEEF 2.0 syslog events over UDP or TCP.
 *
 * <h3>LEEF format used</h3>
 * <pre>
 * &lt;priority&gt;timestamp hostname LEEF:2.0|IBM|OomWatchdog|1.0|&lt;EventID&gt;|\t
 * key=value\tkey=value\t...
 * </pre>
 *
 * <p>LEEF fields follow the IBM QRadar LEEF 2.0 specification:
 * <ul>
 *   <li>Header: {@code LEEF:2.0|Vendor|Product|Version|EventID|}</li>
 *   <li>Attributes: tab-separated {@code key=value} pairs</li>
 *   <li>Syslog header conforms to RFC 3164</li>
 * </ul>
 *
 * <p>The {@code sev} attribute uses QRadar's 1–10 scale:
 * {@code WARNING=5}, {@code CRITICAL=9}, {@code OOM_FIRING=10}.
 *
 * <p>Callers choose UDP (default, fire-and-forget, lower overhead) or TCP
 * (guaranteed delivery) via the constructor.
 */
public final class QRadarAlertChannel implements AlertChannel {

    /** Transport protocol for syslog delivery to QRadar. */
    public enum Transport { UDP, TCP }

    private static final String VENDOR  = "IBM";
    private static final String PRODUCT = "OomWatchdog";
    private static final String VERSION = "1.0";

    // Syslog facility 1 (user-level) + severity 5 (notice) = priority 13
    private static final int SYSLOG_PRIORITY = 13;

    private final String    qradarHost;
    private final int       qradarPort;
    private final Transport transport;
    private final String    localHostname;

    public QRadarAlertChannel(String qradarHost, int qradarPort, Transport transport) {
        this.qradarHost = qradarHost;
        this.qradarPort = qradarPort;
        this.transport  = transport;
        this.localHostname = resolveLocalHostname();
    }

    /** Convenience constructor using UDP transport on port 514. */
    public QRadarAlertChannel(String qradarHost) {
        this(qradarHost, 514, Transport.UDP);
    }

    @Override
    public void alert(JvmSnapshot snapshot) {
        String leefMessage = buildLeefMessage(snapshot);
        byte[] payload     = leefMessage.getBytes(StandardCharsets.UTF_8);

        try {
            if (transport == Transport.UDP) {
                sendUdp(payload);
            } else {
                sendTcp(payload);
            }
        } catch (IOException e) {
            System.err.println("[OomWatchdog][QRadar] Failed to send LEEF event: " + e.getMessage());
        }
    }

    @Override
    public String channelName() {
        return String.format("QRadar(%s:%d/%s)", qradarHost, qradarPort, transport);
    }

    // -------------------------------------------------------------------------
    // LEEF message construction
    // -------------------------------------------------------------------------

    private String buildLeefMessage(JvmSnapshot snap) {
        long mb = 1024L * 1024L;

        // RFC 3164 syslog header
        String syslogTimestamp = rfc3164Timestamp(snap.getTimestampMs());
        String syslogHeader    = String.format("<%d>%s %s ", SYSLOG_PRIORITY, syslogTimestamp, localHostname);

        // LEEF 2.0 header
        String eventId   = "OOM_" + snap.getRiskLevel().name();
        String leefHeader = String.format("LEEF:2.0|%s|%s|%s|%s|", VENDOR, PRODUCT, VERSION, eventId);

        // Severity mapping (QRadar scale 1–10)
        int sev;
        switch (snap.getRiskLevel()) {
            case WARNING:    sev = 5; break;
            case CRITICAL:   sev = 9; break;
            case OOM_FIRING: sev = 10; break;
            default:         sev = 1; break;
        }

        // Tab-separated LEEF attribute payload
        double slope  = snap.getPostGcHeapGrowthRatePerMs();
        String growth = Double.isNaN(slope) ? "N/A"
                : String.format(Locale.US, "%.2f MB/h", slope * 3_600_000.0 / mb);

        // Sanitise free-text fields – tabs and newlines must not appear in LEEF attributes
        String notes = sanitise(snap.getDiagnosisNotes());

        StringBuilder attrs = new StringBuilder();
        attrs.append("sev=").append(sev).append('\t');
        attrs.append("cat=JVM_OOM_Risk").append('\t');
        attrs.append("process=").append(sanitise(snap.getProcessName())).append('\t');
        attrs.append("heapUsedMB=").append(snap.getHeapUsedBytes() / mb).append('\t');
        attrs.append("heapMaxMB=").append(snap.getHeapMaxBytes() / mb).append('\t');
        attrs.append("heapPct=").append(String.format(Locale.US, "%.1f", snap.getHeapUsedRatio() * 100)).append('\t');
        attrs.append("nonHeapUsedMB=").append(snap.getNonHeapUsedBytes() / mb).append('\t');
        attrs.append("gcOverheadPct=").append(String.format(Locale.US, "%.1f", snap.getGcOverheadRatio() * 100)).append('\t');
        attrs.append("totalGcTimeMs=").append(snap.getTotalGcTimeMs()).append('\t');
        attrs.append("postGcGrowth=").append(growth).append('\t');
        attrs.append("riskLevel=").append(snap.getRiskLevel()).append('\t');

        // Append each GC collector as a separate attribute
        for (java.util.Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
            String gcName = sanitise(e.getKey()).replace(' ', '_');
            long   gcTime = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
            attrs.append("gc_").append(gcName).append("_count=").append(e.getValue()).append('\t');
            attrs.append("gc_").append(gcName).append("_timeMs=").append(gcTime).append('\t');
        }

        if (snap.getHeapDumpPath() != null) {
            attrs.append("heapDump=").append(sanitise(snap.getHeapDumpPath())).append('\t');
        }

        attrs.append("msg=").append(notes);

        return syslogHeader + leefHeader + attrs.toString();
    }

    // -------------------------------------------------------------------------
    // Transport helpers
    // -------------------------------------------------------------------------

    private void sendUdp(byte[] payload) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress addr   = InetAddress.getByName(qradarHost);
            DatagramPacket pkt = new DatagramPacket(payload, payload.length, addr, qradarPort);
            socket.send(pkt);
        }
    }

    private void sendTcp(byte[] payload) throws IOException {
        try (Socket socket = new Socket(qradarHost, qradarPort);
             OutputStream out = socket.getOutputStream()) {
            out.write(payload);
            // Syslog over TCP uses newline as frame delimiter (RFC 6587 non-transparent framing)
            out.write('\n');
            out.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** RFC 3164 timestamp: {@code Mmm DD HH:mm:ss} */
    private static String rfc3164Timestamp(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(epochMs));
    }

    private static String resolveLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /** Replace tab, newline and pipe characters to keep LEEF format valid. */
    private static String sanitise(String input) {
        if (input == null) return "";
        return input.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
