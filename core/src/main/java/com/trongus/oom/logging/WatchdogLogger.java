package com.trongus.oom.logging;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Thin, structured JUL-backed logger for all internal OOM Watchdog diagnostics.
 *
 * <h2>Design</h2>
 * <p>All watchdog subsystems share a single named JUL logger hierarchy rooted at
 * {@code "com.trongus.oom"}.  Child loggers (e.g. {@code "com.trongus.oom.monitor"},
 * {@code "com.trongus.oom.dump"}) are obtained via {@link #forClass(Class)} and inherit
 * the root logger's level and handler configuration.
 *
 * <p>A {@link WatchdogLogFormatter} is installed on a {@link ConsoleHandler} attached to
 * the root logger during {@link #initialise(Level)} so that all watchdog log output
 * flows through the structured format regardless of the default JUL configuration.
 *
 * <h2>Level mapping</h2>
 * <table border="1">
 *   <caption>Log-level semantics</caption>
 *   <tr><th>JUL Level</th><th>Meaning</th></tr>
 *   <tr><td>{@code FINEST}</td> <td>Verbose / trace – detailed poll-cycle internals</td></tr>
 *   <tr><td>{@code FINE}</td>   <td>Debug – fine-grained subsystem events</td></tr>
 *   <tr><td>{@code CONFIG}</td> <td>Configuration / startup information</td></tr>
 *   <tr><td>{@code INFO}</td>   <td>Normal operations (default level)</td></tr>
 *   <tr><td>{@code WARNING}</td><td>Degraded / recoverable – non-fatal issues</td></tr>
 *   <tr><td>{@code SEVERE}</td> <td>Fatal / unrecoverable – watchdog cannot continue</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * private static final Logger LOG = WatchdogLogger.forClass(OomWatchdog.class);
 *
 * LOG.info("Started – polling every {0} ms", config.getPollIntervalMs());
 * LOG.warning("Alert channel {0} failed: {1}", channel.channelName(), ex.getMessage());
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #initialise(Level)} is {@code synchronized} on the class monitor.
 * All other methods delegate directly to thread-safe JUL infrastructure.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.12.9
 * @since 1.6.0
 * @see WatchdogLogFormatter
 */
public final class WatchdogLogger {

    /** Root logger name for the entire watchdog subsystem. */
    public static final String ROOT_LOGGER_NAME = "com.trongus.oom";

    private static volatile boolean initialised = false;

    /** Private constructor – this class is a static factory only. */
    private WatchdogLogger() {}

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Returns a JUL {@link Logger} scoped to the given class, named
     * {@code "com.trongus.oom.<canonicalName>"}.
     *
     * <p>If {@link #initialise(Level)} has not yet been called, the logger will
     * use whatever JUL configuration is currently active.
     *
     * @param clazz the class for which to obtain a logger; must not be {@code null}
     * @return a non-null JUL logger named for the given class
     */
    public static Logger forClass(Class<?> clazz) {
        return Logger.getLogger(clazz.getName());
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    /**
     * Installs the {@link WatchdogLogFormatter} on a {@link ConsoleHandler} attached to
     * the root watchdog logger and sets the effective log level.
     *
     * <p>This method is idempotent: calling it more than once has no effect.
     * Call this once at application startup, before constructing any watchdog components.
     *
     * @param level the minimum log level to capture; must not be {@code null}.
     *              Use {@link Level#INFO} for normal production deployments,
     *              {@link Level#FINE} for debug, or {@link Level#FINEST} for trace output.
     */
    public static synchronized void initialise(Level level) {
        if (initialised) return;

        Logger root = Logger.getLogger(ROOT_LOGGER_NAME);

        // Prevent propagation to the root JUL logger (which typically goes to System.err
        // with the default JVM configuration) so we control the output exclusively.
        root.setUseParentHandlers(false);

        // Remove any handlers installed by prior initialisation attempts or JUL defaults
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }

        // Install a structured console handler that writes to System.err
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new WatchdogLogFormatter());
        consoleHandler.setLevel(Level.ALL);
        root.addHandler(consoleHandler);

        root.setLevel(level);

        initialised = true;
    }

    // =========================================================================
    // Convenience logging methods
    // =========================================================================

    /**
     * Logs a {@code CONFIG}-level record on the supplied logger.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void config(Logger logger, String message, Object... params) {
        log(logger, Level.CONFIG, null, message, params);
    }

    /**
     * Logs an {@code INFO}-level record on the supplied logger.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void info(Logger logger, String message, Object... params) {
        log(logger, Level.INFO, null, message, params);
    }

    /**
     * Logs a {@code WARNING}-level record on the supplied logger.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void warning(Logger logger, String message, Object... params) {
        log(logger, Level.WARNING, null, message, params);
    }

    /**
     * Logs a {@code WARNING}-level record with an associated throwable.
     *
     * @param logger  the JUL logger to write to
     * @param thrown  the exception or error to attach
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void warning(Logger logger, Throwable thrown, String message, Object... params) {
        log(logger, Level.WARNING, thrown, message, params);
    }

    /**
     * Logs a {@code SEVERE}-level record on the supplied logger.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void severe(Logger logger, String message, Object... params) {
        log(logger, Level.SEVERE, null, message, params);
    }

    /**
     * Logs a {@code SEVERE}-level record with an associated throwable.
     *
     * @param logger  the JUL logger to write to
     * @param thrown  the exception or error to attach
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void severe(Logger logger, Throwable thrown, String message, Object... params) {
        log(logger, Level.SEVERE, thrown, message, params);
    }

    /**
     * Logs a {@code FINE}-level record on the supplied logger.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void fine(Logger logger, String message, Object... params) {
        log(logger, Level.FINE, null, message, params);
    }

    /**
     * Logs a {@code FINEST}-level (trace) record on the supplied logger.
     * Use for verbose output such as full message payloads — only emitted when
     * the log level is set to {@code FINEST}.
     *
     * @param logger  the JUL logger to write to
     * @param message message text; supports JUL {@code {0}}-style placeholders
     * @param params  optional message parameters
     */
    public static void finest(Logger logger, String message, Object... params) {
        log(logger, Level.FINEST, null, message, params);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    /**
     * Core log dispatch helper.
     *
     * @param logger  target logger
     * @param level   log level
     * @param thrown  optional throwable to attach; may be {@code null}
     * @param message message pattern
     * @param params  optional parameters for the message pattern
     */
    private static void log(Logger logger, Level level, Throwable thrown,
                            String message, Object... params) {
        if (!logger.isLoggable(level)) return;

        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(logger.getName());
        if (params != null && params.length > 0) {
            record.setParameters(params);
        }
        if (thrown != null) {
            record.setThrown(thrown);
        }
        logger.log(record);
    }

    /**
     * Returns whether the underlying root logger has been initialised via
     * {@link #initialise(Level)}.  Primarily useful in tests.
     *
     * @return {@code true} if {@link #initialise(Level)} has been called at least once
     */
    static boolean isInitialised() {
        return initialised;
    }

    /**
     * Resets the initialisation flag.  <strong>For testing only.</strong>
     * Not thread-safe; must be called from a single-threaded test setup.
     */
    static synchronized void resetForTesting() {
        initialised = false;
        Logger root = Logger.getLogger(ROOT_LOGGER_NAME);
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.setUseParentHandlers(true);
    }
}
