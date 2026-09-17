package com.ibm.oomwatchdog.collector;

import com.ibm.oomwatchdog.model.JvmSnapshot;

/**
 * SRP / ISP: single responsibility is to <em>collect</em> raw JVM metrics and
 * produce a {@link JvmSnapshot}.  Risk assessment is done elsewhere.
 */
public interface JvmDiagnosticsCollector {

    /**
     * Collects the current JVM state and returns a snapshot.
     * The {@code riskLevel} on the returned snapshot will be {@code OK} –
     * risk classification is the monitor's job.
     *
     * @return a freshly populated, immutable {@link JvmSnapshot}
     */
    JvmSnapshot collect();
}
