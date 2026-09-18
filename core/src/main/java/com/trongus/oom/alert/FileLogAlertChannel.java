package com.trongus.oom.alert;

import com.trongus.oom.logging.WatchdogLogger;
import com.trongus.oom.model.JvmSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

/**
 * Appends a structured alert entry to a rotating log file.
 *
 * <p>Each entry is a single ISO-8601-prefixed line of the compact syslog-style
 * format produced by {@link AlertFormatter#toSingleLine(JvmSnapshot)}.
 * A separate detailed multi-line entry is also appended so the file can be
 * both machine-parsed and human-read.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.2
 * @since 1.0.0
 */
public final class FileLogAlertChannel implements AlertChannel {

    private static final Logger LOG = WatchdogLogger.forClass(FileLogAlertChannel.class);

    private final Path logPath;

    /**
     * Constructs a file log channel writing to the specified path.
     *
     * @param logFilePath absolute or relative path to the log file;
     *                    parent directories are created if they do not exist
     */
    public FileLogAlertChannel(String logFilePath) {
        this.logPath = Paths.get(logFilePath);
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
        } catch (IOException e) {
            WatchdogLogger.warning(LOG, e, "Failed to create log directory: {0}", e.getMessage());
        }
    }

    /**
     * Appends a timestamped single-line entry and a detailed multi-line entry
     * to the configured log file.
     *
     * @param snapshot the assessed {@link JvmSnapshot} to log; must not be {@code null}
     */
    @Override
    public void alert(JvmSnapshot snapshot) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                .format(new Date(snapshot.getTimestampMs()));

        String singleLine  = timestamp + " " + AlertFormatter.toSingleLine(snapshot);
        String multiLine   = "\n" + timestamp + "\n" + AlertFormatter.toHumanReadable(snapshot);

        try {
            Files.write(logPath,
                    (singleLine + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.write(logPath,
                    multiLine.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            WatchdogLogger.warning(LOG, e, "Failed to write alert: {0}", e.getMessage());
        }
    }

    /**
     * Returns a human-readable name identifying this channel, including the absolute log file path.
     *
     * @return channel name string in the form {@code "FileLog(/absolute/path/to/file)"}
     */
    @Override
    public String channelName() {
        return "FileLog(" + logPath.toAbsolutePath() + ")";
    }
}
