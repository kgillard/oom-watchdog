package com.trongus.oom.logging;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Compact, visually scannable log formatter for {@link WatchdogLogger}.
 *
 * <h2>Output format</h2>
 * <pre>
 * 12:36:55.246  INFO   OomWatchdog      Started – polling every 200 ms.
 * 12:36:55.312  WARN   JmxCollector     Failed to connect to [hostcontext]: connection refused
 * 12:36:55.400  SEVERE WatchdogMain     Unrecoverable error: …
 *                                       java.lang.NullPointerException: config
 *                                           at com.trongus.oom.WatchdogMain.main(WatchdogMain.java:215)
 * </pre>
 *
 * <p>Each record includes:
 * <ol>
 *   <li>Time-of-day timestamp ({@code HH:mm:ss.SSS}) — no date, no timezone clutter</li>
 *   <li>Fixed-width level label ({@code INFO}, {@code WARN}, {@code SEVERE}, etc.)</li>
 *   <li>Short class name — just the simple class name, not the full package path</li>
 *   <li>Message text</li>
 *   <li>Exception stack trace (if a {@link Throwable} is attached), indented below</li>
 * </ol>
 *
 * <p>This formatter is package-private: callers obtain a pre-configured
 * {@link java.util.logging.Handler} from {@link WatchdogLogger} rather than
 * constructing this class directly.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.5
 * @since 1.6.0
 * @see WatchdogLogger
 */
final class WatchdogLogFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                             .withZone(ZoneId.systemDefault());

    /** Line separator for the current platform. */
    private static final String NL = System.lineSeparator();

    /** Width of the level column — "WARNING" is the longest at 7 chars. */
    private static final int LEVEL_WIDTH  = 7;
    /** Width of the class-name column — padded so messages align. */
    private static final int SOURCE_WIDTH = 16;

    /** {@inheritDoc} */
    @Override
    public String format(LogRecord record) {
        String timestamp = TIMESTAMP_FMT.format(Instant.ofEpochMilli(record.getMillis()));
        String level     = abbreviateLevel(record.getLevel());
        String source    = shortClassName(record.getLoggerName());
        String message   = formatMessage(record);

        StringBuilder sb = new StringBuilder(160);
        sb.append(timestamp)
          .append("  ")
          .append(padRight(level,  LEVEL_WIDTH))
          .append("  ")
          .append(padRight(source, SOURCE_WIDTH))
          .append("  ")
          .append(message)
          .append(NL);

        // Append exception stack trace when present, indented to align with message
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            String indent = " ".repeat(timestamp.length() + 2 + LEVEL_WIDTH + 2 + SOURCE_WIDTH + 2);
            sb.append(stackTraceOf(thrown, indent));
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a short, fixed-width level label. WARNING → WARN to keep
     * columns tighter; all others use their natural name.
     */
    private static String abbreviateLevel(java.util.logging.Level level) {
        String name = level.getName();
        if ("WARNING".equals(name)) return "WARN";
        if ("CONFIG".equals(name))  return "CONFIG";
        return name; // INFO(4), FINE(4), FINEST(6), SEVERE(6)
    }

    /**
     * Extracts the simple class name from a fully-qualified logger name.
     * {@code "com.trongus.oom.monitor.OomWatchdog"} → {@code "OomWatchdog"}.
     */
    private static String shortClassName(String loggerName) {
        if (loggerName == null) return "OomWatchdog";
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 ? loggerName.substring(dot + 1) : loggerName;
    }

    /** Right-pads {@code s} to exactly {@code width} chars. */
    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * Renders the full stack trace of a {@link Throwable} as a string,
     * with each line prefixed by {@code indent} so it aligns under the message column.
     *
     * @param t      the throwable to render
     * @param indent leading whitespace to prepend to each line
     * @return multi-line indented stack trace string
     */
    private static String stackTraceOf(Throwable t, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(t.getClass().getName()).append(": ").append(t.getMessage()).append(NL);
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append(indent).append("  at ").append(ste).append(NL);
        }
        Throwable cause = t.getCause();
        if (cause != null) {
            sb.append(indent).append("Caused by: ");
            sb.append(stackTraceOf(cause, indent));
        }
        return sb.toString();
    }
}
