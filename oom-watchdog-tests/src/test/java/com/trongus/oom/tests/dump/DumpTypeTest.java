package com.trongus.oom.tests.dump;

import com.trongus.oom.dump.DumpType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DumpType}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The exact set of defined constants</li>
 *   <li>{@link DumpType#fromString(String)} case-insensitive parsing</li>
 *   <li>{@link DumpType#fromString(String)} hyphen-to-underscore normalisation</li>
 *   <li>Error handling for unrecognised type names</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.9
 * @since 1.0.0
 * @see DumpType
 */
public class DumpTypeTest {

    // ── constant existence ────────────────────────────────────────────────────

    /** Exactly four dump type constants must exist. */
    @Test
    public void testFourConstantsDefined() {
        assertEquals(4, DumpType.values().length);
    }

    /** HEAP constant must be present. */
    @Test
    public void testHeapConstantExists() {
        assertNotNull(DumpType.HEAP);
    }

    /** CORE constant must be present. */
    @Test
    public void testCoreConstantExists() {
        assertNotNull(DumpType.CORE);
    }

    /** THREAD constant must be present. */
    @Test
    public void testThreadConstantExists() {
        assertNotNull(DumpType.THREAD);
    }

    /** CLASS_HISTOGRAM constant must be present. */
    @Test
    public void testClassHistogramConstantExists() {
        assertNotNull(DumpType.CLASS_HISTOGRAM);
    }

    // ── fromString — exact uppercase ─────────────────────────────────────────

    /** {@code "HEAP"} must resolve to {@link DumpType#HEAP}. */
    @Test
    public void testFromStringHeapUppercase() {
        assertSame(DumpType.HEAP, DumpType.fromString("HEAP"));
    }

    /** {@code "CORE"} must resolve to {@link DumpType#CORE}. */
    @Test
    public void testFromStringCoreUppercase() {
        assertSame(DumpType.CORE, DumpType.fromString("CORE"));
    }

    /** {@code "THREAD"} must resolve to {@link DumpType#THREAD}. */
    @Test
    public void testFromStringThreadUppercase() {
        assertSame(DumpType.THREAD, DumpType.fromString("THREAD"));
    }

    /** {@code "CLASS_HISTOGRAM"} must resolve to {@link DumpType#CLASS_HISTOGRAM}. */
    @Test
    public void testFromStringClassHistogramUppercase() {
        assertSame(DumpType.CLASS_HISTOGRAM, DumpType.fromString("CLASS_HISTOGRAM"));
    }

    // ── fromString — lowercase ────────────────────────────────────────────────

    /** {@code "heap"} (lowercase) must resolve to {@link DumpType#HEAP}. */
    @Test
    public void testFromStringHeapLowercase() {
        assertSame(DumpType.HEAP, DumpType.fromString("heap"));
    }

    /** {@code "thread"} must resolve to {@link DumpType#THREAD}. */
    @Test
    public void testFromStringThreadLowercase() {
        assertSame(DumpType.THREAD, DumpType.fromString("thread"));
    }

    /** {@code "class_histogram"} must resolve to {@link DumpType#CLASS_HISTOGRAM}. */
    @Test
    public void testFromStringClassHistogramLowercase() {
        assertSame(DumpType.CLASS_HISTOGRAM, DumpType.fromString("class_histogram"));
    }

    // ── fromString — hyphen normalisation ─────────────────────────────────────

    /** {@code "class-histogram"} (hyphenated) must resolve to {@link DumpType#CLASS_HISTOGRAM}. */
    @Test
    public void testFromStringClassHistogramHyphenated() {
        assertSame(DumpType.CLASS_HISTOGRAM, DumpType.fromString("class-histogram"));
    }

    /** {@code "CLASS-HISTOGRAM"} (uppercase hyphenated) must resolve correctly. */
    @Test
    public void testFromStringClassHistogramUppercaseHyphenated() {
        assertSame(DumpType.CLASS_HISTOGRAM, DumpType.fromString("CLASS-HISTOGRAM"));
    }

    // ── fromString — whitespace trimming ──────────────────────────────────────

    /** Leading/trailing whitespace around the type name must be trimmed. */
    @Test
    public void testFromStringTrimsWhitespace() {
        assertSame(DumpType.HEAP, DumpType.fromString("  heap  "));
    }

    // ── fromString — error handling ───────────────────────────────────────────

    /** An unrecognised type name must throw {@link IllegalArgumentException}. */
    @Test(expected = IllegalArgumentException.class)
    public void testFromStringUnknownNameThrows() {
        DumpType.fromString("unknown_dump_type");
    }

    /** An empty string must throw {@link IllegalArgumentException}. */
    @Test(expected = IllegalArgumentException.class)
    public void testFromStringEmptyThrows() {
        DumpType.fromString("");
    }

    /** {@code null} input must throw {@link NullPointerException}. */
    @Test(expected = NullPointerException.class)
    public void testFromStringNullThrows() {
        DumpType.fromString(null);
    }
}
