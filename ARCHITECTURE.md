# OOM Watchdog – Architecture

## Overview

OOM Watchdog is a zero-dependency, production-quality JVM memory watchdog library that
preemptively detects Out-Of-Memory conditions and fires structured alerts before the JVM
crashes.  It supports **all major JVM vendors** (HotSpot, IBM J9/OpenJ9, GraalVM JVM,
GraalVM Native Image) and **JDK 8 through 26+**.

Key architecture features include:
- In-process and remote JMX multi-target JVM health monitoring.
- Real-time risk assessment via heap ratios, GC overhead, and OLS regression leak slope analysis.
- Structured single-line and detailed human-readable logging named after the target JVM.
- Multiple alert channels (Console, File, QRadar LEEF 2.0, WAS SystemErr, Liberty messages.log, Cognos).
- Non-blocking, rate-limited dump capture (heap, thread, histogram, core dumps).
- Built-in root cause analysis and internationalisation across 9 locales.

The design follows the five SOLID principles throughout: every class has one reason to
change, new behaviour is added by extension rather than modification, subtypes are fully
substitutable, interfaces are narrow, and all high-level modules depend on abstractions.

---

## Component Diagram

```mermaid
graph TD
    CLI["WatchdogMain (CLI)"] -->|builds| W["OomWatchdog (Orchestrator)"]
    CLI -->|or builds| DAE["WatchdogDaemon (Multi-target)"]
    DAE -->|loads| REG["TargetRegistry"]
    REG -->|parses| TD["TargetDescriptor"]
    DAE -->|manages N x| W
    W --> C["JvmDiagnosticsCollector"]
    W --> A["RiskAssessor"]
    W --> CH["AlertChannel(s)"]
    W --> D["HeapDumpService"]
    W --> WC["WatchdogConfig"]

    C -->|impl local| MX["MxBeanDiagnosticsCollector"]
    C -->|impl remote JMX| JMX["JmxDiagnosticsCollector"]
    A -->|impl| TR["ThresholdRiskAssessor"]
    CH -->|impl| CON["ConsoleAlertChannel"]
    CH -->|impl| FILE["FileLogAlertChannel"]
    CH -->|impl| QR["QRadarAlertChannel (LEEF 2.0)"]
    CH -->|impl| WAS["WasAlertChannel (JUL → SystemErr.log)"]
    CH -->|impl| LIB["LibertyAlertChannel (JUL → messages.log)"]
    CH -->|impl| COG["CognosAlertChannel (pipe-delimited log)"]
    D -->|impl| CDS["CompositeDumpService"]

    CDS --> S1["HotSpotHeapDumpStrategy"]
    CDS --> S2["J9HeapDumpStrategy"]
    CDS --> S3["GraalNativeHeapDumpStrategy"]
    CDS --> S4["ThreadDumpStrategy"]
    CDS --> S5["ClassHistogramStrategy"]
    CDS --> S6["CoreDumpStrategy"]

    MX -->|reads| JP["JvmPlatform (static detection)"]
    CDS -->|reads| JP
    MX -->|produces| SNAP["JvmSnapshot (immutable value object)"]
    TR -->|consumes/produces| SNAP

    TR -->|uses| CA["OomCauseAnalyser"]
    TR -->|uses| MSG["Messages (i18n)"]
    MX -->|uses| MSG
    CA -->|produces| OC["OomCause + OomCauseCategory"]

    subgraph model
        SNAP
        ORL["OomRiskLevel (OK/WARNING/CRITICAL/OOM_FIRING)"]
    end

    subgraph i18n
        MSG
        OC
    end

    subgraph config
        WC
    end
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
        -ScheduledFuture task
        -AtomicBoolean dumpTakenThisEpisode
        -AtomicReference~OomRiskLevel~ lastLevel
        -AtomicReference~JvmSnapshot~ lastSnapshot
        -GcHistoryStore gcHistoryStore
        -String gcHistoryTarget
        +OomWatchdog(WatchdogConfig, JvmDiagnosticsCollector, RiskAssessor, List, HeapDumpService)
        +start()
        +stop()
        +getLastRiskLevel() OomRiskLevel
        +getLastSnapshot() JvmSnapshot
        +setGcHistoryStore(GcHistoryStore, String)
        -poll()
        -checkDumpThresholds(JvmSnapshot)
    }

    class GcHistoryStore {
        -Path baseDir
        -int maxLines
        -ConcurrentHashMap~String,Object~ locks
        +GcHistoryStore()
        +GcHistoryStore(Path, int)
        +record(String, JvmSnapshot)
        +readHistory(String, int) String
        +availableTargets() List~String~
        +buildLine(JvmSnapshot) String
        +slugify(String) String
    }

    class MetricsHttpServer {
        -OomWatchdog selfWatchdog
        -Map~String,OomWatchdog~ remoteWatchdogs
        -Map~String,JmxDiagnosticsCollector~ remoteCollectors
        -Map~String,String~ remoteDumpApiUrls
        -GcHistoryStore gcHistory
        -int port
        -boolean bindAll
        -TlsConfig tlsConfig
        -AtomicReference~SSLContext~ sslContextRef
        +MetricsHttpServer(OomWatchdog, int, boolean, TlsConfig)
        +MetricsHttpServer(OomWatchdog, Map, Map, Map, int, boolean, TlsConfig)
        +start()
        +stop()
        +recordSnapshot(String, JvmSnapshot)
        +resolveBindAddress(boolean, int) InetSocketAddress
        -buildSslContext() SSLContext
        -generateSelfSignedKeystore(char[]) KeyStore
        -buildLiveJson(JvmSnapshot, String) String
        -buildSnapshotJsonWithDumpApi(JvmSnapshot, String, String) String
    }

    class TlsConfig {
        <<immutable>>
        -Mode mode
        -int selfSignedValidDays
        -String keystorePath
        -char[] keystorePassword
        +disabled() TlsConfig
        +selfSigned() TlsConfig
        +selfSigned(int) TlsConfig
        +fromKeystore(String, char[]) TlsConfig
        +getMode() Mode
        +isKeystoreReadable() boolean
    }

    class Mode {
        <<enumeration>>
        DISABLED
        SELF_SIGNED
        KEYSTORE
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
        -Messages messages
        -Deque~long[]~ postGcWindow
        -long prevTotalGcTime
        +MxBeanDiagnosticsCollector(WatchdogConfig)
        +collect() JvmSnapshot
        -computeSlope(Deque~long[]~) double
    }

    class ThresholdRiskAssessor {
        -WatchdogConfig config
        -Messages messages
        -OomCauseAnalyser causeAnalyser
        +ThresholdRiskAssessor(WatchdogConfig)
        +assess(JvmSnapshot) JvmSnapshot
    }

    class ConsoleAlertChannel {
        +alert(JvmSnapshot)
        +channelName() String
    }

    class FileLogAlertChannel {
        -Path logPath
        +FileLogAlertChannel(String)
        +alert(JvmSnapshot)
    }

    class QRadarAlertChannel {
        -String qradarHost
        -int qradarPort
        -Transport transport
        -String localHostname
        -boolean localDestination
        -String effectiveTcpHost
        +QRadarAlertChannel(String, int, Transport)
        +alert(JvmSnapshot)
        +channelName() String
        -sendUdp(byte[]) void
        -sendTcp(byte[], String) void
        -resolveNonLoopbackAddress(boolean) InetAddress
        -isLocalAddress(String) boolean
    }

    class Transport {
        <<enumeration>>
        UDP
        TCP
    }

    class WasAlertChannel {
        +alert(JvmSnapshot)
        +channelName() String
    }

    class LibertyAlertChannel {
        -AtomicReference~OomRiskLevel~ lastRiskLevel
        +alert(JvmSnapshot)
        +isHealthy() boolean
        +getLastRiskLevel() OomRiskLevel
    }

    class CognosAlertChannel {
        -String cognosComponent
        -String cognosServer
        -Path logPath
        +CognosAlertChannel(String, String)
        +alert(JvmSnapshot)
    }

    class AlertFormatter {
        <<package-private>>
        +toHumanReadable(JvmSnapshot) String
        +toHumanReadable(JvmSnapshot, Messages) String
        +toSingleLine(JvmSnapshot) String
        -sanitiseMultiLine(String) String
        -sanitiseSingleLine(String) String
    }

    class CompositeDumpService {
        -WatchdogConfig config
        -Map~DumpType, List~DumpStrategy~~ chains
        +CompositeDumpService(WatchdogConfig)
        +dump(JvmSnapshot, List~DumpType~) List~String~
        -buildChains() Map
        -buildPath(JvmSnapshot, DumpType) String
    }

    class DumpStrategy {
        <<interface>>
        +type() DumpType
        +attempt(JvmSnapshot, String) String
        +name() String
    }

    class JmxDiagnosticsCollector {
        -TargetDescriptor descriptor
        -WatchdogConfig config
        -Deque~long[]~ postGcWindow
        -JMXConnector connector
        -MBeanServerConnection mbsc
        +JmxDiagnosticsCollector(TargetDescriptor, WatchdogConfig)
        +collect() JvmSnapshot
        +close()
        -ensureConnected() boolean
        -buildUnreachableSnapshot(long, String) JvmSnapshot
    }

    class TargetDescriptor {
        +String name
        +String jmxUrl
        +String username
        +double warnThreshold
        +double critThreshold
        +double gcThreshold
        +long pollIntervalMs
        +Set~DumpType~ dumpTypes
        +String dumpDirectory
        +String leefCategory
        +String leefTags
        +double gcDumpThreshold
        +double heapDumpThreshold
        +double nurseryDumpThreshold
        +builder(String, String) Builder$
    }

    class TargetRegistry {
        +loadFromFile(String) List~TargetDescriptor~$
        +loadFromString(String) List~TargetDescriptor~$
    }

    class WatchdogDaemon {
        -List~TargetDescriptor~ targets
        -WatchdogConfig baseConfig
        -List~AlertChannel~ sharedAlertChannels
        -Map~String,OomWatchdog~ activeWatchdogs
        +WatchdogDaemon(List, WatchdogConfig, List)
        +start()
        +stop()
        +isRunning() boolean
        +getActiveWatchdogCount() int
        +close()
    }

    class JvmSnapshot {
        +String targetName
        +String processName
        +long timestampMs
        +long heapUsedBytes
        +long heapCommittedBytes
        +long heapMaxBytes
        +double heapUsedRatio
        +long nurseryUsedBytes
        +double nurseryUsedRatio
        +long nonHeapUsedBytes
        +long nonHeapMaxBytes
        +Map~String,Long~ poolUsedBytes
        +Map~String,Long~ gcCollectionCounts
        +Map~String,Long~ gcCollectionTimesMs
        +long totalGcTimeMs
        +long jvmUptimeMs
        +double gcOverheadRatio
        +long postGcHeapUsedBytes
        +double postGcHeapGrowthRatePerMs
        +OomRiskLevel riskLevel
        +String diagnosisNotes
        +String heapDumpPath
        +String leefCategory
        +String leefTags
        +double critThreshold
        +withHeapDumpPath(String) JvmSnapshot
        +toBuilder() Builder
    }

    class OomRiskLevel {
        <<enumeration>>
        OK
        WARNING
        CRITICAL
        OOM_FIRING
    }

    class WatchdogConfig {
        +double warningHeapThreshold
        +double criticalHeapThreshold
        +double gcOverheadThreshold
        +int leakDetectionWindowSize
        +long pollIntervalMs
        +String heapDumpDirectory
        +Set~DumpType~ dumpTypes
        +String qradarHost
        +int qradarPort
        +Locale locale
        +Level logLevel
        +double gcDumpThreshold
        +double heapDumpThreshold
        +double nurseryDumpThreshold
        +defaults() Builder
        +getLocale() Locale
        +getLogLevel() Level
        +getGcDumpThreshold() double
        +getHeapDumpThreshold() double
        +getNurseryDumpThreshold() double
    }

    class JvmPlatform {
        +int JDK_VERSION$
        +boolean IS_GRAAL_NATIVE$
        +boolean IS_GRAAL_JVM$
        +boolean IS_J9$
        +boolean IS_HOTSPOT$
        +long PID$
        +summary() String$
    }

    class OomCauseAnalyser {
        -double criticalHeapThreshold
        -double gcOverheadThreshold
        -Messages messages
        +OomCauseAnalyser(double, double, Messages)
        +analyse(double, double, double) OomCause
    }

    class OomCause {
        -OomCauseCategory category
        -String explanation
        +getCategory() OomCauseCategory
        +getExplanation() String
    }

    class OomCauseCategory {
        <<enumeration>>
        NONE
        MEMORY_LEAK_TREND
        GC_OVERHEAD_EXCEEDED
        HEAP_EXHAUSTION
        RUNAWAY_GC_WITH_HIGH_HEAP
    }

    class Messages {
        -ResourceBundle bundle
        -Locale locale
        +Messages(Locale)
        +get(String) String
        +format(String, Object...) String
    }

    class WatchdogLogger {
        <<static utility>>
        +ROOT_LOGGER_NAME$ String
        +forClass(Class) Logger$
        +initialise(Level)$
        +initialise(Level, String)$
        +initialise(Level, String, boolean)$
        +config(Logger, String, Object...)$
        +info(Logger, String, Object...)$
        +warning(Logger, String, Object...)$
        +warning(Logger, Throwable, String, Object...)$
        +severe(Logger, String, Object...)$
        +severe(Logger, Throwable, String, Object...)$
        +fine(Logger, String, Object...)$
        +finest(Logger, String, Object...)$
    }

    class WatchdogLogFormatter {
        <<package-private>>
        +format(LogRecord) String
        -abbreviateLevel(Level) String
        -shortClassName(String) String
        -padRight(String, int) String
        -stackTraceOf(Throwable, String) String
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
    QRadarAlertChannel --> Transport
    WasAlertChannel ..|> AlertChannel
    LibertyAlertChannel ..|> AlertChannel
    CognosAlertChannel ..|> AlertChannel
    CompositeDumpService ..|> HeapDumpService
    JmxDiagnosticsCollector ..|> JvmDiagnosticsCollector
    JmxDiagnosticsCollector --> TargetDescriptor
    TargetRegistry --> TargetDescriptor
    WatchdogDaemon --> TargetDescriptor
    WatchdogDaemon --> OomWatchdog
    WatchdogDaemon --> JmxDiagnosticsCollector

    FileLogAlertChannel --> AlertFormatter
    ConsoleAlertChannel --> AlertFormatter
    CompositeDumpService --> DumpStrategy
    CompositeDumpService --> JvmPlatform
    MxBeanDiagnosticsCollector --> JvmSnapshot
    MxBeanDiagnosticsCollector --> JvmPlatform
    MxBeanDiagnosticsCollector --> Messages
    ThresholdRiskAssessor --> JvmSnapshot
    ThresholdRiskAssessor --> Messages
    ThresholdRiskAssessor --> OomCauseAnalyser
    OomCauseAnalyser --> OomCause
    OomCause --> OomCauseCategory
    JvmSnapshot --> OomRiskLevel
    MetricsHttpServer --> OomWatchdog
    MetricsHttpServer --> TlsConfig
    MetricsHttpServer --> GcHistoryStore
    MetricsHttpServer --> JmxDiagnosticsCollector
    OomWatchdog --> GcHistoryStore
    TlsConfig --> Mode
    WatchdogDaemon --> MetricsHttpServer

    WatchdogLogger --> WatchdogLogFormatter : installs on ConsoleHandler / FileHandler
    OomWatchdog ..> WatchdogLogger : logs via
    WatchdogDaemon ..> WatchdogLogger : logs via
    JmxDiagnosticsCollector ..> WatchdogLogger : logs via
    QRadarAlertChannel ..> WatchdogLogger : logs via
    MetricsHttpServer ..> WatchdogLogger : logs via
    CompositeDumpService ..> WatchdogLogger : logs via
    ThresholdRiskAssessor ..> WatchdogLogger : logs via
    MxBeanDiagnosticsCollector ..> WatchdogLogger : logs via
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
    participant GCH as GcHistoryStore

    Scheduler->>WD: poll() [every pollIntervalMs]
    WD->>COL: collect()
    COL-->>WD: JvmSnapshot(riskLevel=OK)
    WD->>ASS: assess(snapshot)
    ASS-->>WD: JvmSnapshot(riskLevel=CRITICAL, critThreshold stamped)

    alt riskLevel == OK
        WD->>WD: dumpTakenThisEpisode.set(false)
    end

    alt riskLevel >= WARNING
        alt riskLevel >= CRITICAL AND dumpTakenThisEpisode.compareAndSet(false,true)
            WD->>DUMP: dump(snapshot, dumpTypes)
            DUMP-->>WD: ["/dumps/oom_heap_….hprof", …]
            WD->>WD: toReport = snapshot.withHeapDumpPath(paths)
        end
        loop for each AlertChannel
            WD->>CH: alert(toReport)
        end
    end

    Note over WD: checkDumpThresholds(assessed)
    WD->>WD: check gcDumpThreshold / heapDumpThreshold / nurseryDumpThreshold
    opt threshold exceeded AND !dumpTakenThisEpisode
        WD->>DUMP: dump(snapshot, threshold-triggered types)
        DUMP-->>WD: dumpPaths
    end

    WD->>WD: lastLevel.set(riskLevel)
    opt gcHistoryStore configured
        WD->>GCH: record(targetName, assessed)
        Note over GCH: appends JSON line to .jsonl ring-buffer
    end
```

