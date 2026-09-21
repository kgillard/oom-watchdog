package com.trongus.oom.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Lightweight embeddable HTTP server that exposes on-demand JVM dump endpoints so that
 * an external OOM Watchdog instance running on the <strong>same host</strong> can trigger
 * diagnostics directly via HTTP — no JMX RMI connection required.
 *
 * <h2>Motivation</h2>
 * <p>JMX-triggered heap dumps ({@code HotSpotDiagnosticMXBean.dumpHeap}) and core dumps
 * ({@code com.ibm.jvm:type=Dump}) call out to the <em>remote</em> MBean server.  When the
 * watchdog and the target JVM are on the same host, going through JMX RMI is an unnecessary
 * round-trip that requires JMX ports to be open and subject to RMI firewall rules.  Instead,
 * the target JVM can embed this server, which calls the MXBeans <strong>locally</strong>
 * (i.e. against its own {@link ManagementFactory#getPlatformMBeanServer()}) and returns the
 * dump file path in a simple JSON response.
 *
 * <h2>Endpoints</h2>
 * <table border="1">
 *   <caption>Exposed dump endpoints</caption>
 *   <tr><th>Path</th><th>Method</th><th>Description</th></tr>
 *   <tr><td>{@code /dump/heap}</td><td>POST</td><td>Writes an HPROF heap dump and returns its path</td></tr>
 *   <tr><td>{@code /dump/thread}</td><td>POST</td><td>Writes a thread dump text file and returns its path</td></tr>
 *   <tr><td>{@code /dump/core}</td><td>POST</td><td>Requests a system/core dump (IBM J9 only) and returns its path</td></tr>
 *   <tr><td>{@code /dump/histogram}</td><td>POST</td><td>Writes a class histogram and returns its path</td></tr>
 * </table>
 *
 * <p>All endpoints accept an optional {@code ?dir=<path>} query parameter to override the
 * default output directory.  Successful responses have HTTP 200 and JSON body
 * {@code {"ok":true,"path":"<absolute-path>"}}.  Failures return HTTP 500 with
 * {@code {"ok":false,"error":"<message>"}}.
 *
 * <h2>Embedding</h2>
 * <pre>{@code
 * DumpApiServer server = new DumpApiServer(19999, "/var/log/dumps");
 * server.start();
 * // ... application runs ...
 * server.close();
 * }</pre>
 *
 * <p>Then configure the watchdog with:
 * <pre>{@code
 * target.myapp.dump-api-url = http://localhost:19999
 * }</pre>
 *
 * <h2>Security</h2>
 * <p>Bind to {@code 127.0.0.1} (loopback) by default.  Only the watchdog process on the
 * same host should call these endpoints.  No authentication is implemented; do not expose
 * this server on a public network interface.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.9
 * @since 1.7.12.8
 */
public final class DumpApiServer implements Closeable {

    private static final Logger LOG = WatchdogLogger.forClass(DumpApiServer.class);

    private final int    port;
    private final String defaultDumpDir;

    /**
     * Optional JMX collector used when this server is started by the watchdog on behalf of a
     * remote target.  When non-null, all dump operations are delegated to the target JVM via
     * JMX instead of calling local MXBeans.
     */
    private final JmxDiagnosticsCollector jmxCollector;

    private volatile HttpServer server;

    /**
     * Creates a dump API server bound to the loopback interface.
     * Dump operations call local MXBeans (for use when embedded inside the target JVM).
     *
     * @param port           TCP port to listen on (1–65535)
     * @param defaultDumpDir default directory for dump files; created on demand if absent
     */
    public DumpApiServer(int port, String defaultDumpDir) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        this.port           = port;
        this.defaultDumpDir = defaultDumpDir != null ? defaultDumpDir : System.getProperty("java.io.tmpdir");
        this.jmxCollector   = null;
    }

    /**
     * Creates a dump API server that delegates all dump operations to a remote target JVM via
     * the provided JMX collector.  Intended to be started by {@link WatchdogDaemon} on behalf
     * of a configured target — no code changes are required in the target JVM.
     *
     * @param port           TCP port to listen on (1–65535)
     * @param defaultDumpDir default directory for dump files on the watchdog host
     * @param jmxCollector   open JMX collector for the target; must not be null
     */
    public DumpApiServer(int port, String defaultDumpDir, JmxDiagnosticsCollector jmxCollector) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        this.port           = port;
        this.defaultDumpDir = defaultDumpDir != null ? defaultDumpDir : System.getProperty("java.io.tmpdir");
        this.jmxCollector   = jmxCollector;
    }

    /**
     * Starts the HTTP server on the loopback interface at the configured port.
     *
     * @throws IOException if the server socket cannot be bound
     */
    public synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 4);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "oom-dump-api");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/dump/heap",      ex -> handleDump(ex, "heap"));
        server.createContext("/dump/thread",    ex -> handleDump(ex, "thread"));
        server.createContext("/dump/core",      ex -> handleDump(ex, "core"));
        server.createContext("/dump/histogram", ex -> handleDump(ex, "histogram"));
        server.start();
        WatchdogLogger.info(LOG, "DumpApiServer started on http://127.0.0.1:{0} (defaultDumpDir={1})",
                port, defaultDumpDir);
    }

    /** Stops the HTTP server and releases the port. */
    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            WatchdogLogger.info(LOG, "DumpApiServer stopped.");
        }
    }

    // ── request handling ──────────────────────────────────────────────────────

    private void handleDump(HttpExchange ex, String type) throws IOException {
        // Only POST is accepted
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "{\"ok\":false,\"error\":\"Method Not Allowed\"}");
            return;
        }
        // Consume any request body (ignored)
        ex.getRequestBody().close();

        String dir = queryParam(ex, "dir");
        if (dir == null || dir.isEmpty()) dir = defaultDumpDir;

        // Ensure output directory exists
        File outDir = new File(dir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            send(ex, 500, "{\"ok\":false,\"error\":\"Cannot create dump directory: " + escJson(dir) + "\"}");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        try {
            String path;
            if (jmxCollector != null) {
                // Delegate to the remote target JVM via the open JMX connection
                path = dumpViaJmx(type, outDir, timestamp);
            } else {
                switch (type) {
                    case "heap":      path = dumpHeap(outDir, timestamp);      break;
                    case "thread":    path = dumpThread(outDir, timestamp);    break;
                    case "core":      path = dumpCore(outDir, timestamp);      break;
                    case "histogram": path = dumpHistogram(outDir, timestamp); break;
                    default:
                        send(ex, 400, "{\"ok\":false,\"error\":\"Unknown dump type: " + escJson(type) + "\"}");
                        return;
                }
            }
            if (path == null) {
                send(ex, 500, "{\"ok\":false,\"error\":\"Dump produced no output — check server logs\"}");
            } else {
                send(ex, 200, "{\"ok\":true,\"path\":\"" + escJson(path) + "\"}");
            }
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, e, "DumpApiServer: {0} dump failed: {1}", type, e.getMessage());
            send(ex, 500, "{\"ok\":false,\"error\":\"" + escJson(e.getMessage() != null ? e.getMessage() : e.toString()) + "\"}");
        }
    }

    // ── JMX-delegating dump (watchdog-hosted mode) ────────────────────────────

    /**
     * Triggers a dump on the remote target JVM via the open JMX connection.
     * Maps the HTTP type string to a {@link DumpType} and calls
     * {@link JmxDiagnosticsCollector#triggerRemoteDump}.
     */
    private String dumpViaJmx(String type, File outDir, String timestamp) throws Exception {
        DumpType dumpType;
        switch (type) {
            case "heap":      dumpType = DumpType.HEAP;            break;
            case "thread":    dumpType = DumpType.THREAD;          break;
            case "core":      dumpType = DumpType.CORE;            break;
            case "histogram": dumpType = DumpType.CLASS_HISTOGRAM; break;
            default:
                throw new IllegalArgumentException("Unknown dump type: " + type);
        }
        String outputPath = new File(outDir, type + "_" + timestamp).getAbsolutePath();
        String result = jmxCollector.triggerRemoteDump(dumpType, outputPath);
        WatchdogLogger.info(LOG, "DumpApiServer (JMX mode): triggered {0} dump on remote target → [{1}]",
                type, result);
        // triggerRemoteDump returns "ERROR: ..." on failure
        if (result != null && result.startsWith("ERROR:")) {
            throw new RuntimeException(result);
        }
        return result != null ? result : outputPath;
    }

    // ── dump implementations — called in-process, so MXBeans are local ────────

    /**
     * Writes an HPROF heap dump using {@code HotSpotDiagnosticMXBean.dumpHeap} (HotSpot/OpenJDK)
     * or {@code com.ibm.lang.management:type=JvmMemory createHeapDump()} (IBM J9).
     * These calls operate on <strong>this</strong> JVM's MBean server — no remote JMX.
     */
    private String dumpHeap(File dir, String ts) throws Exception {
        // ── HotSpot / OpenJDK / GraalVM ──────────────────────────────────────
        try {
            Class<?>  cls    = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object    bean   = ManagementFactory.newPlatformMXBeanProxy(
                                   ManagementFactory.getPlatformMBeanServer(),
                                   "com.sun.management:type=HotSpotDiagnostic", cls);
            java.lang.reflect.Method m = cls.getMethod("dumpHeap", String.class, boolean.class);
            File out = new File(dir, "heap_" + ts + ".hprof");
            if (out.exists()) out.delete();
            m.invoke(bean, out.getAbsolutePath(), Boolean.TRUE);
            WatchdogLogger.info(LOG, "Heap dump (HotSpot) written to [{0}]", out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (ClassNotFoundException | java.lang.reflect.InvocationTargetException e) {
            // Not a HotSpot JVM or dumpHeap failed — fall through to IBM J9
            WatchdogLogger.fine(LOG, "HotSpot dumpHeap unavailable or failed, trying IBM J9: {0}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }

        // ── IBM J9 / OpenJ9 — com.ibm.lang.management:type=JvmMemory ─────────
        try {
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "com.ibm.lang.management:type=JvmMemory");
            if (ManagementFactory.getPlatformMBeanServer().isRegistered(on)) {
                Object result = ManagementFactory.getPlatformMBeanServer()
                        .invoke(on, "createHeapDump", new Object[0], new String[0]);
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim()
                        : new File(dir, "heap_" + ts + ".phd").getAbsolutePath();
                WatchdogLogger.info(LOG, "Heap dump (IBM J9 JvmMemory) written to [{0}]", path);
                return path;
            }
        } catch (Exception e2) {
            WatchdogLogger.warning(LOG, "IBM J9 createHeapDump failed: {0}", e2.getMessage());
        }

        // ── IBM J9 fallback — com.ibm.jvm:type=Dump heapDump() ───────────────
        try {
            javax.management.ObjectName on = new javax.management.ObjectName("com.ibm.jvm:type=Dump");
            if (ManagementFactory.getPlatformMBeanServer().isRegistered(on)) {
                File phdOut = new File(dir, "heap_" + ts + ".phd");
                Object result = ManagementFactory.getPlatformMBeanServer()
                        .invoke(on, "heapDump",
                                new Object[]{ "heap:file=" + phdOut.getAbsolutePath() },
                                new String[]{ String.class.getName() });
                String path = result != null && !result.toString().trim().isEmpty()
                        ? result.toString().trim() : phdOut.getAbsolutePath();
                WatchdogLogger.info(LOG, "Heap dump (IBM J9 Dump MBean) written to [{0}]", path);
                return path;
            }
        } catch (Exception e3) {
            WatchdogLogger.warning(LOG, "IBM J9 Dump.heapDump() failed: {0}", e3.getMessage());
        }

        return null; // no supported dump mechanism found
    }

    /**
     * Writes a full thread dump to a text file using {@link ThreadMXBean#dumpAllThreads}.
     * This is universally available on every JMX-enabled JVM.
     */
    private String dumpThread(File dir, String ts) throws IOException {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Full thread dump — ").append(new Date()).append("\n\n");
        for (ThreadInfo ti : infos) sb.append(ti.toString());
        File out = new File(dir, "thread_" + ts + ".txt");
        writeText(out, sb.toString());
        WatchdogLogger.info(LOG, "Thread dump written to [{0}]", out.getAbsolutePath());
        return out.getAbsolutePath();
    }

    /**
     * Requests a system/core dump via {@code com.ibm.jvm:type=Dump systemDump()} (IBM J9 only).
     * Returns {@code null} on non-IBM JVMs since HotSpot cannot produce core dumps in-process.
     */
    private String dumpCore(File dir, String ts) throws Exception {
        javax.management.ObjectName on = new javax.management.ObjectName("com.ibm.jvm:type=Dump");
        if (!ManagementFactory.getPlatformMBeanServer().isRegistered(on)) {
            throw new UnsupportedOperationException(
                    "Core dumps require com.ibm.jvm:type=Dump (IBM J9/OpenJ9). "
                    + "This JVM does not expose that MBean.");
        }
        File dmpOut = new File(dir, "core_" + ts + ".dmp");
        Object result = ManagementFactory.getPlatformMBeanServer()
                .invoke(on, "systemDump",
                        new Object[]{ "system:file=" + dmpOut.getAbsolutePath() },
                        new String[]{ String.class.getName() });
        String path = result != null && !result.toString().trim().isEmpty()
                ? result.toString().trim() : dmpOut.getAbsolutePath();
        WatchdogLogger.info(LOG, "Core dump (IBM J9) written to [{0}]", path);
        return path;
    }

    /**
     * Writes a class histogram via {@code DiagnosticCommand.gcClassHistogram} (HotSpot)
     * or falls back to a best-effort class-count summary via the MBean server.
     */
    private String dumpHistogram(File dir, String ts) throws Exception {
        try {
            javax.management.ObjectName on = new javax.management.ObjectName(
                    "com.sun.management:type=DiagnosticCommand");
            if (ManagementFactory.getPlatformMBeanServer().isRegistered(on)) {
                Object result = ManagementFactory.getPlatformMBeanServer()
                        .invoke(on, "gcClassHistogram",
                                new Object[]{ new String[0] },
                                new String[]{ String[].class.getName() });
                String text = result != null ? result.toString() : "(empty histogram)";
                File out = new File(dir, "histogram_" + ts + ".txt");
                writeText(out, text);
                WatchdogLogger.info(LOG, "Class histogram written to [{0}]", out.getAbsolutePath());
                return out.getAbsolutePath();
            }
        } catch (Exception e) {
            WatchdogLogger.warning(LOG, "DiagnosticCommand.gcClassHistogram failed: {0}", e.getMessage());
        }
        return null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void writeText(File f, String text) throws IOException {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.print(text);
        }
    }

    private static String queryParam(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq);
                if (k.equals(name)) return part.substring(eq + 1);
            }
        }
        return null;
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
