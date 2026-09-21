package com.trongus.oom.tests.remote;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.remote.TargetDescriptor;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TargetDescriptor}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.5
 * @since 1.7.0
 */
public class TargetDescriptorTest {

    @Test
    public void testDefaultValues() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .build();

        assertEquals("hostcontext", td.getName());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi", td.getJmxUrl());
        assertNull(td.getUsername());
        assertNull(td.getPassword());
        assertEquals(TargetDescriptor.DEFAULT_WARN_THRESHOLD, td.getWarnThreshold(), 1e-6);
        assertEquals(TargetDescriptor.DEFAULT_CRIT_THRESHOLD, td.getCritThreshold(), 1e-6);
        assertEquals(TargetDescriptor.DEFAULT_GC_THRESHOLD, td.getGcThreshold(), 1e-6);
        assertEquals(TargetDescriptor.DEFAULT_POLL_INTERVAL_MS, td.getPollIntervalMs());
        assertTrue(td.getDumpTypes().isEmpty());
        assertNull("Default dump directory should be null (inherits from --dump-dir)", td.getDumpDirectory());
    }

    @Test
    public void testCustomValues() {
        TargetDescriptor td = TargetDescriptor.builder("tomcat", "service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi")
                .credentials("admin", "secret123")
                .warnThreshold(0.70)
                .critThreshold(0.85)
                .gcThreshold(0.40)
                .pollIntervalMs(2500)
                .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
                .dumpDirectory("/var/dumps/tomcat")
                .build();

        assertEquals("tomcat", td.getName());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi", td.getJmxUrl());
        assertEquals("admin", td.getUsername());
        assertEquals("secret123", td.getPassword());
        assertEquals(0.70, td.getWarnThreshold(), 1e-6);
        assertEquals(0.85, td.getCritThreshold(), 1e-6);
        assertEquals(0.40, td.getGcThreshold(), 1e-6);
        assertEquals(2500L, td.getPollIntervalMs());
        assertTrue(td.getDumpTypes().contains(DumpType.HEAP));
        assertTrue(td.getDumpTypes().contains(DumpType.THREAD));
        assertFalse(td.getDumpTypes().contains(DumpType.CORE));
        assertEquals("/var/dumps/tomcat", td.getDumpDirectory());
    }

    @Test
    public void testPasswordMaskedInToString() {
        TargetDescriptor td = TargetDescriptor.builder("liberty", "service:jmx:rmi:///jndi/rmi://localhost:9443/jmxrmi")
                .credentials("admin", "superSecretPass")
                .build();

        String str = td.toString();
        assertTrue(str.contains("***"));
        assertFalse(str.contains("superSecretPass"));
    }

    @Test
    public void testLeefCustomisation() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .leefCategory("JVM_OOM_QRadar_hostcontext")
                .leefTags("env=prod,team=platform")
                .build();

        assertEquals("JVM_OOM_QRadar_hostcontext", td.getLeefCategory());
        assertEquals("env=prod,team=platform", td.getLeefTags());
    }

    @Test
    public void testLeefFieldsNullByDefault() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .build();

        assertNull(td.getLeefCategory());
        assertNull(td.getLeefTags());
    }

    @Test
    public void testBlankLeefCategoryTreatedAsNull() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .leefCategory("   ")
                .build();

        assertNull(td.getLeefCategory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBlankNameThrows() {
        TargetDescriptor.builder("   ", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBlankUrlThrows() {
        TargetDescriptor.builder("hostcontext", "  ");
    }

    @Test(expected = IllegalStateException.class)
    public void testWarnGreaterOrEqualCritThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .warnThreshold(0.90)
                .critThreshold(0.80)
                .build();
    }

    // ── dump threshold fields ─────────────────────────────────────────────────

    @Test
    public void testDumpThresholdsDefaultToDisabled() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext",
                "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi").build();

        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getGcDumpThreshold(),    1e-9);
        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getHeapDumpThreshold(),  1e-9);
        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getNurseryDumpThreshold(), 1e-9);
    }

    @Test
    public void testDumpThresholdsSetViaBuilder() {
        TargetDescriptor td = TargetDescriptor.builder("hostcontext",
                "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .gcDumpThreshold(0.40)
                .heapDumpThreshold(0.80)
                .nurseryDumpThreshold(0.90)
                .build();

        assertEquals(0.40, td.getGcDumpThreshold(),       1e-9);
        assertEquals(0.80, td.getHeapDumpThreshold(),     1e-9);
        assertEquals(0.90, td.getNurseryDumpThreshold(),  1e-9);
    }

    @Test
    public void testDumpThresholdsSentinelConstantIsMinusOne() {
        assertEquals(-1.0, TargetDescriptor.DUMP_THRESHOLD_DISABLED, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGcDumpThresholdZeroThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .gcDumpThreshold(0.0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGcDumpThresholdOneThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .gcDumpThreshold(1.0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHeapDumpThresholdZeroThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .heapDumpThreshold(0.0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHeapDumpThresholdOneThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .heapDumpThreshold(1.0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNurseryDumpThresholdZeroThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .nurseryDumpThreshold(0.0)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNurseryDumpThresholdOneThrows() {
        TargetDescriptor.builder("hostcontext", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi")
                .nurseryDumpThreshold(1.0)
                .build();
    }
}
