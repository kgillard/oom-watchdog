package com.trongus.oom.examples;

import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * Example 05 — QRadar LEEF 2.0 syslog integration (UDP and TCP).
 *
 * <h2>What this example shows</h2>
 * <ul>
 *   <li>Forwarding OOM alerts to IBM QRadar SIEM as LEEF 2.0 formatted syslog events.</li>
 *   <li>Choosing between UDP (low-overhead, fire-and-forget) and TCP (guaranteed delivery,
 *       5-second timeout).</li>
 *   <li>How to configure the QRadar host and port at startup time.</li>
 *   <li>Running QRadar alongside other alert channels so that the same event is
 *       simultaneously visible on the console and in the SIEM.</li>
 * </ul>
 *
 * <h2>LEEF 2.0 format</h2>
 * <p>Each alert is emitted as a LEEF 2.0 syslog message with the following structure:
 * <pre>
 * &lt;13&gt;{RFC3164-timestamp} {hostname} LEEF:2.0|IBM|OomWatchdog|1.0|{EventID}|
 * devTime={...}  sev={1-10}  src={hostname}
 * heapUsedMB={n}  heapMaxMB={n}  heapPct={n.n}
 * nonHeapUsedMB={n}  gcOverheadPct={n.n}  totalGcTimeMs={n}
 * postGcGrowth={n.n MB/h or N/A}  diagnosis={...}
 * </pre>
 *
 * <p>The {@code sev} field maps QRadar severity (1–10):
 * <ul>
 *   <li>{@code WARNING} → sev=5 (Notice)</li>
 *   <li>{@code CRITICAL} → sev=9 (Critical)</li>
 *   <li>{@code OOM_FIRING} → sev=10 (Emergency)</li>
 * </ul>
 *
 * <h2>Transport selection guide</h2>
 * <table border="1">
 *   <caption>UDP vs TCP transport comparison</caption>
 *   <tr>
 *     <th>Property</th>
 *     <th>UDP</th>
 *     <th>TCP</th>
 *   </tr>
 *   <tr>
 *     <td>Delivery guarantee</td>
 *     <td>None (best-effort)</td>
 *     <td>TCP ACK (within 5 s timeout)</td>
 *   </tr>
 *   <tr>
 *     <td>Latency</td>
 *     <td>Lowest</td>
 *     <td>Slightly higher (connection overhead)</td>
 *   </tr>
 *   <tr>
 *     <td>Poll thread blocking</td>
 *     <td>Non-blocking (fire-and-forget)</td>
 *     <td>Blocks up to 5 s on unreachable host</td>
 *   </tr>
 *   <tr>
 *     <td>Max payload</td>
 *     <td>65 007 bytes (hard-capped; fragmentation avoided)</td>
 *     <td>Unlimited (stream-based)</td>
 *   </tr>
 *   <tr>
 *     <td>Recommended for</td>
 *     <td>High-frequency polling, stable LAN links</td>
 *     <td>Critical events, WAN links, compliance requirements</td>
 *   </tr>
 * </table>
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>Syslog is transmitted in cleartext (RFC 3164/5424).  Use a TLS-capable
 *       syslog relay (e.g. rsyslog with TLS forwarding) in front of QRadar if you
 *       require confidentiality in transit.</li>
 *   <li>The QRadar host is validated at construction time; an empty string disables
 *       the channel entirely so you cannot accidentally send events to {@code ""}.</li>
 *   <li>All alert text is sanitised inside {@code AlertFormatter} before transmission
 *       to prevent log-injection attacks on the QRadar parser.</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.5.0
 * @since 1.0.0
 * @see QRadarAlertChannel
 * @see QRadarAlertChannel.Transport
 */
public final class Example05QRadarIntegration {

    /** Utility class — construction is not permitted. */
    private Example05QRadarIntegration() {}

    /**
     * Starts monitoring with both UDP and TCP QRadar channels active.
     *
     * <p>Substitute {@code YOUR_QRADAR_HOST} with the actual syslog receiver hostname
     * or IP address of your QRadar deployment (e.g. {@code "192.168.1.100"} or
     * {@code "qradar.corp.example.com"}).
     *
     * @param args command-line arguments:
     *             {@code args[0]} — QRadar host (defaults to {@code "localhost"} if absent)
     *             {@code args[1]} — QRadar port (defaults to {@code 514} if absent)
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        // Parse optional CLI overrides for demo flexibility
        String host = args.length > 0 ? args[0] : "localhost";
        int    port = args.length > 1 ? Integer.parseInt(args[1]) : 514;

        // ── Configuration ─────────────────────────────────────────────────────
        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .pollIntervalMs(5_000L)
                .heapDumpDirectory("./dumps")
                .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
                // QRadar host and port must be set here too so WatchdogConfig is
                // self-consistent; the QRadarAlertChannel constructed below will
                // receive the same values.
                .qradarHost(host)
                .qradarPort(port)
                .build();

        // ── QRadar channel: UDP (fire-and-forget, lowest overhead) ────────────
        //
        // Use UDP for high-frequency environments where occasional event loss is
        // acceptable.  The channel caps each datagram at 65 007 bytes to avoid
        // silent fragmentation on Ethernet networks.
        QRadarAlertChannel udpChannel = new QRadarAlertChannel(
                host, port, QRadarAlertChannel.Transport.UDP);

        // ── QRadar channel: TCP (guaranteed delivery, 5 s timeout) ────────────
        //
        // Use TCP when you need every alert to reach QRadar reliably.  A fresh TCP
        // connection is opened (and closed) for each alert.  The built-in 5-second
        // connect + read timeout prevents the watchdog's poll thread from blocking
        // indefinitely if QRadar is unreachable.
        //
        // Uncomment to enable both transports simultaneously (useful for dual-path
        // SIEM deployments or failover testing):
        //
        // QRadarAlertChannel tcpChannel = new QRadarAlertChannel(
        //         host, port, QRadarAlertChannel.Transport.TCP);

        // ── Wire and start ─────────────────────────────────────────────────────
        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config),
                new ThresholdRiskAssessor(config),
                Arrays.asList(
                        new ConsoleAlertChannel(),   // local visibility
                        udpChannel                   // LEEF 2.0 to QRadar
                ),
                new CompositeDumpService(config));

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();
        System.out.println("[Example05] QRadar LEEF 2.0 syslog alerts → "
                + host + ":" + port + "/UDP");
        System.out.println("[Example05] Heap dump + thread dump on CRITICAL → ./dumps/");

        Thread.currentThread().join();
    }
}
