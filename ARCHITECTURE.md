# OOM Watchdog – Architecture

## Overview

OOM Watchdog is a zero-dependency, production-quality JVM memory watchdog library that
preemptively detects Out-Of-Memory conditions and fires structured alerts before the JVM
crashes.  It supports **all major JVM vendors** (HotSpot, IBM J9/OpenJ9, GraalVM JVM,
GraalVM Native Image) and **JDK 8 through 26+**.

The design follows the five SOLID principles throughout: every class has one reason to
change, new behaviour is added by extension rather than modification, subtypes are fully
substitutable, interfaces are narrow, and all high-level modules depend on abstractions.

---

## Component Diagram

```mermaid
graph TD
    CLI["WatchdogMain (CLI)"] -->|builds| W["OomWatchdog (Orchestrator)"]
    W --> C["JvmDiagnosticsCollector"]
    W --> A["RiskAssessor"]
    W --> CH["AlertChannel(s)"]
    W --> D["HeapDumpService"]

    C -->|impl| MX["MxBeanDiagnosticsCollector"]
    A -->|impl| TR["ThresholdRiskAssessor"]
    CH -->|impl| CON["ConsoleAlertChannel"]
    CH -->|impl| FILE["FileLogAlertChannel"]
    CH -->|impl| QR["QRadarAlertChannel (LEEF 2.0)"]
    D -->|impl| CDS["CompositeDumpService"]

    CDS --> S1["HotSpotHeapDumpStrategy"]
    CDS --> S2["J9HeapDumpStrategy"]
    CDS --> S3["GraalNativeHeapDumpStrategy"]
    CDS --> S4["ThreadDumpStrategy"]
    CDS --> S5["ClassHistogramStrategy"]
    CDS --> S6["CoreDumpStrategy"]

    MX -->|reads| JP["JvmPlatform (static detection)"]
    MX -->|produces| SNAP["JvmSnapshot (immutable value object)"]
    TR -->|consumes| SNAP
    TR -->|produces| SNAP2["JvmSnapshot (with OomRiskLevel)"]
    CH -->|consumes| SNAP2
    D -->|consumes| SNAP2

    subgraph model
        SNAP
        ORL["OomRiskLevel (OK/WARNING/CRITICAL/OOM_FIRING)"]
    end

    subgraph config
        WC["WatchdogConfig (immutable, builder)"]
    end

    W --- WC
```

---

## Class Diagram

```mermaid
classDiagram
    class OomWatchdog {
        -WatchdogConfig config
        -JvmDiagnosticsCollector collector
        -RiskAssessor assessor
        -List~AlertChannel~ alertChannels
        -HeapDumpService dumpService
        -ScheduledExecutorService scheduler
        +start()
        +stop()
        +getLastRiskLevel() OomRiskLevel
        -poll()
    }

    class JvmDiagnosticsCollector {
        <<interface>>
        +collect() JvmSnapshot
    }

    class RiskAssessor {
        <<interface>>
        +assess(JvmSnapshot) JvmSnapshot
    }

    class AlertChannel {
        <<interface>>
        +alert(JvmSnapshot)
        +channelName() String
    }

    class HeapDumpService {
        <<interface>>
        +dump(JvmSnapshot, List~DumpType~) List~String~
    }

    class MxBeanDiagnosticsCollector {
        -WatchdogConfig config
        -Deque~long[]~ postGcWindow
        +collect() JvmSnapshot
        -computeSlope(List~long[]~) double
    }

    class ThresholdRiskAssessor {
        -WatchdogConfig config
        +assess(JvmSnapshot) JvmSnapshot
    }

    class ConsoleAlertChannel
    class FileLogAlertChannel {
        -Path logPath
    }
    class QRadarAlertChannel {
        -String host
        -int port
        -String protocol
    }

    class AlertFormatter {
        <<package-private>>
        +toHumanReadable(JvmSnapshot) String$
        +toSingleLine(JvmSnapshot) String$
    }

    class CompositeDumpService {
        -WatchdogConfig config
        -Map~DumpType, List~DumpStrategy~~ chains
        +dump(JvmSnapshot, List~DumpType~) List~String~
        -buildChains() Map$
        -buildPath(JvmSnapshot, DumpType) String
    }

    class DumpStrategy {
        <<interface>>
        +attempt(JvmSnapshot, String) String
        +name() String
    }

    class JvmSnapshot {
        +processName String
        +timestampMs long
        +heapUsedBytes long
        +heapMaxBytes long
        +heapUsedRatio double
        +gcOverheadRatio double
        +postGcHeapGrowthRatePerMs double
        +riskLevel OomRiskLevel
        +withHeapDumpPath(String) JvmSnapshot
    }

    class OomRiskLevel {
        <<enumeration>>
        OK
        WARNING
        CRITICAL
        OOM_FIRING
    }

    class WatchdogConfig {
        +warningHeapThreshold double
        +criticalHeapThreshold double
        +gcOverheadThreshold double
        +pollIntervalMs long
        +heapDumpDirectory String
        +dumpTypes Set~DumpType~
    }

    class JvmPlatform {
        +JDK_VERSION int$
        +IS_GRAAL_NATIVE bool$
        +IS_J9 bool$
        +IS_HOTSPOT bool$
        +PID long$
        +summary() String$
    }

    OomWatchdog --> JvmDiagnosticsCollector
    OomWatchdog --> RiskAssessor
    OomWatchdog --> AlertChannel
    OomWatchdog --> HeapDumpService
    OomWatchdog --> WatchdogConfig

    MxBeanDiagnosticsCollector ..|> JvmDiagnosticsCollector
    ThresholdRiskAssessor ..|> RiskAssessor
    ConsoleAlertChannel ..|> AlertChannel
    FileLogAlertChannel ..|> AlertChannel
    QRadarAlertChannel ..|> AlertChannel
    CompositeDumpService ..|> HeapDumpService

    FileLogAlertChannel --> AlertFormatter
    ConsoleAlertChannel --> AlertFormatter
    CompositeDumpService --> DumpStrategy
    MxBeanDiagnosticsCollector --> JvmSnapshot
    ThresholdRiskAssessor --> JvmSnapshot
    JvmSnapshot --> OomRiskLevel
```

