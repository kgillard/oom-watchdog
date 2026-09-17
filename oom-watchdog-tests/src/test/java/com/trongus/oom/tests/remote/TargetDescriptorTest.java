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
 * @version 1.7.0
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
        assertEquals("./dumps/hostcontext", td.getDumpDirectory());
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
}