---

## Dump Strategy Chain (Decision Flow)

```mermaid
flowchart TD
    START([dump request]) --> TYPE{DumpType?}

    TYPE -->|HEAP| H1[HotSpotHeapDumpStrategy\nHotSpotDiagnosticMXBean]
    H1 -->|success| DONE([path returned])
    H1 -->|null - not HotSpot| H2[J9HeapDumpStrategy\nOpenJ9 com.ibm.jvm.Dump]
    H2 -->|success| DONE
    H2 -->|null - not J9| H3[GraalNativeHeapDumpStrategy\nVMRuntime / pool summary fallback]
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
oom-watchdog/                  Maven multi-module root (v1.7.13.28)
├── core/                      oom-watchdog.jar  (fat jar via maven-shade-plugin)
│   └── src/main/java/com/trongus/oom/
│       ├── WatchdogMain.java  CLI entry point (local + daemon modes)
│       ├── alert/             AlertChannel (interface) + 6 implementations
│       │                      (Console, FileLog, QRadar, Was, Liberty, Cognos)
│       │                      + AlertFormatter (package-private, i18n-aware)
│       ├── collector/         JvmDiagnosticsCollector (interface)
│       │                      + MxBeanDiagnosticsCollector (in-process MXBeans)
│       ├── config/            WatchdogConfig (immutable builder, locale)
│       ├── dump/              HeapDumpService (interface) + CompositeDumpService
│       │                      + DumpType enum
│       │   └── strategy/      DumpStrategy (interface) + 6 implementations
│       │                      (HotSpotHeapDump, J9HeapDump, GraalNativeHeapDump,
│       │                       ThreadDump, ClassHistogram, CoreDump)
│       ├── examples/          11 runnable example classes (01–11)
│       ├── i18n/              Messages (UTF-8 ResourceBundle wrapper)
│       ├── logging/           WatchdogLogger + WatchdogLogFormatter
│       ├── model/             JvmSnapshot (immutable value object, targetName)
│       │                      + OomRiskLevel enum
│       ├── monitor/           OomWatchdog + RiskAssessor (interface)
│       │                      + ThresholdRiskAssessor + GcHistoryStore
│       │                      + MetricsHttpServer + TlsConfig
│       ├── platform/          JvmPlatform (static detection, all fields final)
│       ├── remote/            TargetDescriptor, TargetRegistry,
│       │                      JmxDiagnosticsCollector, WatchdogDaemon
│       └── test/              OomSimulator (3-phase heap exhaustion)
│   └── src/main/resources/
│       ├── com/trongus/oom/i18n/
│       │   ├── Messages.properties       English (base / fallback)
│       │   ├── Messages_de.properties    German
│       │   ├── Messages_es.properties    Spanish
│       │   ├── Messages_fr.properties    French
│       │   ├── Messages_ja.properties    Japanese (UTF-8)
│       │   ├── Messages_ko.properties    Korean (UTF-8)
│       │   ├── Messages_pt_BR.properties Brazilian Portuguese
│       │   ├── Messages_zh_CN.properties Simplified Chinese (UTF-8)
│       │   └── Messages_zh_TW.properties Traditional Chinese (UTF-8)
│       └── targets.properties.example    Daemon-mode reference config
│
├── test-harness/              test-harness.jar
│   └── src/main/java/com/trongus/oom/harness/
│       ├── TestHarnessMain.java
│       ├── DynamicOomClassGenerator.java  (javax.tools at runtime)
│       ├── BuiltInHeapExhauster.java
│       └── HarnessAlertRecorder.java      (CopyOnWriteArrayList + AtomicInteger)
│
└── oom-watchdog-tests/        JUnit 4 test suite (267 tests)
    └── src/test/java/com/trongus/oom/tests/
        ├── OomWatchdogTestSuite.java
        ├── model/             OomRiskLevelTest, JvmSnapshotTest
        ├── config/            WatchdogConfigTest
        ├── dump/              DumpTypeTest, CompositeDumpServiceTest
        ├── alert/             AlertFormatterTest, FileLogAlertChannelTest,
        │                      QRadarAlertChannelTest
        ├── collector/         MxBeanDiagnosticsCollectorTest
        ├── monitor/           ThresholdRiskAssessorTest, GcHistoryStoreTest
        ├── platform/          JvmPlatformTest
        ├── remote/            TargetDescriptorTest, TargetRegistryTest,
        │                      WatchdogDaemonTest
        └── integration/       OomWatchdogIntegrationTest, QRadarPipelineTest
```

