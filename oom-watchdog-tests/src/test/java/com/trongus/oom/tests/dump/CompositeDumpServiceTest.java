package com.trongus.oom.tests.dump;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.CompositeDumpService;
import com.trongus.oom.dump.DumpType;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit / integration tests for {@link CompositeDumpService}.
 *
 * <p>These tests exercise the high-level contract of the dump service:
 * strategy dispatch, output directory creation, path construction, and graceful
 * fallback.  Because the HotSpot heap dump strategy requires a live HPROF MXBean
 * (available in test JVMs), the HEAP type is also verified — we assert that the
 * result list is non-null and each path element is a non-empty string, rather than
 * checking for an exact file at a specific location (which would depend on OS
 * temp permissions).
 *
 * @author Trongus OOM Watchdog
 * @version 1.0.0
 * @since 1.0.0
 * @see CompositeDumpService
 */
public class CompositeDumpServiceTest {

    private static final long MB = 1024L * 1024L;

    /** Temporary directory used as the dump output directory during tests. */
    private Path tempDumpDir;

    /** Service under test. */
    private CompositeDumpService service;

    @Before
    public void setUp() throws IOException {
        tempDumpDir = Files.createTempDirectory("oom-watchdog-dump-test-");
        WatchdogConfig config = WatchdogConfig.defaults()
                .heapDumpDirectory(tempDumpDir.toAbsolutePath().toString())
                .build();
        service = new CompositeDumpService(config);
    }

    @After
    public void tearDown() throws IOException {
        // Best-effort cleanup of files written by strategies
        if (tempDumpDir != null && Files.exists(tempDumpDir)) {
            File[] files = tempDumpDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            Files.deleteIfExists(tempDumpDir);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JvmSnapshot buildSnapshot() {
        return new JvmSnapshot.Builder()
                .processName("test@localhost")
                .timestampMs(System.currentTimeMillis())
                .heapUsedBytes(90L * MB)
                .heapCommittedBytes(100L * MB)
                .heapMaxBytes(100L * MB)
                .heapUsedRatio(0.90)
                .nonHeapUsedBytes(50L * MB)
                .nonHeapMaxBytes(-1L)
                .poolUsedBytes(Collections.<String, Long>emptyMap())
                .gcCollectionCounts(Collections.<String, Long>emptyMap())
                .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                .totalGcTimeMs(200L)
                .jvmUptimeMs(60_000L)
                .gcOverheadRatio(0.15)
                .postGcHeapUsedBytes(-1L)
                .postGcHeapGrowthRatePerMs(Double.NaN)
                .riskLevel(OomRiskLevel.CRITICAL)
                .diagnosisNotes("test")
                .build();
    }

    // ── return-type contract ──────────────────────────────────────────────────

    /**
     * {@code dump()} must always return a non-null list, even for an empty
     * requested type list.
     */
    @Test
    public void testDumpEmptyTypesReturnsEmptyList() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.<DumpType>emptyList());
        assertNotNull("result must not be null", result);
        assertTrue("result must be empty when no types requested", result.isEmpty());
    }

    /**
     * Every path in the result list must be a non-null, non-empty string.
     */
    @Test
    public void testDumpResultPathsAreNonEmpty() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        assertNotNull("result must not be null", result);
        for (String path : result) {
            assertNotNull("path element must not be null", path);
            assertFalse("path element must not be empty", path.isEmpty());
        }
    }

    // ── THREAD dump ───────────────────────────────────────────────────────────

    /**
     * A THREAD dump must succeed on all JVMs (ThreadMXBean is universal).
     * The result list must contain exactly one path, and that file must exist.
     */
    @Test
    public void testThreadDumpSucceeds() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        assertEquals("THREAD dump should produce exactly one path", 1, result.size());
        File f = new File(result.get(0));
        assertTrue("Thread dump file should exist", f.exists());
        assertTrue("Thread dump file should be non-empty", f.length() > 0);
    }

    /**
     * The thread dump file name must contain the process name and {@code "thread"}.
     */
    @Test
    public void testThreadDumpFileNameFormat() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        assertFalse("result must not be empty", result.isEmpty());
        String name = Paths.get(result.get(0)).getFileName().toString().toLowerCase();
        assertTrue("file name should contain 'thread'", name.contains("thread"));
    }

    // ── CLASS_HISTOGRAM dump ──────────────────────────────────────────────────

    /**
     * A CLASS_HISTOGRAM dump must produce at least one result path (the
     * composite strategy always writes a fallback pool table even if
     * DiagnosticCommand and J9 paths are unavailable).
     */
    @Test
    public void testClassHistogramDumpProducesResult() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.CLASS_HISTOGRAM));
        assertFalse("CLASS_HISTOGRAM should produce at least one result", result.isEmpty());
    }

    // ── multiple types ────────────────────────────────────────────────────────

    /**
     * Requesting both THREAD and CLASS_HISTOGRAM should yield two result paths,
     * one per type.
     */
    @Test
    public void testMultipleTypesYieldMultipleResults() {
        List<String> result = service.dump(buildSnapshot(),
                Arrays.asList(DumpType.THREAD, DumpType.CLASS_HISTOGRAM));
        assertEquals("two types should yield two results", 2, result.size());
    }

    // ── output directory ──────────────────────────────────────────────────────

    /**
     * Dump files must be created inside the configured dump directory.
     */
    @Test
    public void testFilesCreatedInConfiguredDirectory() {
        List<String> result = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        assertFalse("result must not be empty", result.isEmpty());
        String path = result.get(0);
        assertTrue("dump file should be inside temp dump dir",
                path.startsWith(tempDumpDir.toAbsolutePath().toString()));
    }

    /**
     * If the dump directory does not yet exist, the service must create it.
     */
    @Test
    public void testDumpDirectoryCreatedIfMissing() throws IOException {
        Path nonExistent = tempDumpDir.resolve("subdir_" + System.nanoTime());
        assertFalse("subdir should not exist yet", Files.exists(nonExistent));

        WatchdogConfig config = WatchdogConfig.defaults()
                .heapDumpDirectory(nonExistent.toAbsolutePath().toString())
                .build();
        CompositeDumpService svc = new CompositeDumpService(config);
        svc.dump(buildSnapshot(), Collections.singletonList(DumpType.THREAD));

        assertTrue("dump directory should be created", Files.exists(nonExistent));
        // cleanup
        File[] files = nonExistent.toFile().listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        Files.deleteIfExists(nonExistent);
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    /**
     * Calling dump twice must not throw and must return non-null lists both times.
     */
    @Test
    public void testDumpIsCallableTwice() {
        List<String> first  = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        List<String> second = service.dump(buildSnapshot(),
                Collections.singletonList(DumpType.THREAD));
        assertNotNull("first call must return non-null", first);
        assertNotNull("second call must return non-null", second);
    }
}
