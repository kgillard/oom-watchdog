package com.trongus.oom.remote;

import com.trongus.oom.dump.DumpType;
import com.trongus.oom.logging.WatchdogLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Parses and validates multi-target configuration files (e.g. {@code targets.properties})
 * and produces immutable {@link TargetDescriptor} instances.
 *
 * <h2>Configuration Format</h2>
 * <p>Targets are declared using a hierarchical {@code target.<name>.<property>} syntax:
 * <pre>{@code
 * target.hostcontext.jmx-url              = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi
 * target.hostcontext.warn                 = 0.75
 * target.hostcontext.crit                 = 0.85
 * target.hostcontext.gc                   = 0.40
 * target.hostcontext.dump-types           = heap,thread
 * target.hostcontext.dump-dir             = /var/log/qradar/dumps/hostcontext
 * target.hostcontext.poll-ms              = 3000
 * target.hostcontext.username             = admin
 * target.hostcontext.password             = secret
 * target.hostcontext.leef-category        = JVM_OOM_hostcontext
 * target.hostcontext.leef-tags            = env=prod,team=platform
 * target.hostcontext.gc-dump-threshold    = 0.70
 * target.hostcontext.heap-dump-threshold  = 0.85
 * target.hostcontext.nursery-dump-threshold = 0.90
 *
 * target.tomcat.jmx-url                   = service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi
 * target.tomcat.warn                      = 0.80
 * target.tomcat.crit                      = 0.90
 * }</pre>
 *
 * <h2>Recognised Property Keys</h2>
 * <table border="1">
 *   <caption>Supported per-target property keys</caption>
 *   <tr><th>Key</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@code jmx-url} (or {@code jmxurl}, {@code url})</td><td><em>required</em></td>
 *       <td>JMX Service URL for connecting to the target JVM.</td></tr>
 *   <tr><td>{@code warn} (or {@code warn-threshold})</td><td>{@code 0.80}</td>
 *       <td>Heap-usage ratio (0–1) that triggers a {@code WARNING} alert.</td></tr>
 *   <tr><td>{@code crit} (or {@code crit-threshold})</td><td>{@code 0.90}</td>
 *       <td>Heap-usage ratio (0–1) that triggers a {@code CRITICAL} alert and dumps.</td></tr>
 *   <tr><td>{@code gc} (or {@code gc-threshold})</td><td>{@code 0.50}</td>
 *       <td>GC overhead fraction (0–1) that triggers a {@code WARNING} alert.</td></tr>
 *   <tr><td>{@code gc-dump-threshold}</td><td>{@code -1} (disabled)</td>
 *       <td>GC overhead ratio (0–1) that independently triggers a dump.</td></tr>
 *   <tr><td>{@code heap-dump-threshold}</td><td>{@code -1} (disabled)</td>
 *       <td>Heap-usage ratio (0–1) that independently triggers a dump.</td></tr>
 *   <tr><td>{@code nursery-dump-threshold}</td><td>{@code -1} (disabled)</td>
 *       <td>Nursery/young-gen used ratio (0–1) that independently triggers a dump.</td></tr>
 *   <tr><td>{@code poll-ms} (or {@code poll-interval-ms})</td><td>{@code 5000}</td>
 *       <td>Polling interval between diagnostics collections, in milliseconds.</td></tr>
 *   <tr><td>{@code dump-types} (or {@code dumptypes})</td><td>(empty)</td>
 *       <td>Comma-separated list of dump types at {@code CRITICAL}: {@code HEAP}, {@code CORE},
 *           {@code THREAD}, {@code CLASS_HISTOGRAM}.</td></tr>
 *   <tr><td>{@code dump-dir} (or {@code dump-directory})</td><td>{@code ./dumps/<name>}</td>
 *       <td>Directory where diagnostic dumps for this target are saved.</td></tr>
 *   <tr><td>{@code username}</td><td>(none)</td>
 *       <td>Optional JMX authentication username.</td></tr>
 *   <tr><td>{@code password}</td><td>(none)</td>
 *       <td>Optional JMX authentication password.</td></tr>
 *   <tr><td>{@code leef-category}</td><td>{@code "JVM_OOM_Risk"}</td>
 *       <td>Optional LEEF {@code cat} override sent in QRadar syslog events for this target.</td></tr>
 *   <tr><td>{@code leef-tags}</td><td>(omitted)</td>
 *       <td>Optional LEEF {@code tags} attribute for environment labels or topology context
 *           (e.g. {@code "env=prod,team=platform,region=us-east-1"}).</td></tr>
 * </table>
 *
 * <h2>Validation Rules</h2>
 * <ul>
 *   <li>Every target must declare a valid, non-blank {@code jmx-url}; if missing an
 *       {@link IllegalArgumentException} is thrown.</li>
 *   <li>{@code warn} must be strictly less than {@code crit}.</li>
 *   <li>Property keys that do not match {@code target.<name>.<property>} are ignored with a warning log.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This utility class is stateless and thread-safe.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.4
 * @since 1.7.0
 * @see TargetDescriptor
 * @see WatchdogDaemon
 */
