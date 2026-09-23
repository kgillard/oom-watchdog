package com.trongus.oom.alert;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.TimeZone;
import java.util.logging.Logger;

/**
 * Forwards OOM alerts to IBM QRadar as LEEF 2.0 syslog events over UDP or TCP.
 *
 * <h2>LEEF format used</h2>
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
 * <h2>LEEF attributes emitted</h2>
 * <table border="1">
 *   <caption>LEEF event attributes</caption>
 *   <tr><th>Attribute</th><th>Description</th></tr>
 *   <tr><td>{@code sev}</td><td>QRadar severity (1–10): WARNING=5, CRITICAL=9, OOM_FIRING=10.</td></tr>
 *   <tr><td>{@code cat}</td><td>Event category; defaults to {@code "JVM_OOM_Risk"}, overridden
 *       per-target via {@link com.trongus.oom.remote.TargetDescriptor#getLeefCategory()}.</td></tr>
 *   <tr><td>{@code targetJvm}</td><td>Name of the monitored remote JVM target; omitted in
 *       self-monitoring mode.</td></tr>
 *   <tr><td>{@code tags}</td><td>Free-text tag string from the target's {@code leef-tags}
 *       property (e.g. {@code "env=prod,team=platform"}); omitted when not configured.</td></tr>
 *   <tr><td>{@code process}</td><td>JVM process name from {@code RuntimeMXBean.getName()}.</td></tr>
 *   <tr><td>{@code heapUsedMB}</td><td>Heap memory currently in use, in megabytes.</td></tr>
 *   <tr><td>{@code heapMaxMB}</td><td>Maximum heap size ({@code -Xmx}), in megabytes.</td></tr>
 *   <tr><td>{@code heapPct}</td><td>Heap utilisation percentage (0–100).</td></tr>
 *   <tr><td>{@code critThresholdPct}</td><td>Active critical heap threshold as a percentage;
 *       present only when threshold metadata is available in the snapshot.</td></tr>
 *   <tr><td>{@code heapMarginPct}</td><td>Percentage headroom remaining before the critical
 *       threshold is reached ({@code critThresholdPct − heapPct}).</td></tr>
 *   <tr><td>{@code dumpTaken}</td><td>{@code true} if a diagnostic dump was captured for
 *       this alert event; {@code false} otherwise.</td></tr>
 *   <tr><td>{@code nonHeapUsedMB}</td><td>Non-heap (Metaspace + Code Cache) in use, in megabytes.</td></tr>
 *   <tr><td>{@code gcOverheadPct}</td><td>Fraction of JVM uptime spent in GC, as a percentage.</td></tr>
 *   <tr><td>{@code totalGcTimeMs}</td><td>Cumulative GC time since JVM start, in milliseconds.</td></tr>
 *   <tr><td>{@code postGcGrowth}</td><td>OLS regression slope of post-GC heap samples, in MB/h;
 *       {@code N/A} when insufficient data.</td></tr>
 *   <tr><td>{@code nurseryPct}</td><td>Nursery/young-gen utilisation as a percentage of heap max;
 *       omitted when no nursery pools are found.</td></tr>
 *   <tr><td>{@code riskLevel}</td><td>Assessed {@link com.trongus.oom.model.OomRiskLevel} name.</td></tr>
 *   <tr><td>{@code gc_<name>_count}</td><td>Cumulative collection count per GC collector.</td></tr>
 *   <tr><td>{@code gc_<name>_timeMs}</td><td>Cumulative collection time per GC collector, in ms.</td></tr>
 *   <tr><td>{@code heapDumpPath}</td><td>Semicolon-separated dump file paths; present only
 *       when a dump was captured.</td></tr>
 *   <tr><td>{@code msg}</td><td>Sanitised human-readable assessment and diagnosis notes.</td></tr>
 * </table>
 *
 * <p>Callers choose UDP (default, fire-and-forget, lower overhead) or TCP
 * (guaranteed delivery) via the constructor.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.25
 * @since 1.0.0
 * @see AlertChannel
 * @see com.trongus.oom.model.JvmSnapshot
 */
public final class QRadarAlertChannel implements AlertChannel {

    private static final Logger LOG = WatchdogLogger.forClass(QRadarAlertChannel.class);

    /** Transport protocol for syslog delivery to QRadar. */
    public enum Transport { UDP, TCP }

    private static final String VENDOR  = "IBM";
    private static final String PRODUCT = "OomWatchdog";
    private static final String VERSION = "1.1";

    // Syslog facility 1 (user-level) + severity 5 (notice) = priority 13
    private static final int SYSLOG_PRIORITY = 13;

