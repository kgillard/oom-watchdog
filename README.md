# OOM Watchdog

> **Preemptively detect and alert on JVM Out-of-Memory conditions — before the process crashes.**

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Tests](https://img.shields.io/badge/tests-179%20passing-brightgreen)]()
[![JDK](https://img.shields.io/badge/JDK-8%20%E2%80%93%2026%2B-blue)]()
[![Vendors](https://img.shields.io/badge/JVM-HotSpot%20%7C%20OpenJ9%20%7C%20GraalVM-blue)]()

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

---

## Quick start

### 1 — Add to your project (jar on classpath)

```bash
java -cp oom-watchdog.jar:your-app.jar com.trongus.oom.WatchdogMain \
    --heap-warning  0.80          \
    --heap-critical 0.90          \
    --gc-overhead   0.50          \
    --poll-ms       5000          \
    --dump-dir      /var/dumps    \
    --dump-types    HEAP,THREAD   \
    --log-file      /var/log/oom-watchdog.log
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
    .build();

OomWatchdog watchdog = new OomWatchdog(
    config,
    new MxBeanDiagnosticsCollector(config),
    new ThresholdRiskAssessor(config),
    Arrays.asList(
        new ConsoleAlertChannel(),
        new FileLogAlertChannel("/var/log/oom.log"),
        new QRadarAlertChannel("siem.corp.com", 514, "UDP")
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
| `--heap-warning <0.0–1.0>` | `0.80` | Heap usage fraction that triggers WARNING |
| `--heap-critical <0.0–1.0>` | `0.90` | Heap usage fraction that triggers CRITICAL |
| `--gc-overhead <0.0–1.0>` | `0.50` | GC CPU fraction that triggers WARNING |
| `--poll-ms <ms>` | `5000` | Poll interval in milliseconds |
| `--dump-dir <path>` | `./dumps` | Output directory for dump artefacts |
| `--dump-types <list>` | _(none)_ | Comma-separated: `HEAP,THREAD,CLASS_HISTOGRAM,CORE` |
| `--log-file <path>` | _(none)_ | Append structured alerts to this file |
| `--qradar-host <host>` | `localhost` | QRadar / syslog target hostname |
| `--qradar-port <port>` | `514` | QRadar / syslog target port |
| `--qradar-proto <UDP\|TCP>` | `UDP` | Transport for QRadar LEEF 2.0 |
| `--leak-window <n>` | `5` | Rolling window size for OLS leak-trend slope |
| `--test-mode` | _(off)_ | Run OomSimulator and exit |
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
| `CRITICAL` | Heap > critical threshold **or** GC overhead very high |
| `OOM_FIRING` | `java.lang.OutOfMemoryError` imminent (reserved for future hooks) |

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
  [GC] Total GC time: 14300 ms | Overhead: 23.8%. [Heap] Used 90.0% of max.
  [Leak] Post-GC heap growing at 42.30 MB/hour – possible memory leak.
=====================
```

### Structured single-line (syslog / file)

```
2025-09-17T08:00:00.000+1000 severity=CRITICAL process=98765@prod-host heapUsedMB=921 heapMaxMB=1024 heapPct=90.0 nonHeapUsedMB=128 gcOverheadPct=23.8 totalGcTimeMs=14300 postGcGrowth=42.30 MB/h diagnosis="..."
```

### QRadar — LEEF 2.0 UDP/TCP syslog

```
LEEF:2.0|Trongus|OomWatchdog|1.0|OOM_ALERT|devTime=... sev=9 src=prod-host ... heapPct=90.0 gcOverheadPct=23.8 ...
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
# Tests run: 179, Failures: 0, Errors: 0, Skipped: 0
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
│       ├── alert/                   AlertChannel + 3 impls + AlertFormatter
│       ├── collector/               MxBeanDiagnosticsCollector
│       ├── config/                  WatchdogConfig (immutable builder)
│       ├── dump/                    CompositeDumpService + 6 strategies
│       ├── model/                   JvmSnapshot + OomRiskLevel
│       ├── monitor/                 OomWatchdog + ThresholdRiskAssessor
│       ├── platform/                JvmPlatform (static detection)
│       └── test/                    OomSimulator
├── test-harness/                    → test-harness.jar
│   └── src/main/java/com/trongus/oom/harness/
│       ├── TestHarnessMain.java
│       ├── DynamicOomClassGenerator.java
│       ├── BuiltInHeapExhauster.java
│       └── HarnessAlertRecorder.java
└── oom-watchdog-tests/              JUnit 4 + Mockito 4 test suite (179 tests)
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

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## Author

Trongus OOM Watchdog project — `com.trongus.oom`
