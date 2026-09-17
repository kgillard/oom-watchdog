package com.ibm.oomwatchdog.alert;

import com.ibm.oomwatchdog.model.JvmSnapshot;

/**
 * ISP: each concrete alerter is responsible for exactly one notification
 * channel (console, log file, QRadar syslog, etc.).
 * <p>
 * The watchdog calls {@link #alert(JvmSnapshot)} whenever the risk level
 * crosses a threshold worth reporting for that channel.
 */
public interface AlertChannel {

    /**
     * Send an alert for the given snapshot.
     *
     * @param snapshot fully populated and risk-assessed snapshot
     */
    void alert(JvmSnapshot snapshot);

    /**
     * Human-readable name for this channel, used in log output.
     * Default implementation returns the simple class name.
     */
    default String channelName() {
        return getClass().getSimpleName();
    }
}
