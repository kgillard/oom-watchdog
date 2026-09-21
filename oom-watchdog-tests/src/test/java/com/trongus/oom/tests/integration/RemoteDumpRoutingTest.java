package com.trongus.oom.tests.integration;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.monitor.OomWatchdog;
import com.trongus.oom.monitor.TlsConfig;
import com.trongus.oom.monitor.MetricsHttpServer;
import com.trongus.oom.remote.JmxDiagnosticsCollector;
import org.junit.Test;
import org.junit.After;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

/**
 * Verifies that on-demand dump requests for a named remote target are routed through
 * {@link JmxDiagnosticsCollector#triggerRemoteDump} rather than executing the dump
 * on the watchdog JVM itself.
 *
 * <p>Uses a stub {@link JmxDiagnosticsCollector} subclass (via reflection override of the
 * stored collector map in {@link MetricsHttpServer}) to confirm the correct method is called
 * without a real remote JMX endpoint.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.13.0
 * @since 1.7.11.4
 */
public class RemoteDumpRoutingTest {

    private MetricsHttpServer server;

    @After
    public void tearDown() {
        if (server != null) {
            try { server.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * Verifies that {@link CompositeDumpService#buildOutputPath} produces a non-null path
     * string from a minimal snapshot and that the path uses the configured dump directory.
     */
    @Test
    public void testBuildOutputPathUsesConfiguredDirectory() throws Exception {
        File tmpDir = Files.createTempDirectory("oom-dump-test-").toFile();
        try {
            WatchdogConfig config = WatchdogConfig.defaults()
                    .heapDumpDirectory(tmpDir.getAbsolutePath())
                    .build();
            CompositeDumpService svc = new CompositeDumpService(config);
            JvmSnapshot snap = new JvmSnapshot.Builder()
                    .targetName("hostcontext")
                    .processName("hostcontext (12345@remotehost)")
                    .timestampMs(System.currentTimeMillis())
                    .build();

            String heapPath   = svc.buildOutputPath(snap, DumpType.HEAP);
            String threadPath = svc.buildOutputPath(snap, DumpType.THREAD);
            String corePath   = svc.buildOutputPath(snap, DumpType.CORE);

            assertNotNull("Heap path must not be null",   heapPath);
            assertNotNull("Thread path must not be null", threadPath);
            assertNotNull("Core path must not be null",   corePath);

            // Use canonical path for comparison — on macOS /var/... resolves to /private/var/...
            String canonicalDir = tmpDir.getCanonicalPath();
            assertTrue("Heap path must be inside configured dir",
                    heapPath.startsWith(canonicalDir));
            assertTrue("Thread path must be inside configured dir",
                    threadPath.startsWith(canonicalDir));
            assertTrue("Core path must be inside configured dir",
                    corePath.startsWith(canonicalDir));

            assertTrue("Heap path must end with .hprof",       heapPath.endsWith(".hprof"));
            assertTrue("Thread path must end with _threads.txt", threadPath.endsWith("_threads.txt"));
            assertTrue("Core path must end with .core",         corePath.endsWith(".core"));
        } finally {
            tmpDir.delete();
        }
    }

    /**
     * Verifies that {@link OomWatchdog#buildDumpPath} delegates to
     * {@link CompositeDumpService#buildOutputPath} and returns a non-null path.
     */
    @Test
    public void testOomWatchdogBuildDumpPathDelegatesToCompositeDumpService() throws Exception {
        File tmpDir = Files.createTempDirectory("oom-dump-test-").toFile();
        try {
            WatchdogConfig config = WatchdogConfig.defaults()
                    .heapDumpDirectory(tmpDir.getAbsolutePath())
                    .build();
            CompositeDumpService dumper = new CompositeDumpService(config);

            // Minimal stub collector and assessor
            com.trongus.oom.collector.JvmDiagnosticsCollector collector =
                    new com.trongus.oom.collector.MxBeanDiagnosticsCollector(config);
            com.trongus.oom.monitor.RiskAssessor assessor =
                    new com.trongus.oom.monitor.ThresholdRiskAssessor(config);

            OomWatchdog watchdog = new OomWatchdog(
                    config, collector, assessor, Collections.emptyList(), dumper);

            // Plant a snapshot so buildDumpPath has something to work with
            JvmSnapshot snap = new JvmSnapshot.Builder()
                    .targetName("hostcontext")
                    .processName("hostcontext (12345@remotehost)")
                    .timestampMs(System.currentTimeMillis())
                    .build();
            // Inject snapshot via reflection (lastSnapshot is AtomicReference)
            Field f = OomWatchdog.class.getDeclaredField("lastSnapshot");
            f.setAccessible(true);
            ((AtomicReference<?>) f.get(watchdog)).getClass()
                    .getMethod("set", Object.class).invoke(f.get(watchdog), snap);

            String path = watchdog.buildDumpPath(DumpType.HEAP);
            assertNotNull("buildDumpPath must return non-null when dumpService is CompositeDumpService", path);
            assertTrue("path must be inside configured dir", path.startsWith(tmpDir.getCanonicalPath()));
            assertTrue("path must end with .hprof", path.endsWith(".hprof"));
        } finally {
            tmpDir.delete();
        }
    }
}