---

## Logging Destinations by Platform

The following table shows exactly where OOM Watchdog alert output appears for every supported runtime. All channels are composable; add multiple channels to send alerts to multiple destinations simultaneously.

| Runtime / Platform | Alert Channel(s) | Log file / destination | Format | JUL level |
|--------------------|-----------------|----------------------|--------|-----------|
| **Any JVM** (stdout/err) | `ConsoleAlertChannel` | `System.err` | Multi-line human-readable | n/a |
| **Any JVM** (file) | `FileLogAlertChannel` | Configured file path (append, UTF-8) | Single-line + multi-line | n/a |
| **Any JVM** (SIEM) | `QRadarAlertChannel` | QRadar SIEM via UDP or TCP port 514 | LEEF 2.0 syslog | n/a |
| **HotSpot** (Oracle/OpenJDK/Azul/Corretto) | `FileLogAlertChannel` | `/var/log/oom-watchdog.log` (example) | Single-line + multi-line | n/a |
| **OpenJ9 / IBM J9** (standalone) | `FileLogAlertChannel` | `/var/log/oom-watchdog.log` (example) | Single-line + multi-line | n/a |
| **GraalVM JVM** | `FileLogAlertChannel` | `/var/log/oom-watchdog.log` (example) | Single-line + multi-line | n/a |
| **GraalVM Native Image** | `FileLogAlertChannel` | `/var/log/oom-watchdog.log` (example) | Single-line + multi-line | n/a |
| **WAS** (traditional) | `WasAlertChannel` | `${SERVER_LOG_ROOT}/SystemErr.log` | Key=value + FFDC ID | `WARNING` / `SEVERE` |
| **WAS** (traditional) | `WasAlertChannel` | `${SERVER_LOG_ROOT}/SystemOut.log` | — | Never (watchdog omits INFO) |
| **WAS** (traditional) | `WasAlertChannel` | `${SERVER_LOG_ROOT}/ffdc/` | FFDC cross-reference only | `SEVERE` triggers FFDC |
| **Liberty / Open Liberty** | `LibertyAlertChannel` | `${server.output.dir}/logs/messages.log` | Key=value (always present) | `WARNING` / `SEVERE` |
| **Liberty** (JSON mode) | `LibertyAlertChannel` | `messages.log` | JSON object per event | `WARNING` / `SEVERE` |
| **Liberty** (foreground) | `LibertyAlertChannel` | `console.log` | Mirrors messages.log | `WARNING` / `SEVERE` |
| **Liberty** (trace enabled) | `LibertyAlertChannel` | `trace.log` | All levels | `WARNING` / `SEVERE` |
| **Cognos ATC JVM** | `CognosAlertChannel` | `oom-watchdog-ATC.log` (configured) | Pipe-delimited | n/a (file write) |
| **Cognos CM JVM** | `CognosAlertChannel` | `oom-watchdog-CM.log` (configured) | Pipe-delimited | n/a (file write) |
| **Cognos Gateway JVM** | `CognosAlertChannel` | `oom-watchdog-GW.log` (configured) | Pipe-delimited | n/a (file write) |
| **Cognos on WAS** (secondary) | `CognosAlertChannel` | `SystemErr.log` (via JUL) | Key=value | `WARNING` / `SEVERE` |
| **Cognos on Liberty** (secondary) | `CognosAlertChannel` | `messages.log` (via JUL) | Key=value | `WARNING` / `SEVERE` |

