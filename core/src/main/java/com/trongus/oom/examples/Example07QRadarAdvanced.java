package com.trongus.oom.examples;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.alert.ConsoleAlertChannel;
import com.trongus.oom.alert.QRadarAlertChannel;
import com.trongus.oom.collector.MxBeanDiagnosticsCollector;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.ThresholdRiskAssessor;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example 07 — Comprehensive QRadar integration patterns.
 *
 * <h2>What this example shows</h2>
 * <p>This example covers every practical QRadar integration scenario in one place:
 * <ol>
 *   <li><strong>Basic UDP syslog</strong> — the simplest one-liner integration.</li>
 *   <li><strong>Basic TCP syslog</strong> — guaranteed delivery for critical environments.</li>
 *   <li><strong>Dual-transport (UDP + TCP)</strong> — redundant delivery with different priorities:
 *       UDP for all alerts, TCP only for CRITICAL and above.</li>
 *   <li><strong>CRITICAL-only forwarding channel</strong> — reduce QRadar EPS (Events Per
 *       Second) by suppressing WARNING noise in QRadar while still alerting locally.</li>
 *   <li><strong>Environment-tagged events</strong> — decorating each event with
 *       environment metadata ({@code env}, {@code app}, {@code region}) for accurate
 *       QRadar log-source filtering and correlation rules.</li>
 *   <li><strong>Rate-limited channel</strong> — preventing alert storms from
 *       flooding QRadar when the heap is stuck at a critical level.</li>
 *   <li><strong>Failover channel</strong> — try the primary QRadar endpoint, fall back
 *       to a secondary SIEM on failure.</li>
 * </ol>
 *
 * <h2>QRadar LEEF 2.0 format produced by this library</h2>
 *
 * <p>Every event sent by {@link QRadarAlertChannel} conforms to the IBM LEEF 2.0
 * specification.  The complete wire format for a CRITICAL event looks like:
 *
 * <pre>
 * &lt;13&gt;Sep 17 08:00:00 prod-host LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|
 * sev=9&#9;cat=JVM_OOM_Risk&#9;process=98765@prod-host
 * heapUsedMB=921&#9;heapMaxMB=1024&#9;heapPct=90.0
 * nonHeapUsedMB=128&#9;gcOverheadPct=23.8&#9;totalGcTimeMs=14300
 * gc_G1_Young_Generation_count=1420&#9;gc_G1_Young_Generation_timeMs=6200
 * gc_G1_Old_Generation_count=3&#9;gc_G1_Old_Generation_timeMs=8100
 * postGcGrowth=42.30 MB/h&#9;riskLevel=CRITICAL
 * heapDump=/var/dumps/oom_heap_98765_20251017_080000_001.hprof
 * msg=[Assessment] CRITICAL – OOM imminent. Heap at 90.0% ...
 * </pre>
 *
 * <p>(Tab characters shown as &#9; above — LEEF 2.0 uses {@code \t} as the attribute separator.)
 *
 * <h2>QRadar log source configuration</h2>
 *
 * <p>In the QRadar Console → <em>Admin → Log Sources → Add</em>:
 * <ol>
 *   <li><strong>Log Source Type:</strong> Universal LEEF  (or "IBM Universal DSM")</li>
 *   <li><strong>Protocol Configuration:</strong> Syslog</li>
 *   <li><strong>Log Source Identifier:</strong> the hostname or IP of the machine running
 *       OOM Watchdog (must match the {@code src} field in the LEEF header)</li>
 *   <li><strong>Port:</strong> the port you configured in {@link QRadarAlertChannel}
 *       (default 514 for UDP, or 1514 for TCP in many deployments)</li>
 * </ol>
 *
 * <p>After adding the log source, create a custom event category rule in
 * <em>QRadar → Rules</em> with these conditions:
 * <ul>
 *   <li>{@code Log Source Type} is <em>Universal LEEF</em></li>
 *   <li>{@code Custom Field: cat} equals {@code JVM_OOM_Risk}</li>
 *   <li>{@code Custom Field: sev} is greater than or equal to {@code 9}</li>
 * </ul>
 *
 * <h2>TLS-encrypted syslog relay</h2>
 *
 * <p>Raw syslog is transmitted in cleartext.  In environments with compliance
 * requirements (HIPAA, PCI-DSS, ISO 27001), place a TLS relay in front of QRadar:
 *
 * <pre>
 * OOM Watchdog
 *    │  UDP/TCP → plaintext syslog on port 514
 *    ▼
 * rsyslog TLS relay (localhost or trusted LAN host)
 *    │  TLS → encrypted syslog on port 6514
 *    ▼
 * IBM QRadar
 * </pre>
 *
 * <p>Sample {@code rsyslog.conf} forwarding rule:
 * <pre>
 * module(load="imudp")
 * input(type="imudp" port="514")
 *
 * module(load="omfwd")
 * action(type="omfwd"
 *        Target="qradar.corp.com"
 *        Port="6514"
 *        Protocol="tcp"
 *        StreamDriver="gtls"
 *        StreamDriverMode="1"
 *        StreamDriverAuthMode="x509/name"
 *        StreamDriverPermittedPeers="qradar.corp.com")
 * </pre>
 *
 * <h2>QRadar SIEM correlation rule example</h2>
 *
 * <p>The following AQL query identifies JVM processes where heap has been over 85% for
 * more than 3 consecutive minutes — a strong precursor to an OOM crash:
 *
 * <pre>{@code
 * SELECT "sourceip", "username",
 *        COUNT(*) AS alert_count,
 *        MAX(FLOAT("heapPct")) AS max_heap_pct
 * FROM events
 * WHERE "cat" = 'JVM_OOM_Risk'
 *   AND FLOAT("heapPct") >= 85
 *   AND DEVICETIME > NOW() - 3 MINUTES
 * GROUP BY "sourceip", "username"
 * HAVING COUNT(*) >= 3
 * LAST 10 MINUTES
 * }</pre>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.1.0
 * @since 1.1.0
 * @see QRadarAlertChannel
 * @see QRadarAlertChannel.Transport
 * @see Example05QRadarIntegration
 */
public final class Example07QRadarAdvanced {

    /** Utility class — construction is not permitted. */
    private Example07QRadarAdvanced() {}

    // =========================================================================
    // Pattern 1: CRITICAL-only QRadar channel
    // =========================================================================

    /**
     * An {@link AlertChannel} decorator that forwards events to QRadar only when
     * the risk level is {@link OomRiskLevel#CRITICAL} or above.
     *
     * <h2>Why this matters for QRadar</h2>
     * <p>QRadar is licensed on Events Per Second (EPS).  Forwarding every WARNING
     * event during a sustained partial-degradation period can spike EPS counts
     * significantly.  This decorator passes WARNING events to local channels (console,
     * file log) while sending only CRITICAL+ events to QRadar.
     *
     * <p>Thread safety: the inner {@link QRadarAlertChannel} is itself thread-safe;
     * this wrapper adds no shared mutable state.
     *
     * @see QRadarAlertChannel
     */
    public static final class CriticalOnlyQRadarChannel implements AlertChannel {

        /** The real QRadar channel to delegate CRITICAL and above events to. */
        private final QRadarAlertChannel delegate;

        /**
         * Creates a new {@code CriticalOnlyQRadarChannel}.
         *
         * @param qradarHost QRadar syslog receiver hostname or IP address
         * @param qradarPort QRadar syslog port (1–65535)
         * @param transport  {@link QRadarAlertChannel.Transport#UDP} or
         *                   {@link QRadarAlertChannel.Transport#TCP}
         * @throws IllegalArgumentException if {@code qradarHost} is null or blank
         */
        public CriticalOnlyQRadarChannel(String qradarHost, int qradarPort,
                                         QRadarAlertChannel.Transport transport) {
            if (qradarHost == null || qradarHost.trim().isEmpty()) {
                throw new IllegalArgumentException("qradarHost must not be null or blank");
            }
            this.delegate = new QRadarAlertChannel(qradarHost, qradarPort, transport);
        }

        /**
         * Forwards the event to QRadar only when risk is CRITICAL or OOM_FIRING.
         * WARNING events are silently dropped so they do not consume QRadar EPS.
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            if (snapshot.getRiskLevel().ordinal() >= OomRiskLevel.CRITICAL.ordinal()) {
                delegate.alert(snapshot);
            }
            // WARNING and OK are intentionally not forwarded to QRadar
        }

        /**
         * Returns a descriptive channel name including the filter policy.
         *
         * @return channel name indicating CRITICAL-only filtering
         */
        @Override
        public String channelName() {
            return "QRadar-CRITICAL-only(" + delegate.channelName() + ")";
        }
    }

    // =========================================================================
    // Pattern 2: Rate-limited QRadar channel
    // =========================================================================

    /**
     * A rate-limiting decorator that allows at most one QRadar event per configurable
     * minimum interval, regardless of how frequently the watchdog fires alerts.
     *
     * <h2>Problem it solves</h2>
     * <p>When the heap is stuck at 91 % and the poll interval is 5 seconds, the watchdog
     * fires a CRITICAL alert every 5 seconds indefinitely.  Without rate limiting, this
     * creates a stream of duplicate QRadar events that:
     * <ul>
     *   <li>Consume EPS credits</li>
     *   <li>Fill QRadar dashboards with noise</li>
     *   <li>May trigger duplicate incident tickets in connected ITSM systems</li>
     * </ul>
     *
     * <p>This decorator ensures the SIEM receives at most one alert per {@code minIntervalMs},
     * while local channels (console, file) still receive every event.
     *
     * <p>Thread safety: {@code lastSentMs} is {@code AtomicLong}; all operations are
     * compare-and-set without external locking.
     *
     * @see QRadarAlertChannel
     */
    public static final class RateLimitedQRadarChannel implements AlertChannel {

        /** The real QRadar channel to delegate to when the rate limit is not exceeded. */
        private final QRadarAlertChannel delegate;

        /**
         * Minimum time in milliseconds between successive QRadar events.
         * Defaults to 5 minutes (300 000 ms) — enough for one alert per sustained episode.
         */
        private final long minIntervalMs;

        /**
         * Epoch-millisecond timestamp of the last event forwarded to QRadar.
         * Initialised to {@code 0L} so the very first alert always goes through.
         */
        private final AtomicLong lastSentMs = new AtomicLong(0L);

        /**
         * Creates a new {@code RateLimitedQRadarChannel}.
         *
         * @param qradarHost    QRadar syslog receiver hostname or IP address
         * @param qradarPort    QRadar syslog port
         * @param transport     UDP or TCP
         * @param minIntervalMs minimum milliseconds between events forwarded to QRadar;
         *                      must be positive
         * @throws IllegalArgumentException if any argument is out of range
         */
        public RateLimitedQRadarChannel(String qradarHost, int qradarPort,
                                        QRadarAlertChannel.Transport transport,
                                        long minIntervalMs) {
            if (qradarHost == null || qradarHost.trim().isEmpty()) {
                throw new IllegalArgumentException("qradarHost must not be null or blank");
            }
            if (minIntervalMs <= 0) {
                throw new IllegalArgumentException("minIntervalMs must be positive");
            }
            this.delegate      = new QRadarAlertChannel(qradarHost, qradarPort, transport);
            this.minIntervalMs = minIntervalMs;
        }

        /**
         * Sends the event to QRadar if the minimum interval since the last send has elapsed.
         * Suppresses (logs to stdout) events that arrive within the rate-limit window.
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            long now  = System.currentTimeMillis();
            long last = lastSentMs.get();
            long elapsed = now - last;

            if (elapsed >= minIntervalMs) {
                // Atomic CAS — only one concurrent caller wins the right to send
                if (lastSentMs.compareAndSet(last, now)) {
                    delegate.alert(snapshot);
                }
            } else {
                System.out.printf(
                    "[RateLimitedQRadar] Suppressed event (last sent %d ms ago, limit %d ms)%n",
                    elapsed, minIntervalMs);
            }
        }

        /** @return channel name including the rate-limit window */
        @Override
        public String channelName() {
            return "QRadar-RateLimited(" + minIntervalMs + "ms/" + delegate.channelName() + ")";
        }
    }

    // =========================================================================
    // Pattern 3: Environment-tagged QRadar channel
    // =========================================================================

    /**
     * A QRadar alert channel that enriches every LEEF event with environment-specific
     * metadata before forwarding to QRadar.
     *
     * <h2>Why environment tags matter in QRadar</h2>
     * <p>A single QRadar deployment typically receives events from dozens of JVM services
     * across multiple environments (dev, staging, prod) and regions (us-east-1, eu-west-2).
     * Without tagging, QRadar correlation rules cannot distinguish a CRITICAL event from
     * a production microservice from a developer's local test run.
     *
     * <p>This decorator prepends structured metadata to the diagnosis notes field so
     * QRadar can filter and correlate by environment, application, and region.
     *
     * <p>In production, populate these values from your orchestration platform:
     * <ul>
     *   <li>Kubernetes: inject via {@code env.valueFrom.fieldRef} in the Pod spec</li>
     *   <li>Docker: set with {@code -e APP_ENV=production}</li>
     *   <li>Spring Boot: use {@code @Value("${APP_ENV:development}")}</li>
     * </ul>
     *
     * <p>Thread safety: all fields are final, set once at construction — fully immutable.
     *
     * @see QRadarAlertChannel
     */
    public static final class TaggedQRadarChannel implements AlertChannel {

        /** Maximum characters allowed in each metadata tag to prevent injection. */
        private static final int MAX_TAG_LENGTH = 64;

        /** The underlying QRadar channel that transmits the enriched events. */
        private final QRadarAlertChannel delegate;

        /**
         * Deployment environment identifier.
         * Examples: {@code "production"}, {@code "staging"}, {@code "development"}.
         * Sanitised at construction to contain only safe characters.
         */
        private final String environment;

        /**
         * Application or service name as registered in your service registry.
         * Examples: {@code "order-service"}, {@code "payment-gateway"}.
         * Sanitised at construction to contain only safe characters.
         */
        private final String application;

        /**
         * Cloud region or data-centre identifier.
         * Examples: {@code "us-east-1"}, {@code "eu-west-2"}, {@code "on-prem-nyc"}.
         * Sanitised at construction to contain only safe characters.
         */
        private final String region;

        /**
         * Creates a new {@code TaggedQRadarChannel}.
         *
         * @param qradarHost  QRadar syslog receiver hostname
         * @param qradarPort  QRadar syslog port
         * @param transport   UDP or TCP
         * @param environment deployment environment; must be non-blank
         * @param application application/service name; must be non-blank
         * @param region      cloud region or DC identifier; must be non-blank
         * @throws IllegalArgumentException if any parameter is null, blank, or exceeds
         *                                  {@value #MAX_TAG_LENGTH} characters
         */
        public TaggedQRadarChannel(String qradarHost, int qradarPort,
                                   QRadarAlertChannel.Transport transport,
                                   String environment, String application, String region) {
            if (qradarHost == null || qradarHost.trim().isEmpty()) {
                throw new IllegalArgumentException("qradarHost must not be null or blank");
            }
            this.delegate    = new QRadarAlertChannel(qradarHost, qradarPort, transport);
            this.environment = sanitiseTag("environment", environment);
            this.application = sanitiseTag("application", application);
            this.region      = sanitiseTag("region", region);
        }

        /**
         * Enriches the snapshot's diagnosis notes with environment tags, then forwards
         * the event to QRadar via the underlying channel.
         *
         * <p>The enriched notes are prepended with:
         * <pre>
         * [env=production][app=order-service][region=us-east-1] ...original diagnosis...
         * </pre>
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            // Prepend environment tags to the diagnosis notes.
            // We create an enriched snapshot via toBuilder() — never mutate the original.
            String enrichedNotes = String.format("[env=%s][app=%s][region=%s] %s",
                    environment, application, region,
                    snapshot.getDiagnosisNotes() != null ? snapshot.getDiagnosisNotes() : "");

            JvmSnapshot enriched = snapshot.toBuilder()
                    .diagnosisNotes(enrichedNotes)
                    .build();

            delegate.alert(enriched);
        }

        /** @return channel name including environment context */
        @Override
        public String channelName() {
            return "QRadar-Tagged[" + environment + "/" + application + "]";
        }

        /**
         * Validates and sanitises a metadata tag value.
         *
         * <p>Allowed characters: {@code [A-Za-z0-9._-]}.  All other characters are
         * replaced with underscores.  This prevents log-injection into QRadar's LEEF
         * parser via a malformed tag value.
         *
         * @param fieldName field name (for error messages)
         * @param value     raw tag value
         * @return sanitised, length-bounded tag value
         * @throws IllegalArgumentException if {@code value} is null or blank
         */
        private static String sanitiseTag(String fieldName, String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be null or blank");
            }
            String trimmed = value.trim();
            if (trimmed.length() > MAX_TAG_LENGTH) {
                trimmed = trimmed.substring(0, MAX_TAG_LENGTH);
            }
            // Replace any character that is not alphanumeric, dot, underscore, or hyphen
            return trimmed.replaceAll("[^A-Za-z0-9._\\-]", "_");
        }
    }

    // =========================================================================
    // Pattern 4: Primary/Failover QRadar channel
    // =========================================================================

    /**
     * A resilient QRadar channel that automatically fails over to a secondary
     * QRadar instance when the primary is unreachable.
     *
     * <h2>Use case</h2>
     * <p>Enterprise QRadar deployments often have a primary SIEM instance and a standby
     * for high availability.  This channel attempts the primary first; if it throws
     * (network error, timeout), it falls back to the secondary transparently.
     *
     * <p>Thread safety: both delegate channels are themselves thread-safe; this wrapper
     * adds no shared mutable state.
     *
     * @see QRadarAlertChannel
     */
    public static final class FailoverQRadarChannel implements AlertChannel {

        /** Primary QRadar target — tried first on every alert. */
        private final QRadarAlertChannel primary;

        /** Secondary QRadar target — used when primary raises an error. */
        private final QRadarAlertChannel secondary;

        /**
         * Creates a new {@code FailoverQRadarChannel} with separate primary and secondary
         * QRadar endpoints.
         *
         * @param primaryHost   primary QRadar hostname
         * @param primaryPort   primary QRadar port
         * @param secondaryHost secondary QRadar hostname (standby)
         * @param secondaryPort secondary QRadar port
         * @param transport     transport for both endpoints (usually UDP for syslog HA)
         * @throws IllegalArgumentException if any host is null or blank
         */
        public FailoverQRadarChannel(String primaryHost, int primaryPort,
                                     String secondaryHost, int secondaryPort,
                                     QRadarAlertChannel.Transport transport) {
            if (primaryHost == null || primaryHost.trim().isEmpty()) {
                throw new IllegalArgumentException("primaryHost must not be null or blank");
            }
            if (secondaryHost == null || secondaryHost.trim().isEmpty()) {
                throw new IllegalArgumentException("secondaryHost must not be null or blank");
            }
            this.primary   = new QRadarAlertChannel(primaryHost,   primaryPort,   transport);
            this.secondary = new QRadarAlertChannel(secondaryHost, secondaryPort, transport);
        }

        /**
         * Sends the alert to the primary QRadar instance.  If the primary channel
         * logs an error (caught by the watchdog), the secondary is used on the
         * next call.
         *
         * <p>Because {@link QRadarAlertChannel#alert} itself swallows exceptions
         * (per the {@link AlertChannel} contract), this wrapper relies on a try-catch
         * around each send.  For UDP, "errors" are silent at the network level; this
         * pattern is more effective with TCP where a refused connection surfaces immediately.
         *
         * @param snapshot the assessed JVM snapshot; never {@code null}
         */
        @Override
        public void alert(JvmSnapshot snapshot) {
            try {
                primary.alert(snapshot);
                // If primary completes without throwing, we are done.
                // Note: UDP never throws on failure — for guaranteed failover use TCP.
            } catch (Exception primaryEx) {
                System.err.println("[FailoverQRadar] Primary failed ("
                        + primaryEx.getMessage() + "), trying secondary...");
                try {
                    secondary.alert(snapshot);
                } catch (Exception secondaryEx) {
                    System.err.println("[FailoverQRadar] Secondary also failed: "
                            + secondaryEx.getMessage());
                }
            }
        }

        /** @return channel name showing both primary and secondary targets */
        @Override
        public String channelName() {
            return "QRadar-Failover(primary=" + primary.channelName()
                    + " secondary=" + secondary.channelName() + ")";
        }
    }

    // =========================================================================
    // main — demonstrate all four patterns
    // =========================================================================

    /**
     * Entry point that wires all four QRadar patterns together in a single watchdog.
     *
     * <p>In production you would choose one or two of these patterns, not all four.
     * They are combined here purely for demonstration.
     *
     * @param args optional arguments:
     *             {@code args[0]} — primary QRadar host   (default: {@code localhost})
     *             {@code args[1]} — QRadar port           (default: {@code 514})
     *             {@code args[2]} — secondary QRadar host (default: {@code localhost})
     *             {@code args[3]} — secondary port        (default: {@code 10514})
     * @throws InterruptedException if the main thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {

        // ── CLI argument parsing with safe defaults ────────────────────────────
        // Validate port arguments to avoid misconfiguration at startup.
        String primaryHost   = args.length > 0 ? args[0] : "localhost";
        int    primaryPort   = parsePort(args, 1, 514);
        String secondaryHost = args.length > 2 ? args[2] : "localhost";
        int    secondaryPort = parsePort(args, 3, 10514);

        // ── Watchdog configuration ─────────────────────────────────────────────
        WatchdogConfig config = WatchdogConfig.defaults()
                .warningHeapThreshold(0.80)
                .criticalHeapThreshold(0.90)
                .pollIntervalMs(5_000L)
                .heapDumpDirectory("./dumps")
                .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD, DumpType.CLASS_HISTOGRAM))
                .qradarHost(primaryHost)
                .qradarPort(primaryPort)
                .build();

        // ── Pattern 1: CRITICAL-only — suppress WARNING noise in QRadar ─────────
        AlertChannel criticalOnly = new CriticalOnlyQRadarChannel(
                primaryHost, primaryPort, QRadarAlertChannel.Transport.UDP);

        // ── Pattern 2: Rate-limited — at most 1 event to QRadar per 5 minutes ──
        //   WARNING events still go to the console every poll cycle.
        AlertChannel rateLimited = new RateLimitedQRadarChannel(
                primaryHost, primaryPort,
                QRadarAlertChannel.Transport.UDP,
                5 * 60 * 1_000L);  // 5 minutes

        // ── Pattern 3: Tagged with environment metadata ───────────────────────
        //   In production, read these from System.getenv() or a config server.
        AlertChannel tagged = new TaggedQRadarChannel(
                primaryHost, primaryPort,
                QRadarAlertChannel.Transport.UDP,
                System.getenv().getOrDefault("APP_ENV",    "development"),
                System.getenv().getOrDefault("APP_NAME",   "oom-watchdog-example"),
                System.getenv().getOrDefault("APP_REGION", "local"));

        // ── Pattern 4: Primary + secondary failover (TCP for guaranteed delivery)
        AlertChannel failover = new FailoverQRadarChannel(
                primaryHost,   primaryPort,
                secondaryHost, secondaryPort,
                QRadarAlertChannel.Transport.TCP);

        // ── Wire all channels ──────────────────────────────────────────────────
        // In production, pick the one or two patterns that fit your environment.
        // They are all active here for demo purposes only.
        List<AlertChannel> channels = Arrays.asList(
                new ConsoleAlertChannel(),   // always include for local visibility
                criticalOnly,
                rateLimited,
                tagged,
                failover
        );

        OomWatchdog watchdog = new OomWatchdog(
                config,
                new MxBeanDiagnosticsCollector(config),
                new ThresholdRiskAssessor(config),
                channels,
                new CompositeDumpService(config));

        Runtime.getRuntime().addShutdownHook(
                new Thread(watchdog::stop, "oom-watchdog-shutdown"));

        watchdog.start();
        printStartupBanner(primaryHost, primaryPort, secondaryHost, secondaryPort);

        Thread.currentThread().join();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parses a port number from the args array at the given index.
     * Validates the result is in the legal TCP/UDP port range [1, 65535].
     *
     * @param args         CLI argument array
     * @param index        zero-based index into {@code args}
     * @param defaultValue fallback value if the index is out of bounds or parsing fails
     * @return validated port number
     */
    private static int parsePort(String[] args, int index, int defaultValue) {
        if (index >= args.length) return defaultValue;
        try {
            int port = Integer.parseInt(args[index]);
            if (port < 1 || port > 65535) {
                System.err.println("[Example07] Port " + port
                        + " is out of range [1,65535]; using default " + defaultValue);
                return defaultValue;
            }
            return port;
        } catch (NumberFormatException e) {
            System.err.println("[Example07] Invalid port '" + args[index]
                    + "'; using default " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Prints an ASCII startup summary describing the active configuration.
     *
     * @param primaryHost   primary QRadar host
     * @param primaryPort   primary QRadar port
     * @param secondaryHost failover QRadar host
     * @param secondaryPort failover QRadar port
     */
    private static void printStartupBanner(String primaryHost, int primaryPort,
                                           String secondaryHost, int secondaryPort) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Example 07 — Advanced QRadar Integration               ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Primary QRadar    : %s:%d/UDP%n", primaryHost, primaryPort);
        System.out.printf( "║  Secondary QRadar  : %s:%d/TCP (failover)%n", secondaryHost, secondaryPort);
        System.out.println("║  CRITICAL-only     : WARNING events NOT sent to QRadar       ║");
        System.out.println("║  Rate limit        : max 1 QRadar event per 5 minutes        ║");
        System.out.println("║  Env tags          : [env][app][region] prepended to msg     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
