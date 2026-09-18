package com.trongus.oom.alert;

import com.trongus.oom.model.JvmSnapshot;

/**
 * Interface Segregation Principle: each concrete implementation is responsible
 * for exactly one notification channel (console, log file, QRadar syslog, etc.).
 *
 * <p>The watchdog calls {@link #alert(JvmSnapshot)} on every registered channel
 * whenever the risk level is at or above {@code WARNING}.  Implementations must
 * not throw checked exceptions; all I/O failures should be caught internally and
 * logged to {@code System.err} so that a failed channel never silences others.
 *
 * <p>Implementations must be thread-safe: {@link #alert(JvmSnapshot)} may be
 * called concurrently from the watchdog's scheduler thread.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.2
 * @since 1.0.0
 * @see com.trongus.oom.alert.ConsoleAlertChannel
 * @see com.trongus.oom.alert.FileLogAlertChannel
 * @see com.trongus.oom.alert.QRadarAlertChannel
 */
public interface AlertChannel {

    /**
     * Sends an alert for the supplied snapshot to this channel.
     *
     * <p>Implementations must not propagate exceptions: any internal failure
     * (network error, I/O error, serialisation error) must be caught and logged
     * so that remaining channels in the chain are still notified.
     *
     * @param snapshot fully populated and risk-assessed {@link JvmSnapshot};
     *                 never {@code null}
     */
    void alert(JvmSnapshot snapshot);

    /**
     * Returns a human-readable identifier for this channel, used in diagnostic
     * log output to attribute alert deliveries and errors.
     *
     * <p>The default implementation returns the simple class name.
     *
     * @return channel name string; never {@code null}
     */
    default String channelName() {
        return getClass().getSimpleName();
    }
}