**Notes:**
- "JUL level" refers to the `java.util.logging.Level` emitted. WAS and Liberty intercept JUL automatically; no custom handler registration is required.
- `WARNING` is emitted for `OomRiskLevel.WARNING`; `SEVERE` is emitted for `CRITICAL` and `OOM_FIRING`.
- For Cognos, the dedicated `.log` file is the **primary** output. The JUL secondary output only fires if Cognos runs inside WAS or Liberty.

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Pure `java.lang.management` MXBeans for collection | No native agent needed; works on all JVMs from JDK 8+ |
| Strategy pattern for dump dispatch | Adding a new vendor dump requires only a new `DumpStrategy` class |
| OLS slope on post-GC heap samples | Detects slow leaks that thresholds alone miss |
| Episode deduplication in `OomWatchdog` | Prevents dump storms during a sustained critical episode |
| `AtomicBoolean.compareAndSet(false,true)` for dump guard | Atomic check-and-set eliminates check-then-act race (SEC-2) |
| `AtomicReference<OomRiskLevel>` for `lastLevel` | Consistent cross-thread memory visibility without `synchronized` (SEC-3) |
| Immutable `JvmSnapshot` with `withHeapDumpPath()` copy | Thread-safe, trivially testable, no shared mutable state |
| Defensive copies in `JvmSnapshot.Builder` map setters | Caller-mutated maps cannot corrupt in-flight snapshots |
| `AlertFormatter` sanitises all free-text fields | Prevents control-character injection into log/syslog output |
| `AlertFormatter` package-private | Formatting is an implementation detail; only alert channels need it |
| `WatchdogConfig` immutable builder with full validation | Config cannot drift at runtime; rejects out-of-range values at construction |
| `WatchdogConfig.locale()` | Single locale setting propagates to `Messages`, `OomCauseAnalyser`, and `AlertFormatter` |
| LEEF 2.0 for QRadar | Native IBM SIEM format; field-indexed for high-speed correlation |
| TCP socket connect + read timeout (5 s) | Slow/unreachable QRadar host cannot block the watchdog poll thread |
| UDP payload capped at 65 007 bytes | Prevents silent datagram truncation on standard Ethernet MTUs |
| `gcore` path canonicalization + 60 s timeout | Prevents path-traversal; avoids hung dump process blocking the JVM |
| J9 `HeapDump(String)` / `SystemDump(String)` used when available | PHD and DMP files written to the configured dump directory; falls back to no-arg form on older J9 builds |
| JVM process-detail fields stored in `JvmSnapshot` | All `/metrics` responses — including remote targets — carry `javaHome`, `jvmName`, `osName`, `cpuCount`, etc. from the actual monitored JVM |
| `info-grid` column 1 uses `max-content` | Label column sized to its widest text — no fixed `min-width` needed; value column starts immediately after |
| Dashboard metric cards use HTML5 DnD + `localStorage` | Card order drag-and-dropped by user is persisted per-tab under `oom-card-order:<pane-id>`; restored on every load |
| JVM flags rendered one-per-line as `<ul>` in full-width row | `fmtFlags()` splits on `\s+(?=-)` boundaries; each flag gets its own `<li>` with `white-space:nowrap`; flags row spans the full card width below the two-column grid |
| Sparkline hover crosshair + tooltip via invisible `<rect>` | A transparent `<rect>` over each SVG captures `mousemove`; mouse coords are mapped to SVG coordinate space via `getBoundingClientRect()` to find the nearest data point; threshold proximity (±4 SVG-px) shows threshold value instead |
| Server URL history persisted to `localStorage` | Recent server URLs stored under `oom-server-history`; shown in a dropdown (▾ button); most-recent URL pre-filled on page load; each entry individually deletable |
| `AtomicInteger` counters in `HarnessAlertRecorder` | Thread-safe read-modify-write without external synchronisation |
| `CopyOnWriteArrayList` for `HarnessAlertRecorder.dumpPaths` | Lock-free iteration from the results thread; no unsynchronised read race (SEC-4) |
| All file writes use explicit `StandardCharsets.UTF_8` | Consistent output across all platforms; no platform-default charset risk |
| `ResourceBundle.Control` with UTF-8 reader | Prevents ISO-8859-1 corruption of CJK double-byte characters in `.properties` files |
| `OomCauseAnalyser` stateless | Can be shared across threads; no synchronisation cost |
| `OomCause` immutable value object | Safe to pass across threads with no defensive copy |
| `WatchdogDaemon` target name sanitised before file path use | Prevents path traversal via malicious target names (`[^A-Za-z0-9._-]` → `_`) |
| `TargetRegistry.loadFromFile()` uses `getCanonicalFile()` | Prevents path traversal when loading targets config file |
| `JmxDiagnosticsCollector` sanitises exception reason in unreachable snapshot | Prevents control-character injection from remote JMX errors into diagnosis notes |
| `TargetRegistry` null-guards `getProperty()` before `.trim()` | Prevents NPE on malformed properties files with valueless keys |
| Dashboard dump buttons route through `JmxDiagnosticsCollector.triggerRemoteDump()` | Ensures heap/thread/core dumps are written on the **target JVM**, not the watchdog process; uses `HotSpotDiagnosticMXBean` for heap and `DiagnosticCommand` MBean for thread/core |
| `TargetDescriptor.dumpDirectory` defaults to `null` | Inherits the global `--dump-dir` CLI value; only overridden when `dump-dir` is explicitly set per-target in `targets.properties`, preventing silent default path override |
| `QRadarAlertChannel` logs at INFO on success, FINE on build start, FINEST for raw LEEF payload | Keeps high-volume payload bytes off the default log level while preserving full observability at `FINEST`; operators see delivery confirmation at INFO without noise |
| `MetricsHttpServer.resolveBindAddress()` prefers IPv6 (`::` / `::1`) with IPv4 fallback | Single `::` socket serves both address families on dual-stack Linux kernels; automatic `0.0.0.0`/`127.0.0.1` fallback on IPv4-only stacks; respects `-Djava.net.preferIPv4Stack=true` |
| `QRadarAlertChannel.sendUdp()` matches socket family to destination via `instanceof Inet6Address`; binds to the real host IP when the destination is loopback | Opens a `::` datagram socket for IPv6 destinations and a default `DatagramSocket` for IPv4; resolves all A+AAAA records via `InetAddress.getAllByName()`; when the destination resolves to a loopback address (e.g. `127.0.0.1`), `resolveNonLoopbackAddress()` enumerates `NetworkInterface` to find the machine's first up, non-loopback, non-link-local address and binds the `DatagramSocket` to it — prevents QRadar from silently discarding syslog datagrams sourced from `127.0.0.1` |
| Dashboard auto-reconnects on page refresh | Last-used server URL, polling interval, and active target tab are persisted to `localStorage`; `startPolling()` is called automatically on `window.load` when a saved URL exists |