---

## Poll Cycle Sequence Diagram

```mermaid
sequenceDiagram
    participant Scheduler as ScheduledExecutor
    participant WD as OomWatchdog
    participant COL as MxBeanDiagnosticsCollector
    participant ASS as ThresholdRiskAssessor
    participant DUMP as CompositeDumpService
    participant CH as AlertChannel(s)

    Scheduler->>WD: poll() [every pollIntervalMs]
    WD->>COL: collect()
    COL-->>WD: JvmSnapshot(riskLevel=OK)
    WD->>ASS: assess(snapshot)
    ASS-->>WD: JvmSnapshot(riskLevel=CRITICAL)

    alt riskLevel >= WARNING
        alt riskLevel >= CRITICAL and not dumpTaken
            WD->>DUMP: dump(snapshot, dumpTypes)
            DUMP-->>WD: ["/dumps/oom_heap_…hprof"]
            WD->>WD: snapshot.withHeapDumpPath(paths)
        end
        loop for each AlertChannel
            WD->>CH: alert(enrichedSnapshot)
        end
    end

    WD->>WD: lastLevel = riskLevel
```

---

## Dump Strategy Chain (Decision Flow)

```mermaid
flowchart TD
    START([dump request]) --> TYPE{DumpType?}

    TYPE -->|HEAP| H1[HotSpotHeapDumpStrategy\nHotSpotDiagnosticMXBean]
    H1 -->|success| DONE([path returned])
    H1 -->|fail| H2[J9HeapDumpStrategy\nOpenJ9 com.ibm.jvm.Dump]
    H2 -->|success| DONE
    H2 -->|fail| H3[GraalNativeHeapDumpStrategy\nVMRuntime / pool summary]
    H3 --> DONE

    TYPE -->|THREAD| T1[ThreadDumpStrategy\nThreadMXBean universal]
    T1 --> DONE

    TYPE -->|CLASS_HISTOGRAM| C1[ClassHistogramStrategy\nDiagnosticCommand → J9 JavaDump → pool table]
    C1 --> DONE

    TYPE -->|CORE| R1[CoreDumpStrategy\nJ9 SystemDump → gcore]
    R1 --> DONE
```

---

## Module Structure

```
oom-watchdog/                  Maven multi-module root
├── core/                      oom-watchdog.jar  (fat jar via maven-shade-plugin)
│   └── src/main/java/com/trongus/oom/
│       ├── WatchdogMain.java  CLI entry point
│       ├── alert/             AlertChannel ISP + 3 implementations + AlertFormatter
│       ├── collector/         JvmDiagnosticsCollector + MxBeanDiagnosticsCollector
│       ├── config/            WatchdogConfig (immutable, builder)
│       ├── dump/              HeapDumpService + CompositeDumpService + DumpType + strategies
│       ├── model/             JvmSnapshot + OomRiskLevel
│       ├── monitor/           OomWatchdog + RiskAssessor + ThresholdRiskAssessor
│       ├── platform/          JvmPlatform (static detection)
│       └── test/              OomSimulator (3-phase heap exhaustion)
│
├── test-harness/              test-harness.jar
│   └── src/main/java/com/trongus/oom/harness/
│       ├── TestHarnessMain.java
│       ├── DynamicOomClassGenerator.java  (javax.tools at runtime)
│       ├── BuiltInHeapExhauster.java
│       └── HarnessAlertRecorder.java
│
└── oom-watchdog-tests/        JUnit 4 test suite (179 tests)
    └── src/test/java/com/trongus/oom/tests/
        ├── model/             OomRiskLevelTest, JvmSnapshotTest
        ├── config/            WatchdogConfigTest
        ├── dump/              DumpTypeTest, CompositeDumpServiceTest
        ├── alert/             AlertFormatterTest, FileLogAlertChannelTest
        ├── collector/         MxBeanDiagnosticsCollectorTest
        ├── monitor/           ThresholdRiskAssessorTest
        ├── platform/          JvmPlatformTest
        └── integration/       OomWatchdogIntegrationTest
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pure `java.lang.management` MXBeans for collection | No native agent needed; works on all JVMs from JDK 8+ |
| Strategy pattern for dump dispatch | Adding a new vendor dump requires only a new `DumpStrategy` class |
| OLS slope on post-GC heap samples | Detects slow leaks that thresholds alone miss |
| Episode deduplication in `OomWatchdog` | Prevents dump storms during a sustained critical episode |
| Immutable `JvmSnapshot` with `withHeapDumpPath()` copy | Thread-safe, trivially testable, no shared mutable state |
| `AlertFormatter` package-private | Formatting is an implementation detail; only alert channels need it |
| `WatchdogConfig` immutable builder | Config cannot drift at runtime; safe to pass across threads |
| LEEF 2.0 for QRadar | Native IBM SIEM format; field-indexed for high-speed correlation |
