package com.trongus.oom.tests.config;

import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.dump.DumpType;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WatchdogConfig} covering:
 * <ul>
 *   <li>Default values from {@link WatchdogConfig#defaults()}</li>
 *   <li>Builder overrides for every field</li>
 *   <li>Validation: {@code warningThreshold} must be less than {@code criticalThreshold}</li>
 *   <li>{@link WatchdogConfig.Builder#dumpTypesFromString(String)} CSV parsing</li>
 *   <li>Immutability of the returned {@link Set} of dump types</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.9
 * @since 1.0.0
 * @see WatchdogConfig
 */
public class WatchdogConfigTest {

    // ── default values ────────────────────────────────────────────────────────

    /** Default warning threshold must be 0.80. */
    @Test
    public void testDefaultWarningThreshold() {
        assertEquals(0.80, WatchdogConfig.defaults().build().getWarningHeapThreshold(), 1e-9);
    }

    /** Default critical threshold must be 0.90. */
    @Test
    public void testDefaultCriticalThreshold() {
        assertEquals(0.90, WatchdogConfig.defaults().build().getCriticalHeapThreshold(), 1e-9);
    }

    /** Default GC overhead threshold must be 0.50. */
    @Test
    public void testDefaultGcOverheadThreshold() {
        assertEquals(0.50, WatchdogConfig.defaults().build().getGcOverheadThreshold(), 1e-9);
    }

    /** Default poll interval must be 5000 ms. */
    @Test
    public void testDefaultPollIntervalMs() {
        assertEquals(5_000L, WatchdogConfig.defaults().build().getPollIntervalMs());
    }

    /** Default leak detection window must be 5. */
    @Test
    public void testDefaultLeakDetectionWindowSize() {
        assertEquals(5, WatchdogConfig.defaults().build().getLeakDetectionWindowSize());
    }

    /** Default dump directory must be {@code ./dumps}. */
    @Test
    public void testDefaultHeapDumpDirectory() {
        assertEquals("./dumps", WatchdogConfig.defaults().build().getHeapDumpDirectory());
    }

    /** Default dump types must be empty (no dumps unless user opts in). */
    @Test
    public void testDefaultDumpTypesIsEmpty() {
        assertTrue(WatchdogConfig.defaults().build().getDumpTypes().isEmpty());
    }

    /** Default QRadar port must be 514. */
    @Test
    public void testDefaultQradarPort() {
        assertEquals(514, WatchdogConfig.defaults().build().getQradarPort());
    }

    // ── builder overrides ─────────────────────────────────────────────────────

    /** Custom warning threshold must be stored and returned correctly. */
    @Test
    public void testCustomWarningThreshold() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .warningHeapThreshold(0.70)
                .build();
        assertEquals(0.70, cfg.getWarningHeapThreshold(), 1e-9);
    }

    /** Custom critical threshold must be stored and returned correctly. */
    @Test
    public void testCustomCriticalThreshold() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .criticalHeapThreshold(0.95)
                .build();
        assertEquals(0.95, cfg.getCriticalHeapThreshold(), 1e-9);
    }

    /** Custom poll interval must be stored correctly. */
    @Test
    public void testCustomPollIntervalMs() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .pollIntervalMs(1_000L)
                .build();
        assertEquals(1_000L, cfg.getPollIntervalMs());
    }

    /** Custom dump directory must be stored correctly. */
    @Test
    public void testCustomHeapDumpDirectory() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .heapDumpDirectory("/var/dumps")
                .build();
        assertEquals("/var/dumps", cfg.getHeapDumpDirectory());
    }

    /** Custom dump types set via {@link Set} must be stored and returned. */
    @Test
    public void testCustomDumpTypes() {
        Set<DumpType> types = EnumSet.of(DumpType.HEAP, DumpType.THREAD);
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypes(types)
                .build();
        assertTrue(cfg.getDumpTypes().contains(DumpType.HEAP));
        assertTrue(cfg.getDumpTypes().contains(DumpType.THREAD));
        assertEquals(2, cfg.getDumpTypes().size());
    }

    /** Custom QRadar host must be stored correctly. */
    @Test
    public void testCustomQradarHost() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .qradarHost("10.0.0.1")
                .build();
        assertEquals("10.0.0.1", cfg.getQradarHost());
    }

    /** Custom QRadar port must be stored correctly. */
    @Test
    public void testCustomQradarPort() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .qradarPort(10514)
                .build();
        assertEquals(10514, cfg.getQradarPort());
    }

    // ── validation ────────────────────────────────────────────────────────────

    /**
     * Building a config where warning ≥ critical must throw
     * {@link IllegalArgumentException}.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationWarningMustBeLessThanCritical() {
        WatchdogConfig.defaults()
                .warningHeapThreshold(0.90)
                .criticalHeapThreshold(0.80)
                .build();
    }

    /**
     * Building a config where warning equals critical must throw
     * {@link IllegalArgumentException}.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationWarningEqualCriticalIsInvalid() {
        WatchdogConfig.defaults()
                .warningHeapThreshold(0.85)
                .criticalHeapThreshold(0.85)
                .build();
    }

    /** Warning threshold of 0.0 (inclusive boundary) must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationWarningThresholdZeroIsInvalid() {
        WatchdogConfig.defaults()
                .warningHeapThreshold(0.0)
                .build();
    }

    /** Warning threshold of 1.0 (inclusive boundary) must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationWarningThresholdOneIsInvalid() {
        WatchdogConfig.defaults()
                .warningHeapThreshold(1.0)
                .criticalHeapThreshold(1.0)
                .build();
    }

    /** Poll interval below 100 ms must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationPollIntervalBelowMinimumIsInvalid() {
        WatchdogConfig.defaults()
                .pollIntervalMs(99L)
                .build();
    }

    /** Poll interval of exactly 100 ms must be accepted. */
    @Test
    public void testValidationPollIntervalAtMinimumIsValid() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .pollIntervalMs(100L)
                .build();
        assertEquals(100L, cfg.getPollIntervalMs());
    }

    /** QRadar port 0 must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationQradarPortZeroIsInvalid() {
        WatchdogConfig.defaults()
                .qradarPort(0)
                .build();
    }

    /** QRadar port 65536 must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationQradarPort65536IsInvalid() {
        WatchdogConfig.defaults()
                .qradarPort(65536)
                .build();
    }

    /** QRadar port 65535 (maximum valid port) must be accepted. */
    @Test
    public void testValidationQradarPortMaxValid() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .qradarPort(65535)
                .build();
        assertEquals(65535, cfg.getQradarPort());
    }

    /** Blank dump directory must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationBlankDumpDirectoryIsInvalid() {
        WatchdogConfig.defaults()
                .heapDumpDirectory("   ")
                .build();
    }

    /** Leak detection window size of 1 must be rejected. */
    @Test(expected = IllegalArgumentException.class)
    public void testValidationLeakWindowSizeOneIsInvalid() {
        WatchdogConfig.defaults()
                .leakDetectionWindowSize(1)
                .build();
    }

    /** Leak detection window size of 2 (minimum) must be accepted. */
    @Test
    public void testValidationLeakWindowSizeTwoIsValid() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .leakDetectionWindowSize(2)
                .build();
        assertEquals(2, cfg.getLeakDetectionWindowSize());
    }

    // ── dumpTypesFromString ───────────────────────────────────────────────────

    /** Single type lowercase CSV must parse to the correct singleton set. */
    @Test
    public void testDumpTypesFromStringSingleType() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypesFromString("heap")
                .build();
        assertEquals(1, cfg.getDumpTypes().size());
        assertTrue(cfg.getDumpTypes().contains(DumpType.HEAP));
    }

    /** Multi-type uppercase CSV must parse to the correct set. */
    @Test
    public void testDumpTypesFromStringMultipleTypes() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypesFromString("HEAP,THREAD,CLASS_HISTOGRAM")
                .build();
        assertEquals(3, cfg.getDumpTypes().size());
        assertTrue(cfg.getDumpTypes().contains(DumpType.HEAP));
        assertTrue(cfg.getDumpTypes().contains(DumpType.THREAD));
        assertTrue(cfg.getDumpTypes().contains(DumpType.CLASS_HISTOGRAM));
    }

    /** Hyphenated type name {@code class-histogram} must parse as CLASS_HISTOGRAM. */
    @Test
    public void testDumpTypesFromStringHyphenated() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypesFromString("class-histogram")
                .build();
        assertTrue(cfg.getDumpTypes().contains(DumpType.CLASS_HISTOGRAM));
    }

    /** Whitespace-padded tokens must be trimmed correctly. */
    @Test
    public void testDumpTypesFromStringWithWhitespace() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypesFromString(" heap , thread ")
                .build();
        assertEquals(2, cfg.getDumpTypes().size());
    }

    /** Invalid type name must throw {@link IllegalArgumentException}. */
    @Test(expected = IllegalArgumentException.class)
    public void testDumpTypesFromStringInvalidType() {
        WatchdogConfig.defaults().dumpTypesFromString("invalid_type").build();
    }

    // ── immutability ──────────────────────────────────────────────────────────

    /** The dump types set returned must be unmodifiable. */
    @Test(expected = UnsupportedOperationException.class)
    public void testDumpTypesSetIsUnmodifiable() {
        WatchdogConfig cfg = WatchdogConfig.defaults()
                .dumpTypes(EnumSet.of(DumpType.HEAP))
                .build();
        cfg.getDumpTypes().add(DumpType.THREAD);
    }
}
