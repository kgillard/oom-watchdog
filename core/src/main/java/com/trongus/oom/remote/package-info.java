/**
 * Multi-target remote JVM diagnostics collection and daemon orchestration.
 *
 * <p>Provides support for monitoring external JVM processes via standard JMX (JSR-160)
 * connections. Key components include:
 * <ul>
 *   <li>{@link com.trongus.oom.remote.TargetDescriptor} &ndash; Immutable configuration model for a remote JVM target.</li>
 *   <li>{@link com.trongus.oom.remote.TargetRegistry} &ndash; File and properties parser for {@code targets.properties}.</li>
 *   <li>{@link com.trongus.oom.remote.JmxDiagnosticsCollector} &ndash; Remote JMX metrics collector with automatic reconnect.</li>
 *   <li>{@link com.trongus.oom.remote.WatchdogDaemon} &ndash; Multi-target lifecycle orchestrator managing concurrent watchdogs.</li>
 * </ul>
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.11.6
 * @since 1.7.0
 */
package com.trongus.oom.remote;
