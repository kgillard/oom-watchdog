package com.trongus.oom.logging;

import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Structured single-line log formatter for {@link WatchdogLogger}.
 *
 * <h2>Output format</h2>
 * <pre>
 * 2026-09-17T08:00:00.123+1000 [INFO ] [pid=12345@prod-host] [thread=oom-watchdog] com.trongus.oom.monitor.OomWatchdog – message
 * </pre>
 *
 * <p>Each record includes, in order:
 * <ol>
 *   <li>ISO-8601 timestamp with millisecond precision and timezone offset</li>
 *   <li>Fixed-width log level label (5 chars, left-aligned)</li>
 *   <li>Process identifier ({@code pid=<pid>@<hostname>})</li>
 *   <li>Thread name</li>
 *   <li>Logger name (fully-qualified class name)</li>
 *   <li>Message text</li>
 *   <li>Exception stack trace (if a {@link Throwable} is attached)</li>
 * </ol>
 *
 * <p>This formatter is package-private: callers obtain a pre-configured
 * {@link java.util.logging.Handler} from {@link WatchdogLogger} rather than
 * constructing this class directly.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.1
 * @since 1.6.0
 * @see WatchdogLogger
 */
final class WatchdogLogFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
                             .withZone(ZoneId.systemDefault());

    /** Process-identity string ({@code pid=<pid>@<host>}), computed once at class-load time. */
    private static final String PROCESS_ID = buildProcessId();

    /** Line separator for the current platform. */
    private static final String NL = System.lineSeparator();

    /** {@inheritDoc} */
    @Override
    public String format(LogRecord record) {
        String timestamp  = TIMESTAMP_FMT.format(Instant.ofEpochMilli(record.getMillis()));
        String level      = padLevel(record.getLevel().getName());
        String thread     = record.getSourceMethodName() != null
                            ? Thread.currentThread().getName()
                            : Thread.currentThread().getName();
        String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "com.trongus.oom";
        String message    = formatMessage(record);

        StringBuilder sb = new StringBuilder(256);
        sb.append(timestamp)
          .append(" [").append(level).append(']')
          .append(" [").append(PROCESS_ID).append(']')
          .append(" [thread=").append(sanitiseThreadName(Thread.currentThread().getName())).append(']')
          .append(' ').append(loggerName)
          .append(" \u2013 ")   // em-dash separator
          .append(message)
          .append(NL);

        // Append exception stack trace when present
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            sb.append(stackTraceOf(thrown));
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Left-pads the level name to exactly 7 characters so columns align.
     * Levels used: FINEST(6), FINE(4), CONFIG(6), INFO(4), WARNING(7), SEVERE(6).
     */
    private static String padLevel(String name) {
        if (name.length() >= 7) return name;
        return name + "       ".substring(name.length());
    }

    /**
     * Replaces control characters in the thread name to prevent log-injection.
     *
     * @param name raw thread name
     * @return sanitised thread name
     */
    private static String sanitiseThreadName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\x00-\\x1F\\x7F\\[\\]]", "_");
    }

    /**
     * Renders the full stack trace of a {@link Throwable} as a string.
     *
     * @param t the throwable to render
     * @return multi-line stack trace string
     */
    private static String stackTraceOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(NL);
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append("    at ").append(ste).append(NL);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            sb.append("Caused by: ").append(stackTraceOf(cause));
        }
        return sb.toString();
    }

    /**
     * Builds the static process-identity string {@code pid=<pid>@<hostname>}.
     * Hostname resolution failure falls back to {@code "localhost"}.
     */
    private static String buildProcessId() {
        // PID: JDK 9+ uses ProcessHandle; JDK 8 parses RuntimeMXBean name
        long pid = -1L;
        try {
            Class<?> ph   = Class.forName("java.lang.ProcessHandle");
            Object   curr = ph.getMethod("current").invoke(null);
            pid = (Long) curr.getClass().getMethod("pid").invoke(curr);
        } catch (Exception ignored) {
            try {
                String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                int at = name.indexOf('@');
                pid = Long.parseLong(at > 0 ? name.substring(0, at) : name);
            } catch (Exception ignored2) {
                // leave as -1
            }
        }

        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "localhost";
        }

        return "pid=" + pid + "@" + host;
    }
}