    /** Maximum safe UDP syslog payload (bytes). IPv4 min MTU 576 − IP/UDP headers = 548;
     *  practical Ethernet MTU gives ~65007 but we cap conservatively at 64 KB. */
    private static final int MAX_UDP_PAYLOAD = 65_007;

    /** TCP socket connect and read timeout in milliseconds (5 s). */
    private static final int TCP_TIMEOUT_MS = 5_000;

    private final String    qradarHost;
    private final int       qradarPort;
    private final Transport transport;
    private final String    localHostname;

    /**
     * {@code true} when {@link #qradarHost} resolves to an IP address assigned to a local
     * network interface on this machine (including both loopback and the host's own NIC IPs).
     * Linux routes all same-host UDP through the loopback path regardless of which local IP
     * is used as destination, so UDP datagrams never appear on the physical NIC and QRadar
     * never receives them. TCP is used automatically in this case.
     * @since 1.7.13.24
     */
    private final boolean localDestination;

    /**
     * Constructs a channel targeting a specific QRadar syslog receiver.
     *
     * @param qradarHost hostname or IP address of the QRadar syslog receiver
     * @param qradarPort syslog destination port (typically 514)
     * @param transport  syslog transport protocol ({@link Transport#UDP} or {@link Transport#TCP})
     */
    public QRadarAlertChannel(String qradarHost, int qradarPort, Transport transport) {
        this.qradarHost       = qradarHost;
        this.qradarPort       = qradarPort;
        this.transport        = transport;
        this.localHostname    = resolveLocalHostname();
        this.localDestination = isLocalAddress(qradarHost);
        if (this.localDestination && transport == Transport.UDP) {
            WatchdogLogger.warning(LOG,
                    "QRadar host [{0}] is a local interface address. Linux routes same-host UDP " +
                    "through the loopback path — datagrams never reach the physical NIC and " +
                    "QRadar will not receive them. Switching to TCP automatically. " +
                    "Add --qradar-tcp to your command line to suppress this warning.",
                    qradarHost);
        }
    }

    /**
     * Convenience constructor using UDP transport on port 514.
     *
     * @param qradarHost hostname or IP address of the QRadar syslog receiver
     */
    public QRadarAlertChannel(String qradarHost) {
        this(qradarHost, 514, Transport.UDP);
    }

    /**
     * Formats the snapshot as a LEEF 2.0 syslog message and transmits it to the configured
     * QRadar host using the selected transport.
     *
     * @param snapshot the assessed {@link JvmSnapshot} to forward; must not be {@code null}
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        String target = snapshot.getTargetName() != null ? snapshot.getTargetName()
                      : snapshot.getProcessName() != null ? snapshot.getProcessName() : "unknown";
        WatchdogLogger.fine(LOG,
                "Building LEEF event: target=[{0}] riskLevel=[{1}] destination=[{2}:{3}/{4}]",
                target, snapshot.getRiskLevel(), qradarHost, qradarPort, transport);

        String leefMessage = buildLeefMessage(snapshot);
        byte[] payload     = leefMessage.getBytes(StandardCharsets.UTF_8);

        // Log at INFO so operators can confirm LEEF events are being sent without
        // needing FINEST level — critical for troubleshooting QRadar delivery.
        WatchdogLogger.info(LOG, "LEEF payload ({0} bytes) \u2192 {1}:{2}/{3}: {4}",
                payload.length, qradarHost, qradarPort, transport, leefMessage);

        try {
            // On same-host deployments Linux routes UDP to local IPs through the kernel
            // loopback path — the packet never reaches the physical NIC and QRadar (which
            // listens on the physical interface) never receives it. Fall back to TCP, which
            // uses a proper socket pair that QRadar's ecs syslog listener accepts.
            if (transport == Transport.UDP && !localDestination) {
                sendUdp(payload);
            } else {
                sendTcp(payload);
            }
            WatchdogLogger.info(LOG,
                    "LEEF event sent: target=[{0}] riskLevel=[{1}] destination=[{2}:{3}/{4}] bytes={5}",
                    target, snapshot.getRiskLevel(), qradarHost, qradarPort, transport, payload.length);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG, e,
                    "Failed to send LEEF event to [{0}:{1}/{2}] for target [{3}]: {4}",
                    qradarHost, qradarPort, transport, target, e.getMessage());
        }
    }

    /**
     * Returns a human-readable name identifying this channel, including host, port, and transport.
     *
     * @return channel name string in the form {@code "QRadar(host:port/transport)"}
     */
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
        // Use per-target leef-category override when present, otherwise fall back to default
        String cat = (snap.getLeefCategory() != null) ? snap.getLeefCategory() : "JVM_OOM_Risk";
        attrs.append("cat=").append(sanitise(cat)).append('\t');
        if (snap.getTargetName() != null) {
            attrs.append("targetJvm=").append(sanitise(snap.getTargetName())).append('\t');
        }
        if (snap.getLeefTags() != null) {
            attrs.append("tags=").append(sanitise(snap.getLeefTags())).append('\t');
        }
        attrs.append("process=").append(sanitise(snap.getProcessName())).append('\t');
        attrs.append("heapUsedMB=").append(snap.getHeapUsedBytes() / mb).append('\t');
        attrs.append("heapMaxMB=").append(snap.getHeapMaxBytes() / mb).append('\t');
        double heapPct = snap.getHeapUsedRatio() * 100;
        attrs.append("heapPct=").append(String.format(Locale.US, "%.1f", heapPct)).append('\t');
        // Threshold context: how far from critical, and whether a dump was taken
        if (snap.getCritThreshold() >= 0) {
            double critPct   = snap.getCritThreshold() * 100;
            double marginPct = critPct - heapPct;
            attrs.append("critThresholdPct=").append(String.format(Locale.US, "%.1f", critPct)).append('\t');
            attrs.append("heapMarginPct=").append(String.format(Locale.US, "%.1f", marginPct)).append('\t');
        }
        attrs.append("dumpTaken=").append(snap.getHeapDumpPath() != null ? "true" : "false").append('\t');
        attrs.append("nonHeapUsedMB=").append(snap.getNonHeapUsedBytes() / mb).append('\t');
        attrs.append("gcOverheadPct=").append(String.format(Locale.US, "%.1f", snap.getGcOverheadRatio() * 100)).append('\t');
        attrs.append("totalGcTimeMs=").append(snap.getTotalGcTimeMs()).append('\t');
        attrs.append("postGcGrowth=").append(growth).append('\t');
        if (!Double.isNaN(snap.getNurseryUsedRatio())) {
            attrs.append("nurseryPct=").append(String.format(Locale.US, "%.1f", snap.getNurseryUsedRatio() * 100)).append('\t');
        }
        attrs.append("riskLevel=").append(snap.getRiskLevel()).append('\t');

