package com.trongus.oom.tests.remote;

import com.trongus.oom.alert.AlertChannel;
import com.trongus.oom.config.WatchdogConfig;
import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.remote.TargetDescriptor;
import com.trongus.oom.remote.WatchdogDaemon;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WatchdogDaemon}.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.1
 * @since 1.7.0
 */
public class WatchdogDaemonTest {

    private static final class TestAlertChannel implements AlertChannel {
        final List<JvmSnapshot> alerts = new ArrayList<>();

        @Override
        public void alert(JvmSnapshot snapshot) {
            alerts.add(snapshot);
        }

        @Override
        public String channelName() {
            return "TestAlertChannel";
        }
    }

    @Test
    public void testLifecycleStartAndStop() {
        TargetDescriptor t1 = TargetDescriptor.builder("mock-t1", "service:jmx:rmi:///jndi/rmi://localhost:9991/jmxrmi")
                .pollIntervalMs(1000)
                .build();
        TargetDescriptor t2 = TargetDescriptor.builder("mock-t2", "service:jmx:rmi:///jndi/rmi://localhost:9992/jmxrmi")
                .pollIntervalMs(1000)
                .build();

        WatchdogConfig config = WatchdogConfig.defaults().build();
        TestAlertChannel testChannel = new TestAlertChannel();

        WatchdogDaemon daemon = new WatchdogDaemon(
                Arrays.asList(t1, t2),
                config,
                Collections.singletonList(testChannel));

        assertFalse(daemon.isRunning());
        assertEquals(2, daemon.getTargets().size());

        daemon.start();
        assertTrue(daemon.isRunning());
        assertEquals(2, daemon.getActiveWatchdogCount());

        // Stop
        daemon.stop();
        assertFalse(daemon.isRunning());
        assertEquals(0, daemon.getActiveWatchdogCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyTargetsThrows() {
        new WatchdogDaemon(Collections.emptyList(), WatchdogConfig.defaults().build());
    }
}
