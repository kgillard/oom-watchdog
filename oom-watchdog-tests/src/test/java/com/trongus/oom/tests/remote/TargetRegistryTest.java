package com.trongus.oom.tests.remote;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.remote.TargetDescriptor;
import com.trongus.oom.remote.TargetRegistry;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link TargetRegistry}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.2
 * @since 1.7.0
 */
public class TargetRegistryTest {

    @Test
    public void testParseMultiTargetPropertiesString() throws IOException {
        String propertiesContent =
                "# Multi target config\n" +
                "target.hostcontext.jmx-url    = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi\n" +
                "target.hostcontext.warn       = 0.75\n" +
                "target.hostcontext.crit       = 0.85\n" +
                "target.hostcontext.dump-types = heap,thread\n" +
                "target.hostcontext.poll-ms    = 3000\n" +
                "\n" +
                "target.tomcat.jmx-url         = service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi\n" +
                "target.tomcat.warn            = 0.80\n" +
                "target.tomcat.crit            = 0.90\n" +
                "\n" +
                "target.was.jmx-url            = service:jmx:rmi:///jndi/rmi://localhost:8880/jmxrmi\n" +
                "target.was.username           = admin\n" +
                "target.was.password           = secret\n";

        List<TargetDescriptor> targets = TargetRegistry.loadFromString(propertiesContent);
        assertEquals(3, targets.size());

        // Target 1: hostcontext
        TargetDescriptor hostcontext = targets.get(0);
        assertEquals("hostcontext", hostcontext.getName());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi", hostcontext.getJmxUrl());
        assertEquals(0.75, hostcontext.getWarnThreshold(), 1e-6);
        assertEquals(0.85, hostcontext.getCritThreshold(), 1e-6);
        assertEquals(3000L, hostcontext.getPollIntervalMs());
        assertTrue(hostcontext.getDumpTypes().contains(DumpType.HEAP));
        assertTrue(hostcontext.getDumpTypes().contains(DumpType.THREAD));
        assertFalse(hostcontext.getDumpTypes().contains(DumpType.CLASS_HISTOGRAM));

        // Target 2: tomcat
        TargetDescriptor tomcat = targets.get(1);
        assertEquals("tomcat", tomcat.getName());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi", tomcat.getJmxUrl());
        assertEquals(0.80, tomcat.getWarnThreshold(), 1e-6);
        assertEquals(0.90, tomcat.getCritThreshold(), 1e-6);
        assertEquals(TargetDescriptor.DEFAULT_POLL_INTERVAL_MS, tomcat.getPollIntervalMs());
        assertTrue(tomcat.getDumpTypes().isEmpty());

        // Target 3: was
        TargetDescriptor was = targets.get(2);
        assertEquals("was", was.getName());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:8880/jmxrmi", was.getJmxUrl());
        assertEquals("admin", was.getUsername());
        assertEquals("secret", was.getPassword());
    }

    @Test
    public void testLeefFieldsParsedFromProperties() throws IOException {
        String content =
                "target.hostcontext.jmx-url       = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi\n" +
                "target.hostcontext.leef-category = JVM_OOM_QRadar_hostcontext\n" +
                "target.hostcontext.leef-tags     = env=prod,team=platform,region=us-east-1\n";

        List<TargetDescriptor> targets = TargetRegistry.loadFromString(content);
        assertEquals(1, targets.size());

        TargetDescriptor td = targets.get(0);
        assertEquals("JVM_OOM_QRadar_hostcontext", td.getLeefCategory());
        assertEquals("env=prod,team=platform,region=us-east-1", td.getLeefTags());
    }

    @Test
    public void testLeefFieldsAbsentWhenNotInProperties() throws IOException {
        String content = "target.hostcontext.jmx-url = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi\n";

        List<TargetDescriptor> targets = TargetRegistry.loadFromString(content);
        TargetDescriptor td = targets.get(0);

        assertNull(td.getLeefCategory());
        assertNull(td.getLeefTags());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingJmxUrlThrows() throws IOException {
        String invalid = "target.hostcontext.warn = 0.75\n";
        TargetRegistry.loadFromString(invalid);
    }

    @Test
    public void testEmptyPropertiesReturnsEmptyList() throws IOException {
        List<TargetDescriptor> targets = TargetRegistry.loadFromString("# only comments\n");
        assertTrue(targets.isEmpty());
    }

    // ── dump threshold properties ─────────────────────────────────────────────

    @Test
    public void testDumpThresholdsParsedFromProperties() throws IOException {
        String content =
                "target.hostcontext.jmx-url              = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi\n" +
                "target.hostcontext.gc-dump-threshold    = 0.40\n" +
                "target.hostcontext.heap-dump-threshold  = 0.80\n" +
                "target.hostcontext.nursery-dump-threshold = 0.90\n";

        List<TargetDescriptor> targets = TargetRegistry.loadFromString(content);
        assertEquals(1, targets.size());

        TargetDescriptor td = targets.get(0);
        assertEquals(0.40, td.getGcDumpThreshold(),       1e-6);
        assertEquals(0.80, td.getHeapDumpThreshold(),     1e-6);
        assertEquals(0.90, td.getNurseryDumpThreshold(),  1e-6);
    }

    @Test
    public void testDumpThresholdsDefaultToDisabledWhenAbsent() throws IOException {
        String content = "target.hostcontext.jmx-url = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi\n";

        List<TargetDescriptor> targets = TargetRegistry.loadFromString(content);
        TargetDescriptor td = targets.get(0);

        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getGcDumpThreshold(),      1e-9);
        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getHeapDumpThreshold(),    1e-9);
        assertEquals(TargetDescriptor.DUMP_THRESHOLD_DISABLED, td.getNurseryDumpThreshold(), 1e-9);
    }
}
