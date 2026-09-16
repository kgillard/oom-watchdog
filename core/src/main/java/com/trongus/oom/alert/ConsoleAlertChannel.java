package com.trongus.oom.alert;

import com.trongus.oom.model.JvmSnapshot;

/**
 * Writes a formatted alert to {@code System.err} so it is immediately
 * visible to any operator watching the process console.
 */
public final class ConsoleAlertChannel implements AlertChannel {

    @Override
    public void alert(JvmSnapshot snapshot) {
        System.err.println(AlertFormatter.toHumanReadable(snapshot));
        System.err.flush();
    }

    @Override
    public String channelName() {
        return "ConsoleAlert";
    }
}
