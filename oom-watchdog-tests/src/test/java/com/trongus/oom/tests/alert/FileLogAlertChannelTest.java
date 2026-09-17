package com.trongus.oom.tests.alert;

import com.trongus.oom.alert.FileLogAlertChannel;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FileLogAlertChannel}.
 *
 * <p>Each test writes to a fresh temporary file under the system temp
 * directory and reads it back to verify both the single-line structured
 * prefix and the multi-line human-readable block.  The temporary file is
 * deleted in {@link #tearDown()}.
 *
 * <p>Because {@code AlertFormatter} is package-private, its formatting is
 * exercised indirectly through the file channel.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.0
 * @since 1.0.0
 * @see FileLogAlertChannel
 */
public class FileLogAlertChannelTest {

    /** Temporary log file used for each test — deleted in tearDown. */
    private Path tempLog;

    /** Channel under test. */
    private FileLogAlertChannel channel;

    private static final long MB = 1024L * 1024L;

    @Before
    public void setUp() throws IOException {
        tempLog = Files.createTempFile("oom-watchdog-test-", ".log");
        // Start with a fresh empty file
        Files.write(tempLog, new byte[0]);
        channel = new FileLogAlertChannel(tempLog.toAbsolutePath().toString());
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(tempLog);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JvmSnapshot buildSnapshot(OomRiskLevel level, long heapUsed, long heapMax) {
        return new JvmSnapshot.Builder()
                .processName("test@localhost")
                .timestampMs(System.currentTimeMillis())
                .heapUsedBytes(heapUsed)
                .heapCommittedBytes(heapMax)
                .heapMaxBytes(heapMax)
                .heapUsedRatio((double) heapUsed / heapMax)
                .nonHeapUsedBytes(50L * MB)
                .nonHeapMaxBytes(-1L)
                .poolUsedBytes(Collections.<String, Long>emptyMap())
                .gcCollectionCounts(Collections.<String, Long>emptyMap())
                .gcCollectionTimesMs(Collections.<String, Long>emptyMap())
                .totalGcTimeMs(0L)
                .jvmUptimeMs(60_000L)
                .gcOverheadRatio(0.02)
                .postGcHeapUsedBytes(-1L)
                .postGcHeapGrowthRatePerMs(Double.NaN)
                .riskLevel(level)
                .diagnosisNotes("test diagnosis")
                .build();
    }

    // ── channelName ───────────────────────────────────────────────────────────

    /**
     * The channel name must include the absolute path of the log file.
     */
    @Test
    public void testChannelNameContainsPath() {
        assertTrue("channelName should contain absolute path",
                channel.channelName().contains(tempLog.toAbsolutePath().toString()));
    }

    /**
     * The channel name must be prefixed with {@code "FileLog("}.
     */
    @Test
    public void testChannelNamePrefix() {
        assertTrue("channelName should start with FileLog(",
                channel.channelName().startsWith("FileLog("));
    }

    // ── alert writes content ──────────────────────────────────────────────────

    /**
     * After a single alert the log file must be non-empty.
     */
    @Test
    public void testAlertWritesContent() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING, 85L * MB, 100L * MB));
        List<String> lines = Files.readAllLines(tempLog, StandardCharsets.UTF_8);
        assertFalse("Log file should not be empty after alert", lines.isEmpty());
    }

    /**
     * The first line written must start with an ISO-8601 timestamp
     * ({@code yyyy-MM-dd}) followed by the structured key=value pairs.
     */
    @Test
    public void testFirstLineHasTimestampPrefix() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING, 85L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        // ISO-8601 date portion present
        assertTrue("First line should contain ISO timestamp", content.matches("(?s)\\d{4}-\\d{2}-\\d{2}.*"));
    }

    /**
     * The single-line section must contain the severity key.
     */
    @Test
    public void testSingleLineContainsSeverity() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.CRITICAL, 92L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain severity=", content.contains("severity=CRITICAL"));
    }

    /**
     * The single-line section must include heap statistics.
     */
    @Test
    public void testSingleLineContainsHeapPct() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING, 85L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain heapPct=", content.contains("heapPct="));
    }

    /**
     * The human-readable multi-line block must be present with the banner header.
     */
    @Test
    public void testMultiLineBlockHasBanner() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING, 85L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain === JVM OOM Alert ===", content.contains("=== JVM OOM Alert ==="));
    }

    /**
     * The multi-line block must include the severity field.
     */
    @Test
    public void testMultiLineBlockHasSeverity() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.CRITICAL, 92L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain Severity line", content.contains("CRITICAL"));
    }

    /**
     * A second alert must be appended — the file must grow, not be overwritten.
     */
    @Test
    public void testSecondAlertAppends() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING,  85L * MB, 100L * MB));
        long sizeAfterFirst = Files.size(tempLog);

        channel.alert(buildSnapshot(OomRiskLevel.CRITICAL, 92L * MB, 100L * MB));
        long sizeAfterSecond = Files.size(tempLog);

        assertTrue("File should grow after second alert", sizeAfterSecond > sizeAfterFirst);
    }

    /**
     * Both WARNING and CRITICAL severity strings must appear when two alerts of
     * different severity are written to the same log.
     */
    @Test
    public void testBothSeveritiesAppearInLog() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING,  85L * MB, 100L * MB));
        channel.alert(buildSnapshot(OomRiskLevel.CRITICAL, 92L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain WARNING",  content.contains("WARNING"));
        assertTrue("Log should contain CRITICAL", content.contains("CRITICAL"));
    }

    /**
     * When the snapshot has a heap dump path set, it must appear in the log.
     */
    @Test
    public void testHeapDumpPathAppearsInLog() throws IOException {
        JvmSnapshot snap = buildSnapshot(OomRiskLevel.CRITICAL, 92L * MB, 100L * MB)
                .withHeapDumpPath("/tmp/oom.hprof");
        channel.alert(snap);
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain heap dump path", content.contains("/tmp/oom.hprof"));
    }

    /**
     * The process name in the snapshot must appear in the structured log line.
     * The single-line format uses the raw process name (no sanitization).
     */
    @Test
    public void testProcessNameInLog() throws IOException {
        channel.alert(buildSnapshot(OomRiskLevel.WARNING, 85L * MB, 100L * MB));
        String content = new String(Files.readAllBytes(tempLog), StandardCharsets.UTF_8);
        assertTrue("Log should contain process name", content.contains("test@localhost"));
    }
}