---

## Security Hardening Summary

Five security audit passes have been performed.  Passes 1 and 2 identified and
fixed 11 issues.  Pass 3 reviewed all remaining source files and confirmed no
further issues.  Pass 4 identified and fixed 3 concurrency issues (SEC-2, SEC-3, SEC-4).
Pass 5 identified and fixed 4 issues in the remote JMX monitoring subsystem.

| # | Audit | File | Issue | Fix |
|---|-------|------|-------|-----|
| 1 | 1–2 | `WatchdogConfig` | No range validation on numeric fields | `build()` rejects thresholds outside `(0,1)`, `pollIntervalMs < 100`, `leakDetectionWindowSize < 2`, `qradarPort` outside `[1,65535]`, blank `heapDumpDirectory` |
| 2 | 1–2 | `WatchdogMain` | CLI values passed to builder without validation | Parser clamps/rejects out-of-range values before calling `build()` |
| 3 | 1–2 | `QRadarAlertChannel` | TCP socket had no timeout | `socket.connect()` + `setSoTimeout()` both set to 5 s |
| 4 | 1–2 | `QRadarAlertChannel` | UDP payload not length-checked | Payload truncated to `MAX_UDP_PAYLOAD = 65 007` bytes before send |
| 5 | 1–2 | `CoreDumpStrategy` | `gcore` had no timeout; unbounded output accumulation | `waitFor(60, SECONDS)` + `destroyForcibly()`; output capped at 4 096 bytes |
| 6 | 1–2 | `CoreDumpStrategy` | `outputPath` not canonicalized — path-traversal risk | `File.getCanonicalPath()` applied before passing to `ProcessBuilder` |
| 7 | 1–2 | `DynamicOomClassGenerator` | `FileWriter` used platform default charset | Replaced with `OutputStreamWriter(…, UTF_8)` |
| 8 | 1–2 | `HotSpotHeapDumpService` | All three `FileWriter` usages used platform default charset | Replaced with `OutputStreamWriter(…, UTF_8)` |
| 9 | 1–2 | `AlertFormatter` | Free-text fields embedded unsanitised | `sanitiseMultiLine()` strips control chars in human-readable output; `sanitiseSingleLine()` strips newlines + quotes in single-line output |
| 10 | 1–2 | `JvmSnapshot.Builder` | Map setters stored caller's reference | Defensive `LinkedHashMap` copy taken in all three map setter methods |
| 11 | 3 | `HarnessAlertRecorder` | `volatile int++` is not atomic under concurrent access | Replaced with `AtomicInteger.incrementAndGet()` |
| SEC-2 | 4 | `OomWatchdog` | `volatile boolean dumpTakenForCurrentEpisode` — check-then-act race between poll cycles | Replaced with `AtomicBoolean.compareAndSet(false, true)` |
| SEC-3 | 4 | `OomWatchdog` | `volatile OomRiskLevel lastLevel` — weak memory ordering for `getLastRiskLevel()` callers | Replaced with `AtomicReference<OomRiskLevel>` |
| SEC-4 | 4 | `HarnessAlertRecorder` | `ArrayList dumpPaths` — `synchronized` writes but unsynchronised iteration in `printResults()` | Replaced with `CopyOnWriteArrayList` |
| SEC-5 | 5 | `WatchdogDaemon` | Target name used raw in file path — path traversal risk | Sanitised with `[^A-Za-z0-9._-]` → `_` before constructing path |
| SEC-6 | 5 | `TargetRegistry` | `loadFromFile()` not using canonical path — path traversal risk | `getCanonicalFile()` applied before opening file |
| SEC-7 | 5 | `JmxDiagnosticsCollector` | Raw exception message embedded in `diagnosisNotes` without sanitisation | Control characters stripped with `replaceAll("[\\x00-\\x1F\\x7F]", " ")` |
| SEC-8 | 5 | `TargetRegistry` | `getProperty(key).trim()` called without null guard on every key | Null-safe guard added before `.trim()` across all property reads |

