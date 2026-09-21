package com.trongus.oom.harness;

import com.trongus.oom.model.JvmSnapshot;
import com.trongus.oom.model.OomRiskLevel;
import com.trongus.oom.alert.AlertChannel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe, in-memory {@link AlertChannel} implementation used by the test harness
 * to capture alert notifications and generated diagnostic dump paths.
 *
 * <h2>Design Rationale</h2>
 * <p>During automated testing, standard alert channels (such as console stdout or external syslog)
 * do not expose programmatically verifiable assertions. {@code HarnessAlertRecorder} intercepts
 * published {@link JvmSnapshot} events in-memory, tabulating alert counts per {@link OomRiskLevel}
 * and recording generated dump file locations so the test harness can assert on watchdog
 * escalation correctness prior to JVM termination.
 *
 * <h2>Thread Safety</h2>
 * <p>All mutable state is either an {@link AtomicInteger} (for counters) or a
 * {@link CopyOnWriteArrayList} (for dump paths), so readers — including
 * {@link TestHarnessMain#printResults} — never need to hold an external lock.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.7
 * @since 1.0.0
 * @see com.trongus.oom.alert.AlertChannel
 * @see com.trongus.oom.model.JvmSnapshot
 * @see com.trongus.oom.model.OomRiskLevel
 * @see com.trongus.oom.harness.TestHarnessMain
 */
final class HarnessAlertRecorder implements AlertChannel {

    /**
     * Cumulative tally of {@link OomRiskLevel#WARNING} alerts received during the test run.
     * Uses {@link AtomicInteger} for thread-safe increment-and-read without external locking.
     */
    final AtomicInteger warnCount = new AtomicInteger(0);

    /**
     * Cumulative tally of {@link OomRiskLevel#CRITICAL} alerts received during the test run.
     * Uses {@link AtomicInteger} for thread-safe increment-and-read without external locking.
     */
    final AtomicInteger critCount = new AtomicInteger(0);

    /**
     * File paths where diagnostic dumps were written upon escalation.
     * {@link CopyOnWriteArrayList} provides thread-safe writes from the watchdog
     * thread and lock-free iteration from the results-printing thread.
     */
    final List<String> dumpPaths = new CopyOnWriteArrayList<>();

    /**
     * Default package-private constructor for the test alert recorder.
     */
    HarnessAlertRecorder() {}

    /**
     * Records an alert dispatch event from the watchdog engine.
     * <p>Inspects the {@link JvmSnapshot#getRiskLevel()} to increment corresponding warning or
     * critical counters, and records the snapshot's heap dump path if present.
     *
     * @param snapshot the diagnostic snapshot representing the alert event
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        // Atomically increment the appropriate counter
        if (snapshot.getRiskLevel() == OomRiskLevel.WARNING) {
            warnCount.incrementAndGet();
        }
        if (snapshot.getRiskLevel() == OomRiskLevel.CRITICAL) {
            critCount.incrementAndGet();
        }
        // CopyOnWriteArrayList.add() is thread-safe; no external lock needed
        if (snapshot.getHeapDumpPath() != null) {
            dumpPaths.add(snapshot.getHeapDumpPath());
        }
    }

    /**
     * Returns the human-readable identifier for this alert channel.
     *
     * @return the string name {@code "HarnessAlertRecorder"}
     */
    @Override
    public String channelName() {
        return "HarnessAlertRecorder";
    }
}
