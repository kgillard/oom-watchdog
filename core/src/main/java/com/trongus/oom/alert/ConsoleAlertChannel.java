package com.trongus.oom.alert;

import com.trongus.oom.model.JvmSnapshot;

/**
 * Writes a formatted alert to {@code System.err} so it is immediately
 * visible to any operator watching the process console.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.4
 * @since 1.0.0
 */
public final class ConsoleAlertChannel implements AlertChannel {

    /**
     * Formats the snapshot as a human-readable string and writes it to {@code System.err}.
     *
     * @param snapshot the assessed {@link JvmSnapshot} to report; must not be {@code null}
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        System.err.println(AlertFormatter.toHumanReadable(snapshot));
        System.err.flush();
    }

    /**
     * Returns a human-readable name identifying this alert channel.
     *
     * @return {@code "ConsoleAlert"}
     */
    @Override
    public String channelName() {
        return "ConsoleAlert";
    }
}