---

## Release Artefacts

The v1.7.13.28 release publishes two executable fat JARs built with `maven-shade-plugin`.
Both will be attached to the [GitHub release](https://github.com/kgillard/oom-watchdog/releases/tag/v1.7.13.28).

| Artefact | Main class | Contents | Size (approx) |
|----------|-----------|----------|---------------|
| `oom-watchdog.jar` | `com.trongus.oom.WatchdogMain` | `core` module + all runtime dependencies shaded | ~165 KB |
| `test-harness.jar` | `com.trongus.oom.harness.TestHarnessMain` | `test-harness` + `core` modules shaded | ~180 KB |

### Build reproducibility

```bash
cd oom-watchdog
mvn clean package -DskipTests -q
# core/target/oom-watchdog.jar
# test-harness/target/test-harness.jar
```

---

## IBM Application Server Support

OOM Watchdog 1.2.0 extends the `AlertChannel` interface with three new implementations
targeting IBM application server platforms: `WasAlertChannel`, `LibertyAlertChannel`,
and `CognosAlertChannel`.  All three use `java.util.logging` (JUL) as the sole
external dependency, which each server intercepts and routes at runtime.

---

### WAS (WebSphere Application Server) — traditional

| Characteristic | Detail |
|----------------|--------|
| JVM vendor     | IBM J9 (IBM SDK for Java 8; also available on OpenJ9) |
| GC policy      | `gencon` by default (generational + concurrent); configurable via `-Xgcpolicy` |
| Heap model     | Nursery (`-Xmn`) + tenure space; typical production heap 1 GB – 4 GB |
| Logging        | JUL records intercepted by WAS logging handler; routed to `SystemOut.log` (INFO and below) and `SystemErr.log` (WARNING and above) |
| FFDC           | First Failure Data Capture: WAS generates an FFDC incident file in `${SERVER_LOG_ROOT}/ffdc/` on exception; `WasAlertChannel` includes a matching incident ID in every log entry |
| Thread pool    | WAS manages web-container and EJB thread pools independently; the watchdog scheduler uses its own `ScheduledExecutorService` and does not consume a WAS-managed thread |
| Poll interval  | 30 s recommended for production WAS (heap growth is gradual; aligns with WAS PMI 10–60 s sampling) |

**How `WasAlertChannel` integrates:**

1. Emits a JUL `WARNING`/`SEVERE` record to logger `com.trongus.oom.WasAlert`.
2. WAS's built-in JUL handler writes the record to `SystemErr.log`.
3. A structured single-line entry is also written to `System.err` for FFDC correlation.
4. Logger level is configured via WAS Admin Console → **Logging and Tracing →
   Change Log Detail Levels** → `com.trongus.oom.*=ALL`.

---

### Liberty (WebSphere Liberty / Open Liberty)

| Characteristic | Detail |
|----------------|--------|
| JVM vendor     | IBM J9 / OpenJ9 (default); HotSpot also supported |
| Heap model     | Standard JVM heap; typical container deployments use 256 MB – 512 MB (`-Xmx`) |
| Logging        | JUL unified with Liberty's logging pipeline; output goes to `messages.log`, `console.log`, and `trace.log` depending on level and `server.xml` configuration |
| JSON logging   | When `messageFormat="JSON"` is set in `server.xml`, every JUL record is emitted as a JSON object; `LibertyAlertChannel` embeds a JSON fragment as the log message so all OOM fields are top-level JSON keys queryable in Elastic / Splunk / IBM Log Analysis |
| MicroProfile   | Liberty exposes `/health` endpoints backed by MicroProfile Health; `LibertyAlertChannel.isHealthy()` is designed for direct use in a `@Liveness` `HealthCheck` implementation |
| CDI lifecycle  | Managed via `@ApplicationScoped` + `@PostConstruct` / `@PreDestroy` |

**How `LibertyAlertChannel` integrates:**

1. Emits a JUL `WARNING`/`SEVERE` record to logger `com.trongus.oom.LibertyAlert` with a
   JSON-fragment message body.
2. Liberty routes the record to `messages.log` (always) and `console.log` (if foreground).
3. When JSON logging is active, all OOM fields appear as top-level JSON keys.
4. `isHealthy()` / `getLastRiskLevel()` enable direct MicroProfile Health wiring.

---

### Cognos Analytics — multiple JVM processes

Cognos Analytics runs three or more separate JVM processes.  Each is monitored
independently by its own `OomWatchdog` instance paired with a `CognosAlertChannel`.

| Component             | Default JVM | Primary OOM causes                                     | Recommended `-Xmx` |
|-----------------------|-------------|--------------------------------------------------------|---------------------|
| Application Tier (ATC)| IBM J9      | Large report datasets, PDF rendering, session caches   | 4 GB – 8 GB         |
| Content Manager (CM)  | IBM J9      | JDBC result caches, XML metadata trees                 | 2 GB – 4 GB         |
| Gateway / Dispatcher  | IBM J9      | HTTP session routing, request buffering                | 1 GB – 2 GB         |

**How `CognosAlertChannel` integrates:**

1. Writes a pipe-delimited structured log entry to a dedicated Cognos alert log file
   (`oom-watchdog-ATC.log`, etc.) that the Cognos Log Server can ingest.
2. Emits a JUL record at the appropriate level for the application server hosting Cognos.
3. Log entries include `cognosComponent`, `cognosServer`, `reportEngineHeapMb`, and all
   standard JVM memory metrics.
4. The log file path and Cognos component name are configured at construction time.

---

### Architecture diagram — WAS / Liberty / Cognos → OOM Watchdog → QRadar

```mermaid
graph TD
    subgraph IBM_Platforms["IBM Application Server Platforms"]
        WAS["WebSphere Application Server<br/>(IBM J9 / gencon GC)<br/>SystemErr.log · FFDC"]
        Liberty["WebSphere Liberty / Open Liberty<br/>(J9 or HotSpot)<br/>messages.log · JSON logging · /health"]
        ATC["Cognos ATC JVM<br/>(-Xmx4g–8g)<br/>Report Engine · Session Cache"]
        CM["Cognos CM JVM<br/>(-Xmx2g–4g)<br/>Content Manager · JDBC Cache"]
        GW["Cognos Gateway JVM<br/>(-Xmx1g–2g)<br/>HTTP Dispatcher"]
    end

    subgraph OomWatchdog["OOM Watchdog (per JVM)"]
        direction TB
        Collector["MxBeanDiagnosticsCollector<br/>(MemoryMXBean · GcMXBean · RuntimeMXBean)"]
        Assessor["ThresholdRiskAssessor<br/>(heap % · GC overhead · leak trend)<br/>uses OomCauseAnalyser + Messages"]
        Channels["AlertChannel chain"]
    end

    subgraph Channels_Detail["Alert Channels"]
        WasChannel["WasAlertChannel<br/>JUL → SystemErr.log<br/>FFDC incident ID"]
        LibertyChannel["LibertyAlertChannel<br/>JUL → messages.log<br/>JSON fragment · isHealthy()"]
        CognosChannel["CognosAlertChannel<br/>pipe-delimited log file<br/>component · server · heapMb"]
        QRadarChannel["QRadarAlertChannel<br/>LEEF 2.0 syslog<br/>UDP or TCP"]
        FileChannel["FileLogAlertChannel<br/>structured file log"]
        ConsoleChannel["ConsoleAlertChannel<br/>System.err"]
    end

    WAS -->|hosts| WasChannel
    Liberty -->|hosts| LibertyChannel
    ATC -->|hosts| CognosChannel
    CM -->|hosts| CognosChannel
    GW -->|hosts| CognosChannel

    Collector --> Assessor --> Channels
    Channels --> WasChannel
    Channels --> LibertyChannel
    Channels --> CognosChannel
    Channels --> QRadarChannel
    Channels --> FileChannel
    Channels --> ConsoleChannel

    QRadarChannel -->|"LEEF 2.0 syslog (UDP/TCP)"| QRadar["IBM QRadar SIEM"]
    WasChannel -->|"SystemErr.log / FFDC"| WASLogs["WAS Log Files"]
    LibertyChannel -->|"messages.log (JSON)"| LibertyLogs["Liberty Log Files"]
    CognosChannel -->|"oom-watchdog-*.log"| CognosLogs["Cognos Log Server"]
```
