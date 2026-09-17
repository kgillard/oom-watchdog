# OOM Watchdog — Examples Guide

This document covers every way to use OOM Watchdog: running the pre-built JAR from the
command line without writing any code, embedding it in an application with the Java API,
and writing your own custom implementations of every extension point.

Six worked examples live in
[`core/src/main/java/com/trongus/oom/examples/`](core/src/main/java/com/trongus/oom/examples/)
and are documented in detail below.

---

## Table of Contents

1. [Using the JAR without any code](#1-using-the-jar-without-any-code)
   - [Download](#11-download)
   - [Monitoring mode](#12-monitoring-mode)
   - [Test / demo mode](#13-test--demo-mode)
   - [CLI quick-reference](#14-cli-quick-reference)
2. [Embedding the API in your application](#2-embedding-the-api-in-your-application)
   - [Maven / Gradle dependency](#21-maven--gradle-dependency)
   - [Minimal wiring](#22-minimal-wiring)
3. [Example 01 — Basic monitoring (console alerts)](#3-example-01--basic-monitoring)
4. [Example 02 — Multiple alert channels + custom channel](#4-example-02--multiple-alert-channels)
5. [Example 03 — Automatic dump capture on CRITICAL](#5-example-03--dump-on-critical)
6. [Example 04 — Custom implementations of all extension points](#6-example-04--custom-implementations)
7. [Example 05 — QRadar LEEF 2.0 syslog integration](#7-example-05--qradar-integration)
8. [Example 06 — Framework integration (Spring Boot, health checks, metrics)](#8-example-06--framework-integration)
9. [Extension point reference](#9-extension-point-reference)
10. [Security notes for custom implementations](#10-security-notes-for-custom-implementations)
11. [Building the examples](#11-building-the-examples)

---

## 1. Using the JAR without any code

### 1.1 Download

```bash
curl -L -o oom-watchdog.jar \
  https://github.com/kgillard/oom-watchdog/releases/download/v1.0.0/oom-watchdog.jar
```

No installation, no classpath setup — the JAR is a self-contained fat JAR with no
runtime dependencies.

### 1.2 Monitoring mode

Attach OOM Watchdog to any running JVM by adding the JAR to the classpath and
specifying `WatchdogMain` as the entry point.  The simplest useful invocation:

```bash
java -jar oom-watchdog.jar
```

This starts monitoring **the JVM the JAR itself runs inside** using all defaults:
- Warning at 80% heap, critical at 90%
- Console + log-file alerts (→ `./oom-watchdog.log`)
- No dump files (opt in with `--dump-types`)
- Poll every 5 seconds

**Production example** — monitoring with heap dump and QRadar forwarding:

```bash
java -jar oom-watchdog.jar \
    --warn-threshold 0.80      \
    --crit-threshold 0.90      \
    --gc-threshold   0.50      \
    --poll-ms        5000      \
    --dump-dir       /var/dumps \
    --dump-types     HEAP,THREAD \
    --log-file       /var/log/oom-watchdog.log \
    --qradar-host    192.168.1.100 \
    --qradar-port    514
```

**To monitor another JVM** (e.g. your application server), add the JAR to that JVM's
classpath and start `WatchdogMain` as a background thread.  The easiest approach is
embedding (see Section 2); for standalone monitoring of an external process, use a JVM
agent or attach via the Java Attach API (not included in this library).

### 1.3 Test / demo mode

Run on a deliberately small heap to trigger WARNING → CRITICAL → OOM in a controlled
environment:

```bash
java -Xmx64m -jar oom-watchdog.jar \
    --test-mode \
    --warn-threshold 0.50 \
    --crit-threshold 0.70 \
    --poll-ms 1000 \
    --dump-types HEAP,THREAD,CLASS_HISTOGRAM \
    --test-leak-secs 10
```

`--test-mode` activates the built-in `OomSimulator` which:
1. **Phase 1 — slow leak**: allocates ~1 MB/s for `--test-leak-secs` seconds (default 20 s),
   triggering WARNING then CRITICAL.
2. **Phase 2 — rapid fill**: allocates as fast as possible until the heap is exhausted.
3. **Phase 3 — OOM**: the JVM throws `OutOfMemoryError` and exits.

You should see output like:

```
[OomWatchdog] Started – polling every 1000 ms.
=== JVM OOM Alert ===
  Severity   : WARNING
  Process    : 12345@localhost
  ...
=== JVM OOM Alert ===
  Severity   : CRITICAL
  ...
[OomWatchdog][Dump] HEAP (HotSpot): ./dumps/oom_heap_12345@localhost_20251015_082233_001.hprof
[OomWatchdog][Dump] THREAD: ./dumps/oom_thread_12345@localhost_20251015_082233_001_threads.txt
```

### 1.4 CLI quick-reference

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--warn-threshold` | `double` (0–1) | `0.80` | Heap ratio that triggers WARNING |
| `--crit-threshold` | `double` (0–1) | `0.90` | Heap ratio that triggers CRITICAL |
| `--gc-threshold` | `double` (0–1) | `0.50` | GC overhead fraction that triggers WARNING |
| `--poll-ms` | `long` ≥100 | `5000` | Poll interval in milliseconds |
| `--dump-dir` | `path` | `./dumps` | Output directory for dump artefacts |
| `--dump-types` | CSV | _(none)_ | `HEAP`, `THREAD`, `CLASS_HISTOGRAM`, `CORE` |
| `--log-file` | `path` | `./oom-watchdog.log` | Append structured alerts to this file |
| `--qradar-host` | `host/IP` | _(disabled)_ | QRadar syslog receiver hostname |
| `--qradar-port` | `int` 1–65535 | `514` | QRadar syslog port |
| `--qradar-tcp` | flag | _(off — UDP)_ | Use TCP transport for QRadar |
| `--test-mode` | flag | _(off)_ | Run the OomSimulator |
| `--test-leak-secs` | `long` | `20` | Slow-leak phase seconds in test mode |
| `--help` | flag | | Print usage and exit |

---

## 2. Embedding the API in your application

### 2.1 Maven / Gradle dependency

Build the JAR locally and install it:

```bash
cd oom-watchdog
mvn clean install -DskipTests -q
```

Then reference it in your `pom.xml`:

```xml
<dependency>
    <groupId>com.trongus</groupId>
    <artifactId>oom-watchdog-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Or in Gradle:

```groovy
implementation 'com.trongus:oom-watchdog-core:1.0.0'
```

### 2.2 Minimal wiring

The watchdog is assembled by passing four collaborators into the `OomWatchdog` constructor.
You can use all built-in implementations or replace any of them with your own:

```java
WatchdogConfig config = WatchdogConfig.defaults()
    .warningHeapThreshold(0.80)
    .criticalHeapThreshold(0.90)
    .pollIntervalMs(5_000L)
    .heapDumpDirectory("/var/dumps")
    .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
    .build();

OomWatchdog watchdog = new OomWatchdog(
    config,
    new MxBeanDiagnosticsCollector(config),   // collect JVM metrics
    new ThresholdRiskAssessor(config),          // classify risk
    Arrays.asList(
        new ConsoleAlertChannel(),
        new FileLogAlertChannel("/var/log/oom.log")
    ),
    new CompositeDumpService(config)            // write dump files
);

// Register shutdown hook
Runtime.getRuntime().addShutdownHook(
    new Thread(watchdog::stop, "oom-watchdog-shutdown"));

watchdog.start();
```

The watchdog runs on a single daemon thread — it never prevents the JVM from shutting
down and adds negligible overhead (a few microseconds per poll cycle).

---

## 3. Example 01 — Basic Monitoring

**File:** [`Example01BasicMonitoring.java`](core/src/main/java/com/trongus/oom/examples/Example01BasicMonitoring.java)

The minimum viable watchdog configuration: one console alert channel, no dump files, all
default thresholds.  This is the right starting point for development or CI.

**Key points demonstrated:**
- `WatchdogConfig.defaults()` provides safe, production-ready values out of the box
- `.build()` validates all settings — misconfiguration throws `IllegalArgumentException`
  immediately, not at runtime
- The shutdown hook pattern that works in any JVM application

```java
WatchdogConfig config = WatchdogConfig.defaults()
        .warningHeapThreshold(0.80)
        .criticalHeapThreshold(0.90)
        .pollIntervalMs(5_000L)
        .build();

OomWatchdog watchdog = new OomWatchdog(
        config,
        new MxBeanDiagnosticsCollector(config),
        new ThresholdRiskAssessor(config),
        Collections.singletonList(new ConsoleAlertChannel()),
        new CompositeDumpService(config));

Runtime.getRuntime().addShutdownHook(
        new Thread(watchdog::stop, "oom-watchdog-shutdown"));

watchdog.start();
```

**Run it:**

```bash
java -cp oom-watchdog.jar com.trongus.oom.examples.Example01BasicMonitoring
```

---

## 4. Example 02 — Multiple Alert Channels

**File:** [`Example02AlertChannels.java`](core/src/main/java/com/trongus/oom/examples/Example02AlertChannels.java)

Shows how to register multiple channels and how to write a **custom `AlertChannel`**
from scratch.  The example includes a `PagerDutyAlertChannel` stub that shows
exactly how to integrate with any webhook-based alerting back-end.

### Writing a custom AlertChannel

Implement the `AlertChannel` interface — just two methods:

```java
public final class PagerDutyAlertChannel implements AlertChannel {

    private final String routingKey;

    public PagerDutyAlertChannel(String routingKey) {
        if (routingKey == null || routingKey.trim().isEmpty()) {
            throw new IllegalArgumentException("routingKey must not be null or blank");
        }
        this.routingKey = routingKey;
    }

    @Override
    public void alert(JvmSnapshot snapshot) {
        try {
            String severity = snapshot.getRiskLevel().ordinal()
                    >= OomRiskLevel.CRITICAL.ordinal() ? "critical" : "warning";
            // POST to PagerDuty Events API v2 using snapshot fields
            // https://developer.pagerduty.com/api-reference/YXBpOjI3NDgyNjU-pager-duty-v2-events-api
        } catch (Exception e) {
            // NEVER propagate exceptions — other channels must still fire
            System.err.println("[PagerDuty] alert failed: " + e.getMessage());
        }
    }

    @Override
    public String channelName() { return "PagerDuty"; }
}
```

**Channel contract rules:**
1. **Never throw** from `alert()` — the watchdog catches per-channel and continues
2. **Thread-safe** — `alert()` is called from the watchdog's scheduler thread
3. **Fast** — long-running I/O should be dispatched to a background executor
4. **Idempotent** — the same snapshot may be delivered twice in rare scheduler edge cases

**Using multiple channels:**

```java
List<AlertChannel> channels = Arrays.asList(
    new ConsoleAlertChannel(),
    new FileLogAlertChannel("./oom.log"),
    new PagerDutyAlertChannel("YOUR_32_CHAR_ROUTING_KEY")
);
```

---

## 5. Example 03 — Dump on Critical

**File:** [`Example03DumpOnCritical.java`](core/src/main/java/com/trongus/oom/examples/Example03DumpOnCritical.java)

Configures the watchdog to automatically write heap, thread, and class histogram dumps
on the first CRITICAL event.  Demonstrates the **episode-deduplication** design that
prevents dump storms.

### Episode deduplication

```
poll 1: risk = CRITICAL  → trigger dump (first in episode)  → dump written once
poll 2: risk = CRITICAL  → dump already taken, skip          → no second dump
poll 3: risk = CRITICAL  → dump already taken, skip          → no third dump
poll 4: risk = OK        → reset episode flag
poll 5: risk = CRITICAL  → trigger dump (new episode)        → dump written again
```

This is intentional: you want **one** heap dump per memory-pressure event, not one
every 5 seconds until the disk fills.

### Dump type selection guide

```java
.dumpTypes(EnumSet.of(
    DumpType.HEAP,            // .hprof — analyse in Eclipse MAT, VisualVM, JProfiler
    DumpType.THREAD,          // stack traces — diagnose deadlocks, CPU hogs
    DumpType.CLASS_HISTOGRAM  // class counts — spot unexpected object retention
    // DumpType.CORE          // OS core dump — only on Linux/macOS with gcore installed
))
```

**When to use each type:**

| Type | File size | Use when |
|------|-----------|----------|
| `HEAP` | Large (= heap size) | You need to find which objects are consuming memory |
| `THREAD` | Small (< 1 MB) | Always useful — shows what all threads are doing at the moment of crisis |
| `CLASS_HISTOGRAM` | Small (< 1 MB) | Fast check of object counts without the full hprof overhead |
| `CORE` | Very large (= process RSS) | You need a full process image for C-level or JVM-internal debugging |

---

## 6. Example 04 — Custom Implementations

**File:** [`Example04CustomImplementations.java`](core/src/main/java/com/trongus/oom/examples/Example04CustomImplementations.java)

Demonstrates replacing all three main extension points with custom implementations —
without modifying `OomWatchdog` itself (Open/Closed Principle).

### Custom JvmDiagnosticsCollector

Replace the built-in MXBean collector with any metric source: APM SDK data, mock data
for tests, data from a cloud-native metrics agent:

```java
public final class EscalatingHeapCollector implements JvmDiagnosticsCollector {

    @Override
    public JvmSnapshot collect() {
        // Return a JvmSnapshot.Builder populated from your metric source.
        // The risk field must be OomRiskLevel.OK — classification is the assessor's job.
        return new JvmSnapshot.Builder()
                .processName(...)
                .heapUsedRatio(myMetricSource.getHeapRatio())
                .riskLevel(OomRiskLevel.OK)
                .build();
    }
}
```

**Key rule:** always set `.riskLevel(OomRiskLevel.OK)` in the collector.  The risk assessor
is responsible for classification; the collector's job is data gathering only.

### Custom RiskAssessor

Replace the threshold-based assessor with any classification logic:

```java
public final class BusinessHoursRiskAssessor implements RiskAssessor {

    @Override
    public JvmSnapshot assess(JvmSnapshot snapshot) {
        // Delegate to different ThresholdRiskAssessor instances depending on
        // time of day, environment, recent deployment state, etc.
        // Always return snapshot.toBuilder().riskLevel(...).diagnosisNotes(...).build()
        // Never modify the input snapshot.
    }
}
```

**Key rules for assessors:**
1. Always return a **new** snapshot (`snapshot.toBuilder()…build()`) — never mutate the input
2. The returned risk level must be a valid `OomRiskLevel` constant (never `null`)
3. Enrich `diagnosisNotes` to explain why the level was chosen — this appears in all alerts

### Custom HeapDumpService

Replace the local file-based dump service with any dump destination:

```java
public final class UploadingDumpService implements HeapDumpService {

    @Override
    public List<String> dump(JvmSnapshot snapshot, List<DumpType> types) {
        // 1. Write files locally using CompositeDumpService
        List<String> paths = localService.dump(snapshot, types);
        // 2. Upload each file to S3, Artifactory, etc.
        paths.forEach(path -> uploadToS3(path));
        return paths;
    }
}
```

---

## 7. Example 05 — QRadar Integration

**File:** [`Example05QRadarIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example05QRadarIntegration.java)

Full QRadar LEEF 2.0 syslog integration over UDP or TCP.

### LEEF 2.0 event format

Each alert is a syslog RFC 3164 packet containing:

```
<13>Sep 17 08:00:00 prod-host LEEF:2.0|IBM|OomWatchdog|1.0|OOM_ALERT|
devTime=Sep 17 2025 08:00:00.000 +0000	sev=9	src=prod-host
heapUsedMB=921	heapMaxMB=1024	heapPct=90.0
nonHeapUsedMB=128	gcOverheadPct=23.8	totalGcTimeMs=14300
postGcGrowth=42.30 MB/h	diagnosis=...
```

The `sev` field maps to QRadar's 1–10 scale:
- `WARNING` → `sev=5`
- `CRITICAL` → `sev=9`
- `OOM_FIRING` → `sev=10`

### Transport selection

```java
// UDP — fire-and-forget, lowest latency, standard syslog port
QRadarAlertChannel udp = new QRadarAlertChannel(
        "siem.corp.com", 514, QRadarAlertChannel.Transport.UDP);

// TCP — guaranteed delivery, 5-second timeout
QRadarAlertChannel tcp = new QRadarAlertChannel(
        "siem.corp.com", 1514, QRadarAlertChannel.Transport.TCP);
```

Use UDP for high-frequency polling on a reliable LAN.  Use TCP when you need reliable
delivery (compliance requirements, WAN links, or critical-only notifications).

### QRadar log source configuration

In the QRadar console, add a **Universal DSM** or **syslog** log source pointing at
the machine running OOM Watchdog.  The LEEF header
(`LEEF:2.0|IBM|OomWatchdog|1.0|OOM_ALERT`) is recognised automatically by QRadar's
LEEF parser.

---

## 8. Example 06 — Framework Integration

**File:** [`Example06FrameworkIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example06FrameworkIntegration.java)

Shows how to wire OOM Watchdog into Spring Boot, CDI, or any IoC framework.

### Spring Boot integration

```java
@Configuration
public class OomWatchdogAutoConfig {

    @Bean(destroyMethod = "close")
    public WatchdogLifecycle oomWatchdog(
            @Value("${oom.watchdog.warn-threshold:0.80}") double warn,
            @Value("${oom.watchdog.crit-threshold:0.90}") double crit,
            @Value("${oom.watchdog.poll-ms:5000}")         long pollMs,
            @Value("${oom.watchdog.dump-dir:./dumps}")     String dumpDir,
            @Value("${oom.watchdog.log-file:./oom.log}")   String logFile) {

        WatchdogProperties props = new WatchdogProperties()
                .warnThreshold(warn)
                .critThreshold(crit)
                .pollMs(pollMs)
                .dumpDir(dumpDir)
                .logFile(logFile);

        return WatchdogLifecycle.fromProperties(props, List.of(
                new ConsoleAlertChannel(),
                new FileLogAlertChannel(logFile),
                metricsAlertChannel()  // inject your MetricsAlertChannel bean
        ));
    }
}
```

Spring calls `close()` on the bean automatically during context shutdown.  No manual
shutdown hook needed.

### Kubernetes readiness probe

Expose the last risk level as a readiness check endpoint:

```java
@RestController
public class OomHealthController {

    @Autowired WatchdogLifecycle watchdog;

    @GetMapping("/readyz")
    public ResponseEntity<String> readiness() {
        if (watchdog.isHealthy()) {
            return ResponseEntity.ok("UP");
        }
        // Return 503 when CRITICAL — Kubernetes routes traffic away from this pod
        return ResponseEntity.status(503).body("DOWN: " + watchdog.getLastRiskLevel());
    }
}
```

### Micrometer / Prometheus metrics

Replace the `AtomicInteger` counters in `MetricsAlertChannel` with real Micrometer calls:

```java
public final class MicrometerAlertChannel implements AlertChannel {

    private final Counter warningCounter;
    private final Counter criticalCounter;
    private final AtomicReference<Double> heapRatioGauge = new AtomicReference<>(0.0);

    public MicrometerAlertChannel(MeterRegistry registry) {
        this.warningCounter  = registry.counter("oom.alerts", "level", "warning");
        this.criticalCounter = registry.counter("oom.alerts", "level", "critical");
        registry.gauge("oom.heap.ratio", heapRatioGauge, AtomicReference::get);
    }

    @Override
    public void alert(JvmSnapshot snapshot) {
        heapRatioGauge.set(snapshot.getHeapUsedRatio());
        if (snapshot.getRiskLevel().ordinal() >= OomRiskLevel.CRITICAL.ordinal()) {
            criticalCounter.increment();
        } else {
            warningCounter.increment();
        }
    }
}
```

---

## 9. Extension Point Reference

OOM Watchdog exposes four narrow interfaces.  Implement any or all of them to
customise behaviour without touching the watchdog core.

### `JvmDiagnosticsCollector`

```java
public interface JvmDiagnosticsCollector {
    JvmSnapshot collect();
}
```

**Built-in:** `MxBeanDiagnosticsCollector` — reads all JVM MXBeans, computes OLS
post-GC leak-trend slope, builds `JvmSnapshot`.

**Custom use-cases:** mock data for unit tests, APM SDK metric source, cloud-native
agent data, multi-JVM aggregation.

**Contract:** return a `JvmSnapshot` with `riskLevel = OomRiskLevel.OK`.  The risk
assessor sets the real level.

---

### `RiskAssessor`

```java
public interface RiskAssessor {
    JvmSnapshot assess(JvmSnapshot snapshot);
}
```

**Built-in:** `ThresholdRiskAssessor` — three-level decision tree based on heap ratio,
GC overhead, and post-GC growth rate.

**Custom use-cases:** time-window-based thresholds, ML-based anomaly detection,
environment-specific rules, suppression during maintenance windows.

**Contract:** always return a new snapshot with a valid `OomRiskLevel` (never mutate
the input, never return `null`).

---

### `HeapDumpService`

```java
public interface HeapDumpService {
    List<String> dump(JvmSnapshot snapshot, List<DumpType> types);
}
```

**Built-in:** `CompositeDumpService` — delegates to a strategy chain per dump type;
supports HotSpot, OpenJ9/IBM J9, GraalVM.

**Custom use-cases:** upload dumps to S3/Artifactory after writing locally, send to a
remote capture agent, record dump metadata in a database.

**Contract:** never throw; return whatever paths were successfully written (may be
empty if all attempts failed).

---

### `AlertChannel`

```java
public interface AlertChannel {
    void alert(JvmSnapshot snapshot);
    default String channelName() { return getClass().getSimpleName(); }
}
```

**Built-in:** `ConsoleAlertChannel`, `FileLogAlertChannel`, `QRadarAlertChannel`.

**Custom use-cases:** Slack/Teams webhook, PagerDuty, email, Prometheus counter,
SNS/SQS publish, in-memory recorder for tests.

**Contract:** never throw from `alert()`; must be thread-safe; should complete quickly
(delegate slow I/O to a background executor).

---

## 10. Security notes for custom implementations

When writing custom implementations, follow these guidelines to match the security
level of the built-in code.

### AlertChannel

| Risk | Mitigation |
|------|-----------|
| `diagnosisNotes` and `processName` contain free text that originates from JVM internals | Sanitise before embedding in HTTP bodies, SQL, or log lines: strip control characters (`\x00`–`\x1F`) and newlines |
| `heapDumpPath` is a file path under an admin-controlled directory | Do not pass this value to `Runtime.exec()` or shell commands |
| Network timeouts on webhook calls | Set connect and read timeouts (≤ 5 s); use a background executor if the call may block |
| Payload size | Cap outgoing payload length before sending (e.g. truncate `diagnosisNotes` at 1 024 chars) |

### JvmDiagnosticsCollector

| Risk | Mitigation |
|------|-----------|
| Mutable maps passed to `JvmSnapshot.Builder` | The builder takes defensive copies; you do not need to pre-copy, but never mutate a map after passing it |
| Exception during `collect()` | The watchdog catches `Exception` in the poll loop; however, prefer catching internally and returning a safe default snapshot |

### RiskAssessor

| Risk | Mitigation |
|------|-----------|
| Returning `null` from `assess()` | Never return `null` — the watchdog will `NullPointerException`; return the input snapshot unchanged if unsure |
| Mutating the input snapshot | Always use `snapshot.toBuilder()…build()` to produce a new object |

### HeapDumpService

| Risk | Mitigation |
|------|-----------|
| Path traversal via user-supplied `heapDumpDirectory` | Call `new File(path).getCanonicalPath()` before using a path in any system call |
| External process execution | Use `ProcessBuilder` with separate argument elements (never string concatenation); always set a timeout; always drain stdout/stderr to prevent buffer-fill deadlock |
| Large output from subprocesses | Cap output accumulation (4 096 bytes is a safe limit) |

### General

- Never log raw `diagnosisNotes` content to a security-sensitive sink without sanitising first.
- Use `StandardCharsets.UTF_8` explicitly in all `FileWriter` / `OutputStreamWriter` calls.
- Use `AtomicInteger` (not `volatile int`) for counters incremented from multiple threads.

---

## 11. Building the examples

The examples are compiled as part of the `core` module automatically:

```bash
cd oom-watchdog
mvn clean package -q
# All 6 example classes are included in core/target/oom-watchdog.jar
```

Run an individual example:

```bash
# Basic monitoring
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example01BasicMonitoring

# Framework integration (runs for 10 seconds then exits)
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example06FrameworkIntegration

# Custom implementations with escalating heap simulation
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example04CustomImplementations

# QRadar integration (pass your QRadar host as first arg)
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example05QRadarIntegration 192.168.1.100 514
```

Run all 189 tests to verify nothing is broken after adding examples:

```bash
mvn test -pl oom-watchdog-tests
# Tests run: 189, Failures: 0, Errors: 0, Skipped: 0
```

---

*See also: [README.md](README.md) | [ARCHITECTURE.md](ARCHITECTURE.md)*
