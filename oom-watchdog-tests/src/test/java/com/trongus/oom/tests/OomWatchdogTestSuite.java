package com.trongus.oom.tests;

import com.trongus.oom.tests.alert.AlertFormatterTest;
import com.trongus.oom.tests.alert.FileLogAlertChannelTest;
import com.trongus.oom.tests.alert.QRadarAlertChannelTest;
import com.trongus.oom.tests.collector.MxBeanDiagnosticsCollectorTest;
import com.trongus.oom.tests.config.WatchdogConfigTest;
import com.trongus.oom.tests.dump.CompositeDumpServiceTest;
import com.trongus.oom.tests.dump.DumpTypeTest;
import com.trongus.oom.tests.integration.OomWatchdogIntegrationTest;
import com.trongus.oom.tests.integration.QRadarPipelineTest;
import com.trongus.oom.tests.integration.RemoteDumpRoutingTest;
import com.trongus.oom.tests.model.JvmSnapshotTest;
import com.trongus.oom.tests.model.OomRiskLevelTest;
import com.trongus.oom.tests.monitor.ThresholdRiskAssessorTest;
import com.trongus.oom.tests.platform.JvmPlatformTest;
import com.trongus.oom.tests.remote.TargetDescriptorTest;
import com.trongus.oom.tests.remote.TargetRegistryTest;
import com.trongus.oom.tests.remote.WatchdogDaemonTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Aggregates all OOM Watchdog unit and integration test classes into a single
 * executable test suite.
 *
 * <p>Run the entire suite with:
 * <pre>
 *   mvn test -pl oom-watchdog-tests
 * </pre>
 *
 * <p>Or run from an IDE by executing this class directly as a JUnit test.
 *
 * <h2>Test categories</h2>
 * <ul>
 *   <li><strong>model</strong> – {@link OomRiskLevelTest}, {@link JvmSnapshotTest}</li>
 *   <li><strong>config</strong> – {@link WatchdogConfigTest}</li>
 *   <li><strong>dump</strong> – {@link DumpTypeTest}, {@link CompositeDumpServiceTest}</li>
 *   <li><strong>alert</strong> – {@link AlertFormatterTest}, {@link FileLogAlertChannelTest},
 *       {@link QRadarAlertChannelTest}</li>
 *   <li><strong>collector</strong> – {@link MxBeanDiagnosticsCollectorTest}</li>
 *   <li><strong>monitor</strong> – {@link ThresholdRiskAssessorTest}</li>
 *   <li><strong>platform</strong> – {@link JvmPlatformTest}</li>
 *   <li><strong>remote</strong> – {@link TargetDescriptorTest}, {@link TargetRegistryTest},
 *       {@link WatchdogDaemonTest}</li>
 *   <li><strong>integration</strong> – {@link OomWatchdogIntegrationTest},
 *       {@link QRadarPipelineTest}, {@link RemoteDumpRoutingTest}</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.3
 * @since 1.0.0
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
    // model
    OomRiskLevelTest.class,
    JvmSnapshotTest.class,
    // config
    WatchdogConfigTest.class,
    // dump
    DumpTypeTest.class,
    CompositeDumpServiceTest.class,
    // alert
    AlertFormatterTest.class,
    FileLogAlertChannelTest.class,
    QRadarAlertChannelTest.class,
    // collector
    MxBeanDiagnosticsCollectorTest.class,
    // monitor
    ThresholdRiskAssessorTest.class,
    // platform
    JvmPlatformTest.class,
    // remote
    TargetDescriptorTest.class,
    TargetRegistryTest.class,
    WatchdogDaemonTest.class,
    // integration
    OomWatchdogIntegrationTest.class,
    QRadarPipelineTest.class,
    RemoteDumpRoutingTest.class,
})
public class OomWatchdogTestSuite {
    // This class is intentionally empty. It serves only as a JUnit Suite holder.
}
