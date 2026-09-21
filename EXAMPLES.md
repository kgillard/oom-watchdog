# OOM Watchdog — Examples Guide

This document covers every way to use OOM Watchdog: running the pre-built JAR from the
command line without writing any code, embedding it in an application with the Java API,
and writing your own custom implementations of every extension point.

Eleven worked examples live in
[`core/src/main/java/com/trongus/oom/examples/`](core/src/main/java/com/trongus/oom/examples/)
and are documented in detail below.

---

## Table of Contents

1. [Quick start with Example 11](#1-quick-start-with-example-11)
2. [Using the JAR without any code](#2-using-the-jar-without-any-code)
   - [Download](#21-download)
   - [Monitoring mode](#22-monitoring-mode)
   - [Test / demo mode](#23-test--demo-mode)
   - [CLI quick-reference](#24-cli-quick-reference)
3. [Embedding the API in your application](#3-embedding-the-api-in-your-application)
   - [Maven / Gradle dependency](#31-maven--gradle-dependency)
   - [Minimal wiring](#32-minimal-wiring)
4. [Example 01 — Basic monitoring (console alerts)](#4-example-01--basic-monitoring)
5. [Example 02 — Multiple alert channels + custom channel](#5-example-02--multiple-alert-channels)
6. [Example 03 — Automatic dump capture on CRITICAL](#6-example-03--dump-on-critical)
7. [Example 04 — Custom implementations of all extension points](#7-example-04--custom-implementations)
8. [Example 05 — QRadar LEEF 2.0 syslog integration (basic)](#8-example-05--qradar-integration-basic)
9. [Example 07 — QRadar advanced patterns](#9-example-07--qradar-advanced-patterns)
10. [Example 06 — Framework integration (Spring Boot, health checks, metrics)](#10-example-06--framework-integration)
11. [Example 11 — OOM cause analysis and i18n](#11-example-11--oom-cause-analysis-and-i18n)
12. [Extension point reference](#12-extension-point-reference)
13. [Security notes for custom implementations](#13-security-notes-for-custom-implementations)
14. [Building the examples](#14-building-the-examples)
15. [IBM Application Server and Cognos Integration](#15-ibm-application-server-and-cognos-integration)
16. [Multi-Target Daemon Mode and Remote JMX Monitoring](#16-multi-target-daemon-mode-and-remote-jmx-monitoring)
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
7. [Example 05 — QRadar LEEF 2.0 syslog integration (basic)](#7-example-05--qradar-integration-basic)
8. [Example 07 — QRadar advanced patterns](#8-example-07--qradar-advanced-patterns)
   - [LEEF 2.0 wire format (complete)](#81-leef-20-wire-format-complete)
   - [QRadar log source setup](#82-qradar-log-source-setup)
   - [Pattern 1: CRITICAL-only forwarding](#83-pattern-1-critical-only-forwarding)
   - [Pattern 2: Rate-limited channel](#84-pattern-2-rate-limited-channel)
   - [Pattern 3: Environment-tagged events](#85-pattern-3-environment-tagged-events)
   - [Pattern 4: Primary / failover channel](#86-pattern-4-primaryfailover-channel)
   - [TLS-encrypted syslog relay](#87-tls-encrypted-syslog-relay)
   - [QRadar AQL correlation queries](#88-qradar-aql-correlation-queries)
9. [Example 06 — Framework integration (Spring Boot, health checks, metrics)](#9-example-06--framework-integration)
10. [Example 11 — OOM cause analysis and i18n](#10-example-11--oom-cause-analysis-and-i18n)
11. [Extension point reference](#11-extension-point-reference)
12. [Security notes for custom implementations](#12-security-notes-for-custom-implementations)
13. [Building the examples](#13-building-the-examples)
14. [IBM Application Server and Cognos Integration](#14-ibm-application-server-and-cognos-integration)
    - [Example 08 — WAS monitoring](#141-example-08--was-monitoring)
15. [Multi-Target Daemon Mode and Remote JMX Monitoring](#15-multi-target-daemon-mode-and-remote-jmx-monitoring)
    - [Example 09 — Liberty + MicroProfile Health](#142-example-09--liberty--microprofile-health)
    - [Example 10 — Cognos Analytics all three components](#143-example-10--cognos-analytics-all-three-components)
    - [WAS heap sizing guide](#144-was-heap-sizing-guide)
    - [Liberty server.xml configuration](#145-liberty-serverxml-configuration)
    - [Cognos component memory architecture](#146-cognos-component-memory-architecture)
    - [cognosservice.xml log file configuration](#147-cognosservicexml-log-file-configuration)

---

## 1. Quick start with Example 11

Run the all-in-one runnable demonstration (`Example11CauseAnalysisAndI18n`):

```bash
# English (default)
java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n

# Japanese
java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n ja

# Simplified Chinese
java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n zh_CN
```

---


## 2. Using the JAR without any code

### 1.1 Download

```bash
curl -L -o oom-watchdog.jar \
  https://github.com/kgillard/oom-watchdog/releases/download/v1.7.11.9/oom-watchdog.jar
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

**To monitor external JVMs remotely**, run in daemon mode with `--daemon` and `--targets-file` (see Section 16).

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
| `--gc-dump-threshold` | `double` (0–1) | _(disabled)_ | GC overhead ratio that triggers an immediate dump |
| `--heap-dump-threshold` | `double` (0–1) | _(disabled)_ | Heap usage ratio that triggers an immediate dump |
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

## 3. Embedding the API in your application

### 2.1 Maven / Gradle dependency

Build the JAR locally and install it:

```bash
cd oom-watchdog
mvn clean install -DskipTests -q
```

Then reference it in your `pom.xml`:

```xml
<dependency>
    <groupId>com.trongus.oom</groupId>
    <artifactId>oom-watchdog-core</artifactId>
    <version>1.5.0</version>
</dependency>
```

Or in Gradle:

```groovy
implementation 'com.trongus.oom:oom-watchdog-core:1.5.0'
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

## 4. Example 01 — Basic Monitoring

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

## 5. Example 02 — Multiple Alert Channels

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

## 6. Example 03 — Dump on Critical

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

## 7. Example 04 — Custom Implementations

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

## 8. Example 05 — QRadar Integration (basic)

**File:** [`Example05QRadarIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example05QRadarIntegration.java)

The simplest QRadar integration: one UDP channel and one (optional) TCP channel
running alongside the console alert channel.

```java
// UDP — fire-and-forget, lowest latency (default for most syslog deployments)
QRadarAlertChannel udp = new QRadarAlertChannel(
        host, port, QRadarAlertChannel.Transport.UDP);

// TCP — guaranteed delivery, fresh connection per alert, 5-second timeout
QRadarAlertChannel tcp = new QRadarAlertChannel(
        host, port, QRadarAlertChannel.Transport.TCP);

OomWatchdog watchdog = new OomWatchdog(config, collector, assessor,
        Arrays.asList(new ConsoleAlertChannel(), udp), dumpService);
watchdog.start();
```

**Run it:**
```bash
java -cp oom-watchdog.jar com.trongus.oom.examples.Example05QRadarIntegration \
     192.168.1.100 514
```

---

## 9. Example 07 — QRadar Advanced Patterns

**File:** [`Example07QRadarAdvanced.java`](core/src/main/java/com/trongus/oom/examples/Example07QRadarAdvanced.java)

Covers every production QRadar integration concern: event format anatomy, log source
configuration, CRITICAL-only filtering, rate limiting, environment tagging, primary/failover
resilience, TLS relay, and AQL correlation queries.

### 8.1 LEEF 2.0 wire format (complete)

Every alert emitted by `QRadarAlertChannel` is a syslog RFC 3164 message with a LEEF 2.0
payload.  LEEF attributes are separated by **literal tab characters** (`\t`).
The complete wire format for a CRITICAL event is a **single line** on the wire
(`<TAB>` marks each tab delimiter):

```
<13>Sep 17 08:00:00 prod-host LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|sev=9<TAB>cat=JVM_OOM_Risk<TAB>process=98765@prod-host<TAB>heapUsedMB=921<TAB>heapMaxMB=1024<TAB>heapPct=90.0<TAB>nonHeapUsedMB=128<TAB>gcOverheadPct=23.8<TAB>totalGcTimeMs=14300<TAB>postGcGrowth=42.30 MB/h<TAB>riskLevel=CRITICAL<TAB>gc_G1_Young_Generation_count=1420<TAB>gc_G1_Young_Generation_timeMs=6200<TAB>gc_G1_Old_Generation_count=3<TAB>gc_G1_Old_Generation_timeMs=8100<TAB>heapDump=/var/dumps/oom_heap_98765_20251017_080000_001.hprof<TAB>msg=[Assessment] CRITICAL – OOM imminent. Heap at 90.0% ...
```

The same message formatted for readability (each `<TAB>` is a literal tab on the wire):

| Attribute | Example value |
|-----------|--------------|
| Syslog header | `<13>Sep 17 08:00:00 prod-host` |
| LEEF header | `LEEF:2.0\|IBM\|OomWatchdog\|1.1\|OOM_CRITICAL\|` |
| `sev` | `9` |
| `cat` | `JVM_OOM_Risk` |
| `process` | `98765@prod-host` |
| `heapUsedMB` | `921` |
| `heapMaxMB` | `1024` |
| `heapPct` | `90.0` |
| `nonHeapUsedMB` | `128` |
| `gcOverheadPct` | `23.8` |
| `totalGcTimeMs` | `14300` |
| `postGcGrowth` | `42.30 MB/h` |
| `riskLevel` | `CRITICAL` |
| `gc_G1_Young_Generation_count` | `1420` |
| `gc_G1_Young_Generation_timeMs` | `6200` |
| `gc_G1_Old_Generation_count` | `3` |
| `gc_G1_Old_Generation_timeMs` | `8100` |
| `heapDump` | `/var/dumps/oom_heap_98765_20251017_080000_001.hprof` |
| `msg` | `[Assessment] CRITICAL – OOM imminent. Heap at 90.0% ...` |

**Why `IBM` as the LEEF vendor field?**
`QRadarAlertChannel` sets `VENDOR = "IBM"` because LEEF 2.0 is an IBM-defined protocol
and `IBM` is the registered vendor identifier that causes QRadar's built-in LEEF DSM to
parse the event without any manual DSM mapping.  The trongus brand name appears in the
`process` and `msg` fields of each event, not in the LEEF vendor header.

**Field reference:**

| Field | Type | Description |
|-------|------|-------------|
| `sev` | int 1–10 | QRadar severity: WARNING=5, CRITICAL=9, OOM_FIRING=10 |
| `cat` | string | Always `JVM_OOM_Risk` — use for QRadar log source filtering |
| `process` | string | JVM process name from `RuntimeMXBean.getName()` (e.g. `12345@host`) |
| `heapUsedMB` | long | Heap memory currently in use by live objects (megabytes) |
| `heapMaxMB` | long | Maximum heap size (`-Xmx`) in megabytes |
| `heapPct` | double | Heap utilisation percentage (`heapUsedMB / heapMaxMB × 100`) |
| `nonHeapUsedMB` | long | Metaspace + Code Cache in use (megabytes) |
| `gcOverheadPct` | double | Fraction of JVM uptime spent in GC (percentage) |
| `totalGcTimeMs` | long | Cumulative GC wall-clock time since JVM start (milliseconds) |
| `gc_<name>_count` | long | Per-collector cumulative collection count (one field per GC algorithm) |
| `gc_<name>_timeMs` | long | Per-collector cumulative collection time (one field per GC algorithm) |
| `postGcGrowth` | string | OLS slope of post-GC heap samples: `42.30 MB/h` or `N/A` |
| `riskLevel` | string | Enum name: `OK`, `WARNING`, `CRITICAL`, or `OOM_FIRING` |
| `heapDump` | string | Semicolon-separated absolute paths to dump files (present on CRITICAL only) |
| `msg` | string | Full diagnosis notes (sanitised: tabs, newlines, and pipes replaced) |

### 8.2 QRadar log source setup

In the QRadar Console → **Admin → Log Sources → Add**:

| Setting | Value |
|---------|-------|
| **Log Source Type** | Universal LEEF |
| **Protocol Configuration** | Syslog |
| **Log Source Identifier** | IP address of the host running OOM Watchdog |
| **Port** | 514 (UDP default) or 1514 (TCP common alternate) |
| **Log Source Group** | JVM Monitoring (create if absent) |

After saving, navigate to **Admin → Log Source Extensions** and verify the LEEF
header `LEEF:2.0|IBM|OomWatchdog` is parsing correctly.  If events appear as
"Unknown Log Source", manually assign the **Universal LEEF** DSM.

**Verifying events reach QRadar** (AQL):
```sql
SELECT * FROM events
WHERE "LogSourceType" = 'UniversalLeef'
  AND "cat" = 'JVM_OOM_Risk'
ORDER BY "starttime" DESC
LAST 1 HOURS
```

### 8.3 Pattern 1: CRITICAL-only forwarding

Reduce QRadar EPS consumption — send WARNING events only to local channels, not to the SIEM:

```java
public final class CriticalOnlyQRadarChannel implements AlertChannel {

    private final QRadarAlertChannel delegate;

    public CriticalOnlyQRadarChannel(String host, int port,
                                     QRadarAlertChannel.Transport transport) {
        this.delegate = new QRadarAlertChannel(host, port, transport);
    }

    @Override
    public void alert(JvmSnapshot snapshot) {
        // Only forward CRITICAL and OOM_FIRING — suppress WARNING noise
        if (snapshot.getRiskLevel().ordinal() >= OomRiskLevel.CRITICAL.ordinal()) {
            delegate.alert(snapshot);
        }
    }
}
```

**When to use:** when your QRadar EPS licence is constrained, or when WARNING-level events
from many JVM services create too much noise in SIEM dashboards.

### 8.4 Pattern 2: Rate-limited channel

Prevent a sustained critical episode from flooding QRadar with one event every poll cycle:

```java
public final class RateLimitedQRadarChannel implements AlertChannel {

    private final QRadarAlertChannel delegate;
    private final long minIntervalMs;
    private final AtomicLong lastSentMs = new AtomicLong(0L);

    @Override
    public void alert(JvmSnapshot snapshot) {
        long now     = System.currentTimeMillis();
        long elapsed = now - lastSentMs.get();
        if (elapsed >= minIntervalMs) {
            if (lastSentMs.compareAndSet(lastSentMs.get(), now)) {
                delegate.alert(snapshot);
            }
        }
        // Otherwise: suppress — QRadar already has an event for this episode
    }
}
```

**Configuration:**
```java
// Allow at most 1 QRadar event per 5 minutes per episode
AlertChannel rateLimited = new RateLimitedQRadarChannel(
        "siem.corp.com", 514, Transport.UDP, 5 * 60 * 1_000L);
```

**When to use:** when `pollIntervalMs` is low (e.g. 1 s) and you do not want QRadar to
receive hundreds of duplicate events during a sustained OOM condition.

### 8.5 Pattern 3: Environment-tagged events

Prepend `[env=production][app=order-service][region=us-east-1]` to every `msg` field
so QRadar rules can distinguish production incidents from dev/staging noise:

```java
public final class TaggedQRadarChannel implements AlertChannel {

    private final QRadarAlertChannel delegate;
    private final String environment;
    private final String application;
    private final String region;

    @Override
    public void alert(JvmSnapshot snapshot) {
        // Enrich the snapshot — never mutate the original
        String enrichedNotes = String.format("[env=%s][app=%s][region=%s] %s",
                environment, application, region, snapshot.getDiagnosisNotes());
        JvmSnapshot enriched = snapshot.toBuilder()
                .diagnosisNotes(enrichedNotes)
                .build();
        delegate.alert(enriched);
    }
}
```

**Populating tags from the environment (Kubernetes):**
```yaml
# Pod spec excerpt
env:
  - name: APP_ENV
    value: "production"
  - name: APP_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.labels['app']
  - name: APP_REGION
    value: "us-east-1"
```

```java
new TaggedQRadarChannel(
    host, port, Transport.UDP,
    System.getenv().getOrDefault("APP_ENV",    "unknown"),
    System.getenv().getOrDefault("APP_NAME",   "unknown"),
    System.getenv().getOrDefault("APP_REGION", "unknown"));
```

**QRadar rule using environment tags:**
```sql
-- Fire a high-priority offense only for production CRITICAL events
SELECT "sourceip", "msg"
FROM events
WHERE "cat" = 'JVM_OOM_Risk'
  AND "msg" ILIKE '%[env=production]%'
  AND "sev" >= 9
LAST 5 MINUTES
```

### 8.6 Pattern 4: Primary/failover channel

Try the primary QRadar endpoint; fall back to a secondary if the primary fails.
Most useful with TCP transport where connection failures are immediately visible:

```java
public final class FailoverQRadarChannel implements AlertChannel {

    private final QRadarAlertChannel primary;
    private final QRadarAlertChannel secondary;

    @Override
    public void alert(JvmSnapshot snapshot) {
        try {
            primary.alert(snapshot);
        } catch (Exception e) {
            System.err.println("Primary QRadar failed: " + e.getMessage());
            secondary.alert(snapshot);   // fallback
        }
    }
}

// Usage
new FailoverQRadarChannel(
    "qradar-primary.corp.com",   514,
    "qradar-standby.corp.com",   514,
    QRadarAlertChannel.Transport.TCP);
```

**Note:** UDP `alert()` never throws (datagrams are fire-and-forget).  For UDP failover,
send to both endpoints simultaneously rather than trying one first.

### 8.7 TLS-encrypted syslog relay

Raw syslog is plaintext.  For PCI-DSS, HIPAA, or ISO 27001 compliance, place an
rsyslog TLS relay in front of QRadar:

```
OOM Watchdog  →  UDP/TCP plaintext on port 514  →  rsyslog relay (localhost or LAN)
                                                       │  TLS on port 6514
                                                       ▼
                                                   IBM QRadar
```

**rsyslog.conf** on the relay host:
```conf
# Accept UDP syslog from OOM Watchdog
module(load="imudp")
input(type="imudp" port="514")

# Forward encrypted to QRadar over TLS
module(load="omfwd")
action(type="omfwd"
       Target="qradar.corp.com"
       Port="6514"
       Protocol="tcp"
       StreamDriver="gtls"
       StreamDriverMode="1"
       StreamDriverAuthMode="x509/name"
       StreamDriverPermittedPeers="qradar.corp.com")
```

No code changes are needed in OOM Watchdog — configure it to send plaintext UDP
to `localhost:514` and the relay handles encryption transparently.

### 8.8 QRadar AQL correlation queries

These AQL queries identify meaningful OOM patterns across your estate.

**Sustained critical pressure (> 3 consecutive polls above 85 %)**:
```sql
SELECT "sourceip", "username",
       COUNT(*) AS alert_count,
       MAX(FLOAT("heapPct")) AS max_heap_pct
FROM events
WHERE "cat" = 'JVM_OOM_Risk'
  AND FLOAT("heapPct") >= 85
GROUP BY "sourceip", "username"
HAVING COUNT(*) >= 3
LAST 10 MINUTES
```

**Leak detection — post-GC growth rate above 10 MB/hour**:
```sql
SELECT "sourceip", "process", "postGcGrowth", "msg"
FROM events
WHERE "cat" = 'JVM_OOM_Risk'
  AND "postGcGrowth" NOT LIKE 'N/A%'
  AND FLOAT(REPLACE("postGcGrowth", ' MB/h', '')) > 10.0
ORDER BY FLOAT(REPLACE("postGcGrowth", ' MB/h', '')) DESC
LAST 1 HOURS
```

**GC overhead emergency (> 80 % of uptime in GC)**:
```sql
SELECT "sourceip", "process", "gcOverheadPct", "heapPct"
FROM events
WHERE "cat" = 'JVM_OOM_Risk'
  AND FLOAT("gcOverheadPct") >= 80
ORDER BY FLOAT("gcOverheadPct") DESC
LAST 30 MINUTES
```

**Dump file inventory — which processes triggered dumps today**:
```sql
SELECT "sourceip", "process", "heapDump", "starttime"
FROM events
WHERE "cat" = 'JVM_OOM_Risk'
  AND "heapDump" IS NOT NULL
  AND "heapDump" != ''
ORDER BY "starttime" DESC
LAST 24 HOURS
```

---

## 10. Example 06 — Framework Integration

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

## 11. Example 11 — OOM Cause Analysis and i18n

**File:** [`Example11CauseAnalysisAndI18n.java`](core/src/main/java/com/trongus/oom/examples/Example11CauseAnalysisAndI18n.java)

Demonstrates the two features introduced in v1.3.0: `OomCauseAnalyser` used standalone
to classify JVM signals into root-cause categories, and `WatchdogConfig.locale()` to
produce alert text in any of nine supported locales.

### Standalone OomCauseAnalyser

The analyser is stateless and thread-safe.  It accepts three signals and returns an
`OomCause` with a category enum and a localised plain-language explanation:

```java
Messages messages = new Messages(Locale.ENGLISH);
OomCauseAnalyser analyser = new OomCauseAnalyser(
        0.90,      // criticalHeapThreshold
        0.50,      // gcOverheadThreshold
        messages);

// All five categories:
analyser.analyse(0.50, 0.10, 0.0);                    // → NONE
analyser.analyse(0.72, 0.22, 50.0 / 3_600_000.0);    // → MEMORY_LEAK_TREND
analyser.analyse(0.65, 0.60, 0.0);                    // → GC_OVERHEAD_EXCEEDED
analyser.analyse(0.93, 0.30, 0.0);                    // → HEAP_EXHAUSTION
analyser.analyse(0.94, 0.72, 20.0 / 3_600_000.0);    // → RUNAWAY_GC_WITH_HIGH_HEAP
```

The explanation is already fully formatted for embedding in alert text, log entries,
or diagnostic tools without further processing.

### Locale-aware watchdog

```java
// Pass a Locale to WatchdogConfig — ThresholdRiskAssessor picks it up automatically
WatchdogConfig config = WatchdogConfig.defaults()
        .warningHeapThreshold(0.80)
        .criticalHeapThreshold(0.90)
        .pollIntervalMs(3_000L)
        .locale(Locale.JAPANESE)   // or: new Locale("pt", "BR"), Locale.KOREAN, etc.
        .build();

OomWatchdog watchdog = new OomWatchdog(
        config,
        new MxBeanDiagnosticsCollector(config),
        new ThresholdRiskAssessor(config),
        Arrays.asList(new ConsoleAlertChannel(),
                      new FileLogAlertChannel("./oom-example11.log")),
        new CompositeDumpService(config)
);
watchdog.start();
```

**Supported locale tags** (pass as arg to `Example11CauseAnalysisAndI18n`):

| Tag | Language |
|-----|----------|
| _(omit)_ or `en` | English (default) |
| `de` | German |
| `es` | Spanish |
| `fr` | French |
| `ja` | Japanese |
| `ko` | Korean |
| `pt_BR` | Brazilian Portuguese |
| `zh_CN` | Simplified Chinese |
| `zh_TW` | Traditional Chinese |

**Run it:**

```bash
# English
java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n

# Japanese (locale as first argument)
java -cp oom-watchdog.jar com.trongus.oom.examples.Example11CauseAnalysisAndI18n ja

# Run with a tiny heap to trigger actual alerts in Japanese
java -Xmx32m -cp oom-watchdog.jar \
     com.trongus.oom.examples.Example11CauseAnalysisAndI18n ja
```

---

## 12. Extension Point Reference

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

## 13. Security notes for custom implementations

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

## 14. Building the examples

The examples are compiled as part of the `core` module automatically:

```bash
cd oom-watchdog
mvn clean package -q
# All 11 example classes are included in core/target/oom-watchdog.jar
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

# Basic QRadar integration (pass your QRadar host as first arg)
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example05QRadarIntegration 192.168.1.100 514

# Advanced QRadar — all four patterns active
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example07QRadarAdvanced \
     qradar-primary.corp.com 514 qradar-standby.corp.com 10514

# IBM Application Server examples
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example08WasIntegration

java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example09LibertyIntegration

java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example10CognosIntegration

# OOM cause analysis + i18n (pass locale tag as optional first argument)
java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example11CauseAnalysisAndI18n

java -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example11CauseAnalysisAndI18n ja

java -Xmx32m -cp core/target/oom-watchdog.jar \
     com.trongus.oom.examples.Example11CauseAnalysisAndI18n zh_CN
```

Run all 189 tests to verify nothing is broken after adding examples:

```bash
mvn test -pl oom-watchdog-tests
# Tests run: 189, Failures: 0, Errors: 0, Skipped: 0
```

---

## 15. IBM Application Server and Cognos Integration

OOM Watchdog 1.5.0 adds three new `AlertChannel` implementations targeting IBM
application server platforms.  Each channel uses `java.util.logging` (JUL), which is
intercepted at runtime by WAS, Liberty, and Cognos without any additional dependencies.

---

### 14.1 Example 08 — WAS monitoring

**File:** [`Example08WasIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example08WasIntegration.java)

**What it demonstrates:**
- Embedding the watchdog in a WAS application via a `WasContextListener` POJO that
  mirrors the `javax.servlet.ServletContextListener` lifecycle methods
  (`contextInitialized` / `contextDestroyed`).
- Using `WasAlertChannel` alongside `FileLogAlertChannel` for dual-destination alerting.
- The WAS-specific shutdown hook pattern via `Runtime.getRuntime().addShutdownHook()`.
- `WatchdogConfig` tuned for production WAS (30-second poll, 256 MB–2 GB heaps).

**Key configuration:**

```java
WatchdogConfig config = WatchdogConfig.defaults()
    .warningHeapThreshold(0.75)
    .criticalHeapThreshold(0.88)
    .pollIntervalMs(30_000L)
    .leakDetectionWindowSize(5)
    .gcOverheadThreshold(0.40)
    .heapDumpDirectory("${SERVER_LOG_ROOT}/oom-dumps")
    .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
    .build();
```

**Channel stack:**

```java
Arrays.asList(
    new WasAlertChannel(),          // → SystemErr.log (FFDC incident ID included)
    new FileLogAlertChannel("${SERVER_LOG_ROOT}/oom-watchdog/oom-watchdog.log")
)
```

**Deploying in a real WAS WAR:**

```java
// 1. Add javax.servlet-api to your WAR's provided scope in pom.xml
// 2. Annotate the inner class:

@WebListener
public static final class WasContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) { startWatchdog(); }

    @Override
    public void contextDestroyed(ServletContextEvent sce)   { stopWatchdog(); }
}
```

**WAS Admin Console — logger configuration:**

1. Open WAS Integrated Solutions Console.
2. Navigate to **Servers → Server Types → WebSphere application servers →
   `<your server>` → Troubleshooting → Logging and Tracing → Change Log Detail Levels**.
3. Add: `com.trongus.oom.*=ALL`
4. Apply and save (no restart required).

---

### 14.2 Example 09 — Liberty + MicroProfile Health

**File:** [`Example09LibertyIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example09LibertyIntegration.java)

**What it demonstrates:**
- CDI `@ApplicationScoped` lifecycle pattern via `LibertyAppBean` inner class.
- `LibertyAlertChannel` producing structured JSON log messages to `messages.log`.
- `OomHealthCheck` inner class illustrating MicroProfile Health `@Liveness` integration.
- Aggressive thresholds appropriate for container-sized heaps (256–512 MB).

**Key configuration (microservice tuning):**

```java
WatchdogConfig config = WatchdogConfig.defaults()
    .warningHeapThreshold(0.70)   // warn earlier — small heap saturates fast
    .criticalHeapThreshold(0.85)  // critical before container OOM-killer fires
    .pollIntervalMs(10_000L)      // 10 s for rapid container spike detection
    .leakDetectionWindowSize(3)   // 30-second leak window
    .gcOverheadThreshold(0.35)    // lower threshold: small heap = faster GC pressure
    .dumpTypes(EnumSet.of(DumpType.THREAD))
    .build();
```

**MicroProfile Health integration:**

```java
// In a real Liberty app with mpHealth-4.0 feature:
@Liveness
@ApplicationScoped
public class OomHealthCheck implements HealthCheck {

    @Inject
    private LibertyAppBean oomBean;

    @Override
    public HealthCheckResponse call() {
        boolean healthy = oomBean.getAlertChannel().isHealthy();
        return HealthCheckResponse.named("jvm-oom-risk")
                .status(healthy)
                .withData("riskLevel", oomBean.getAlertChannel().getLastRiskLevel().name())
                .build();
    }
}
```

The Liberty `/health/live` endpoint returns `DOWN` automatically when the JVM is at
`CRITICAL` or `OOM_FIRING` risk level, triggering a Kubernetes liveness restart.

---

### 14.3 Example 10 — Cognos Analytics all three components

**File:** [`Example10CognosIntegration.java`](core/src/main/java/com/trongus/oom/examples/Example10CognosIntegration.java)

**What it demonstrates:**
- A `CognosStartupMonitor` inner class managing the OOM Watchdog lifecycle for one
  Cognos JVM component.
- Factory methods (`atcMonitor`, `contentManagerMonitor`, `gatewayMonitor`) with
  pre-tuned configurations per component.
- Starting separate watchdog instances for all three Cognos JVM processes.
- Heap thresholds appropriate for Cognos Report Studio large-dataset workloads.

**Per-component configurations:**

| Component | `-Xmx` range | Warn % | Critical % | Poll interval |
|-----------|-------------|--------|-----------|---------------|
| ATC       | 4 GB – 8 GB | 70 %   | 85 %      | 15 s          |
| CM        | 2 GB – 4 GB | 75 %   | 88 %      | 30 s          |
| Gateway   | 1 GB – 2 GB | 80 %   | 90 %      | 30 s          |

**Log file per component:**

```
${COGNOS_LOGS}/oom-watchdog-ATC.log
${COGNOS_LOGS}/oom-watchdog-CM.log
${COGNOS_LOGS}/oom-watchdog-Gateway.log
```

---

### 14.4 WAS heap sizing guide

| JVM argument              | Recommended value for WAS production                           |
|---------------------------|----------------------------------------------------------------|
| `-Xms`                    | Equal to `-Xmx` (avoids GC storms during heap resize)         |
| `-Xmx`                    | 1 GB – 4 GB depending on application workload                 |
| `-Xmn`                    | 25–33 % of `-Xmx` (nursery size for IBM J9 `gencon`)          |
| `-Xgcpolicy`              | `gencon` (generational + concurrent — WAS default)            |
| `-Xdump:heap`             | Enable for automatic OOM heap capture                         |
| `-verbose:gc`             | Route to `${LOG_ROOT}/verbosegc.log` for GC analysis          |
| `-XX:MaxMetaspaceSize`    | 256 m – 512 m (limit Metaspace growth)                        |

---

### 14.5 Liberty `server.xml` configuration

```xml
<featureManager>
    <feature>mpHealth-4.0</feature>
    <feature>cdi-4.0</feature>
</featureManager>

<!-- Enable JSON logging and OOM Watchdog trace -->
<logging traceSpecification="com.trongus.oom.*=all"
         messageFormat="JSON"
         logDirectory="${server.output.dir}/logs"
         maxFileSize="20"
         maxFiles="5" />
```

For Liberty 8.5.5.x (older feature names):

```xml
<logging traceSpecification="com.trongus.oom.*=all"
         messageFormat="ENHANCED"
         logDirectory="${server.output.dir}/logs" />
```

---

### 14.6 Cognos component memory architecture

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                   Cognos Analytics Deployment                   │
  │                                                                 │
  │  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐    │
  │  │   ATC JVM    │   │   CM JVM     │   │  Gateway JVM     │    │
  │  │  -Xmx4g–8g   │   │  -Xmx2g–4g  │   │  -Xmx1g–2g       │    │
  │  │              │   │              │   │                  │    │
  │  │ Report Engine│   │ Content Mgr  │   │ HTTP Dispatcher  │    │
  │  │ Session Cache│   │ JDBC Cache   │   │ Session Routing  │    │
  │  │ PDF Renderer │   │ Metadata Tree│   │ Load Balancer    │    │
  │  │              │   │              │   │                  │    │
  │  │ OomWatchdog  │   │ OomWatchdog  │   │ OomWatchdog      │    │
  │  └──────┬───────┘   └──────┬───────┘   └────────┬─────────┘    │
  │         │                  │                    │              │
  └─────────┼──────────────────┼────────────────────┼──────────────┘
            │                  │                    │
  CognosAlertChannel    CognosAlertChannel   CognosAlertChannel
  oom-watchdog-ATC.log  oom-watchdog-CM.log  oom-watchdog-GW.log
            │                  │                    │
            └──────────────────┴────────────────────┘
                               │
                    Cognos Log Server / QRadar
```

---

### 14.7 `cognosservice.xml` log file configuration

```xml
<!-- In cognosservice.xml — route Cognos Log Server to OOM Watchdog alert files -->
<param name="Log.outputFile">
    /opt/IBM/cognos/analytics/logs/oom-watchdog-ATC.log
</param>
<param name="Log.localCaching">true</param>
<param name="Log.flushInterval">30</param>

<!-- Recommended JVM arguments for ATC component -->
<param name="Environment.JAVA_OPTIONS">
    -Xms2g -Xmx8g -Xmn2g -Xgcpolicy:gencon
    -XX:MaxMetaspaceSize=512m
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/opt/IBM/cognos/analytics/logs/heapdumps
    -verbose:gc -Xverbosegclog:/opt/IBM/cognos/analytics/logs/verbosegc-ATC.log
</param>
```

---

## 16. Multi-Target Daemon Mode and Remote JMX Monitoring

### 15.1 Overview (v1.7.11.9)

In enterprise deployments such as IBM QRadar or multi-tier WebSphere clusters, multiple JVMs run concurrently on a single appliance or host. `WatchdogDaemon` allows a single lightweight watchdog process to monitor all target JVMs simultaneously over standard JMX (JSR-160 RMI).

### 15.2 targets.properties configuration

```properties
# ==============================================================================
# QRadar Multi-Target Configuration
# ==============================================================================

# Target 1: QRadar hostcontext (main orchestrator)
target.hostcontext.jmx-url    = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi
target.hostcontext.warn       = 0.75
target.hostcontext.crit       = 0.85
target.hostcontext.poll-ms    = 3000
target.hostcontext.dump-types = heap,thread
target.hostcontext.dump-dir   = /var/log/qradar/dumps/hostcontext

# Target 2: Tomcat Web Server (UI and REST API)
target.tomcat.jmx-url         = service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi
target.tomcat.warn            = 0.80
target.tomcat.crit            = 0.90
target.tomcat.dump-types      = heap,thread,class_histogram
target.tomcat.dump-dir        = /var/log/qradar/dumps/tomcat

# Target 3: WebSphere Liberty / Open Liberty
target.liberty.jmx-url        = service:jmx:rmi:///jndi/rmi://localhost:9443/jmxrmi
target.liberty.warn           = 0.75
target.liberty.crit           = 0.88
target.liberty.dump-types     = heap,thread
```

### 15.3 Running the Daemon

```bash
java -jar oom-watchdog.jar \
    --daemon \
    --targets-file /etc/oom-watchdog/targets.properties \
    --qradar-host 127.0.0.1 \
    --qradar-port 514
```

### 15.4 IPv6 and Dual-Stack Configuration

OOM Watchdog supports IPv4, IPv6, and dual-stack environments throughout the stack.

#### Metrics server binding

The metrics HTTP/HTTPS server automatically prefers IPv6 and falls back to IPv4:

```bash
# Default loopback — binds to ::1 (falls back to 127.0.0.1 on IPv4-only stacks)
java -jar oom-watchdog.jar --metrics-port 9090

# Bind to all interfaces — binds to :: (falls back to 0.0.0.0 on IPv4-only stacks)
java -jar oom-watchdog.jar --metrics-port 9090 --metrics-bind-all

# Force IPv4-only (useful on systems where IPv6 is present but misconfigured)
java -Djava.net.preferIPv4Stack=true -jar oom-watchdog.jar --metrics-port 9090

# Prefer IPv6 when both families are available
java -Djava.net.preferIPv6Addresses=true -jar oom-watchdog.jar --metrics-port 9090
```

#### Dashboard URL examples for IPv6

Use RFC 3986 bracket notation in the dashboard **Server** field:

| Environment | Server URL |
|---|---|
| IPv6 loopback (same host) | `https://[::1]:9090` |
| Specific IPv6 address | `https://[2001:db8::1]:9090` |
| IPv4 explicit | `https://127.0.0.1:9090` |
| Hostname (DNS resolves either) | `https://localhost:9090` |

#### JMX URLs with IPv6 targets

When a remote JVM is on an IPv6-only host, use RFC 3986 bracket notation in the JMX URL:

```properties
# IPv6 literal address in JMX URL (brackets required by the RMI connector)
target.myapp.jmx-url = service:jmx:rmi:///jndi/rmi://[::1]:7777/jmxrmi
target.myapp.jmx-url = service:jmx:rmi:///jndi/rmi://[2001:db8::1]:7777/jmxrmi

# On the monitored JVM, also set the RMI hostname to the IPv6 address:
# -Djava.rmi.server.hostname=2001:db8::1
```

#### QRadar / syslog IPv6 forwarding

The `--qradar-host` value accepts hostnames, IPv4 literals, and IPv6 literals. The
`QRadarAlertChannel` resolves all A and AAAA records for the hostname and automatically
opens a dual-stack UDP or TCP socket matching the destination address family:

```bash
# Send LEEF events to a QRadar appliance on an IPv6 network
java -jar oom-watchdog.jar \
    --daemon \
    --targets-file /etc/oom-watchdog/targets.properties \
    --qradar-host 2001:db8::10 \
    --qradar-port 514

# Or use the DNS name — both A and AAAA records are tried automatically
java -jar oom-watchdog.jar \
    --daemon \
    --targets-file /etc/oom-watchdog/targets.properties \
    --qradar-host qradar.example.com \
    --qradar-port 514
```

### 15.5 LEEF 2.0 Output with targetJvm Tag

When forwarded to QRadar SIEM, events produced in daemon mode automatically include the `targetJvm` attribute:

```
<13>Sep 17 08:00:00 qradar-appliance LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|sev=9\tcat=JVM_OOM_Risk\ttargetJvm=hostcontext\tprocess=hostcontext (12345@qradar-appliance)\theapUsedMB=1740\theapMaxMB=2048\theapPct=85.0\tnonHeapUsedMB=256\tgcOverheadPct=18.4\ttotalGcTimeMs=9200\tpostGcGrowth=34.10 MB/h\triskLevel=CRITICAL\tmsg=...
```

---

*See also: [README.md](README.md) | [ARCHITECTURE.md](ARCHITECTURE.md)*
