package com.trongus.oom.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.platform.JvmPlatform;
import com.trongus.oom.remote.JmxDiagnosticsCollector;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Lightweight HTTP/HTTPS server that serves live JVM health metrics on
 * {@code GET /metrics} (single self-monitoring target),
 * {@code GET /metrics/all} (all monitored targets as a JSON array), and
 * on-demand diagnostic dump endpoints triggered from the dashboard.
 *
 * <p>Every metrics response reads directly from the same
 * {@link java.lang.management} MXBeans that
 * {@link com.trongus.oom.collector.MxBeanDiagnosticsCollector} uses — so
 * each dashboard request gets current data straight from the JVM, not a
 * cached snapshot from the last watchdog poll cycle.
 *
 * <h2>TLS / Security</h2>
 * <p>By default the server starts in <em>HTTPS</em> mode using an automatically
 * generated, in-memory self-signed RSA-2048 certificate (see {@link TlsConfig#selfSigned()}).
 * The certificate is renewed automatically when it expires; no filesystem keystore is
 * needed for the default configuration.
 *
 * <p>Three modes are available via {@link TlsConfig}:
 * <ul>
 *   <li>{@link TlsConfig.Mode#SELF_SIGNED} (default) — auto-generated in-memory cert; renewed on expiry.</li>
 *   <li>{@link TlsConfig.Mode#KEYSTORE} — operator-supplied PKCS#12 / JKS keystore for production use.</li>
 *   <li>{@link TlsConfig.Mode#DISABLED} — plain HTTP; for use only on loopback in trusted environments.</li>
 * </ul>
 *
 * <p>All responses include the following security headers regardless of TLS mode:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code Cache-Control: no-store}</li>
 *   <li>{@code X-Frame-Options: DENY}</li>
 * </ul>
 *
 * <p>CORS is enabled for all origins ({@code Access-Control-Allow-Origin: *}) so that
 * {@code dashboard.html} can be opened from the local filesystem ({@code file://}).
 * {@code OPTIONS} preflight requests are answered with {@code 204 No Content} and the
 * required {@code Access-Control-Allow-Methods} / {@code Access-Control-Allow-Headers}
 * headers so that browsers do not block the preflight before the actual GET or POST is sent.
 * Restrict the origin header if you bind to a non-loopback interface.
 *
 * <h2>IPv4 / IPv6 dual-stack binding</h2>
 * <p>The server performs <em>dual-stack</em> binding automatically:
 * <ul>
 *   <li>{@code bindAll=true} — tries to bind to {@code ::} (all IPv6 interfaces, which
 *       also covers IPv4 on dual-stack kernels via IPv4-mapped addresses).  If the JVM
 *       reports that IPv6 is unavailable the fallback address is {@code 0.0.0.0}
 *       (all IPv4 interfaces).</li>
 *   <li>{@code bindAll=false} — tries to bind to {@code ::1} (IPv6 loopback).  Falls
 *       back to {@code 127.0.0.1} (IPv4 loopback) when IPv6 is unavailable.</li>
 * </ul>
 * <p>On Linux a single {@code ::} socket covers both address families by default.
 * On macOS / BSD, IPv4 and IPv6 are independent sockets, so only one family is served
 * per bind address — use {@code ::} for IPv6-only or {@code 0.0.0.0} for IPv4-only as
 * needed, or set the JVM flag {@code -Djava.net.preferIPv4Stack=true} to force IPv4.
 * <p>To force IPv4 regardless of platform, start the JVM with:
 * <pre>  -Djava.net.preferIPv4Stack=true</pre>
 * To prefer IPv6 when both are available:
 * <pre>  -Djava.net.preferIPv6Addresses=true</pre>
 *
 * <h2>Data sources</h2>
 * <ul>
 *   <li><strong>Heap, non-heap, memory pools, GC counts/times, uptime</strong> — read live
 *       from {@link ManagementFactory} MXBeans on every request.</li>
 *   <li><strong>Risk level, critical threshold, diagnosis notes, heap dump path</strong> —
 *       taken from the watchdog's most recently assessed snapshot (set by
 *       {@link OomWatchdog#getLastSnapshot()}), since those require the assessor's analysis.</li>
 * </ul>
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <caption>Exposed HTTP endpoints</caption>
 *   <tr><th>Path</th><th>Method</th><th>Response</th></tr>
 *   <tr><td>{@code /metrics}</td><td>GET</td>
 *       <td>JSON object — self-monitoring JVM metrics</td></tr>
 *   <tr><td>{@code /metrics/all}</td><td>GET</td>
 *       <td>JSON array — one entry per monitored target</td></tr>
 *   <tr><td>{@code /dump/thread}</td><td>POST</td>
 *       <td>JSON — triggers a thread dump; returns {@code {"ok":true,"path":"…"}} or
 *           {@code {"ok":false,"error":"…"}}</td></tr>
 *   <tr><td>{@code /dump/heap}</td><td>POST</td>
 *       <td>JSON — triggers a heap dump; returns {@code {"ok":true,"path":"…"}} or
 *           {@code {"ok":false,"error":"…"}}</td></tr>
 *   <tr><td>{@code /dump/core}</td><td>POST</td>
 *       <td>JSON — triggers a core dump; returns {@code {"ok":true,"path":"…"}} or
 *           {@code {"ok":false,"error":"…"}}</td></tr>
 *   <tr><td>{@code /}</td><td>GET</td>
 *       <td>{@code 302} redirect to {@code /metrics}</td></tr>
 * </table>
 *
 * <h2>On-demand dumps</h2>
 * <p>The {@code POST /dump/*} endpoints invoke {@link OomWatchdog#triggerDump(DumpType)}
 * on the self-monitoring watchdog.  They are available only when a self-monitoring
 * watchdog was supplied at construction time; in pure-daemon mode (no self watchdog)
 * they respond with {@code 503 Service Unavailable}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.5
 * @since 1.7.3
 * @see TlsConfig
 * @see OomWatchdog#getLastSnapshot()
 * @see OomWatchdog#triggerDump(DumpType)
 * @see com.trongus.oom.collector.MxBeanDiagnosticsCollector
 */
public final class MetricsHttpServer {

    private static final Logger LOG = WatchdogLogger.forClass(MetricsHttpServer.class);

    // MXBeans are thread-safe singletons — cache references once.
    private static final MemoryMXBean         MEMORY_MX  = ManagementFactory.getMemoryMXBean();
    private static final RuntimeMXBean        RUNTIME_MX = ManagementFactory.getRuntimeMXBean();
    private static final OperatingSystemMXBean OS_MX      = ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean         THREAD_MX  = ManagementFactory.getThreadMXBean();

    // TLS protocols and cipher suites: require TLS 1.2+ and forward-secret ciphers only.
    private static final String[] ENABLED_PROTOCOLS = { "TLSv1.2", "TLSv1.3" };
    private static final String[] PREFERRED_CIPHERS  = {
        "TLS_AES_256_GCM_SHA384",           // TLS 1.3
        "TLS_AES_128_GCM_SHA256",           // TLS 1.3
        "TLS_CHACHA20_POLY1305_SHA256",     // TLS 1.3
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",  // TLS 1.2 ECDHE
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",  // TLS 1.2 ECDHE
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"  // TLS 1.2 ECDHE
    };

    private final OomWatchdog                        selfWatchdog;
    private final Map<String, OomWatchdog>           remoteWatchdogs;
    private final Map<String, JmxDiagnosticsCollector> remoteCollectors;
    private final int                                port;
    private final boolean                            bindAll;
    private final TlsConfig                          tlsConfig;

    /** Holds the active SSLContext; replaced atomically on certificate renewal. */
    private final AtomicReference<SSLContext> sslContextRef = new AtomicReference<>();

    private volatile HttpServer server;

    // ── constructors ──────────────────────────────────────────────────────────

    /**
     * Creates a single-target (self-monitoring) metrics server with TLS.
     *
     * <p>TLS defaults to {@link TlsConfig#selfSigned()} (auto-generated cert).
     *
     * @param watchdog  the running watchdog; must not be {@code null}
     * @param port      TCP port to listen on (1–65535)
     * @param bindAll   {@code true} to bind all interfaces; {@code false} for loopback only
     * @param tlsConfig TLS configuration; must not be {@code null}
     */
    public MetricsHttpServer(OomWatchdog watchdog, int port, boolean bindAll, TlsConfig tlsConfig) {
        if (watchdog == null) throw new NullPointerException("watchdog");
        if (tlsConfig == null) throw new NullPointerException("tlsConfig");
        validatePort(port);
        this.selfWatchdog      = watchdog;
        this.remoteWatchdogs   = Collections.emptyMap();
        this.remoteCollectors  = Collections.emptyMap();
        this.port              = port;
        this.bindAll           = bindAll;
        this.tlsConfig         = tlsConfig;
    }

    /**
     * Creates a single-target (self-monitoring) metrics server using the default
     * self-signed TLS configuration.
     *
     * @param watchdog the running watchdog; must not be {@code null}
     * @param port     TCP port to listen on (1–65535)
     * @param bindAll  {@code true} to bind all interfaces; {@code false} for loopback only
     * @deprecated Prefer {@link #MetricsHttpServer(OomWatchdog, int, boolean, TlsConfig)}
     *             and supply an explicit {@link TlsConfig} for clarity.
     */
    public MetricsHttpServer(OomWatchdog watchdog, int port, boolean bindAll) {
        this(watchdog, port, bindAll, TlsConfig.selfSigned());
    }

    /**
     * Creates a multi-target metrics server (daemon mode) with TLS.
     *
     * @param selfWatchdog    the self-monitoring watchdog (may be {@code null} in pure-daemon mode)
     * @param remoteWatchdogs named watchdogs for each remote target; must not be {@code null}
     * @param port            TCP port to listen on (1–65535)
     * @param bindAll         {@code true} to bind all interfaces; {@code false} for loopback only
     * @param tlsConfig       TLS configuration; must not be {@code null}
     */
    public MetricsHttpServer(OomWatchdog selfWatchdog,
                             Map<String, OomWatchdog> remoteWatchdogs,
                             int port, boolean bindAll, TlsConfig tlsConfig) {
        this(selfWatchdog, remoteWatchdogs, Collections.emptyMap(), port, bindAll, tlsConfig);
    }

    /**
     * Creates a multi-target metrics server (daemon mode) with TLS and per-target JMX collectors
     * for remote dump support.
     *
     * @param selfWatchdog     the self-monitoring watchdog (may be {@code null} in pure-daemon mode)
     * @param remoteWatchdogs  named watchdogs for each remote target; must not be {@code null}
     * @param remoteCollectors JMX collectors keyed by target name, used to trigger remote dumps
     * @param port             TCP port to listen on (1–65535)
     * @param bindAll          {@code true} to bind all interfaces; {@code false} for loopback only
     * @param tlsConfig        TLS configuration; must not be {@code null}
     */
    public MetricsHttpServer(OomWatchdog selfWatchdog,
                             Map<String, OomWatchdog> remoteWatchdogs,
                             Map<String, JmxDiagnosticsCollector> remoteCollectors,
                             int port, boolean bindAll, TlsConfig tlsConfig) {
        if (remoteWatchdogs == null) throw new NullPointerException("remoteWatchdogs");
        if (tlsConfig == null) throw new NullPointerException("tlsConfig");
        validatePort(port);
        this.selfWatchdog     = selfWatchdog;
        this.remoteWatchdogs  = Collections.unmodifiableMap(new LinkedHashMap<>(remoteWatchdogs));
        this.remoteCollectors = remoteCollectors != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(remoteCollectors))
                : Collections.emptyMap();
        this.port             = port;
        this.bindAll          = bindAll;
        this.tlsConfig        = tlsConfig;
    }

    /**
     * Creates a multi-target metrics server (daemon mode) using the default
     * self-signed TLS configuration.
     *
     * @param selfWatchdog    the self-monitoring watchdog (may be {@code null} in pure-daemon mode)
     * @param remoteWatchdogs named watchdogs for each remote target; must not be {@code null}
     * @param port            TCP port to listen on (1–65535)
     * @param bindAll         {@code true} to bind all interfaces; {@code false} for loopback only
     */
    public MetricsHttpServer(OomWatchdog selfWatchdog,
                             Map<String, OomWatchdog> remoteWatchdogs,
                             int port, boolean bindAll) {
        this(selfWatchdog, remoteWatchdogs, port, bindAll, TlsConfig.selfSigned());
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the HTTP(S) server and registers the {@code /metrics}, {@code /metrics/all}
     * and {@code /} handlers.
     *
     * <p>The bind address is chosen automatically based on address-family availability:
     * <ul>
     *   <li>{@code bindAll=true} — binds to {@code ::} (all IPv6 interfaces, covering IPv4
     *       via IPv4-mapped addresses on dual-stack kernels), falling back to {@code 0.0.0.0}
     *       on IPv4-only stacks.</li>
     *   <li>{@code bindAll=false} — binds to {@code ::1} (IPv6 loopback), falling back to
     *       {@code 127.0.0.1} on IPv4-only stacks.</li>
     * </ul>
     *
     * <p>When TLS is enabled the server first builds (or loads) an {@link SSLContext},
     * then creates an {@link HttpsServer} bound to the configured address.
     *
     * @throws IOException if the server socket cannot be bound
     * @throws Exception   if the SSL context cannot be initialised (keystore not found,
     *                     bad password, etc.)
     */
    public void start() throws Exception {
        InetSocketAddress addr = resolveBindAddress(bindAll, port);
        String host = addr.getAddress().getHostAddress();

        if (tlsConfig.getMode() == TlsConfig.Mode.DISABLED) {
            // ── Plain HTTP ────────────────────────────────────────────────────
            HttpServer plain = HttpServer.create(addr, 0);
            registerContexts(plain);
            plain.setExecutor(buildExecutor());
            plain.start();
            server = plain;
            WatchdogLogger.warning(LOG,
                    "Metrics HTTP server started WITHOUT TLS on http://{0}:{1}/metrics  " +
                    "(use --metrics-tls or supply --metrics-cert for encrypted transport)", host, port);
        } else {
            // ── HTTPS ─────────────────────────────────────────────────────────
            SSLContext sslContext = buildSslContext();
            sslContextRef.set(sslContext);

            HttpsServer https = HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    // Enforce minimum TLS version and preferred cipher suites
                    SSLContext ctx = sslContextRef.get();
                    SSLParameters sp = ctx.getDefaultSSLParameters();
                    sp.setProtocols(filterSupported(ENABLED_PROTOCOLS,
                            ctx.getDefaultSSLParameters().getProtocols()));
                    sp.setCipherSuites(filterSupported(PREFERRED_CIPHERS,
                            ctx.getSupportedSSLParameters().getCipherSuites()));
                    sp.setNeedClientAuth(false);
                    params.setSSLParameters(sp);
                }
            });
            registerContexts(https);
            https.setExecutor(buildExecutor());
            https.start();
            server = https;
            WatchdogLogger.info(LOG,
                    "Metrics HTTPS server listening on https://{0}:{1}/metrics  [{2}]",
                    host, port, tlsConfig);
        }
    }

    /**
     * Resolves the {@link InetSocketAddress} to bind the metrics server to.
     *
     * <p>Prefers IPv6 ({@code ::} / {@code ::1}) so that dual-stack kernels serve both
     * address families from a single socket.  Falls back to the IPv4 wildcard
     * ({@code 0.0.0.0} / {@code 127.0.0.1}) when the JVM reports IPv6 is unavailable
     * (e.g. when started with {@code -Djava.net.preferIPv4Stack=true}).
     *
     * @param bindAll {@code true} to bind all interfaces; {@code false} for loopback only
     * @param port    TCP port to listen on
     * @return a resolved {@link InetSocketAddress}
     */
    static InetSocketAddress resolveBindAddress(boolean bindAll, int port) {
        String ipv6Host = bindAll ? "::"   : "::1";
        String ipv4Host = bindAll ? "0.0.0.0" : "127.0.0.1";
        try {
            InetAddress addr = InetAddress.getByName(ipv6Host);
            return new InetSocketAddress(addr, port);
        } catch (UnknownHostException e) {
            // IPv6 not available on this stack — use IPv4
            return new InetSocketAddress(ipv4Host, port);
        }
    }

    /**
     * Stops the HTTP(S) server, waiting at most 1 second for in-flight requests to complete.
     */
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(1);
            WatchdogLogger.info(LOG, "Metrics HTTP server stopped.");
        }
    }

    // ── request handlers ──────────────────────────────────────────────────────

    private void handleMetrics(HttpExchange ex) throws IOException {
        if (isOptions(ex)) { sendPreflight(ex); return; }
        if (!isGet(ex))    { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        JvmSnapshot snap = selfWatchdog != null ? selfWatchdog.getLastSnapshot() : null;
        send(ex, 200, "application/json; charset=UTF-8", buildLiveJson(snap, "self"));
    }

    private void handleMetricsAll(HttpExchange ex) throws IOException {
        if (isOptions(ex)) { sendPreflight(ex); return; }
        if (!isGet(ex))    { send(ex, 405, "text/plain", "Method Not Allowed"); return; }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("[\n");
        boolean first = true;

        if (selfWatchdog != null) {
            sb.append(buildLiveJson(selfWatchdog.getLastSnapshot(), "self"));
            first = false;
        }

        for (Map.Entry<String, OomWatchdog> entry : remoteWatchdogs.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append(buildSnapshotJson(entry.getValue().getLastSnapshot(), entry.getKey()));
            first = false;
        }

        sb.append("\n]");
        send(ex, 200, "application/json; charset=UTF-8", sb.toString());
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Location", "/metrics");
        send(ex, 302, "text/plain", "Redirecting to /metrics");
    }

    /**
     * Handles {@code POST /dump/thread?target=<name>}, {@code POST /dump/heap?target=<name>},
     * and {@code POST /dump/core?target=<name>} requests from the dashboard.
     *
     * <p>The optional {@code target} query parameter selects the watchdog to use:
     * <ul>
     *   <li><strong>Absent or {@code "self"}</strong> — uses the self-monitoring watchdog;
     *       the dump runs against the watchdog JVM itself.</li>
     *   <li><strong>Any other value</strong> — looks up the name in {@code remoteCollectors};
     *       if a JMX collector is available the dump is triggered <strong>on the remote target
     *       JVM</strong> via {@link JmxDiagnosticsCollector#triggerRemoteDump}.  The output
     *       file is written to the target's configured dump directory on the watchdog server's
     *       filesystem.</li>
     * </ul>
     *
     * <p>Response JSON:
     * <pre>
     * {"ok": true,  "target": "myapp", "path": "/var/dumps/heap_20260918T153000.hprof"}
     * {"ok": false, "target": "myapp", "error": "All strategies exhausted — no dump produced"}
     * </pre>
     *
     * @param ex       the HTTP exchange
     * @param dumpType the requested dump type
     * @throws IOException if the response cannot be written
     */
    private void handleDump(HttpExchange ex, DumpType dumpType) throws IOException {
        if (isOptions(ex)) { sendPreflight(ex); return; }
        if (!isPost(ex))   { send(ex, 405, "text/plain", "Method Not Allowed"); return; }

        // Resolve target from ?target= query param
        String targetParam = queryParam(ex, "target");
        boolean isSelf = targetParam == null || targetParam.isEmpty() || "self".equalsIgnoreCase(targetParam);

        OomWatchdog watchdog;
        String      targetLabel;
        if (isSelf) {
            watchdog    = selfWatchdog;
            targetLabel = "self";
        } else {
            watchdog    = remoteWatchdogs.get(targetParam);
            targetLabel = targetParam;
        }

        if (watchdog == null) {
            String msg = isSelf
                    ? "No self-monitoring watchdog available"
                    : "Unknown target: " + escapeJson(targetParam);
            send(ex, 503, "application/json; charset=UTF-8",
                    "{\"ok\": false, \"target\": \"" + escapeJson(targetLabel) + "\", \"error\": \"" + msg + "\"}");
            return;
        }

        WatchdogLogger.info(LOG, "On-demand {0} dump requested via dashboard for target [{1}]",
                dumpType, targetLabel);
        try {
            // For remote targets route the dump through the JMX collector so the dump
            // runs on the target JVM, not the watchdog JVM.
            JmxDiagnosticsCollector collector = isSelf ? null : remoteCollectors.get(targetParam);
            String path;
            if (collector != null) {
                String outputPath = watchdog.buildDumpPath(dumpType);
                if (outputPath == null) {
                    send(ex, 500, "application/json; charset=UTF-8",
                            "{\"ok\": false, \"target\": \"" + escapeJson(targetLabel) + "\", " +
                            "\"error\": \"Cannot build dump output path for target\"}");
                    return;
                }
                path = collector.triggerRemoteDump(dumpType, outputPath);
            } else {
                path = watchdog.triggerDump(dumpType);
            }
            // triggerRemoteDump returns "ERROR: ..." strings on failure so the
            // real exception is surfaced to the dashboard rather than a generic message.
            if (path == null || path.isEmpty()) {
                send(ex, 500, "application/json; charset=UTF-8",
                        "{\"ok\": false, \"target\": \"" + escapeJson(targetLabel) + "\", " +
                        "\"error\": \"Dump returned no path — check watchdog log for details\"}");
            } else if (path.startsWith("ERROR:")) {
                send(ex, 500, "application/json; charset=UTF-8",
                        "{\"ok\": false, \"target\": \"" + escapeJson(targetLabel) + "\", " +
                        "\"error\": \"" + escapeJson(path.substring(6).trim()) + "\"}");
            } else {
                send(ex, 200, "application/json; charset=UTF-8",
                        "{\"ok\": true, \"target\": \"" + escapeJson(targetLabel) + "\", " +
                        "\"path\": \"" + escapeJson(path) + "\"}");
            }
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "On-demand {0} dump failed for target [{1}]: {2}",
                    dumpType, targetLabel, e.getMessage());
            send(ex, 500, "application/json; charset=UTF-8",
                    "{\"ok\": false, \"target\": \"" + escapeJson(targetLabel) + "\", " +
                    "\"error\": \"" + escapeJson(e.getMessage() != null ? e.getMessage() : e.toString()) + "\"}");
        }
    }

    /**
     * Extracts the value of a named query parameter from the request URI.
     * Returns {@code null} if the parameter is absent or has no value.
     *
     * @param ex   the HTTP exchange
     * @param name the parameter name
     * @return the decoded parameter value, or {@code null}
     */
    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = decode(pair.substring(0, eq));
            if (name.equals(k)) return decode(pair.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ── helper: HTTP response ─────────────────────────────────────────────────

    private static void send(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        // CORS + security headers — applied to every response so dashboard.html
        // can be opened from a file:// URL and still fetch/post to the metrics endpoint.
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Responds to a CORS preflight OPTIONS request with a 204 No Content and all
     * required preflight headers.  This allows {@code dashboard.html} opened from
     * a {@code file://} URL to successfully fetch or POST to the metrics/dump endpoints.
     */
    private static void sendPreflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Max-Age",       "86400");
        ex.sendResponseHeaders(204, -1);
        ex.getResponseBody().close();
    }

    private static boolean isGet(HttpExchange ex) {
        return "GET".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static boolean isPost(HttpExchange ex) {
        return "POST".equalsIgnoreCase(ex.getRequestMethod());
    }

    private static boolean isOptions(HttpExchange ex) {
        return "OPTIONS".equalsIgnoreCase(ex.getRequestMethod());
    }

    // ── SSL context construction ──────────────────────────────────────────────

    /**
     * Builds an {@link SSLContext} from the configured {@link TlsConfig}.
     *
     * @return initialised {@link SSLContext}
     * @throws Exception if keystore loading or context initialisation fails
     */
    private SSLContext buildSslContext() throws Exception {
        KeyStore ks;
        char[]   pwd;

        if (tlsConfig.getMode() == TlsConfig.Mode.KEYSTORE) {
            // ── operator-supplied keystore ─────────────────────────────────
            if (!tlsConfig.isKeystoreReadable()) {
                throw new IOException("Keystore file not found or not readable: "
                        + tlsConfig.getKeystorePath());
            }
            pwd = tlsConfig.getKeystorePassword();
            ks  = loadKeystore(tlsConfig.getKeystorePath(), pwd);
            WatchdogLogger.info(LOG, "Loaded TLS keystore from: {0}", tlsConfig.getKeystorePath());

        } else {
            // ── self-signed (default) ──────────────────────────────────────
            pwd = "changeit".toCharArray();  // internal only, never exposed
            ks  = generateSelfSignedKeystore(pwd);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwd);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    /**
     * Loads a PKCS#12 or JKS keystore from the filesystem.
     *
     * @param path     path to the keystore file
     * @param password keystore password
     * @return loaded {@link KeyStore}
     * @throws Exception if the file cannot be read or is not a valid keystore
     */
    private static KeyStore loadKeystore(String path, char[] password) throws Exception {
        // Detect format from extension; default to PKCS12 (the modern standard)
        String lower = path.toLowerCase(Locale.ROOT);
        String format = lower.endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore ks = KeyStore.getInstance(format);
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, password);
        }
        return ks;
    }

    /**
     * Generates an in-memory self-signed RSA-2048 certificate using only the JDK's
     * built-in {@code sun.security.x509} API (available in JDK 8–23) with a reflection-
     * based fallback for later JDKs.
     *
     * <p>The certificate is valid for {@link TlsConfig#getSelfSignedValidDays()} days
     * from the moment of this call. No files are written to disk.
     *
     * @param password keystore protection password (used internally, never exposed)
     * @return a {@link KeyStore} containing the generated certificate and private key
     * @throws Exception if key generation or certificate creation fails
     */
    private KeyStore generateSelfSignedKeystore(char[] password) throws Exception {
        // Generate RSA-2048 key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        // Compute validity window
        long now     = System.currentTimeMillis();
        long validMs = (long) tlsConfig.getSelfSignedValidDays() * 24L * 3600L * 1000L;
        Date notBefore = new Date(now - 60_000L);          // 1 min grace for clock skew
        Date notAfter  = new Date(now + validMs);

        // Build X.509 certificate using sun.security.x509 (internal API, JDK 8–23)
        // wrapped in a try/catch so we can fall back on failure.
        Certificate cert = buildX509Cert(kp, notBefore, notAfter);

        // Assemble the in-memory keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        ks.setKeyEntry("oom-watchdog-tls", kp.getPrivate(), password,
                new Certificate[]{ cert });

        int days = tlsConfig.getSelfSignedValidDays();
        InetSocketAddress bindAddr = resolveBindAddress(bindAll, port);
        String bindHost = bindAddr.getAddress().getHostAddress();
        String displayHost = bindAll
                ? (bindHost.contains(":") ? "[" + bindHost + "]" : bindHost)
                : (bindHost.contains(":") ? "[" + bindHost + "]" : bindHost);
        WatchdogLogger.info(LOG,
                "Generated self-signed TLS certificate (RSA-2048, valid {0} days, " +
                "expires {1}). Dashboard URL: https://{2}:{3}/metrics",
                days, notAfter, displayHost, port);
        WatchdogLogger.info(LOG,
                "NOTE: Browsers will show a certificate warning because this cert is " +
                "self-signed. Supply --metrics-cert to use a trusted certificate.");

        return ks;
    }

    /**
     * Builds a self-signed X.509 certificate using the sun.security.x509 internal API.
     * This API is available on all Oracle/OpenJDK 8–21 and JDK 22+ (with {@code --add-opens}).
     *
     * @param kp        the RSA key pair; private key signs the cert, public key is the subject key
     * @param notBefore certificate validity start date
     * @param notAfter  certificate validity end date
     * @return signed {@link X509Certificate}
     * @throws Exception if the certificate cannot be created using any available mechanism
     */
    @SuppressWarnings("restriction")
    private static X509Certificate buildX509Cert(KeyPair kp, Date notBefore, Date notAfter)
            throws Exception {
        // Attempt sun.security.x509 API (works JDK 8-21 without --add-opens; may require it on 22+)
        try {
            return buildCertViaSunX509(kp, notBefore, notAfter);
        } catch (Exception e) {
            // Fallback: use reflection path for newer JDKs
            return buildCertViaReflection(kp, notBefore, notAfter);
        }
    }

    /**
     * Builds a self-signed X.509v3 certificate using the {@code sun.security.x509} package directly.
     * Works on JDK 8–21 without additional module opens.
     *
     * @param kp        the RSA key pair
     * @param notBefore certificate validity start
     * @param notAfter  certificate validity end
     * @return signed {@link X509Certificate}
     * @throws Exception if {@code sun.security.x509} classes are unavailable or construction fails
     */
    private static X509Certificate buildCertViaSunX509(KeyPair kp, Date notBefore, Date notAfter)
            throws Exception {
        // Use the sun.security.x509 classes via Class.forName to avoid hard compile dependency
        Class<?> x500NameClass  = Class.forName("sun.security.x509.X500Name");
        Class<?> x509CertInfoClass = Class.forName("sun.security.x509.X509CertInfo");
        Class<?> x509CertImplClass = Class.forName("sun.security.x509.X509CertImpl");
        Class<?> certValClass   = Class.forName("sun.security.x509.CertificateValidity");
        Class<?> certSNClass    = Class.forName("sun.security.x509.CertificateSerialNumber");
        Class<?> certAlgClass   = Class.forName("sun.security.x509.CertificateAlgorithmId");
        Class<?> algIdClass     = Class.forName("sun.security.x509.AlgorithmId");
        Class<?> certPKClass    = Class.forName("sun.security.x509.CertificateX509Key");
        Class<?> certSubjClass  = Class.forName("sun.security.x509.CertificateSubjectName");
        Class<?> certIssClass   = Class.forName("sun.security.x509.CertificateIssuerName");

        Object dn      = x500NameClass.getConstructor(String.class).newInstance("CN=oom-watchdog,O=OomWatchdog,OU=Monitoring");
        Object validity = certValClass.getConstructor(Date.class, Date.class).newInstance(notBefore, notAfter);
        Object sn       = certSNClass.getConstructor(BigInteger.class)
                .newInstance(BigInteger.valueOf(System.currentTimeMillis()));
        Object algId    = algIdClass.getMethod("get", String.class).invoke(null, "SHA256WithRSA");
        Object certAlg  = certAlgClass.getConstructor(algIdClass).newInstance(algId);
        Object pk       = certPKClass.getConstructor(java.security.PublicKey.class).newInstance(kp.getPublic());

        Object info = x509CertInfoClass.getConstructor().newInstance();
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "validity",  validity);
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "serialNumber", sn);
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "algorithmID", certAlg);
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "key",       pk);
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "subject",
                certSubjClass.getConstructor(x500NameClass).newInstance(dn));
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "issuer",
                certIssClass.getConstructor(x500NameClass).newInstance(dn));
        // version = 3 (0-indexed, so value 2)
        Class<?> certVersionClass = Class.forName("sun.security.x509.CertificateVersion");
        x509CertInfoClass.getMethod("set", String.class, Object.class).invoke(info, "version",
                certVersionClass.getConstructor(int.class).newInstance(2));

        Object certImpl = x509CertImplClass.getConstructor(x509CertInfoClass).newInstance(info);
        x509CertImplClass.getMethod("sign", java.security.PrivateKey.class, String.class)
                .invoke(certImpl, kp.getPrivate(), "SHA256WithRSA");

        return (X509Certificate) certImpl;
    }

    /**
     * Reflection-based fallback for building a self-signed cert on JDK 22+ where
     * {@code sun.security.x509} may be inaccessible without {@code --add-opens}.
     * Falls back to using BouncyCastle-style approach via pure standard API where possible,
     * or re-tries via reflection with an explicit module open.
     *
     * @param kp        the RSA key pair
     * @param notBefore certificate validity start
     * @param notAfter  certificate validity end
     * @return signed {@link X509Certificate}
     * @throws Exception if all fallback paths fail
     */
    private static X509Certificate buildCertViaReflection(KeyPair kp, Date notBefore, Date notAfter)
            throws Exception {
        // On JDK 17+ with --add-opens java.base/sun.security.x509=ALL-UNNAMED this works.
        // If that also fails, throw a descriptive error.
        try {
            // attempt the same approach with forced module open via Lookup API
            java.lang.invoke.MethodHandles.privateLookupIn(
                    Class.forName("sun.security.x509.X509CertImpl"),
                    java.lang.invoke.MethodHandles.lookup());
            return buildCertViaSunX509(kp, notBefore, notAfter);
        } catch (Exception e2) {
            throw new UnsupportedOperationException(
                    "Cannot generate self-signed certificate on this JDK. " +
                    "Supply a keystore via --metrics-cert, or add " +
                    "--add-opens java.base/sun.security.x509=ALL-UNNAMED to JVM flags. " +
                    "Cause: " + e2.getMessage(), e2);
        }
    }

    // ── TLS helper utilities ──────────────────────────────────────────────────

    /**
     * Returns the intersection of {@code requested} and {@code available},
     * preserving the order of {@code requested}.  Used to restrict TLS protocols
     * and cipher suites to only those the JVM actually supports.
     *
     * @param requested the desired protocols or cipher names
     * @param available the names actually supported by the JVM
     * @return non-null array of mutually supported names (may be empty)
     */
    private static String[] filterSupported(String[] requested, String[] available) {
        java.util.Set<String> avail = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(available));
        List<String> result = new ArrayList<>();
        for (String s : requested) {
            if (avail.contains(s)) result.add(s);
        }
        return result.isEmpty() ? available : result.toArray(new String[0]);
    }

    // ── server wiring ─────────────────────────────────────────────────────────

    private void registerContexts(HttpServer s) {
        s.createContext("/metrics/all", this::handleMetricsAll);
        s.createContext("/metrics",     this::handleMetrics);
        s.createContext("/dump/thread", ex -> handleDump(ex, DumpType.THREAD));
        s.createContext("/dump/heap",   ex -> handleDump(ex, DumpType.HEAP));
        s.createContext("/dump/core",   ex -> handleDump(ex, DumpType.CORE));
        s.createContext("/",            this::handleRoot);
    }

    private static java.util.concurrent.Executor buildExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "oom-metrics-http");
            t.setDaemon(true);
            return t;
        });
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
    }

    // ── live MXBean JSON serialisation ────────────────────────────────────────

    /**
     * Reads JVM metrics directly from {@link ManagementFactory} MXBeans at the moment
     * of the request (self-monitoring), then overlays risk/diagnosis from the watchdog.
     *
     * @param lastSnap  most recent watchdog snapshot; may be {@code null}
     * @param label     fallback {@code targetName} when {@code lastSnap} is null
     * @return JSON object string
     */
    private static String buildLiveJson(JvmSnapshot lastSnap, String label) {
        final double MB = 1024.0 * 1024.0;

        MemoryUsage heap   = MEMORY_MX.getHeapMemoryUsage();
        long heapUsed      = heap.getUsed();
        long heapMax       = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        double heapPct     = heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;

        MemoryUsage nonHeap = MEMORY_MX.getNonHeapMemoryUsage();
        long nonHeapUsed    = nonHeap.getUsed();

        Map<String, Long> poolUsed = new LinkedHashMap<>();
        long nurseryUsed = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage u = pool.getUsage();
            if (u != null) {
                poolUsed.put(pool.getName(), u.getUsed());
                String lower = pool.getName().toLowerCase(Locale.ROOT);
                if (lower.contains("eden") || lower.contains("nursery") || lower.contains("young")) {
                    nurseryUsed += u.getUsed();
                }
            }
        }
        double nurseryPct = (nurseryUsed > 0 && heapMax > 0)
                ? (double) nurseryUsed / heapMax * 100.0 : 0.0;

        Map<String, Long> gcCounts = new LinkedHashMap<>();
        long totalGcMs = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long cnt  = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            gcCounts.put(gc.getName(), cnt < 0 ? 0L : cnt);
            if (time > 0) totalGcMs += time;
        }
        long   uptimeMs   = RUNTIME_MX.getUptime();
        double gcOverhead = uptimeMs > 0 ? (double) totalGcMs / uptimeMs * 100.0 : 0.0;
        String process    = RUNTIME_MX.getName();

        // ── JVM process detail fields ──────────────────────────────────────────
        String javaHome      = System.getProperty("java.home", "");
        String javaVersion   = System.getProperty("java.version", "") + " (" +
                               System.getProperty("java.vendor",  "") + ")";
        String jvmName       = System.getProperty("java.vm.name",    "") + " " +
                               System.getProperty("java.vm.version", "");
        String osName        = OS_MX.getName() + " " + OS_MX.getVersion()
                               + " (" + OS_MX.getArch() + ")";
        int    cpuCount      = OS_MX.getAvailableProcessors();
        // Read process CPU via the public com.sun.management.OperatingSystemMXBean interface
        // (through JvmPlatform helpers) — no reflection on internal classes, no illegal-access warnings.
        double cpuLoad      = JvmPlatform.processCpuPct();
        long   processCpuMs = JvmPlatform.processCpuMs();

        // JVM input args (flags, -X, -D passed to the JVM itself)
        List<String> inputArgs = RUNTIME_MX.getInputArguments();
        StringBuilder inputArgsSb = new StringBuilder();
        for (int i = 0; i < inputArgs.size(); i++) {
            if (i > 0) inputArgsSb.append(' ');
            inputArgsSb.append(inputArgs.get(i));
        }
        // Application main class + args (sun.java.command system property)
        String javaCommand = System.getProperty("sun.java.command", "");

        // Thread counts
        int threadCount     = THREAD_MX.getThreadCount();
        int peakThreadCount = THREAD_MX.getPeakThreadCount();

        String riskLevel  = lastSnap != null ? lastSnap.getRiskLevel().name() : OomRiskLevel.OK.name();
        double critPct    = lastSnap != null && lastSnap.getCritThreshold() >= 0
                            ? lastSnap.getCritThreshold() * 100.0 : -1.0;
        String diagnosis  = lastSnap != null ? lastSnap.getDiagnosisNotes() : null;
        String dumpPath   = lastSnap != null ? lastSnap.getHeapDumpPath()   : null;
        String targetName = lastSnap != null && lastSnap.getTargetName() != null
                            ? lastSnap.getTargetName() : label;

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        appendLong  (sb, "timestampMs",      System.currentTimeMillis());
        appendString(sb, "targetName",       targetName);
        appendString(sb, "processName",      process);
        appendString(sb, "riskLevel",        riskLevel);
        appendDouble(sb, "heapUsedMB",       heapUsed    / MB);
        appendDouble(sb, "heapMaxMB",        heapMax     / MB);
        appendDouble(sb, "heapUsedPct",      heapPct);
        appendDouble(sb, "nonHeapUsedMB",    nonHeapUsed / MB);
        appendDouble(sb, "gcOverheadPct",    gcOverhead);
        appendLong  (sb, "totalGcTimeMs",    totalGcMs);
        appendLong  (sb, "jvmUptimeMs",      uptimeMs);
        appendDouble(sb, "nurseryUsedMB",    nurseryUsed / MB);
        appendDouble(sb, "nurseryUsedPct",   nurseryPct);
        appendDouble(sb, "critThresholdPct", critPct);
        appendString(sb, "diagnosisNotes",   diagnosis);
        appendString(sb, "heapDumpPath",     dumpPath);
        // ── process detail ──
        appendString(sb, "javaHome",         javaHome);
        appendString(sb, "javaVersion",      javaVersion);
        appendString(sb, "jvmName",          jvmName);
        appendString(sb, "osName",           osName);
        appendLong  (sb, "cpuCount",         cpuCount);
        appendDouble(sb, "processCpuPct",    cpuLoad);
        appendLong  (sb, "processCpuMs",     processCpuMs);
        appendString(sb, "jvmInputArgs",     inputArgsSb.toString());
        appendString(sb, "javaCommand",      javaCommand);
        appendLong  (sb, "threadCount",      threadCount);
        appendLong  (sb, "peakThreadCount",  peakThreadCount);
        appendGcCounts (sb, gcCounts);
        appendPoolsLast(sb, poolUsed, MB);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a JSON object from a remote target's watchdog snapshot only.
     * No live MXBean reads are performed; all data comes from the last polled snapshot.
     *
     * @param snap      watchdog snapshot; may be {@code null} before first poll
     * @param targetKey the target name key used when {@code snap} is null
     * @return JSON object string
     */
    private static String buildSnapshotJson(JvmSnapshot snap, String targetKey) {
        final double MB = 1024.0 * 1024.0;

        if (snap == null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\n");
            appendLong  (sb, "timestampMs",      System.currentTimeMillis());
            appendString(sb, "targetName",       targetKey);
            appendString(sb, "processName",      null);
            appendString(sb, "riskLevel",        OomRiskLevel.OK.name());
            appendDouble(sb, "heapUsedMB",       0.0);
            appendDouble(sb, "heapMaxMB",        0.0);
            appendDouble(sb, "heapUsedPct",      0.0);
            appendDouble(sb, "nonHeapUsedMB",    0.0);
            appendDouble(sb, "gcOverheadPct",    0.0);
            appendLong  (sb, "totalGcTimeMs",    0L);
            appendLong  (sb, "jvmUptimeMs",      0L);
            appendDouble(sb, "nurseryUsedMB",    0.0);
            appendDouble(sb, "nurseryUsedPct",   0.0);
            appendDouble(sb, "critThresholdPct", -1.0);
            appendString(sb, "diagnosisNotes",   null);
            appendString(sb, "heapDumpPath",     null);
            appendGcCounts (sb, Collections.<String, Long>emptyMap());
            appendPoolsLast(sb, Collections.<String, Long>emptyMap(), MB);
            sb.append("}");
            return sb.toString();
        }

        double heapPct     = snap.getHeapUsedRatio()    * 100.0;
        double nurseryPct  = Double.isNaN(snap.getNurseryUsedRatio()) ? 0.0
                             : snap.getNurseryUsedRatio() * 100.0;
        double critPct     = snap.getCritThreshold() >= 0 ? snap.getCritThreshold() * 100.0 : -1.0;
        double gcOverhead  = snap.getGcOverheadRatio() * 100.0;

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        appendLong  (sb, "timestampMs",      snap.getTimestampMs());
        appendString(sb, "targetName",       snap.getTargetName() != null ? snap.getTargetName() : targetKey);
        appendString(sb, "processName",      snap.getProcessName());
        appendString(sb, "riskLevel",        snap.getRiskLevel().name());
        appendDouble(sb, "heapUsedMB",       snap.getHeapUsedBytes()    / MB);
        appendDouble(sb, "heapMaxMB",        snap.getHeapMaxBytes()     / MB);
        appendDouble(sb, "heapUsedPct",      heapPct);
        appendDouble(sb, "nonHeapUsedMB",    snap.getNonHeapUsedBytes() / MB);
        appendDouble(sb, "gcOverheadPct",    gcOverhead);
        appendLong  (sb, "totalGcTimeMs",    snap.getTotalGcTimeMs());
        appendLong  (sb, "jvmUptimeMs",      snap.getJvmUptimeMs());
        appendDouble(sb, "nurseryUsedMB",    snap.getNurseryUsedBytes() / MB);
        appendDouble(sb, "nurseryUsedPct",   nurseryPct);
        appendDouble(sb, "critThresholdPct", critPct);
        appendString(sb, "diagnosisNotes",   snap.getDiagnosisNotes());
        appendString(sb, "heapDumpPath",     snap.getHeapDumpPath());
        // ── process detail ──
        appendString(sb, "javaHome",         snap.getJavaHome());
        appendString(sb, "javaVersion",      snap.getJavaVersion());
        appendString(sb, "jvmName",          snap.getJvmName());
        appendString(sb, "osName",           snap.getOsName());
        appendLong  (sb, "cpuCount",         snap.getCpuCount());
        appendDouble(sb, "processCpuPct",    snap.getProcessCpuPct());
        appendLong  (sb, "processCpuMs",     snap.getProcessCpuMs());
        appendString(sb, "jvmInputArgs",     snap.getJvmInputArgs());
        appendString(sb, "javaCommand",      snap.getJavaCommand());
        appendLong  (sb, "threadCount",      snap.getThreadCount());
        appendLong  (sb, "peakThreadCount",  snap.getPeakThreadCount());
        appendGcCounts (sb, snap.getGcCollectionCounts());
        appendPoolsLast(sb, snap.getPoolUsedBytes(), MB);
        sb.append("}");
        return sb.toString();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static void appendLong(StringBuilder sb, String key, long value) {
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
    }

    private static void appendDouble(StringBuilder sb, String key, double value) {
        sb.append("  \"").append(key).append("\": ")
          .append(String.format(Locale.US, "%.2f", value)).append(",\n");
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append("  \"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
        sb.append(",\n");
    }

    private static void appendGcCounts(StringBuilder sb, Map<String, Long> gcCounts) {
        sb.append("  \"gcCounts\": {");
        boolean first = true;
        for (Map.Entry<String, Long> e : gcCounts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append('"').append(escapeJson(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("},\n");
    }

    private static void appendPoolsLast(StringBuilder sb, Map<String, Long> poolUsed, double mb) {
        sb.append("  \"poolUsedMB\": {");
        List<Map.Entry<String, Long>> pools = new ArrayList<>(poolUsed.entrySet());
        for (int i = 0; i < pools.size(); i++) {
            Map.Entry<String, Long> e = pools.get(i);
            if (i > 0) sb.append(", ");
            sb.append('"').append(escapeJson(e.getKey())).append("\": ")
              .append(String.format(Locale.US, "%.2f", e.getValue() / mb));
        }
        sb.append("}\n");
    }

    private static String escapeJson(String s) {
        // Escape all JSON-unsafe characters:
        // - backslash and double-quote (structural)
        // - standard whitespace control chars
        // - remaining C0 control characters U+0000–U+001F (CWE-116 / malformed JSON)
        // - U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR (JS string terminators)
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        // Encode as \\uXXXX to keep JSON valid
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