        // Append each GC collector as a separate attribute
        for (java.util.Map.Entry<String, Long> e : snap.getGcCollectionCounts().entrySet()) {
            String gcName = sanitise(e.getKey()).replace(' ', '_');
            long   gcTime = snap.getGcCollectionTimesMs().getOrDefault(e.getKey(), 0L);
            attrs.append("gc_").append(gcName).append("_count=").append(e.getValue()).append('\t');
            attrs.append("gc_").append(gcName).append("_timeMs=").append(gcTime).append('\t');
        }

        if (snap.getHeapDumpPath() != null) {
            attrs.append("heapDumpPath=").append(sanitise(snap.getHeapDumpPath())).append('\t');
        }

        attrs.append("msg=").append(notes);

        return syslogHeader + leefHeader + attrs.toString();
    }

    // -------------------------------------------------------------------------
    // Transport helpers
    // -------------------------------------------------------------------------

    /**
     * Sends a LEEF payload over UDP, truncating to {@link #MAX_UDP_PAYLOAD} bytes if necessary.
     *
     * <p>When the destination resolves to a loopback address (e.g. {@code 127.0.0.1}),
     * the socket is explicitly bound to the machine's first non-loopback interface address
     * so that QRadar sees the real host IP as the UDP source — loopback-sourced syslog
     * datagrams are silently discarded by QRadar's log-source auto-discovery engine.
     *
     * @param payload UTF-8 encoded syslog message bytes
     * @throws IOException if the datagram socket cannot be created or the packet cannot be sent
     */
    private void sendUdp(byte[] payload) throws IOException {
        // Truncate oversized payloads to the safe UDP limit to avoid silent fragmentation/drop.
        byte[] safe = payload.length > MAX_UDP_PAYLOAD
                ? Arrays.copyOf(payload, MAX_UDP_PAYLOAD)
                : payload;
        InetAddress[] addrs = InetAddress.getAllByName(qradarHost);
        IOException lastEx = null;
        for (InetAddress addr : addrs) {
            // When the destination is loopback, bind the source socket to the machine's real
            // non-loopback interface address so the UDP source IP visible to QRadar is the
            // actual host IP, not 127.0.0.1. QRadar uses the source IP for log-source matching
            // and will discard datagrams sourced from loopback.
            // NOTE: the destination address is always the configured qradarHost. If QRadar's
            // syslog listener does not bind to 127.0.0.1 (it typically binds to the physical
            // interface IP), use --qradar-host <real-ip> instead of 127.0.0.1.
            InetAddress bindAddr = addr.isLoopbackAddress()
                    ? resolveNonLoopbackAddress(addr instanceof Inet6Address)
                    : null;

            DatagramSocket socket;
            if (bindAddr != null) {
                socket = new DatagramSocket(new InetSocketAddress(bindAddr, 0));
            } else if (addr instanceof Inet6Address) {
                socket = new DatagramSocket(new InetSocketAddress("::", 0));
            } else {
                socket = new DatagramSocket();
            }
            try (DatagramSocket s = socket) {
                DatagramPacket pkt = new DatagramPacket(safe, safe.length, addr, qradarPort);
                s.send(pkt);
                return; // first successful send wins
            } catch (IOException e) {
                lastEx = e;
            }
        }
        // All addresses failed — rethrow the last exception so the caller can log it.
        if (lastEx != null) throw lastEx;
    }

    /**
     * Returns the first non-loopback, non-link-local IP address of the matching address
     * family found on any up network interface, or {@code null} if none is found.
     *
     * @param ipv6 {@code true} to look for an IPv6 address; {@code false} for IPv4
     * @return a non-loopback bind address, or {@code null} to fall back to the wildcard
     * @since 1.7.13.22
     */
    private static InetAddress resolveNonLoopbackAddress(boolean ipv6) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> iaddrs = iface.getInetAddresses();
                while (iaddrs.hasMoreElements()) {
                    InetAddress a = iaddrs.nextElement();
                    boolean isV6 = a instanceof Inet6Address;
                    if (isV6 != ipv6) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    return a;
                }
            }
        } catch (Exception ignored) {
            // Fall through: return null and let the caller use the wildcard bind
        }
        return null;
    }

    /**
     * Sends a LEEF payload over TCP using a newline frame delimiter (RFC 6587).
     *
     * @param payload UTF-8 encoded syslog message bytes
     * @throws IOException if connection or write fails
     */
    private void sendTcp(byte[] payload) throws IOException {
        // Use an explicit connect timeout and SO_TIMEOUT to prevent the watchdog
        // poll thread from blocking indefinitely on a slow or unreachable QRadar host.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(qradarHost, qradarPort), TCP_TIMEOUT_MS);
            socket.setSoTimeout(TCP_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            // Syslog over TCP uses newline as frame delimiter (RFC 6587 non-transparent framing)
            out.write('\n');
            out.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Formats a timestamp as an RFC 3164 syslog date string ({@code Mmm DD HH:mm:ss}).
     *
     * @param epochMs epoch milliseconds to format
     * @return formatted RFC 3164 timestamp string
     */
    private static String rfc3164Timestamp(long epochMs) {
        // Locale.US mandated by RFC 3164 — month abbreviations must be English.
        // TimeZone.getDefault() is intentional: syslog timestamps are local time per the RFC.
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getDefault());
        return sdf.format(new Date(epochMs));
    }

    /**
     * Resolves the local hostname for use in the syslog header, falling back to {@code "localhost"}.
     *
     * @return local hostname string; never {@code null}
     */
    private static String resolveLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * Returns {@code true} if {@code host} resolves to any IP address currently assigned
     * to a local network interface on this machine (loopback or any NIC).
     *
     * <p>Linux routes UDP packets whose destination is a local address entirely through the
     * kernel without ever touching the physical NIC, so {@code tcpdump} on the NIC sees
     * nothing and QRadar never receives the datagram. This check is used to switch
     * automatically to TCP for same-host deployments.
     *
     * @param host hostname or IP string to test
     * @return {@code true} if the host resolves to a local interface address
     * @since 1.7.13.24
     */
    private static boolean isLocalAddress(String host) {
        try {
            InetAddress target = InetAddress.getByName(host);
            if (target.isLoopbackAddress()) return true;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                Enumeration<InetAddress> iaddrs = iface.getInetAddresses();
                while (iaddrs.hasMoreElements()) {
                    if (iaddrs.nextElement().equals(target)) return true;
                }
            }
        } catch (Exception ignored) { /* treat as non-local */ }
        return false;
    }

    /**
     * Sanitises a string for inclusion in a LEEF attribute value by replacing tab,
     * newline, carriage-return, and pipe characters with safe alternatives.
     *
     * @param input the raw string value; may be {@code null}
     * @return sanitised string safe for LEEF encoding; empty string when {@code input} is {@code null}
     */
    private static String sanitise(String input) {
        if (input == null) return "";
        return input.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
