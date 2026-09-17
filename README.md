# OOM Watchdog

> **Preemptively detect and alert on JVM Out-of-Memory conditions — before the process crashes.**

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Tests](https://img.shields.io/badge/tests-189%20passing-brightgreen)]()
[![Security Audit](https://img.shields.io/badge/security%20audit-4%20passes%20clean-brightgreen)]()
[![JDK](https://img.shields.io/badge/JDK-8%20%E2%80%93%2026%2B-blue)]()
[![Vendors](https://img.shields.io/badge/JVM-HotSpot%20%7C%20OpenJ9%20%7C%20GraalVM-blue)]()
[![Release](https://img.shields.io/badge/release-v1.5.0-blue)](https://github.com/kgillard/oom-watchdog/releases/tag/v1.5.0)

---

## Download

Pre-built JARs are available in the [v1.5.0 release](https://github.com/kgillard/oom-watchdog/releases/tag/v1.5.0):

| Artefact | Description | Size |
|----------|-------------|------|
| [`oom-watchdog.jar`](https://github.com/kgillard/oom-watchdog/releases/download/v1.5.0/oom-watchdog.jar) | Fat JAR — monitoring agent + CLI entry point | ~79 KB |
| [`test-harness.jar`](https://github.com/kgillard/oom-watchdog/releases/download/v1.5.0/test-harness.jar) | Fat JAR — interactive OOM test harness | ~92 KB |

---

## What it does

OOM Watchdog runs a lightweight daemon thread inside your JVM that polls the standard
`java.lang.management` MXBeans every N milliseconds and evaluates three risk dimensions:

| Dimension | What it detects |
|-----------|-----------------|
| **Heap usage ratio** | Heap used / max > warning/critical threshold |
| **GC overhead** | Fraction of CPU time spent in GC > threshold |
| **Post-GC heap growth (OLS slope)** | Slow leaks invisible to threshold checks |

When the risk level crosses `WARNING` or `CRITICAL`, every configured alert channel fires.
On the first `CRITICAL` event in an episode, the dump service captures diagnostic artefacts
(heap dump, thread dump, class histogram, core dump) — but only **once per episode** to
prevent dump storms.

**New in v1.5.0:** Per-release Quick Start guide, Example 11 (OOM cause analysis + i18n),
and all example `@version` tags updated.  See [`EXAMPLES.md`](EXAMPLES.md) for the
complete release-by-release changelog.

**v1.4.0:** Security audit pass 4 — `AtomicBoolean.compareAndSet`, `AtomicReference`,
`CopyOnWriteArrayList` hardening.

**v1.3.0:** `OomCauseAnalyser` root-cause analysis and full i18n across 9 locales
(`en`, `de`, `es`, `fr`, `ja`, `ko`, `pt_BR`, `zh_CN`, `zh_TW`).

---

## Quick start

### 1 — Download and run

```bash
# Download the release JAR
curl -L -o oom-watchdog.jar \
  https://github.com/kgillard/oom-watchdog/releases/download/v1.5.0/oom-watchdog.jar

# Run against a target JVM process (monitoring mode)
java -jar oom-watchdog.jar \
    --warn-threshold 0.80          \
    --crit-threshold 0.90          \
    --gc-threshold   0.50          \
    --poll-ms        5000          \
    --dump-dir       /var/dumps    \
    --dump-types     HEAP,THREAD   \
    --log-file       /var/log/oom-watchdog.log
```

### 2 — Embed in your application

```java
WatchdogConfig config = WatchdogConfig.defaults()
    .warningHeapThreshold(0.80)
    .criticalHeapThreshold(0.90)
    .gcOverheadThreshold(0.50)
    .pollIntervalMs(5_000)
    .heapDumpDirectory("/var/dumps")
    .dumpTypes(EnumSet.of(DumpType.HEAP, DumpType.THREAD))
    .locale(Locale.ENGLISH)          // optional; defaults to Locale.getDefault()
    .build();

OomWatchdog watchdog = new OomWatchdog(
    config,
    new MxBeanDiagnosticsCollector(config),
    new ThresholdRiskAssessor(config),
    Arrays.asList(
        new ConsoleAlertChannel(),
        new FileLogAlertChannel("/var/log/oom.log"),
        new QRadarAlertChannel("siem.corp.com", 514, Transport.UDP)
    ),
    new CompositeDumpService(config)
);

watchdog.start();
// … your application runs …
watchdog.stop();
```

### 3 — Self-extracting installer

```bash
chmod +x oom-watchdog-installer.sh
./oom-watchdog-installer.sh          # extracts oom-watchdog.jar + test-harness.jar
```

---

## CLI reference

| Flag | Default | Description |
|------|---------|-------------|
| `--warn-threshold <0.0–1.0>` | `0.80` | Heap usage fraction that triggers WARNING |
| `--crit-threshold <0.0–1.0>` | `0.90` | Heap usage fraction that triggers CRITICAL |
| `--gc-threshold <0.0–1.0>` | `0.50` | GC CPU fraction that triggers WARNING |
| `--poll-ms <ms>` | `5000` | Poll interval in milliseconds (minimum: 100) |
| `--dump-dir <path>` | `./dumps` | Output directory for dump artefacts |
| `--dump-types <list>` | _(none)_ | Comma-separated: `HEAP,THREAD,CLASS_HISTOGRAM,CORE` |
| `--log-file <path>` | `./oom-watchdog.log` | Append structured alerts to this file |
| `--qradar-host <host>` | _(disabled)_ | QRadar / syslog target hostname |
| `--qradar-port <port>` | `514` | QRadar / syslog target port (1–65535) |
| `--qradar-tcp` | _(off)_ | Use TCP instead of UDP for QRadar |
| `--test-mode` | _(off)_ | Run OomSimulator and exit |
| `--test-leak-secs <s>` | `20` | Slow-leak phase duration in test mode |
| `--help` | | Print usage and exit |

---

## Risk levels

```
OK  →  WARNING  →  CRITICAL  →  OOM_FIRING
```

| Level | Condition |
|-------|-----------|
| `OK` | All metrics below thresholds |
| `WARNING` | Heap > warning threshold **or** GC overhead > threshold **or** positive post-GC growth slope |
| `CRITICAL` | Heap > critical threshold **or** (GC overhead > threshold **and** heap ≥ warning threshold) |
| `OOM_FIRING` | `java.lang.OutOfMemoryError` imminent (reserved for future hooks) |

---

## Alert channels and logging destinations

OOM Watchdog ships six alert channel implementations. Each targets a different runtime environment. All channels are composable — you can register any combination of them on a single watchdog instance.

### Channel summary

| Channel | Output destination | Best for |
|---------|--------------------|----------|
| `ConsoleAlertChannel` | `System.err` | Any JVM — development, containers, scripts |
| `FileLogAlertChannel` | Append-only UTF-8 file | Any JVM — production file-based log pipelines |
| `QRadarAlertChannel` | IBM QRadar SIEM (LEEF 2.0 syslog UDP/TCP) | Any JVM with SIEM integration |
| `WasAlertChannel` | WAS `SystemErr.log` via JUL | WebSphere Application Server (traditional) |
| `LibertyAlertChannel` | Liberty `messages.log` via JUL + JSON | WebSphere Liberty / Open Liberty |
| `CognosAlertChannel` | Cognos pipe-delimited log file + JUL | IBM Cognos Analytics (ATC / CM / Gateway JVM) |

---

### Standard JVM (HotSpot, OpenJ9, GraalVM) — no app server

Use `ConsoleAlertChannel` and/or `FileLogAlertChannel`. Both require no external dependencies and work on every JVM that supports `java.lang.management`.

```java
new ConsoleAlertChannel()                          // → System.err
new FileLogAlertChannel("/var/log/oom.log")        // → UTF-8 append file
```

**Log format — console / file:**

```
=== JVM OOM Alert ===
  Severity   : CRITICAL
  Process    : 98765@prod-host
  Timestamp  : Wed Sep 17 08:00:00 AEST 2025

-- Heap --
  Used       : 921 MB
  Committed  : 1024 MB
  Max (-Xmx) : 1024 MB
  Usage      : 90.0%

-- Non-Heap (Metaspace / Code Cache) --
  Used       : 128 MB
  Max        : unlimited

-- Memory Pools --
  G1 Eden Space                            : 128 MB
  G1 Old Gen                               : 793 MB

-- Garbage Collection --
  G1 Young Generation                      count=1420   time=6200 ms
  G1 Old Generation                        count=3      time=8100 ms
  Total GC time  : 14300 ms
  JVM uptime     : 60000 ms
  GC overhead    : 23.8%

-- Leak Trend --
  Post-GC heap   : 860 MB
  Growth rate    : 42.30 MB/hour

-- Diagnosis --
  [Assessment] CRITICAL – OOM imminent. ... [Cause] Heap used 92.0% of maximum
  capacity (critical threshold 90.0%). An OutOfMemoryError may be thrown on the
  next large allocation.
======================
```

A single-line structured entry is also appended for machine parsing:

```
2025-09-17T08:00:00.000+1000 severity=CRITICAL process=98765@prod-host heapUsedMB=921 heapMaxMB=1024 heapPct=90.0 nonHeapUsedMB=128 gcOverheadPct=23.8 totalGcTimeMs=14300 postGcGrowth=42.30 MB/h diagnosis="..."
```

---

### IBM QRadar SIEM

`QRadarAlertChannel` sends LEEF 2.0 syslog events over UDP (default) or TCP. Combines with any other channel.

```java
new QRadarAlertChannel("siem.corp.com", 514, Transport.UDP)
new QRadarAlertChannel("siem.corp.com", 6514, Transport.TCP)  // TLS proxy
```

**LEEF 2.0 event format** (single line on the wire; `<TAB>` = literal tab delimiter):

```
<13>Sep 17 08:00:00 prod-host LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|sev=9<TAB>cat=JVM_OOM_Risk<TAB>process=98765@prod-host<TAB>heapUsedMB=921<TAB>heapMaxMB=1024<TAB>heapPct=90.0<TAB>nonHeapUsedMB=128<TAB>gcOverheadPct=23.8<TAB>totalGcTimeMs=14300<TAB>postGcGrowth=42.30 MB/h<TAB>riskLevel=CRITICAL<TAB>msg=...
```

| `sev` value | Risk level |
|------------|------------|
| `1` | OK |
| `5` | WARNING |
| `9` | CRITICAL |
| `10` | OOM_FIRING |

---

### WebSphere Application Server (WAS, traditional)

`WasAlertChannel` relies on WAS's built-in interception of `java.util.logging` (JUL). No configuration is required for log routing — WAS handles it automatically at runtime.

```java
new WasAlertChannel()
```

**Where alerts appear:**

| Log file | JUL level written | When |
|----------|------------------|------|
| `SystemErr.log` | `WARNING` | Risk level = `WARNING` |
| `SystemErr.log` | `SEVERE` | Risk level = `CRITICAL` or `OOM_FIRING` |
| `SystemOut.log` | — | Never (watchdog only writes WARNING/SEVERE) |
| `ffdc/` | — | Correlated via the 8-hex-char incident ID included in every alert |

**Enabling the logger in WAS Admin Console:**

1. Navigate to **Servers → Server Types → WebSphere application servers → `<server>` → Troubleshooting → Logging and Tracing → Change Log Detail Levels**.
2. Add entry: `com.trongus.oom.*=ALL`
3. Click **Apply**. No restart required.

**Recommended poll interval:** 30 s — aligns with WAS PMI 10–60 s sampling cadence.

**JVM (WAS default):** IBM J9 with `gencon` GC policy. Heap typically 1 GB – 4 GB.

---

### WebSphere Liberty / Open Liberty

`LibertyAlertChannel` routes through Liberty's unified JUL pipeline. No server restart or additional JAR is required.

```java
new LibertyAlertChannel()
```

**Where alerts appear:**

| Log file | Condition |
|----------|-----------|
| `messages.log` | Always (INFO and above) — `WARNING` and `SEVERE` records always present |
| `console.log` | When Liberty runs in foreground / `server run` mode |
| `trace.log` | Only if `traceSpecification="com.trongus.oom.*=all"` is active |

**Liberty `server.xml` configuration (recommended):**

```xml
<!-- Standard enhanced format -->
<logging traceSpecification="com.trongus.oom.*=all"
         messageFormat="ENHANCED"
         logDirectory="${server.output.dir}/logs" />

<!-- JSON format for log aggregators (Elastic, Splunk, IBM Log Analysis) -->
<logging messageFormat="JSON"
         jsonFieldMappings="ibm_userDir:userDir,ibm_serverName:serverName"
         traceSpecification="com.trongus.oom.*=all" />
```

When JSON logging is active, `LibertyAlertChannel` embeds a JSON fragment as the JUL message body, making all OOM fields (`oomRiskLevel`, `heapPct`, `gcOverheadPct`, etc.) directly addressable as top-level JSON keys in your log aggregator.

**MicroProfile Health integration:**

```java
@Liveness
@ApplicationScoped
public class OomHealthCheck implements HealthCheck {

    @Inject
    private LibertyAlertChannel oomChannel;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("jvm-oom-risk")
                .status(oomChannel.isHealthy())
                .withData("riskLevel", oomChannel.getLastRiskLevel().name())
                .build();
    }
}
```

Liberty's `/health/live` endpoint reports `DOWN` automatically when the JVM is at `CRITICAL` or `OOM_FIRING`.

**JVM (Liberty default):** IBM J9 / OpenJ9. HotSpot is also fully supported.

---

### IBM Cognos Analytics

Cognos Analytics runs **three separate JVM processes** — each must be monitored independently with its own `OomWatchdog` instance.

| Component | JVM | Recommended `-Xmx` | Primary OOM causes |
|-----------|-----|--------------------|--------------------|
| Application Tier Component (ATC) | IBM J9 | 4 GB – 8 GB | Large report datasets, PDF rendering, session caches |
| Content Manager (CM) | IBM J9 | 2 GB – 4 GB | JDBC result caches, XML metadata trees |
| Gateway / Dispatcher | IBM J9 | 1 GB – 2 GB | HTTP session routing, request buffering |

```java
// One instance per JVM process
new CognosAlertChannel("ATC", "/opt/IBM/cognos/analytics/logs/oom-watchdog-ATC.log")
new CognosAlertChannel("CM",  "/opt/IBM/cognos/analytics/logs/oom-watchdog-CM.log")
new CognosAlertChannel("Gateway", "/opt/IBM/cognos/analytics/logs/oom-watchdog-GW.log")
```

**Where alerts appear:**

| Destination | Format | Consumed by |
|-------------|--------|-------------|
| Dedicated `.log` file (primary) | Pipe-delimited structured log | Cognos Log Server, Cognos Audit DB |
| JUL record (secondary) | Key=value | Liberty/WAS `messages.log` / `SystemErr.log` if hosted on app server |

**Pipe-delimited log format:**

```
2025-09-17T08:00:00.000+1000|ATC|cognos-host|CRITICAL|921|1024|90.0|23.8|12345@cognos-host|[Assessment] CRITICAL – OOM imminent. ...
```

Fields: `timestamp | component | server | riskLevel | heapUsedMB | heapMaxMB | heapPct | gcOverheadPct | process | notes [| heapDumpPath]`

**Cognos Log Server configuration** — add to `cognosservice.xml`:

```xml
<param name="Log.outputFile">/opt/IBM/cognos/analytics/logs/oom-watchdog-ATC.log</param>
```

---

## OOM cause analysis (v1.5.0)

Every alert now includes a plain-language root cause diagnosis produced by `OomCauseAnalyser`:

| `OomCauseCategory` | Signals | Explanation |
|--------------------|---------|-------------|
| `RUNAWAY_GC_WITH_HIGH_HEAP` | heap ≥ critical AND GC overhead ≥ threshold | JVM spending most of its time in GC and cannot reclaim enough memory |
| `HEAP_EXHAUSTION` | heap ≥ critical threshold | Available free heap too small for typical allocations |
| `GC_OVERHEAD_EXCEEDED` | GC overhead ≥ threshold (heap healthy) | JVM wasting too much CPU on GC relative to useful work |
| `MEMORY_LEAK_TREND` | positive post-GC growth slope | Live object retention growing steadily — probable memory leak |
| `NONE` | all metrics OK | No OOM risk detected |

The explanation is embedded in the `[Cause]` section of every diagnosis note and alert message.

---

## Internationalisation (v1.5.0)

All alert text, section headings, and diagnosis strings are locale-aware. Set the locale on `WatchdogConfig`:

```java
WatchdogConfig config = WatchdogConfig.defaults()
    .locale(Locale.JAPANESE)
    .build();
```

Supported locales: `en` (default), `de`, `es`, `fr`, `ja`, `ko`, `pt_BR`, `zh_CN`, `zh_TW`.

Resource bundles are stored as UTF-8 `.properties` files under `com/trongus/oom/i18n/` and are loaded with an explicit UTF-8 reader — double-byte CJK characters are never corrupted.

---

## Alert output

### Console / log file — human-readable

```
=== JVM OOM Alert ===
  Severity   : CRITICAL
  Process    : 98765@prod-host
  Timestamp  : Wed Sep 17 08:00:00 AEST 2025

-- Heap --
  Used       : 921 MB
  Committed  : 1024 MB
  Max (-Xmx) : 1024 MB
  Usage      : 90.0%

-- Non-Heap (Metaspace / Code Cache) --
  Used       : 128 MB
  Max        : unlimited

-- Memory Pools --
  G1 Eden Space                            : 128 MB
  G1 Old Gen                               : 793 MB

-- Garbage Collection --
  G1 Young Generation                      count=1420   time=6200 ms
  G1 Old Generation                        count=3      time=8100 ms
  Total GC time  : 14300 ms
  JVM uptime     : 60000 ms
  GC overhead    : 23.8%

-- Leak Trend --
  Post-GC heap   : 860 MB
  Growth rate    : 42.30 MB/hour

-- Diagnosis --
  [Assessment] CRITICAL – OOM imminent. Heap at 90.0% (threshold 90.0%).
  [Cause] Heap used 92.0% of maximum capacity (critical threshold 90.0%).
  Available free heap is too small to service typical allocation requests.
  An OutOfMemoryError may be thrown on the next large allocation.
======================
```

### Structured single-line (syslog / file)

```
2025-09-17T08:00:00.000+1000 severity=CRITICAL process=98765@prod-host heapUsedMB=921 heapMaxMB=1024 heapPct=90.0 nonHeapUsedMB=128 gcOverheadPct=23.8 totalGcTimeMs=14300 postGcGrowth=42.30 MB/h diagnosis="..."
```

### QRadar — LEEF 2.0 UDP/TCP syslog

```
LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|sev=9<TAB>heapPct=90.0<TAB>gcOverheadPct=23.8<TAB>...
```

---

## Dump artefacts

| Type | Mechanism (priority order) | Extension |
|------|---------------------------|-----------|
| `HEAP` | HotSpot `HotSpotDiagnosticMXBean` → IBM J9 `com.ibm.jvm.Dump` → GraalVM VMRuntime / pool summary | `.hprof` |
| `THREAD` | `ThreadMXBean` (universal — all JVMs) | `_threads.txt` |
| `CLASS_HISTOGRAM` | `DiagnosticCommand` MBean → J9 `JavaDump` → pool table | `_histogram.txt` |
| `CORE` | J9 `SystemDump` → `gcore` (Linux/macOS) | `.core` |

---

## Supported JVMs

| JVM | Heap dump | Thread dump | Class histogram | Core dump |
|-----|-----------|-------------|-----------------|-----------|
| HotSpot (Oracle, OpenJDK, Azul, Corretto) | ✅ | ✅ | ✅ | ✅ gcore |
| Eclipse OpenJ9 / IBM J9 | ✅ | ✅ | ✅ | ✅ SystemDump |
| GraalVM JVM | ✅ | ✅ | ✅ | ✅ gcore |
| GraalVM Native Image | ✅ VMRuntime | ✅ | ✅ pool table | ✅ gcore |

---

## Building from source

### Prerequisites

- JDK 8 or later (compiled with `--release 8` for broad compatibility)
- Apache Maven 3.6+

### Build

```bash
cd oom-watchdog
mvn clean package -q          # produces core/target/oom-watchdog.jar
                               #          test-harness/target/test-harness.jar
```

### Run tests

```bash
mvn test -pl oom-watchdog-tests
# Tests run: 189, Failures: 0, Errors: 0, Skipped: 0
```

### Generate self-extracting installer

```bash
chmod +x make-installer.sh
./make-installer.sh
# → oom-watchdog-installer.sh
```

---

## Project layout

```
oom-watchdog/
├── pom.xml                          Parent POM (modules: core, test-harness, oom-watchdog-tests)
├── ARCHITECTURE.md                  Architecture with Mermaid diagrams
├── README.md                        This file
├── make-installer.sh                Generates oom-watchdog-installer.sh
├── core/                            → oom-watchdog.jar (fat jar)
│   └── src/main/java/com/trongus/oom/
│       ├── WatchdogMain.java        CLI entry point
│       ├── alert/                   AlertChannel + 6 impls + AlertFormatter (i18n)
│       ├── collector/               MxBeanDiagnosticsCollector
│       ├── config/                  WatchdogConfig (immutable builder, locale)
│       ├── diagnosis/               OomCause + OomCauseCategory + OomCauseAnalyser (NEW)
│       ├── dump/                    CompositeDumpService + 6 strategies
│       ├── i18n/                    Messages (UTF-8 ResourceBundle wrapper) (NEW)
│       ├── model/                   JvmSnapshot + OomRiskLevel
│       ├── monitor/                 OomWatchdog + ThresholdRiskAssessor
│       ├── platform/                JvmPlatform (static detection)
│       └── test/                    OomSimulator
│   └── src/main/resources/com/trongus/oom/i18n/
│       ├── Messages.properties      English (base / fallback)
│       ├── Messages_de.properties   German
│       ├── Messages_es.properties   Spanish
│       ├── Messages_fr.properties   French
│       ├── Messages_ja.properties   Japanese (UTF-8, double-byte)
│       ├── Messages_ko.properties   Korean (UTF-8, double-byte)
│       ├── Messages_pt_BR.properties Brazilian Portuguese
│       ├── Messages_zh_CN.properties Simplified Chinese (UTF-8, double-byte)
│       └── Messages_zh_TW.properties Traditional Chinese (UTF-8, double-byte)
├── test-harness/                    → test-harness.jar
│   └── src/main/java/com/trongus/oom/harness/
│       ├── TestHarnessMain.java
│       ├── DynamicOomClassGenerator.java
│       ├── BuiltInHeapExhauster.java
│       └── HarnessAlertRecorder.java
└── oom-watchdog-tests/              JUnit 4 test suite (189 tests)
```

---

## How it detects leaks early

The collector keeps a rolling window of **post-GC heap used** samples (configurable via
`--leak-window`).  After each GC event, it records the heap size.  Once the window is full
it fits an **Ordinary Least Squares** regression line through the `(timestamp, heapUsed)`
points.  A positive slope means the surviving object set is growing — a classic memory leak
signature — even when the instantaneous heap percentage is still well below the threshold.

```
post-GC heap used
       │          ∙
  860M │       ∙
  820M │    ∙
  780M │ ∙
       └──────────────▶ time
         slope = +42 MB/hour → WARNING (leak trend)
```

---

## Security

Four security audit passes have been performed against the full source tree.
See [`ARCHITECTURE.md`](ARCHITECTURE.md#security-hardening-summary) for the complete
per-issue table.  Summary of all hardening applied:

| Audit | Area | Hardening applied |
|-------|------|-------------------|
| 1–2 | Config validation | `WatchdogConfig.build()` rejects all out-of-range values (thresholds, port, poll interval, window size, dump directory) |
| 1–2 | QRadar TCP | 5-second connect + read timeout prevents poll-thread blocking on unreachable hosts |
| 1–2 | QRadar UDP | Payload capped at 65 007 bytes to prevent silent datagram truncation |
| 1–2 | `gcore` execution | Output path canonicalized (`getCanonicalPath()`); 60-second timeout + `destroyForcibly()`; output capped at 4 096 bytes |
| 1–2 | Alert formatting | `AlertFormatter.sanitiseMultiLine()` / `sanitiseSingleLine()` strip control characters and quote injection |
| 1–2 | Snapshot integrity | `JvmSnapshot.Builder` map setters take defensive `LinkedHashMap` copies |
| 1–2 | Charset safety | All file writes use explicit `StandardCharsets.UTF_8`; resource bundles loaded with explicit UTF-8 reader |
| 3 | Thread safety (counters) | `HarnessAlertRecorder` alert counters use `AtomicInteger.incrementAndGet()` |
| 4 | Thread safety (dump guard) | `OomWatchdog.dumpTakenThisEpisode` replaced with `AtomicBoolean.compareAndSet(false, true)` — eliminates check-then-act race (SEC-2) |
| 4 | Thread safety (risk level) | `OomWatchdog.lastLevel` replaced with `AtomicReference<OomRiskLevel>` for consistent cross-thread visibility (SEC-3) |
| 4 | Thread safety (dump paths) | `HarnessAlertRecorder.dumpPaths` replaced with `CopyOnWriteArrayList` — eliminates unsynchronised read race (SEC-4) |

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## Author

trongus OOM Watchdog project — `com.trongus.oom`
