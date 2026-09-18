package com.trongus.oom.tests.platform;

import com.trongus.oom.platform.JvmPlatform;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link JvmPlatform}.
 *
 * <p>Platform detection is largely governed by system properties set by
 * the JVM at startup, so many of these tests assert the <em>contract</em>
 * (return types, consistency rules, string content) rather than exact values
 * that would differ between JDK vendors and versions.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.10
 * @since 1.0.0
 * @see JvmPlatform
 */
public class JvmPlatformTest {

    // ── JDK version ──────────────────────────────────────────────────────────

    /**
     * The detected JDK version must be a realistic positive integer.
     * The test accepts any value from 8 (minimum supported) to 99 (future-proof).
     */
    @Test
    public void testJdkVersionInReasonableRange() {
        int ver = JvmPlatform.JDK_VERSION;
        assertTrue("JDK version should be >= 8", ver >= 8);
        assertTrue("JDK version should be < 100 (future-proof)", ver < 100);
    }

    /**
     * The JDK version detected at class load must be consistent with
     * the {@code java.specification.version} system property.
     */
    @Test
    public void testJdkVersionMatchesSystemProperty() {
        String spec = System.getProperty("java.specification.version", "1.8");
        int expected;
        if (spec.startsWith("1.")) {
            expected = Integer.parseInt(spec.substring(2));
        } else {
            expected = Integer.parseInt(spec.split("\\.")[0]);
        }
        assertEquals("JDK_VERSION should match java.specification.version",
                expected, JvmPlatform.JDK_VERSION);
    }

    // ── PID ───────────────────────────────────────────────────────────────────

    /**
     * The PID must be a positive long, or −1 if it could not be determined.
     * The test only asserts that the field is non-zero (a PID of 0 is not valid
     * on any mainstream OS).
     */
    @Test
    public void testPidIsNonZero() {
        assertTrue("PID should be > 0 or -1",
                JvmPlatform.PID > 0 || JvmPlatform.PID == -1);
    }

    /**
     * When running in a standard test environment the PID should be detectable
     * and therefore positive.
     */
    @Test
    public void testPidPositiveInNormalJvm() {
        // If this test runs on a supported JVM the PID must be positive.
        // Only skip the assertion on exotic environments where detection is
        // explicitly expected to fail (PID == -1).
        if (JvmPlatform.PID != -1) {
            assertTrue("PID should be positive", JvmPlatform.PID > 0);
        }
    }

    // ── vendor flags mutual exclusion ────────────────────────────────────────

    /**
     * IS_GRAAL_NATIVE and IS_J9 are mutually exclusive: a JVM cannot be both.
     */
    @Test
    public void testGraalNativeAndJ9AreMutuallyExclusive() {
        assertFalse("IS_GRAAL_NATIVE and IS_J9 cannot both be true",
                JvmPlatform.IS_GRAAL_NATIVE && JvmPlatform.IS_J9);
    }

    /**
     * IS_GRAAL_NATIVE and IS_GRAAL_JVM are mutually exclusive by definition.
     */
    @Test
    public void testGraalNativeAndGraalJvmAreMutuallyExclusive() {
        assertFalse("IS_GRAAL_NATIVE and IS_GRAAL_JVM cannot both be true",
                JvmPlatform.IS_GRAAL_NATIVE && JvmPlatform.IS_GRAAL_JVM);
    }

    /**
     * IS_HOTSPOT and IS_J9 are mutually exclusive.
     */
    @Test
    public void testHotSpotAndJ9AreMutuallyExclusive() {
        assertFalse("IS_HOTSPOT and IS_J9 cannot both be true",
                JvmPlatform.IS_HOTSPOT && JvmPlatform.IS_J9);
    }

    // ── summary ───────────────────────────────────────────────────────────────

    /**
     * {@code summary()} must return a non-null, non-empty string.
     */
    @Test
    public void testSummaryNonEmpty() {
        String s = JvmPlatform.summary();
        assertNotNull("summary() must not be null", s);
        assertFalse("summary() must not be empty", s.isEmpty());
    }

    /**
     * The summary string must contain key diagnostic fields: JDK, pid, vendor.
     */
    @Test
    public void testSummaryContainsDiagnosticFields() {
        String s = JvmPlatform.summary();
        assertTrue("summary should contain 'JDK='",  s.contains("JDK="));
        assertTrue("summary should contain 'pid='",  s.contains("pid="));
        assertTrue("summary should contain 'vendor='", s.contains("vendor="));
    }
}
