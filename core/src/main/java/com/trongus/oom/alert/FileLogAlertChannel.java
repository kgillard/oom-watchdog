package com.trongus.oom.alert;

import com.trongus.oom.model.JvmSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Appends a structured alert entry to a rotating log file.
 *
 * <p>Each entry is a single ISO-8601-prefixed line of the compact syslog-style
 * format produced by {@link AlertFormatter#toSingleLine(JvmSnapshot)}.
 * A separate detailed multi-line entry is also appended so the file can be
 * both machine-parsed and human-read.
 */
public final class FileLogAlertChannel implements AlertChannel {

    private final Path logPath;

    /**
     * @param logFilePath absolute or relative path to the log file;
     *                    parent directories are created if they do not exist.
     */
    public FileLogAlertChannel(String logFilePath) {
        this.logPath = Paths.get(logFilePath);
        try {
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
        } catch (IOException e) {
            System.err.println("[OomWatchdog][FileLog] Failed to create log directory: " + e.getMessage());
        }
    }

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
            System.err.println("[OomWatchdog][FileLog] Failed to write alert: " + e.getMessage());
        }
    }

    @Override
    public String channelName() {
        return "FileLog(" + logPath.toAbsolutePath() + ")";
    }
}
