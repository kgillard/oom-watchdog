package com.trongus.oom.tests.model;

import com.trongus.oom.model.OomRiskLevel;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link OomRiskLevel}.
 *
 * <p>Verifies the ordering contract, the number of constants, and any
 * ordinal-based comparisons relied upon by the watchdog pipeline.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.5
 * @since 1.0.0
 * @see OomRiskLevel
 */
public class OomRiskLevelTest {

    /**
     * Verifies that exactly four constants are defined.
     * Adding a level without updating this test is a deliberate breaking change.
     */
    @Test
    public void testExactlyFourLevelsDefined() {
        assertEquals("Expected exactly 4 OomRiskLevel constants",
                4, OomRiskLevel.values().length);
    }

    /**
     * The watchdog compares levels via {@code ordinal()} so the ordering
     * OK < WARNING < CRITICAL < OOM_FIRING must hold.
     */
    @Test
    public void testOrderingOkIsLowest() {
        assertTrue("OK must have the lowest ordinal",
                OomRiskLevel.OK.ordinal() < OomRiskLevel.WARNING.ordinal());
    }

    /** WARNING must be below CRITICAL in ordinal order. */
    @Test
    public void testOrderingWarningBelowCritical() {
        assertTrue("WARNING ordinal must be < CRITICAL ordinal",
                OomRiskLevel.WARNING.ordinal() < OomRiskLevel.CRITICAL.ordinal());
    }

    /** CRITICAL must be below OOM_FIRING in ordinal order. */
    @Test
    public void testOrderingCriticalBelowOomFiring() {
        assertTrue("CRITICAL ordinal must be < OOM_FIRING ordinal",
                OomRiskLevel.CRITICAL.ordinal() < OomRiskLevel.OOM_FIRING.ordinal());
    }

    /**
     * The "at least WARNING" comparison pattern used in {@code OomWatchdog.poll()}
     * must correctly accept WARNING, CRITICAL, and OOM_FIRING, but not OK.
     */
    @Test
    public void testAtLeastWarningComparison() {
        assertTrue(OomRiskLevel.WARNING.ordinal()    >= OomRiskLevel.WARNING.ordinal());
        assertTrue(OomRiskLevel.CRITICAL.ordinal()   >= OomRiskLevel.WARNING.ordinal());
        assertTrue(OomRiskLevel.OOM_FIRING.ordinal() >= OomRiskLevel.WARNING.ordinal());
        assertFalse(OomRiskLevel.OK.ordinal()        >= OomRiskLevel.WARNING.ordinal());
    }

    /**
     * The "at least CRITICAL" comparison must accept only CRITICAL and OOM_FIRING.
     */
    @Test
    public void testAtLeastCriticalComparison() {
        assertTrue(OomRiskLevel.CRITICAL.ordinal()   >= OomRiskLevel.CRITICAL.ordinal());
        assertTrue(OomRiskLevel.OOM_FIRING.ordinal() >= OomRiskLevel.CRITICAL.ordinal());
        assertFalse(OomRiskLevel.WARNING.ordinal()   >= OomRiskLevel.CRITICAL.ordinal());
        assertFalse(OomRiskLevel.OK.ordinal()        >= OomRiskLevel.CRITICAL.ordinal());
    }

    /** Constant names must be retrievable by name (used in logging and LEEF payloads). */
    @Test
    public void testValueOfByName() {
        assertSame(OomRiskLevel.OK,          OomRiskLevel.valueOf("OK"));
        assertSame(OomRiskLevel.WARNING,     OomRiskLevel.valueOf("WARNING"));
        assertSame(OomRiskLevel.CRITICAL,    OomRiskLevel.valueOf("CRITICAL"));
        assertSame(OomRiskLevel.OOM_FIRING,  OomRiskLevel.valueOf("OOM_FIRING"));
    }
}
