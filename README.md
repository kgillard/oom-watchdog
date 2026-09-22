# OOM Watchdog

> **Preemptively detect and alert on JVM Out-of-Memory conditions — before the process crashes.**

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Tests](https://img.shields.io/badge/tests-267%20passing-brightgreen)]()
[![Security Audit](https://img.shields.io/badge/security%20audit-4%20passes%20clean-brightgreen)]()
[![JDK](https://img.shields.io/badge/JDK-8%20%E2%80%93%2026%2B-blue)]()
[![Vendors](https://img.shields.io/badge/JVM-HotSpot%20%7C%20OpenJ9%20%7C%20GraalVM-blue)]()
[![Release](https://img.shields.io/badge/release-v1.7.13.17-blue)](https://github.com/kgillard/oom-watchdog/releases/tag/v1.7.13.17)

---

## Download

Pre-built JARs are available in the [v1.7.13.17 release](https://github.com/kgillard/oom-watchdog/releases/tag/v1.7.13.17):

| Artefact | Description | Size |
|----------|-------------|------|
| [`oom-watchdog.jar`](https://github.com/kgillard/oom-watchdog/releases/download/v1.7.13.17/oom-watchdog.jar) | Fat JAR — monitoring agent + CLI entry point | ~157 KB |
| [`test-harness.jar`](https://github.com/kgillard/oom-watchdog/releases/download/v1.7.13.17/test-harness.jar) | Fat JAR — interactive OOM test harness | ~171 KB |

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

OOM Watchdog provides real-time health monitoring, per-process logging named after the target JVM, multi-target daemon monitoring via remote JMX, and root-cause analysis.

---

## Quick start

### 1 — Download and run

```bash
# Download the release JAR or self-extracting installer
curl -L -o oom-watchdog.jar \
  https://github.com/kgillard/oom-watchdog/releases/download/v1.7.13.17/oom-watchdog.jar

# Run in multi-target daemon mode (monitors external JVMs over JMX)
java -jar oom-watchdog.jar --daemon --targets-file /etc/oom-watchdog/targets.properties

# Or run in single-process monitoring mode
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
| `--gc-dump-threshold <0.0–1.0>` | _(disabled)_ | GC overhead ratio that triggers an immediate dump regardless of risk level |
| `--heap-dump-threshold <0.0–1.0>` | _(disabled)_ | Heap usage ratio that triggers an immediate dump (supplements the CRITICAL-level dump) |
| `--poll-ms <ms>` | `5000` | Poll interval in milliseconds (minimum: 100) |
| `--dump-dir <path>` | `./oom-watchdog` | Output directory for dump artefacts |
| `--dump-types <list>` | _(none)_ | Comma-separated: `HEAP,THREAD,CLASS_HISTOGRAM,CORE` |
| `--log-file <path>` | `./oom-watchdog.log` | Append structured alerts to this file |
| `--log-level <level>` | `INFO` | Internal diagnostic log level: `FINEST` (full trace incl. LEEF payloads), `FINE` (debug — per-poll and per-channel events), `CONFIG`, `INFO`, `WARNING`, `SEVERE` |
| `--qradar-host <host>` | _(disabled)_ | QRadar / syslog target hostname |
| `--qradar-port <port>` | `514` | QRadar / syslog target port (1–65535) |
| `--qradar-tcp` | _(off)_ | Use TCP instead of UDP for QRadar |
| `--metrics-port <port>` | _(disabled)_ | Start HTTPS metrics server for `dashboard.html` |
| `--metrics-cert <path>` | _(auto self-signed)_ | PKCS#12 / JKS keystore for the metrics HTTPS server |
| `--metrics-cert-password <pwd>` | _(empty)_ | Password for the `--metrics-cert` keystore |
| `--metrics-no-tls` | _(off)_ | Use plain HTTP instead of HTTPS for the metrics server |
| `--test-mode` | _(off)_ | Run OomSimulator and exit |
| `--daemon` | _(off)_ | Run in multi-target daemon mode monitoring external JVMs via JMX |
| `--targets-file <path>` | `./targets.properties` | Path to targets configuration file in daemon mode |
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

### Multi-Target Daemon Mode (Monitoring External JVMs via JMX)

`WatchdogDaemon` monitors multiple external JVM processes **concurrently** via standard JMX
(JSR-160). A single watchdog process can simultaneously cover IBM QRadar components
(`hostcontext`, `tomcat`), WebSphere Application Server, WebSphere Liberty, Cognos Analytics,
and any custom microservice — each with independent thresholds, poll rates, and dump
preferences. One failure or unreachable target does not affect the others.

- [Step 1 — Enable JMX on each target JVM](#step-1--enable-jmx-on-each-target-jvm)
- [Step 2 — Write your `targets.properties`](#step-2--write-your-targetsproperties)
  - [Property reference](#property-reference)
  - [IBM QRadar — hostcontext](#ibm-qradar--hostcontext)
  - [IBM QRadar — Tomcat](#ibm-qradar--tomcat)
  - [WebSphere Application Server (WAS)](#websphere-application-server-was)
  - [WebSphere Liberty / Open Liberty](#websphere-liberty--open-liberty)
  - [IBM Cognos Analytics](#ibm-cognos-analytics)
  - [JMX authentication](#jmx-authentication)
  - [Validation rules](#validation-rules)
- [Step 3 — Run the daemon](#step-3--run-the-daemon)
- [How the daemon handles unreachable targets](#how-the-daemon-handles-unreachable-targets)

---

#### Step 1 — Enable JMX on each target JVM

Remote JMX requires the target JVM to open a management port at startup. Add the following
JVM flags to the target process's startup script. **The watchdog never modifies the target
JVM** — it only reads from it over the JMX connection.

**Minimal (unauthenticated, localhost only — development/internal use):**

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=<PORT>
-Dcom.sun.management.jmxremote.rmi.port=<PORT>
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

Setting `jmxremote.rmi.port` to the same value as `jmxremote.port` keeps both the registry
and RMI object server on a single well-known port, which is required when firewall rules or
Docker port mappings are in use.

**With password authentication (production):**

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=<PORT>
-Dcom.sun.management.jmxremote.rmi.port=<PORT>
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=true
-Dcom.sun.management.jmxremote.password.file=/etc/jmx/jmxremote.password
-Dcom.sun.management.jmxremote.access.file=/etc/jmx/jmxremote.access
-Djava.rmi.server.hostname=<WATCHDOG_ACCESSIBLE_IP>
```

`jmxremote.password` example (mode `600`, owned by the JVM process user):

```
# username  password
oomwatchdog  s3cr3t
```

`jmxremote.access` example:

```
oomwatchdog  readonly
```

Credentials are stored only in the properties file and in the `TargetDescriptor` in memory.
They are **never written to any log** — the watchdog masks passwords with `***` in all
diagnostic output.

---

#### Step 2 — Write your `targets.properties`

Each target is a logical name you choose (e.g. `hostcontext`, `tomcat`, `was`) followed by a
set of `target.<name>.<property>` keys. Lines beginning with `#` are comments and are ignored.

A complete example file is provided at
`core/src/main/resources/targets.properties.example`.

##### Property reference

| Property | Required | Default | Description |
|---|---|---|---|
| `target.<name>.jmx-url` | **Yes** | — | JMX Service URL. Always in the form `service:jmx:rmi:///jndi/rmi://<host>:<port>/jmxrmi` |
| `target.<name>.warn` | No | `0.80` | Heap used / max ratio (0.0–1.0) that triggers a **WARNING** alert |
| `target.<name>.crit` | No | `0.90` | Heap used / max ratio (0.0–1.0) that triggers a **CRITICAL** alert and dump |
| `target.<name>.gc` | No | `0.50` | Fraction of uptime spent in GC (0.0–1.0) that contributes to risk escalation |
| `target.<name>.poll-ms` | No | `5000` | Milliseconds between JMX collections. Minimum: `100` |
| `target.<name>.dump-types` | No | _(none)_ | Comma-separated list of dump artefacts to capture at CRITICAL. Values: `heap`, `thread`, `class_histogram`, `core` |
| `target.<name>.dump-dir` | No | _(inherits `--dump-dir`)_ | Filesystem directory where dump files are written for this target. When omitted, inherits the global `--dump-dir` value |
| `target.<name>.username` | No | _(none)_ | JMX authentication username |
| `target.<name>.password` | No | _(none)_ | JMX authentication password |
| `target.<name>.gc-dump-threshold` | No | _(disabled)_ | GC overhead ratio (0.0–1.0) that triggers an immediate dump |
| `target.<name>.heap-dump-threshold` | No | _(disabled)_ | Heap ratio (0.0–1.0) that triggers an immediate dump at WARNING level |
| `target.<name>.nursery-dump-threshold` | No | _(disabled)_ | Young-gen/nursery memory ratio (0.0–1.0) that triggers an immediate dump |
| `target.<name>.leef-category` | No | `JVM_OOM_Risk` | Overrides the LEEF `cat` attribute in every QRadar syslog event sent for this target. Use to distinguish components in QRadar **Log Activity** searches and custom rules |
| `target.<name>.leef-tags` | No | _(omitted)_ | Adds a `tags` attribute to the LEEF event. Recommended format: `key=value,key=value` (e.g. `env=prod,team=platform,region=us-east-1`) |

> **Constraint:** `warn` must be strictly less than `crit`. Providing equal values or
> `warn >= crit` causes a startup validation error for that target.

---

##### IBM QRadar — hostcontext

`hostcontext` is the core event pipeline JVM in QRadar. It processes incoming events and
feeds the Ariel database. OOM here causes event loss and pipeline stalls.

**Enable JMX** — add to `/opt/qradar/systemd/bin/hostcontext.sh` (or the systemd
`Environment=` override for the `hostcontext` service unit):

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=7777
-Dcom.sun.management.jmxremote.rmi.port=7777
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

**`targets.properties` entry:**

```properties
# IBM QRadar – hostcontext (event processing JVM)
# JVM: IBM J9 / OpenJ9  |  Typical heap: 2 GB – 8 GB
target.hostcontext.jmx-url    = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi
target.hostcontext.warn       = 0.75
target.hostcontext.crit       = 0.85
target.hostcontext.gc         = 0.40
target.hostcontext.poll-ms    = 3000
target.hostcontext.dump-types = heap,thread
target.hostcontext.dump-dir   = /var/log/qradar/dumps/hostcontext
```

**Rationale for these settings:**

- `warn=0.75` / `crit=0.85` — tighter than defaults because `hostcontext` heap is large and
  the J9 `gencon` GC policy can exhaust remaining headroom quickly once high watermarks are
  reached.
- `gc=0.40` — J9 `gencon` runs frequent nursery collections; a lower GC threshold catches
  runaway scavenge cycles earlier.
- `poll-ms=3000` — 3-second polling matches the typical QRadar pipeline heartbeat period.
- `dump-types=heap,thread` — heap dump identifies retained event objects; thread dump shows
  which pipeline threads are blocked or looping.

---

##### IBM QRadar — Tomcat

Tomcat serves the QRadar Console web UI and REST API. OOM here causes the UI to become
unresponsive and API calls to time out.

**Enable JMX** — add to `/opt/tomcat/bin/setenv.sh` or `/opt/qradar/bin/tomcat.sh`:

```bash
CATALINA_OPTS="$CATALINA_OPTS \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=8090 \
  -Dcom.sun.management.jmxremote.rmi.port=8090 \
  -Dcom.sun.management.jmxremote.ssl=false \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Djava.rmi.server.hostname=127.0.0.1"
```

**`targets.properties` entry:**

```properties
# IBM QRadar – Tomcat (Console UI + REST API)
# JVM: HotSpot  |  Typical heap: 1 GB – 4 GB
target.tomcat.jmx-url         = service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi
target.tomcat.warn             = 0.80
target.tomcat.crit             = 0.90
target.tomcat.gc               = 0.50
target.tomcat.poll-ms          = 15000
target.tomcat.dump-types       = heap,thread,class_histogram
target.tomcat.dump-dir         = /var/log/qradar/dumps/tomcat
```

**Rationale:**

- `warn=0.80` / `crit=0.90` — standard defaults are appropriate; Tomcat's HotSpot G1GC
  handles moderate heap pressure well.
- `poll-ms=15000` — 15-second polling is sufficient for a UI server that accumulates memory
  gradually via session growth.
- `dump-types=heap,thread,class_histogram` — the class histogram is particularly useful here
  to identify which Tomcat session objects or cached JSP results are being retained.

---

##### WebSphere Application Server (WAS)

WAS Traditional runs on IBM J9. Each WAS server process is an independent JVM and should be
declared as its own target.

**Enable JMX** — add to the JVM Custom Properties in the WAS Admin Console
(**Servers → Server Types → WebSphere application servers → `<server>` → Java and Process
Management → Process Definition → Java Virtual Machine → Custom Properties**) or directly in
`server.xml`:

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=8880
-Dcom.sun.management.jmxremote.rmi.port=8880
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

> **Note:** WAS also exposes its own SOAP/RMI admin connector on port 8879. This is separate
> from the standard JMX RMI connector used here. Use the `-Dcom.sun.management.jmxremote.*`
> flags above — not the WAS admin connector URL.

**`targets.properties` entry:**

```properties
# IBM WebSphere Application Server (WAS Traditional)
# JVM: IBM J9  |  Typical heap: 1 GB – 4 GB  |  GC policy: gencon or optthruput
target.was.jmx-url             = service:jmx:rmi:///jndi/rmi://localhost:8880/jmxrmi
target.was.warn                = 0.80
target.was.crit                = 0.90
target.was.gc                  = 0.40
target.was.poll-ms             = 30000
target.was.dump-types          = heap,thread
target.was.dump-dir            = /opt/IBM/WebSphere/AppServer/logs/oom-dumps
```

**Rationale:**

- `gc=0.40` — J9 `gencon` performs many short scavenge cycles; a lower GC threshold
  triggers earlier than HotSpot's G1 would require.
- `poll-ms=30000` — aligns with WAS PMI sampling cadence (10–60 s). More frequent polling
  adds negligible overhead but `30000` is a comfortable default.
- `dump-dir` — writing dumps to the WAS logs directory keeps artefacts co-located with
  `SystemErr.log` and FFDC data for easier incident correlation.

---

##### WebSphere Liberty / Open Liberty

Liberty runs on IBM J9 or OpenJ9 (and fully supports HotSpot). Each Liberty server process
is a separate JVM and should be its own target.

**Enable JMX** — add to `jvm.options` in `${server.config.dir}`:

```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9050
-Dcom.sun.management.jmxremote.rmi.port=9050
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

Alternatively, enable the `localConnector-1.0` or `restConnector-2.0` feature in
`server.xml` for Liberty-native JMX access (the standard RMI connector above is simpler and
requires no `server.xml` changes).

**`targets.properties` entry:**

```properties
# IBM WebSphere Liberty / Open Liberty
# JVM: IBM J9 / OpenJ9 (HotSpot also supported)  |  Typical heap: 512 MB – 2 GB
target.liberty.jmx-url         = service:jmx:rmi:///jndi/rmi://localhost:9050/jmxrmi
target.liberty.warn            = 0.75
target.liberty.crit            = 0.88
target.liberty.gc              = 0.45
target.liberty.poll-ms         = 10000
target.liberty.dump-types      = heap,thread
target.liberty.dump-dir        = /opt/IBM/WebSphere/Liberty/usr/servers/defaultServer/logs/oom-dumps
```

**Rationale:**

- `warn=0.75` / `crit=0.88` — Liberty microservice heaps are often smaller and less elastic
  than WAS heaps; tighter thresholds give more warning time.
- `gc=0.45` — OpenJ9's balanced GC policy can mask growing heap pressure until a concurrent
  GC cycle stalls; the lower threshold detects this earlier.
- `poll-ms=10000` — 10-second polling is appropriate for REST-facing services where load
  spikes are frequent and short.

---

##### IBM Cognos Analytics

Cognos runs **three separate JVM processes** — each declared as its own target with
independent thresholds.

| Target name | Cognos component | Typical heap | Primary OOM causes |
|---|---|---|---|
| `cognos-atc` | Application Tier Component (ATC) | 4 GB – 8 GB | Large report datasets, PDF rendering, session caches |
| `cognos-cm` | Content Manager (CM) | 2 GB – 4 GB | JDBC result caches, XML metadata trees |
| `cognos-gw` | Gateway / Dispatcher | 1 GB – 2 GB | HTTP session routing, request buffering |

**Enable JMX** — add to each Cognos JVM's startup configuration
(`cognosservice.xml` or the relevant wrapper configuration):

```bash
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=<PORT>
-Dcom.sun.management.jmxremote.rmi.port=<PORT>
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=127.0.0.1
```

Use a distinct port for each component (e.g. 9300, 9301, 9302).

**`targets.properties` entry:**

```properties
# IBM Cognos – Application Tier Component (ATC)
# JVM: IBM J9  |  Typical heap: 4 GB – 8 GB
target.cognos-atc.jmx-url      = service:jmx:rmi:///jndi/rmi://localhost:9300/jmxrmi
target.cognos-atc.warn         = 0.80
target.cognos-atc.crit         = 0.90
target.cognos-atc.gc           = 0.40
target.cognos-atc.poll-ms      = 15000
target.cognos-atc.dump-types   = heap,thread,class_histogram
target.cognos-atc.dump-dir     = /opt/IBM/cognos/analytics/logs/oom-dumps/atc

# IBM Cognos – Content Manager (CM)
# JVM: IBM J9  |  Typical heap: 2 GB – 4 GB
target.cognos-cm.jmx-url       = service:jmx:rmi:///jndi/rmi://localhost:9301/jmxrmi
target.cognos-cm.warn          = 0.80
target.cognos-cm.crit          = 0.90
target.cognos-cm.gc            = 0.40
target.cognos-cm.poll-ms       = 15000
target.cognos-cm.dump-types    = heap,thread
target.cognos-cm.dump-dir      = /opt/IBM/cognos/analytics/logs/oom-dumps/cm

# IBM Cognos – Gateway / Dispatcher
# JVM: IBM J9  |  Typical heap: 1 GB – 2 GB
target.cognos-gw.jmx-url       = service:jmx:rmi:///jndi/rmi://localhost:9302/jmxrmi
target.cognos-gw.warn          = 0.80
target.cognos-gw.crit          = 0.90
target.cognos-gw.gc            = 0.50
target.cognos-gw.poll-ms       = 10000
target.cognos-gw.dump-types    = thread
target.cognos-gw.dump-dir      = /opt/IBM/cognos/analytics/logs/oom-dumps/gw
```

**Rationale:**

- ATC is the highest memory consumer; `class_histogram` dumps identify which report objects
  and datasets are being retained across requests.
- CM's primary risk is JDBC result-set caches growing unbounded; heap + thread dumps reveal
  which queries are outstanding.
- Gateway is the lowest risk; a thread dump alone is usually sufficient to diagnose request
  routing issues.

---

##### JMX authentication

When `authenticate=true` is set on the target JVM, supply credentials per target:

```properties
target.hostcontext.username    = oomwatchdog
target.hostcontext.password    = s3cr3t
```

Credentials are read by `TargetRegistry` at startup. They are held in memory as part of the
`TargetDescriptor` and passed to `JMXConnectorFactory.connect()` as a
`JMXConnector.CREDENTIALS` environment entry. **They are never written to any log file** —
`TargetDescriptor.toString()` replaces passwords with `***`.

---

##### Validation rules

`TargetRegistry` enforces the following at startup. A misconfigured target throws an
`IllegalArgumentException` identifying the offending target by name; other targets continue
loading normally.

| Rule | Error |
|---|---|
| `jmx-url` is missing or blank | `IllegalArgumentException` |
| `warn >= crit` | `IllegalStateException` ("warnThreshold must be strictly less than critThreshold") |
| `warn` or `crit` ≤ 0.0 or ≥ 1.0 | `IllegalArgumentException` |
| `gc` ≤ 0.0 or ≥ 1.0 | `IllegalArgumentException` |
| `poll-ms` < 100 | `IllegalArgumentException` |
| Unrecognised `dump-types` value | value silently ignored; valid values loaded |

---

#### Step 3 — Run the daemon

```bash
java -jar oom-watchdog.jar \
    --daemon \
    --targets-file /etc/oom-watchdog/targets.properties \
    --qradar-host 192.168.1.10 \
    --qradar-port 514
```

`--qradar-host` adds a shared `QRadarAlertChannel` to every target's alert pipeline. It
supplements (not replaces) the per-target console and file log channels that the daemon
always creates automatically.

To omit QRadar alerting and use only file logs:

```bash
java -jar oom-watchdog.jar \
    --daemon \
    --targets-file /etc/oom-watchdog/targets.properties
```

On startup the daemon logs one line per successfully initialised target:

```
INFO: Started watchdog for target [hostcontext] (JMX: service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi)
INFO: Started watchdog for target [tomcat]      (JMX: service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi)
INFO: WatchdogDaemon successfully initialized 2 active monitor(s).
```

---

#### How the daemon handles unreachable targets

If a target JVM's JMX endpoint cannot be reached, `JmxDiagnosticsCollector` retries up to
**3 times** with a **2-second back-off** between attempts. If all retries fail, the daemon
does not crash — it generates a synthetic snapshot with `riskLevel=OOM_FIRING` and
`processName="<name> [UNREACHABLE]"`, which immediately fires all alert channels (including
QRadar at severity 10). This means a JVM that has already crashed, or whose JMX port was
never opened, generates an immediate operational alert through the normal pipeline.

Once the target comes back online, the connection is re-established transparently on the next
poll cycle.

---

### IBM QRadar SIEM

`QRadarAlertChannel` sends LEEF 2.0 syslog events over UDP (default) or TCP. Combines with any other channel.

```java
new QRadarAlertChannel("siem.corp.com", 514, Transport.UDP)
new QRadarAlertChannel("siem.corp.com", 6514, Transport.TCP)  // TLS proxy
```

**LEEF 2.0 event format** (single line on the wire; `<TAB>` = literal tab delimiter):

```
<13>Sep 17 08:00:00 prod-host LEEF:2.0|IBM|OomWatchdog|1.1|OOM_CRITICAL|sev=9<TAB>cat=JVM_OOM_Risk<TAB>targetJvm=hostcontext<TAB>tags=env=prod,component=hostcontext<TAB>process=98765@prod-host<TAB>heapUsedMB=921<TAB>heapMaxMB=1024<TAB>heapPct=90.0<TAB>critThresholdPct=90.0<TAB>heapMarginPct=0.0<TAB>dumpTaken=true<TAB>nonHeapUsedMB=128<TAB>gcOverheadPct=23.8<TAB>totalGcTimeMs=14300<TAB>postGcGrowth=42.30 MB/h<TAB>nurseryPct=45.0<TAB>riskLevel=CRITICAL<TAB>msg=...
```

`cat` and `tags` are per-target overrides controlled by `leef-category` and `leef-tags` in `targets.properties`. When not set, `cat` defaults to `JVM_OOM_Risk` and `tags` is omitted entirely.

| `sev` value | Risk level |
|------------|------------|
| `1` | OK |
| `5` | WARNING |
| `9` | CRITICAL |
| `10` | OOM_FIRING |

---

#### Creating a QRadar Log Source for Syslog LEEF

Before OOM Watchdog events appear in QRadar offenses or searches, QRadar must have a **Log Source** configured to accept and parse the incoming LEEF syslog stream. Without a log source, QRadar silently discards or misclassifies the events.

The steps below apply to QRadar 7.4 and 7.5 (SIEM and XDR editions). Screenshots differ slightly between versions, but the field names are identical.

##### Step 1 — Confirm the syslog port is reachable

OOM Watchdog sends syslog to the host and port you supply via `--qradar-host` / `--qradar-port`. That host must be a QRadar **All-in-One**, **Event Processor**, or **QRadar Event Collector** (not a Console-only appliance).

Verify connectivity from the watchdog host before creating the log source:

```bash
# UDP (default)
echo "test" | nc -u -w1 <QRADAR_EP_HOST> 514

# TCP
echo "test" | nc -w1 <QRADAR_EP_HOST> 514
```

QRadar listens on UDP/514 and TCP/514 out of the box. If you use a non-standard port (e.g. `--qradar-port 5514`), open that port on the QRadar host's firewall first:

```bash
# On the QRadar Event Processor (RHEL/CentOS)
firewall-cmd --permanent --add-port=5514/udp
firewall-cmd --permanent --add-port=5514/tcp   # only if using --qradar-tcp
firewall-cmd --reload
```

---

##### Step 2 — Open the Log Source Management wizard

1. Log in to the **QRadar Console** as an administrator.
2. Click the **Admin** tab in the top navigation bar.
3. Under **Data Sources**, click **Log Sources**.
4. In the Log Sources window, click **Add** (top-left toolbar button).

> **QRadar 7.5 / New UI:** Navigate to **Admin → Log Source Management App → Log Sources → Add**.

---

##### Step 3 — Set the log source type

In the **Add a Log Source** wizard, fill in the fields as follows:

| Field | Value |
|---|---|
| **Log Source Name** | `OOM Watchdog – <hostname>` (e.g. `OOM Watchdog – prod-host`) |
| **Log Source Description** | `JVM OOM alerts from OOM Watchdog LEEF 2.0 syslog` |
| **Log Source Type** | `IBM QRadar LEEF` |
| **Protocol Configuration** | `Syslog` |

> **Log Source Type must be `IBM QRadar LEEF`.**
> This selects QRadar's built-in LEEF 2.0 parser, which automatically extracts all
> tab-separated `key=value` attributes (`sev`, `heapPct`, `riskLevel`, `msg`, etc.)
> into searchable QRadar event properties without any custom DSM or regex work.

---

##### Step 4 — Configure the Syslog protocol parameters

After selecting the `Syslog` protocol, a second panel appears with a single required field:

| Field | Value | Notes |
|---|---|---|
| **Log Source Identifier** | IP address or hostname of the watchdog host | Must match the source IP QRadar sees on the arriving UDP/TCP packets |

> **Why there is no port or transport field here:**
> The `Syslog` protocol in QRadar is **passive** — QRadar's own syslog listener (ECS) is
> already accepting UDP and TCP on port 514 (and TCP on port 514) at the system level.
> The log source does **not** open its own socket; it simply claims events that arrive
> from the identified source IP.  Port and transport are therefore configured at the
> system/firewall level, not per log source.

> **If you need a non-standard port or an isolated listener**, use
> **Protocol Configuration: `Syslog Redirect`** instead of `Syslog`.
> That protocol creates a dedicated listener and exposes two additional fields:
>
> | Field | Value | Notes |
> |---|---|---|
> | **Listen Port** | `5514` (or your `--qradar-port` value) | The port this listener binds to |
> | **Protocol** | `UDP` or `TCP` | Match `--qradar-tcp`: omitted = UDP, present = TCP |
>
> Choosing `Syslog Redirect` requires `--qradar-port` in OOM Watchdog to match the port
> you enter here, and the port must be open on the QRadar Event Processor's firewall (see
> Step 1).

> **Log Source Identifier** is the key field for de-duplication. If multiple hosts run OOM
> Watchdog, create one log source per host, each with a distinct identifier.

---

##### Step 5 — Configure event mapping (optional but recommended)

QRadar's LEEF parser maps the `sev` field to its internal event severity automatically.
You can optionally map the `cat` and `riskLevel` fields to QRadar event categories for
richer offense generation:

1. In the log source wizard, click **Configure Event Mapping**.
2. Under **Custom Event Properties**, click **Add** and create the following mappings:

| LEEF Attribute | QRadar Property Name | Property Type |
|---|---|---|
| `heapPct` | `JVM Heap Usage (%)` | Numeric |
| `critThresholdPct` | `Critical Heap Threshold (%)` | Numeric |
| `heapMarginPct` | `Headroom to Critical (%)` | Numeric |
| `dumpTaken` | `Dump Captured Flag` | Text |
| `nurseryPct` | `Nursery Utilisation (%)` | Numeric |
| `riskLevel` | `JVM Risk Level` | Text |
| `process` | `JVM Process` | Text |
| `gcOverheadPct` | `GC Overhead (%)` | Numeric |
| `postGcGrowth` | `Post-GC Growth Rate` | Text |
| `msg` | `OOM Diagnosis` | Text |

These properties become searchable in **Log Activity** and usable in **Custom Rules** and
**Offense** descriptions.

---

##### Step 6 — Save and deploy

1. Click **Save** in the log source wizard.
2. Back in the **Admin** tab, click **Deploy Changes** (top-right).

> QRadar requires a **Deploy Changes** after every log source modification.
> Events arriving before the deploy completes may be held in the input queue and
> processed once the configuration is live — no events are lost.

---

##### Step 7 — Verify events are arriving

1. Click the **Log Activity** tab.
2. In the search bar, enter:

   ```
   LEEF:2.0 AND "OomWatchdog"
   ```

3. Set the time range to **Last 5 Minutes** and click **Search**.

If OOM Watchdog is running and has raised at least one `WARNING` or higher alert, you will see matching events. Each event's **Event Name** column shows the LEEF Event ID (`OOM_WARNING`, `OOM_CRITICAL`, or `OOM_OOM_FIRING`), and the payload columns show the extracted heap and GC attributes.

If no events appear:
- Run OOM Watchdog in `--test-mode` to force all risk levels to fire:
  ```bash
  java -Xmx64m -jar oom-watchdog.jar \
      --test-mode \
      --warn-threshold 0.50 \
      --crit-threshold 0.70 \
      --poll-ms 1000 \
      --qradar-host <QRADAR_EP_HOST> \
      --qradar-port 514
  ```
- Confirm the log source identifier matches the watchdog host's IP exactly.
- Check QRadar's `/var/log/qradar.log` on the Event Processor for `syslog` receive errors.

---

##### Step 8 — Create an offense rule (optional)

To generate a QRadar **Offense** whenever a `CRITICAL` or `OOM_FIRING` event arrives:

1. Go to **Offenses → Rules → Add**.
2. Set rule type to **Event**.
3. Add the condition:

   ```
   when the event(s) are detected by the Local System
   and when the Event Name contains "OOM_CRITICAL" or "OOM_OOM_FIRING"
   ```

4. Under **Actions**, set **Assign Magnitude** to `8` (high) and enable **Notify**.
5. Name the rule `JVM OOM Critical – OOM Watchdog` and click **Finish**.

After the next **Deploy Changes**, any `CRITICAL` or `OOM_FIRING` event from OOM Watchdog
opens an offense automatically, ensuring it appears in the **Offenses** dashboard and
triggers any configured notification (email, ServiceNow, webhook, etc.).

---

##### Log source summary

| Setting | Value |
|---|---|
| Log Source Type | `IBM QRadar LEEF` |
| Protocol | `Syslog` (UDP or TCP) |
| Default port | `514` (overridable via `--qradar-port`) |
| LEEF Vendor | `IBM` |
| LEEF Product | `OomWatchdog` |
| LEEF Version | `1.1` |
| Event IDs | `OOM_OK`, `OOM_WARNING`, `OOM_CRITICAL`, `OOM_OOM_FIRING` |
| Syslog facility | `1` (user-level messages) |
| Syslog severity | `5` (notice) — encoded in `<13>` RFC 3164 priority header |

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

## OOM cause analysis

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

## Internationalisation

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

## Real-time Dashboard

OOM Watchdog includes a self-contained browser dashboard (`dashboard.html`) that displays
live JVM health metrics per monitored target, with rolling sparkline charts and per-target tabs.

### Enabling the metrics endpoint

Pass `--metrics-port <port>` when starting OOM Watchdog:

```bash
java -Xmx256m -jar oom-watchdog.jar \
    --metrics-port 9090 \
    --poll-ms 2000 \
    --warn-threshold 0.80 \
    --crit-threshold 0.90
```

This starts a lightweight **HTTPS** server on `https://localhost:9090` alongside the normal
watchdog. The server exposes these endpoints:

| Endpoint | Method | Response |
|---|---|---|
| `/metrics` | GET | JSON object — self-monitoring JVM metrics |
| `/metrics/all` | GET | JSON array — one entry per monitored target (daemon mode) |
| `/dump/thread?target=<name>` | POST | Trigger thread dump; JSON `{"ok":true,"path":"…"}` |
| `/dump/heap?target=<name>` | POST | Trigger heap dump; JSON `{"ok":true,"path":"…"}` |
| `/dump/core?target=<name>` | POST | Trigger core dump; JSON `{"ok":true,"path":"…"}` |
| `/` | GET | 302 redirect to `/metrics` |

The server uses **dual-stack binding** by default:

| `--metrics-bind-all` | Preferred bind address | IPv4 fallback |
|---|---|---|
| _(absent — loopback)_ | `::1` (IPv6 loopback) | `127.0.0.1` |
| `--metrics-bind-all` | `::` (all IPv6 interfaces) | `0.0.0.0` |

On Linux a `::` socket also accepts IPv4 connections via IPv4-mapped addresses, so a
single socket serves both families. On macOS/BSD only one address family is served per
bind address.

**JVM flags for address-family control:**

```bash
# Force IPv4-only (disables IPv6 socket selection entirely)
-Djava.net.preferIPv4Stack=true

# Prefer IPv6 when both families are available
-Djava.net.preferIPv6Addresses=true
```

**IPv6 literal address examples** — use bracket notation in the Server field of the dashboard:

| Scenario | URL |
|---|---|
| IPv6 loopback | `https://[::1]:9090` |
| Specific IPv6 interface | `https://[2001:db8::1]:9090` |
| IPv6 all-interfaces (bind) | `::` |

#### TLS / HTTPS

The metrics server defaults to HTTPS using an **auto-generated, in-memory self-signed certificate**
(RSA-2048, 90-day validity, renewed automatically on expiry). No keystore file or configuration
is needed for the default setup.

| Scenario | Flags |
|---|---|
| Default — auto self-signed cert | _(no extra flags)_ |
| Production — real certificate | `--metrics-cert /path/to/server.p12 --metrics-cert-password <pwd>` |
| Trusted CA via Let's Encrypt | `--metrics-cert /path/to/fullchain.p12 --metrics-cert-password <pwd>` |
| Plain HTTP (loopback only) | `--metrics-no-tls` |

> **Browser note:** When using the self-signed certificate, your browser will show a security
> warning ("Your connection is not private"). This is expected — click **Advanced → Proceed** to
> open the dashboard. The cert protects the transport; it is just not signed by a trusted CA.

Only **TLS 1.2 and TLS 1.3** are accepted. Weak cipher suites (RC4, 3DES, CBC without HMAC-SHA2,
export-grade) are disabled. All responses include `X-Content-Type-Options: nosniff`,
`Cache-Control: no-store`, and `X-Frame-Options: DENY`.

### Opening the dashboard

`dashboard.html` is a fully self-contained static HTML file — no web server, no build step,
no dependencies. Open it directly from your filesystem:

```bash
# macOS
open dashboard.html

# Linux
xdg-open dashboard.html

# Windows
start dashboard.html
```

Or serve it via any static file server if you want to access it remotely:

```bash
python3 -m http.server 8080
# then open http://<your-host>:8080/dashboard.html
```

### Using the dashboard

On first open, enter the server URL and click **Connect**. On every subsequent page refresh
the dashboard automatically reconnects to the last-used server and restores the previously
active target tab and polling interval — no manual steps required.

1. In the **Server** field at the top, enter `https://localhost:9090` (or your host/port).
   For IPv6, use bracket notation: `https://[::1]:9090`.
2. Select a polling interval (1 s / 2 s / 5 s / 10 s).
3. Click **Connect** (automatic on refresh once a URL has been saved).
4. The dashboard displays real-time data per monitored target on separate tabs:

| Panel | What it shows |
|---|---|
| **Heap Usage** | Used MB / Max MB + live gauge |
| **GC Overhead** | CPU fraction in GC + sparkline chart (last 60 samples) |
| **Young Gen** | Young-gen pool usage gauge |
| **Non-Heap** | Metaspace + code cache MB |
| **JVM Process** | Process name, uptime, critical threshold, last poll time |
| **JVM Detail** | JVM location (`java.home`), JVM name, Java version, OS, CPU cores, process CPU %, CPU time, thread count, JVM flags, application command |
| **Risk Level** | Current `OK` / `WARNING` / `CRITICAL` / `OOM_FIRING` with colour badge |
| **Sparkline Charts** | 60-sample rolling charts for Heap %, GC Overhead %, Young Gen % — hover to inspect values and thresholds |
| **Diagnosis** | Full assessment text from `ThresholdRiskAssessor` |
| **Memory Pools** | All JVM memory pool usages in MB |
| **GC Collections** | Per-collector invocation counts |
| **On-Demand Diagnostics** | Three buttons per target tab — **Thread Dump**, **Heap Dump**, **Core Dump** |
| **Alert History** | Per-target rolling log of WARNING/CRITICAL/OOM_FIRING events |
| **Draggable cards** | Metric cards can be reordered by drag-and-drop; order is remembered per target in `localStorage` |
| **Server URL history** | Recent server URLs are saved in `localStorage` and shown in a dropdown; click ▾ next to the Server field |

Each tab header shows a colour dot indicating the target's current risk level.
In daemon mode (multiple remote JVMs) each target appears as a separate tab.

### Sparkline chart tooltips

Move the mouse over any of the three sparkline charts (**Heap Usage %**, **GC Overhead %**, **Young Gen %**) to inspect historical values:

- **Hovering the chart area** — a vertical crosshair snaps to the nearest recorded data point and a dot appears on the line at that value. A tooltip shows the value at that point (e.g. **`85.3%`**).
- **Hovering the red dashed threshold line** (within ~4 px) — the crosshair and dot are hidden and the tooltip instead shows the threshold value (e.g. **`Threshold: 85.0%`**).
- **Mouse leave** — the tooltip, crosshair, and dot all disappear.

The tooltip follows the cursor and automatically repositions to stay within the viewport.

### Rearranging dashboard cards

The six metric cards (**Heap Usage**, **GC Overhead**, **Young Gen**, **Non-Heap**, **JVM Process**, **Risk Level**) can be dragged into any order:

1. Click and hold any card.
2. Drag it over another card — a blue outline shows the drop target.
3. Release to place the card before or after the target.

The chosen order is saved to `localStorage` under the key `oom-card-order:<pane-id>` and is restored automatically on every subsequent page load or reconnect. Each target tab stores its order independently, so the `hostcontext` tab and the `liberty` tab can have different layouts.

To reset a tab's card order to the default, clear the relevant `localStorage` key in the browser's Developer Tools (Application → Local Storage).

### On-demand dumps from the dashboard

Each target tab contains an **On-Demand Diagnostics** section with three buttons:

| Button | Dump type | File written |
|---|---|---|
| **Thread Dump** | `THREAD` | `<dump-dir>/<target>_threads_<timestamp>.txt` |
| **Heap Dump** | `HEAP` | `<dump-dir>/<target>_heap_<timestamp>.hprof` |
| **Core Dump** | `CORE` | `<dump-dir>/<target>_core_<timestamp>.dmp` (Linux/J9 only) |

Click a button on any tab to trigger the dump **for that specific target** immediately — without waiting for a `CRITICAL` threshold to fire. The dump is written to the configured `--dump-dir` directory (or `dump-dir` in `targets.properties` if set). The dashboard shows the full path once the dump completes, or an error message if it fails.

> **Multi-target / remote JVM note:** In daemon mode, dump buttons invoke the dump **on the remote target JVM** via JMX (`HotSpotDiagnosticMXBean.dumpHeap` for heap, `DiagnosticCommand.threadPrint` for thread, `DiagnosticCommand.systemDump` for core on J9/OpenJ9). The resulting file is written on the **watchdog server's** filesystem (the machine running `oom-watchdog.jar`), not on the remote JVM's host, because it is the watchdog process that writes the file after receiving the dump data over JMX.

### CORS — accessing a remote JVM

The metrics server includes `Access-Control-Allow-Origin: *` on every response, so
`dashboard.html` can poll a watchdog running on a different host — just enter
`https://<remote-host>:<port>` in the Server field.

> **Security:** The metrics endpoint has no authentication. Only bind to loopback when
> the watchdog host is publicly reachable. If you need network access, place it behind
> a reverse proxy with authentication (e.g. nginx `auth_basic`) and supply a real certificate
> via `--metrics-cert`.

### JSON metrics schema

The `/metrics` endpoint returns:

```json
{
  "timestampMs":      1234567890123,
  "processName":      "12345@myhost",
  "targetName":       null,
  "riskLevel":        "WARNING",
  "heapUsedMB":       512.30,
  "heapMaxMB":        1024.00,
  "heapUsedPct":      50.03,
  "nonHeapUsedMB":    64.10,
  "gcOverheadPct":    3.40,
  "totalGcTimeMs":    1230,
  "jvmUptimeMs":      3600000,
  "nurseryUsedMB":    128.00,
  "nurseryUsedPct":   12.50,
  "critThresholdPct": 90.00,
  "diagnosisNotes":   "[Assessment] WARNING – …",
  "heapDumpPath":     null,
  "javaHome":         "/usr/lib/jvm/java-21-openjdk",
  "javaVersion":      "21.0.3 (Eclipse Adoptium)",
  "jvmName":          "OpenJDK 64-Bit Server VM 21.0.3+9",
  "osName":           "Linux 5.15.0 (amd64)",
  "cpuCount":         8,
  "processCpuPct":    12.40,
  "processCpuMs":     45230,
  "jvmInputArgs":     "-Xmx512m -Xms128m",
  "javaCommand":      "com.example.MyApp --port 8080",
  "threadCount":      42,
  "peakThreadCount":  55,
  "gcCounts":         { "G1 Young Generation": 42 },
  "poolUsedMB":       { "G1 Eden Space": 64.00 }
}
```

All numeric fields are rounded to 2 decimal places. `heapDumpPath` is `null` unless a
dump was taken in the current episode. `nurseryUsedMB` / `nurseryUsedPct` are `0` when
no young-gen pool is detected (e.g. ZGC or Epsilon GC). `processCpuPct` and
`processCpuMs` are `-1` on JVMs that do not expose `com.sun.management.OperatingSystemMXBean`
(e.g. some IBM J9 builds). All process-detail fields (`javaHome`, `jvmName`, etc.) are
populated for every target — both the self-watchdog and all remote targets in daemon mode.

### Rebranding the dashboard

`dashboard.html` is designed to be rebranded with zero code changes. Every colour,
font, corner radius, header text, and logo is controlled by a small CSS custom-property
block and two clearly marked HTML sections at the top of the file.

See **[BRANDING.md](BRANDING.md)** for:

- Full token reference table (mandatory and optional tokens)
- Step-by-step instructions for showing an org logo (SVG, data URI, or external URL)
- Three ready-to-paste theme presets in the `branding/` directory:
  - `branding/theme-dark-default.html` — factory dark theme
  - `branding/theme-light-corporate.html` — light page with dark navy header
  - `branding/theme-ibm-carbon.html` — IBM Carbon Design System inspired
- Copy-paste colour presets (minimal accent swap, navy enterprise, high-contrast)
- Light-theme checklist (hardcoded colours that also need updating)
- FAQ (fonts, persistence across upgrades, high-DPI logos)

### Daemon mode + dashboard

In `--daemon` mode the watchdog monitors multiple remote JVMs. The metrics endpoint
reflects the **most recently assessed** snapshot across all targets. For per-target
dashboards, start separate watchdog processes each with their own `--metrics-port`.

---

## Project layout

```
oom-watchdog/
├── pom.xml                          Parent POM (modules: core, test-harness, oom-watchdog-tests)
├── ARCHITECTURE.md                  Architecture with Mermaid diagrams
├── README.md                        This file
├── dashboard.html                   Self-contained real-time JVM dashboard (open in browser)
├── BRANDING.md                      Rebranding guide — tokens, logo, theme presets
├── branding/                        Ready-to-paste theme presets (dark, light, Carbon)
├── make-installer.sh                Generates oom-watchdog-installer.sh from built JARs
├── core/                            → oom-watchdog.jar (fat jar)
│   └── src/main/java/com/trongus/oom/
│       ├── WatchdogMain.java        CLI entry point + --metrics-port wiring
│       ├── alert/                   AlertChannel + 6 impls + AlertFormatter (i18n)
│       ├── collector/               MxBeanDiagnosticsCollector
│       ├── config/                  WatchdogConfig (immutable builder, locale)
│       ├── diagnosis/               OomCause + OomCauseCategory + OomCauseAnalyser
│       ├── dump/                    CompositeDumpService + 6 strategies
│       ├── i18n/                    Messages (UTF-8 ResourceBundle wrapper)
│       ├── model/                   JvmSnapshot + OomRiskLevel
│       ├── monitor/                 OomWatchdog + ThresholdRiskAssessor + MetricsHttpServer
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
└── oom-watchdog-tests/              JUnit 4 test suite (267 tests)
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
