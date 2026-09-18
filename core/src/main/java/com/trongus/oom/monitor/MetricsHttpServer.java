package com.trongus.oom.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Lightweight HTTP server that exposes the latest {@link JvmSnapshot} as a
 * JSON document on {@code GET /metrics}, enabling the OOM Watchdog Dashboard
 * ({@code dashboard.html}) to poll live JVM health data from a browser.
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <caption>Exposed HTTP endpoints</caption>
 *   <tr><th>Path</th><th>Method</th><th>Response</th></tr>
 *   <tr><td>{@code /metrics}</td><td>GET</td>
 *       <td>JSON object with current JVM metrics (see below)</td></tr>
 *   <tr><td>{@code /}</td><td>GET</td>
 *       <td>{@code 302} redirect to {@code /metrics}</td></tr>
 * </table>
 *
 * <h2>JSON schema ({@code /metrics})</h2>
 * <pre>
 * {
 *   "timestampMs":      1234567890123,
 *   "processName":      "12345@myhost",
 *   "riskLevel":        "WARNING",
 *   "heapUsedMB":       512.3,
 *   "heapMaxMB":        1024.0,
 *   "heapUsedPct":      50.0,
 *   "nonHeapUsedMB":    64.1,
 *   "gcOverheadPct":    3.4,
 *   "totalGcTimeMs":    1230,
 *   "nurseryUsedMB":    128.0,
 *   "nurseryUsedPct":   12.5,
 *   "critThresholdPct": 90.0,
 *   "diagnosisNotes":   "[Assessment] ...",
 *   "heapDumpPath":     null,
 *   "gcCounts":         {"G1 Young Generation": 42},
 *   "poolUsedMB":       {"G1 Eden Space": 64.0}
 * }
 * </pre>
 *
 * <p>The server binds only to {@code localhost} (loopback interface) by default.
 * To expose it on all interfaces (e.g. in a container), pass {@code bindAll=true}.
 *
 * <h2>Usage</h2>
 * <p>Pass {@code --metrics-port 9090} (or any free port) to OOM Watchdog on the CLI.
 * Then open {@code dashboard.html} in a browser and enter
 * {@code http://localhost:9090} in the server URL field.
 *
 * <h2>Security</h2>
 * <p>No authentication is provided.  Bind only to loopback unless you accept the
 * risk of exposing JVM internals on your network.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.3
 * @since 1.7.3
 * @see OomWatchdog#getLastSnapshot()
 */
public final class MetricsHttpServer {

    private static final Logger LOG = WatchdogLogger.forClass(MetricsHttpServer.class);

    private final OomWatchdog watchdog;
    private final int         port;
    private final boolean     bindAll;

    private volatile HttpServer server;

    /**
     * Creates a new metrics server.
     *
     * @param watchdog the running watchdog whose snapshot is served; must not be {@code null}
     * @param port     the TCP port to listen on (1–65535)
     * @param bindAll  {@code true} to bind all interfaces; {@code false} for loopback only
     */
    public MetricsHttpServer(OomWatchdog watchdog, int port, boolean bindAll) {
        if (watchdog == null) throw new NullPointerException("watchdog");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        this.watchdog = watchdog;
        this.port     = port;
        this.bindAll  = bindAll;
    }

    /**
     * Starts the HTTP server and registers the {@code /metrics} and {@code /} handlers.
     *
     * @throws IOException if the server socket cannot be bound
     */
    public void start() throws IOException {
        String host = bindAll ? "0.0.0.0" : "127.0.0.1";
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/",        this::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "oom-metrics-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        WatchdogLogger.info(LOG, "Metrics HTTP server listening on http://{0}:{1}/metrics",
                host, port);
    }

    /**
     * Stops the HTTP server, waiting at most 1 second for in-flight requests to complete.
     */
    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(1);
            WatchdogLogger.info(LOG, "Metrics HTTP server stopped.");
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void handleMetrics(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendResponse(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        JvmSnapshot snap = watchdog.getLastSnapshot();
        String json = snap == null ? "{\"status\":\"no data yet\"}" : toJson(snap);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        sendResponse(ex, 200, null, json);
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Location", "/metrics");
        sendResponse(ex, 302, "text/plain", "Redirecting to /metrics");
    }

    private static void sendResponse(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // -------------------------------------------------------------------------
    // JSON serialisation (no external dependency)
    // -------------------------------------------------------------------------

    private static String toJson(JvmSnapshot s) {
        final double MB = 1024.0 * 1024.0;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        appendLong  (sb, "timestampMs",      s.getTimestampMs());
        appendString(sb, "processName",      s.getProcessName());
        appendString(sb, "targetName",       s.getTargetName());
        appendString(sb, "riskLevel",        s.getRiskLevel().name());
        appendDouble(sb, "heapUsedMB",       s.getHeapUsedBytes()    / MB);
        appendDouble(sb, "heapMaxMB",        s.getHeapMaxBytes()     / MB);
        appendDouble(sb, "heapUsedPct",      s.getHeapUsedRatio()    * 100.0);
        appendDouble(sb, "nonHeapUsedMB",    s.getNonHeapUsedBytes() / MB);
        appendDouble(sb, "gcOverheadPct",    s.getGcOverheadRatio()  * 100.0);
        appendLong  (sb, "totalGcTimeMs",    s.getTotalGcTimeMs());
        appendLong  (sb, "jvmUptimeMs",      s.getJvmUptimeMs());

        double nurseryMB = s.getNurseryUsedBytes() / MB;
        double nurseryPct = Double.isNaN(s.getNurseryUsedRatio()) ? 0.0 : s.getNurseryUsedRatio() * 100.0;
        appendDouble(sb, "nurseryUsedMB",    nurseryMB);
        appendDouble(sb, "nurseryUsedPct",   nurseryPct);
        appendDouble(sb, "critThresholdPct", s.getCritThreshold() < 0 ? -1.0 : s.getCritThreshold() * 100.0);
        appendString(sb, "diagnosisNotes",   s.getDiagnosisNotes());
        appendString(sb, "heapDumpPath",     s.getHeapDumpPath());

        // GC collection counts
        sb.append("  \"gcCounts\": {");
        boolean first = true;
        for (Map.Entry<String, Long> e : s.getGcCollectionCounts().entrySet()) {
            if (!first) sb.append(", ");
            sb.append('"').append(escapeJson(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("},\n");

        // Memory pool usage in MB (last entry — no trailing comma)
        sb.append("  \"poolUsedMB\": {");
        List<Map.Entry<String,Long>> pools = new java.util.ArrayList<>(s.getPoolUsedBytes().entrySet());
        for (int i = 0; i < pools.size(); i++) {
            Map.Entry<String,Long> e = pools.get(i);
            if (i > 0) sb.append(", ");
            sb.append('"').append(escapeJson(e.getKey())).append("\": ")
              .append(String.format("%.2f", e.getValue() / MB));
        }
        sb.append("}\n");

        sb.append("}");
        return sb.toString();
    }

    private static void appendLong(StringBuilder sb, String key, long value) {
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
    }

    private static void appendDouble(StringBuilder sb, String key, double value) {
        sb.append("  \"").append(key).append("\": ")
          .append(String.format("%.2f", value)).append(",\n");
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

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
