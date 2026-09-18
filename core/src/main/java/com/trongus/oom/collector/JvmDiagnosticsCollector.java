package com.trongus.oom.collector;

import com.trongus.oom.model.JvmSnapshot;

/**
 * Single Responsibility: collects raw JVM memory and GC metrics at one point in
 * time and packages them into an immutable {@link JvmSnapshot}.
 *
 * <p>Implementations are expected to:
 * <ul>
 *   <li>Set {@link com.trongus.oom.model.OomRiskLevel#OK} on every returned snapshot
 *       — risk classification is delegated to
 *       {@link com.trongus.oom.monitor.RiskAssessor}.</li>
 *   <li>Be stateful only to the extent required for trend detection (e.g. keeping
 *       a rolling window of post-GC samples).</li>
 *   <li>Be callable from a single scheduler thread without external synchronisation.</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.2
 * @since 1.0.0
 * @see com.trongus.oom.collector.MxBeanDiagnosticsCollector
 * @see com.trongus.oom.monitor.RiskAssessor
 */
public interface JvmDiagnosticsCollector {

    /**
     * Collects the current JVM state and returns an immutable snapshot.
     *
     * <p>The {@link com.trongus.oom.model.OomRiskLevel} on the returned snapshot
     * will always be {@link com.trongus.oom.model.OomRiskLevel#OK}; risk
     * classification is the responsibility of a separate
     * {@link com.trongus.oom.monitor.RiskAssessor}.
     *
     * @return a freshly populated, immutable {@link JvmSnapshot}; never {@code null}
     */
    JvmSnapshot collect();
}