public final class TargetRegistry {

    private static final Logger LOG = WatchdogLogger.forClass(TargetRegistry.class);

    private static final String PREFIX = "target.";

    private TargetRegistry() {}

    /**
     * Loads target descriptors from a file path.
     *
     * @param filePath path to the properties file; must not be null or blank
     * @return unmodifiable list of parsed {@link TargetDescriptor} instances
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    public static List<TargetDescriptor> loadFromFile(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path must not be null or blank");
        }
        File file = new File(filePath.trim()).getCanonicalFile();
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Targets configuration file does not exist or is not a regular file: " + file.getPath());
        }
        try (InputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return fromProperties(props);
        }
    }

    /**
     * Loads target descriptors from a raw properties string content.
     *
     * @param content properties string content; must not be null
     * @return unmodifiable list of parsed {@link TargetDescriptor} instances
     * @throws IOException if parsing string stream fails
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    public static List<TargetDescriptor> loadFromString(String content) throws IOException {
        Objects.requireNonNull(content, "content must not be null");
        // Preserve insertion order from string or stream
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new StringReader(content))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                    lines.add(line);
                }
            }
        }

        Map<String, Map<String, String>> targetMap = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();

            if (!key.startsWith(PREFIX)) continue;
            String remainder = key.substring(PREFIX.length());
            int firstDot = remainder.indexOf('.');
            if (firstDot <= 0 || firstDot == remainder.length() - 1) continue;

            String targetName = remainder.substring(0, firstDot).trim();
            String propName   = remainder.substring(firstDot + 1).trim().toLowerCase();
            targetMap.computeIfAbsent(targetName, k -> new LinkedHashMap<>()).put(propName, val);
        }

        return buildDescriptorsFromMap(targetMap);
    }

    /**
     * Parses {@link TargetDescriptor} instances from a populated {@link Properties} object.
     *
     * @param props properties container; must not be null
     * @return unmodifiable list of parsed {@link TargetDescriptor} instances
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    public static List<TargetDescriptor> fromProperties(Properties props) {
        Objects.requireNonNull(props, "props must not be null");

        // Map: targetName -> (subKey -> value)
        Map<String, Map<String, String>> targetMap = new LinkedHashMap<>();

        for (String key : props.stringPropertyNames()) {
            String trimmedKey = key.trim();
            if (!trimmedKey.startsWith(PREFIX)) {
                WatchdogLogger.fine(LOG, "Ignoring non-target property: {0}", trimmedKey);
                continue;
            }

            String remainder = trimmedKey.substring(PREFIX.length());
            int firstDot = remainder.indexOf('.');
            if (firstDot <= 0 || firstDot == remainder.length() - 1) {
                WatchdogLogger.warning(LOG, "Malformed target property key: {0}", trimmedKey);
                continue;
            }

            String targetName = remainder.substring(0, firstDot).trim();
            String propName   = remainder.substring(firstDot + 1).trim().toLowerCase();
            String rawValue   = props.getProperty(key);
            if (rawValue == null) continue;        // guard: Properties.getProperty() can return null
            String value      = rawValue.trim();

            targetMap.computeIfAbsent(targetName, k -> new LinkedHashMap<>()).put(propName, value);
        }

        return buildDescriptorsFromMap(targetMap);
    }

    private static List<TargetDescriptor> buildDescriptorsFromMap(Map<String, Map<String, String>> targetMap) {
        List<TargetDescriptor> descriptors = new ArrayList<>(targetMap.size());

        for (Map.Entry<String, Map<String, String>> entry : targetMap.entrySet()) {
            String targetName = entry.getKey();
            Map<String, String> p = entry.getValue();

            String jmxUrl = p.get("jmx-url");
            if (jmxUrl == null || jmxUrl.isEmpty()) {
                // Also check alias "jmxurl" or "url"
                jmxUrl = p.get("jmxurl");
                if (jmxUrl == null || jmxUrl.isEmpty()) {
                    jmxUrl = p.get("url");
                }
            }

            if (jmxUrl == null || jmxUrl.isEmpty()) {
                throw new IllegalArgumentException(String.format(
                        "Missing required property 'target.%s.jmx-url' for target '%s'", targetName, targetName));
            }

            TargetDescriptor.Builder b = TargetDescriptor.builder(targetName, jmxUrl);

            // Optional username / password
            String username = p.get("username");
            String password = p.get("password");
            if (username != null && !username.isEmpty()) {
                b.credentials(username, password != null ? password : "");
            }

            // Optional thresholds — catch NumberFormatException to give a clear error message
            if (p.containsKey("warn")) {
                b.warnThreshold(parseDouble(p.get("warn"), "warn", targetName));
            } else if (p.containsKey("warn-threshold")) {
                b.warnThreshold(parseDouble(p.get("warn-threshold"), "warn-threshold", targetName));
            }

            if (p.containsKey("crit")) {
                b.critThreshold(parseDouble(p.get("crit"), "crit", targetName));
            } else if (p.containsKey("crit-threshold")) {
                b.critThreshold(parseDouble(p.get("crit-threshold"), "crit-threshold", targetName));
            }

            if (p.containsKey("gc")) {
                b.gcThreshold(parseDouble(p.get("gc"), "gc", targetName));
            } else if (p.containsKey("gc-threshold")) {
                b.gcThreshold(parseDouble(p.get("gc-threshold"), "gc-threshold", targetName));
            }

            // Optional poll interval
            if (p.containsKey("poll-ms")) {
                b.pollIntervalMs(parseLong(p.get("poll-ms"), "poll-ms", targetName));
            } else if (p.containsKey("poll-interval-ms")) {
                b.pollIntervalMs(parseLong(p.get("poll-interval-ms"), "poll-interval-ms", targetName));
            }

            // Optional dump types
            String dumpTypesStr = p.get("dump-types");
            if (dumpTypesStr == null) dumpTypesStr = p.get("dumptypes");
            if (dumpTypesStr != null && !dumpTypesStr.isEmpty()) {
                Set<DumpType> types = EnumSet.noneOf(DumpType.class);
                for (String t : dumpTypesStr.split(",")) {
                    String token = t.trim();
                    if (!token.isEmpty()) {
                        types.add(DumpType.fromString(token));
                    }
                }
                b.dumpTypes(types);
            }

            // Optional dump directory
            String dumpDir = p.get("dump-dir");
            if (dumpDir == null) dumpDir = p.get("dump-directory");
            if (dumpDir != null && !dumpDir.isEmpty()) {
                b.dumpDirectory(dumpDir);
            }
            // Optional LEEF customisation
            String leefCategory = p.get("leef-category");
            if (leefCategory != null && !leefCategory.isEmpty()) {
                b.leefCategory(leefCategory);
            }

            String leefTags = p.get("leef-tags");
            if (leefTags != null && !leefTags.isEmpty()) {
                b.leefTags(leefTags);
            }

            // Optional dump thresholds
            if (p.containsKey("gc-dump-threshold")) {
                b.gcDumpThreshold(parseDouble(p.get("gc-dump-threshold"), "gc-dump-threshold", targetName));
            }
            if (p.containsKey("heap-dump-threshold")) {
                b.heapDumpThreshold(parseDouble(p.get("heap-dump-threshold"), "heap-dump-threshold", targetName));
            }
            if (p.containsKey("nursery-dump-threshold")) {
                b.nurseryDumpThreshold(parseDouble(p.get("nursery-dump-threshold"), "nursery-dump-threshold", targetName));
            }

            descriptors.add(b.build());
        }

        return Collections.unmodifiableList(descriptors);
    }

    /**
     * Parses a double value from a property string, throwing a descriptive
     * {@link IllegalArgumentException} rather than a raw {@link NumberFormatException}.
     *
     * @param value      raw string value
     * @param propName   property name for error reporting
     * @param targetName target name for error reporting
     * @return parsed double value
     * @throws IllegalArgumentException if the value is not a valid double
     */
    private static double parseDouble(String value, String propName, String targetName) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "Invalid value '%s' for property 'target.%s.%s' – expected a decimal number between 0.0 and 1.0",
                    value, targetName, propName));
        }
    }

    /**
     * Parses a long value from a property string, throwing a descriptive
     * {@link IllegalArgumentException} rather than a raw {@link NumberFormatException}.
     *
     * @param value      raw string value
     * @param propName   property name for error reporting
     * @param targetName target name for error reporting
     * @return parsed long value
     * @throws IllegalArgumentException if the value is not a valid long integer
     */
    private static long parseLong(String value, String propName, String targetName) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "Invalid value '%s' for property 'target.%s.%s' – expected a whole number in milliseconds",
                    value, targetName, propName));
        }
    }
}
