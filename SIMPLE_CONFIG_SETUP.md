# OOM Watchdog — Simple Setup Guide

> **Not a developer?  No problem.** This guide walks you through every step using plain English.
> No coding required for the basic setup — just copy, paste, and follow the steps below.

---

## 📍 Start Here

**What is OOM Watchdog?**

OOM Watchdog is a small program that watches your Java application and warns you *before* it runs out of memory and crashes.  When something looks wrong it can:

- Write a warning to a log file on your server
- Send an alert to IBM QRadar (your security console)
- Capture a diagnostic snapshot (heap dump, thread dump) so engineers can diagnose the problem later
- Display live health on a browser dashboard

**What do you need?**

| Requirement | Details |
|---|---|
| Java | Version 8 or newer — the same Java your application already uses |
| The watchdog JAR | `oom-watchdog.jar` — one file, no installation |
| 2 minutes | That is genuinely all it takes for the basic setup |

**Download the JAR**

Go to: `https://github.com/kgillard/oom-watchdog/releases/latest`

Download `oom-watchdog.jar` and save it somewhere easy to find, for example:

| Operating System | Suggested location |
|---|---|
| **Mac** | `~/Downloads/oom-watchdog.jar` or `/opt/oom-watchdog/oom-watchdog.jar` |
| **Windows** | `C:\oom-watchdog\oom-watchdog.jar` |
| **Linux** | `/opt/oom-watchdog/oom-watchdog.jar` |

---

## Step 1 — Verify Java is installed

Open a terminal (Mac/Linux) or Command Prompt (Windows) and run:

```
java -version
```

You should see output like `java version "17.0.x"` or similar.  Any version from 8 upward works.

> **If you see "java is not recognized" or "command not found":**
> Java is not installed or not on your PATH.  Ask your IT team to install Java before continuing.

---

## Step 2 — Run a quick test to confirm everything works

This test simulates an out-of-memory situation inside the watchdog itself.  **It does not affect your application** — it is completely safe to run anywhere.

### Mac / Linux

```bash
java -Xmx256m -jar /opt/oom-watchdog/oom-watchdog.jar \
    --test-mode \
    --warn-threshold 0.50 \
    --crit-threshold 0.70 \
    --poll-ms 1000 \
    --test-leak-secs 10
```

### Windows

```cmd
java -Xmx256m -jar C:\oom-watchdog\oom-watchdog.jar ^
    --test-mode ^
    --warn-threshold 0.50 ^
    --crit-threshold 0.70 ^
    --poll-ms 1000 ^
    --test-leak-secs 10
```

**What you will see:**

The watchdog will print `WARNING` and `CRITICAL` alerts to the screen as simulated memory pressure builds up, then exit cleanly.  If you see those lines, everything is working correctly.

---

## Step 3 — Understand the key options

You do not need to memorise these.  Come back here when you need to change something.

| Option | What it does | Example value |
|--------|-------------|---------------|
| `--warn-threshold` | Percentage of memory used before a WARNING alert fires. `0.80` means 80%. | `0.80` |
| `--crit-threshold` | Percentage used before a CRITICAL alert fires. Must be higher than warn. | `0.90` |
| `--gc-threshold` | How much time the garbage collector is allowed to spend before alerting. `0.50` means 50% of CPU time. | `0.50` |
| `--poll-ms` | How often (in milliseconds) the watchdog checks memory. `5000` = every 5 seconds. | `5000` |
| `--dump-dir` | Where to save diagnostic files if a critical event occurs. | `/var/dumps` or `C:\dumps` |
| `--dump-types` | Which diagnostic files to capture. Choose from: `HEAP`, `THREAD`, `CLASS_HISTOGRAM`, `CORE`. | `HEAP,THREAD` |
| `--log-file` | A file where every alert is saved for later review. | `/var/log/oom.log` |
| `--qradar-host` | The IP address or hostname of your QRadar server (only needed if you use QRadar). | `192.168.1.50` |
| `--qradar-port` | The network port QRadar listens on. Default is `514`. | `514` |
| `--qradar-tcp` | Add this flag (no value) to use TCP instead of UDP to send to QRadar. | _(flag only)_ |
| `--metrics-port` | Enables the live dashboard on this port number. | `9090` |
| `--metrics-bind-all` | Allow connections from other machines (binds to all network interfaces). Without this the dashboard only works on the same machine as the watchdog. | _(flag only)_ |
| `--metrics-no-tls` | Add this flag to use plain HTTP for the dashboard instead of HTTPS. | _(flag only)_ |
| `--daemon` | Run in remote monitoring mode — monitors other JVMs over the network. | _(flag only)_ |
| `--targets-file` | Points to a configuration file listing which remote JVMs to monitor. | `./targets.properties` |
| `--log-level` | How much detail to write to the internal diagnostic log. `INFO` is normal. | `INFO` |
| `--help` | Prints all options and exits. | _(flag only)_ |

---

## Step 4 — Choose your scenario

Jump to the section that matches how you want to use OOM Watchdog:

| I want to… | Go to |
|---|---|
| Monitor my own application on the same machine | [Scenario A — Standalone (same JVM)](#scenario-a--standalone-same-jvm) |
| Monitor a running application over the network | [Scenario B — Remote daemon mode](#scenario-b--remote-daemon-mode) |
| Send alerts to IBM QRadar | [Scenario C — QRadar integration](#scenario-c--qradar-integration) |
| Monitor WebSphere Application Server (traditional) | [Scenario D — WebSphere Application Server](#scenario-d--websphere-application-server) |
| Monitor WebSphere Liberty or Open Liberty | [Scenario E — WebSphere Liberty / Open Liberty](#scenario-e--websphere-liberty--open-liberty) |
| Monitor IBM Cognos Analytics | [Scenario F — IBM Cognos Analytics](#scenario-f--ibm-cognos-analytics) |
| Monitor any Java application or JVM | [Scenario G — Any Java application](#scenario-g--any-java-application) |
| See a live browser dashboard | [Scenario H — Live dashboard (dashboard.html)](#scenario-h--live-dashboard-dashboardhtml) |
| Run the test harness | [Scenario I — Test harness](#scenario-i--test-harness) |

---

## Scenario A — Standalone (same JVM)

**What this does:** Runs the watchdog inside the same JVM process as your application.  This is the simplest option and requires no network ports.

**When to use this:** Your application is a single JAR or `.war` file and you can modify its startup command.

### How it works

You add OOM Watchdog to your application's Java startup command using `-javaagent` or by changing the main class.  The simplest approach is to run the watchdog JAR directly alongside your app.

### Mac / Linux — minimal

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --warn-threshold 0.80 \
    --crit-threshold 0.90 \
    --log-file /var/log/oom-watchdog.log
```

### Mac / Linux — with dumps and QRadar

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --warn-threshold 0.80 \
    --crit-threshold 0.90 \
    --gc-threshold 0.50 \
    --poll-ms 5000 \
    --dump-dir /var/dumps \
    --dump-types HEAP,THREAD \
    --log-file /var/log/oom-watchdog.log \
    --qradar-host 192.168.1.50 \
    --qradar-port 514
```

### Windows — minimal

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --warn-threshold 0.80 ^
    --crit-threshold 0.90 ^
    --log-file C:\logs\oom-watchdog.log
```

### Windows — with dumps and QRadar

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --warn-threshold 0.80 ^
    --crit-threshold 0.90 ^
    --gc-threshold 0.50 ^
    --poll-ms 5000 ^
    --dump-dir C:\dumps ^
    --dump-types HEAP,THREAD ^
    --log-file C:\logs\oom-watchdog.log ^
    --qradar-host 192.168.1.50 ^
    --qradar-port 514
```

> **Tip — Mac/Linux line continuation:** The `\` at the end of each line is just a way to split
> a long command across multiple lines for readability.  You can put it all on one line too.
> On Windows, use `^` instead of `\` for the same purpose.

---

## Scenario B — Remote daemon mode

**What this does:** Runs OOM Watchdog as a separate process that monitors one or more other JVMs over the network using a standard protocol called JMX.

**When to use this:** You want to monitor multiple running applications from a single central place, or you cannot modify the startup command of the target application.

### Part 1 — Enable JMX on the application you want to monitor

Add these flags to the startup command of your **target application** (the one you want to watch).  You only need to do this once.

#### Mac / Linux

```bash
java -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=7777 \
     -Dcom.sun.management.jmxremote.rmi.port=7777 \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Djava.rmi.server.hostname=127.0.0.1 \
     -jar /path/to/your-application.jar
```

#### Windows

```cmd
java -Dcom.sun.management.jmxremote ^
     -Dcom.sun.management.jmxremote.port=7777 ^
     -Dcom.sun.management.jmxremote.rmi.port=7777 ^
     -Dcom.sun.management.jmxremote.ssl=false ^
     -Dcom.sun.management.jmxremote.authenticate=false ^
     -Djava.rmi.server.hostname=127.0.0.1 ^
     -jar C:\path\to\your-application.jar
```

> Change `7777` to any free port number on your system.  Both `jmxremote.port` and `jmxremote.rmi.port` should be set to the **same** number.

### Part 2 — Create a targets.properties file

Create a plain text file called `targets.properties`.  Add one entry per application you want to monitor.  Replace `localhost` with the actual hostname or IP address if the application runs on a different machine, and replace `7777` with the port you chose above.

```properties
# targets.properties
# One block per application. Change the name (e.g. "myapp") to anything you like.

target.myapp.jmx-url   = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi
target.myapp.warn      = 0.80
target.myapp.crit      = 0.90
target.myapp.poll-ms   = 5000
target.myapp.dump-types = heap,thread
target.myapp.dump-dir  = ./dumps/myapp
```

Save this file somewhere convenient, for example:

- Mac/Linux: `/opt/oom-watchdog/targets.properties`
- Windows: `C:\oom-watchdog\targets.properties`

### Part 3 — Start the remote watchdog

#### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file /opt/oom-watchdog/targets.properties \
    --log-file /var/log/oom-watchdog.log
```

#### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file C:\oom-watchdog\targets.properties ^
    --log-file C:\logs\oom-watchdog.log
```

The watchdog will print a line for each connected target and then run silently in the background, writing alerts to the log file whenever memory pressure is detected.

---

## Scenario C — QRadar integration

**What this does:** Sends structured LEEF 2.0 syslog alerts to IBM QRadar every time a WARNING or CRITICAL event occurs.  Events appear in QRadar Log Activity and can trigger custom rules.

**Prerequisites:** You need to know the IP address or hostname of your QRadar console and the syslog port it listens on (default is `514`).

### Standalone mode (single application) — Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --warn-threshold 0.80 \
    --crit-threshold 0.90 \
    --log-file /var/log/oom-watchdog.log \
    --qradar-host 192.168.1.50 \
    --qradar-port 514
```

### Standalone mode — Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --warn-threshold 0.80 ^
    --crit-threshold 0.90 ^
    --log-file C:\logs\oom-watchdog.log ^
    --qradar-host 192.168.1.50 ^
    --qradar-port 514
```

### Using TCP instead of UDP (add `--qradar-tcp`)

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --qradar-host 192.168.1.50 \
    --qradar-port 514 \
    --qradar-tcp \
    --log-file /var/log/oom-watchdog.log
```

### Remote daemon mode with QRadar — targets.properties

Add LEEF customisation keys to give each monitored component its own identity in QRadar Log Activity:

```properties
# QRadar hostcontext
target.hostcontext.jmx-url           = service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi
target.hostcontext.warn              = 0.75
target.hostcontext.crit              = 0.85
target.hostcontext.dump-types        = heap,thread
target.hostcontext.dump-dir          = /var/dumps/hostcontext
target.hostcontext.leef-category     = JVM_OOM_QRadar_hostcontext
target.hostcontext.leef-tags         = env=prod,component=hostcontext

# QRadar Tomcat (UI / API)
target.tomcat.jmx-url                = service:jmx:rmi:///jndi/rmi://localhost:8090/jmxrmi
target.tomcat.warn                   = 0.80
target.tomcat.crit                   = 0.90
target.tomcat.dump-types             = heap,thread,class_histogram
target.tomcat.leef-category          = JVM_OOM_QRadar_tomcat
target.tomcat.leef-tags              = env=prod,component=tomcat
```

Then start the daemon as shown in [Scenario B — Part 3](#part-3--start-the-remote-watchdog), adding the QRadar flags:

#### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file /opt/oom-watchdog/targets.properties \
    --qradar-host 192.168.1.50 \
    --qradar-port 514 \
    --log-file /var/log/oom-watchdog.log
```

#### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file C:\oom-watchdog\targets.properties ^
    --qradar-host 192.168.1.50 ^
    --qradar-port 514 ^
    --log-file C:\logs\oom-watchdog.log
```

---

## Scenario D — WebSphere Application Server

**Traditional WAS (8.5.x / 9.0.x)**

### Step 1 — Enable JMX on WAS

Add these JVM arguments to your WAS server via the WebSphere Admin Console:
**Servers → Server Types → WebSphere application servers → [your server] → Process definition → Java Virtual Machine → Generic JVM arguments**

```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=8880
-Dcom.sun.management.jmxremote.rmi.port=8880
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=<YOUR_WAS_HOST_IP>
```

Restart the server after saving.

### Step 2 — Create targets.properties

```properties
target.was.jmx-url      = service:jmx:rmi:///jndi/rmi://<YOUR_WAS_HOST>:8880/jmxrmi
target.was.warn         = 0.80
target.was.crit         = 0.90
target.was.poll-ms      = 5000
target.was.dump-types   = heap,thread
target.was.dump-dir     = /tmp/dumps/was
```

Replace `<YOUR_WAS_HOST>` with the hostname or IP of the server running WAS.

### Step 3 — Run OOM Watchdog

#### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file /opt/oom-watchdog/targets.properties \
    --log-file /var/log/oom-watchdog.log
```

#### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file C:\oom-watchdog\targets.properties ^
    --log-file C:\logs\oom-watchdog.log
```

> Alerts are formatted for WAS and written to the WAS `SystemErr.log` automatically when the watchdog detects it is monitoring a WAS JVM.

---

## Scenario E — WebSphere Liberty / Open Liberty

### Step 1 — Enable JMX in server.xml

Add the `localConnector-1.0` feature to your Liberty `server.xml`:

```xml
<featureManager>
    <feature>localConnector-1.0</feature>
</featureManager>
```

Or for remote JMX access over RMI, add this to the Liberty JVM options file (`jvm.options`):

```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9090
-Dcom.sun.management.jmxremote.rmi.port=9090
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=<LIBERTY_HOST_IP>
```

### Step 2 — Create targets.properties

```properties
target.liberty.jmx-url      = service:jmx:rmi:///jndi/rmi://<LIBERTY_HOST>:9090/jmxrmi
target.liberty.warn         = 0.75
target.liberty.crit         = 0.88
target.liberty.poll-ms      = 5000
target.liberty.dump-types   = heap,thread
target.liberty.dump-dir     = /tmp/dumps/liberty
```

### Step 3 — Run OOM Watchdog

#### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file /opt/oom-watchdog/targets.properties \
    --log-file /var/log/oom-watchdog.log
```

#### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file C:\oom-watchdog\targets.properties ^
    --log-file C:\logs\oom-watchdog.log
```

> Alerts are written to Liberty `messages.log` in JSON format automatically.

---

## Scenario F — IBM Cognos Analytics

### Step 1 — Enable JMX on the Cognos JVM

Locate the JVM startup script for the Cognos component you want to monitor (typically the Content Manager or Admin Task Center process).  Add the JMX flags to `cogconfig.prefs` or the relevant startup script:

```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.port=9300
-Dcom.sun.management.jmxremote.rmi.port=9300
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.authenticate=false
-Djava.rmi.server.hostname=<COGNOS_HOST_IP>
```

Restart the Cognos service after saving.

### Step 2 — Create targets.properties

```properties
target.cognos-cm.jmx-url      = service:jmx:rmi:///jndi/rmi://<COGNOS_HOST>:9300/jmxrmi
target.cognos-cm.warn         = 0.80
target.cognos-cm.crit         = 0.90
target.cognos-cm.poll-ms      = 5000
target.cognos-cm.dump-types   = heap,thread
target.cognos-cm.dump-dir     = /tmp/dumps/cognos
```

### Step 3 — Run OOM Watchdog

#### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file /opt/oom-watchdog/targets.properties \
    --log-file /var/log/oom-watchdog.log
```

#### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file C:\oom-watchdog\targets.properties ^
    --log-file C:\logs\oom-watchdog.log
```

> Alerts are written to a Cognos-style pipe-delimited log file automatically.

---

## Scenario G — Any Java application

OOM Watchdog works with any JVM — Spring Boot, Tomcat, JBoss/WildFly, Kafka, Elasticsearch, custom microservices, and anything else that runs on Java.

### If you can modify the startup command

Add the JMX flags as shown in [Scenario B — Part 1](#part-1--enable-jmx-on-the-application-you-want-to-monitor), then set up a `targets.properties` file and run in daemon mode.

### If you cannot modify the startup command (e.g. a vendor application)

1. Check whether the application already has JMX enabled (look for `-Dcom.sun.management.jmxremote.port` in its startup script or process list).
2. If yes, note the port number and use it in `targets.properties`.
3. If no, ask the application vendor or your IT team for the standard procedure to enable JMX for that product.

### Example — generic application targets.properties

```properties
# Replace <APP_HOST> with the hostname or IP of the server
# Replace <PORT> with the JMX port the application uses

target.myservice.jmx-url       = service:jmx:rmi:///jndi/rmi://<APP_HOST>:<PORT>/jmxrmi
target.myservice.warn          = 0.80
target.myservice.crit          = 0.90
target.myservice.poll-ms       = 5000
target.myservice.dump-types    = heap,thread
target.myservice.dump-dir      = ./dumps/myservice
```

---

## Scenario H — Live dashboard (dashboard.html)

The `dashboard.html` file is a single-page web application that connects to the watchdog's built-in metrics server and shows a live view of heap usage, GC overhead, risk level, and alert history.

---

### Before you start — requirements

| Requirement | Details |
|---|---|
| Java | Already required to run the watchdog |
| `netstat` or `ss` | To verify the port is listening on the server (built into Linux/macOS) |
| `nc` (netcat) | To test connectivity from your laptop to the server |
| `iptables` or `firewalld` | To open the port in the server's firewall (Linux servers only) |

> **These tools are standard on Linux and macOS.**  On Windows Server use `netstat -ano` and Windows Firewall / `netsh` instead.

---

### Step 1 — Start the watchdog with the metrics server enabled

Add `--metrics-port` to your command.  The watchdog will start a small HTTPS server on that port.

> **Opening the dashboard on the same machine as the watchdog?**  No firewall changes are needed — use the local commands below.
>
> **Opening the dashboard on a different machine** (e.g. your laptop connecting to a remote server)?
> You need **both** of the following or the browser will get `ERR_CONNECTION_REFUSED`:
> 1. Start the watchdog with `--metrics-bind-all` (binds to all interfaces, not just `127.0.0.1`)
> 2. Open the port in the server's firewall (see Step 3 below)

#### Mac / Linux — local (same machine)

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --warn-threshold 0.80 \
    --crit-threshold 0.90 \
    --metrics-port 9090 \
    --log-file /var/log/oom-watchdog.log
```

#### Mac / Linux — remote access (dashboard on a different machine)

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --warn-threshold 0.80 \
    --crit-threshold 0.90 \
    --metrics-port 9090 \
    --metrics-bind-all \
    --log-file /var/log/oom-watchdog.log
```

#### Windows — local (same machine)

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --warn-threshold 0.80 ^
    --crit-threshold 0.90 ^
    --metrics-port 9090 ^
    --log-file C:\logs\oom-watchdog.log
```

#### Windows — remote access (dashboard on a different machine)

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --warn-threshold 0.80 ^
    --crit-threshold 0.90 ^
    --metrics-port 9090 ^
    --metrics-bind-all ^
    --log-file C:\logs\oom-watchdog.log
```

> By default the metrics endpoint uses HTTPS with an automatically generated self-signed certificate.  Your browser will show a security warning the first time — this is expected for internal tools.  Click **Advanced → Proceed** (Chrome) or **Accept the Risk** (Firefox).

#### Using plain HTTP instead (less secure, easier for quick testing)

Add `--metrics-no-tls`:

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --metrics-port 9090 \
    --metrics-no-tls \
    --metrics-bind-all \
    --log-file /var/log/oom-watchdog.log
```

#### Using your own SSL certificate

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --metrics-port 9090 \
    --metrics-cert /etc/ssl/oom-watchdog.p12 \
    --metrics-cert-password changeit \
    --metrics-bind-all \
    --log-file /var/log/oom-watchdog.log
```

---

### Step 2 — Verify the port is listening (on the server)

Run this **on the server** after starting the watchdog:

```bash
netstat -tlnp | grep 9090
```

**What to look for:**

| Output | Meaning |
|---|---|
| `0.0.0.0:9090` or `:::9090` | Correct — listening on all interfaces. Proceed to Step 3. |
| `127.0.0.1:9090` | Wrong — `--metrics-bind-all` was not used. Restart with that flag. |
| _(no output)_ | Watchdog is not running or failed to start. Check the log file. |

---

### Step 3 — Open the port in the server firewall (remote access only)

Even when the watchdog is listening on `0.0.0.0`, the server's OS firewall may still block incoming connections.  This is the most common cause of `ERR_CONNECTION_REFUSED` when the process is running.

#### Linux — iptables (RHEL, CentOS, QRadar)

```bash
# Open the port
iptables -I INPUT -p tcp --dport 9090 -j ACCEPT

# Save the rule so it survives a reboot
service iptables save
```

#### Linux — firewalld (newer RHEL / Fedora)

```bash
firewall-cmd --add-port=9090/tcp --permanent
firewall-cmd --reload
```

#### Windows Server — Windows Firewall

```cmd
netsh advfirewall firewall add rule name="OOM Watchdog Metrics" ^
    protocol=TCP dir=in localport=9090 action=allow
```

> Replace `9090` with whichever port you chose with `--metrics-port`.

---

### Step 4 — Test connectivity from your machine

Run this **on your laptop / the machine running the browser**, replacing the IP and port:

```bash
nc -zv 9.60.246.81 9090
```

**Expected output:**

```
Connection to 9.60.246.81 9090 port [tcp/*] succeeded!
```

If it says `Connection refused` — the firewall rule was not applied.  If it times out — a network-level firewall (e.g. cloud security group, VPN ACL) is blocking the traffic between your laptop and the server.

**On macOS** (if `nc` is not installed):

```bash
/usr/bin/nc -zv 9.60.246.81 9090
```

---

### Step 5 — Open the dashboard in your browser

1. Download `dashboard.html` from the GitHub releases page (same page as `oom-watchdog.jar`).
2. Open `dashboard.html` directly in any modern browser — no web server needed.
3. In the **Server** field at the top of the page, type the address of the watchdog:
   - Same machine (default): `https://localhost:9090`
   - Remote machine: `https://9.60.246.81:9090` (replace with your server's IP)
   - Plain HTTP (if you used `--metrics-no-tls`): `http://9.60.246.81:9090`
4. Click **Connect**.

The dashboard will start updating live every few seconds.

---

### Troubleshooting — `ERR_CONNECTION_REFUSED`

This error always means one of three things.  Work through them in order:

**1. The watchdog is not running**

On the server:
```bash
netstat -tlnp | grep 9090
```
If there is no output, the process is not running.  Start it and check the log file for errors.

**2. The watchdog is bound to `127.0.0.1` only**

If `netstat` shows `127.0.0.1:9090`, the watchdog was started without `--metrics-bind-all`.  Kill it and restart with that flag.

**3. The OS firewall is blocking the port**

If `netstat` shows `0.0.0.0:9090` or `:::9090` but `nc -zv <ip> 9090` still fails — the firewall is the problem.  Apply the iptables or firewalld rule from Step 3 above.

If `nc` succeeds but the dashboard still shows the error — your browser's self-signed certificate warning needs to be accepted first.  Navigate directly to `https://9.60.246.81:9090/metrics` in the browser, accept the certificate warning, then reload the dashboard.

---

### Dashboard features at a glance

| Panel | What it shows |
|---|---|
| Risk level indicator | Current status: OK (green) / WARNING (amber) / CRITICAL (red) |
| Heap gauge | Heap used as a percentage of maximum |
| GC overhead | Fraction of CPU time spent garbage collecting |
| Post-GC growth | Whether memory is gradually leaking (trending upward) |
| Memory Pools | All JVM memory pool usages in MB |
| GC Collections | Per-collector invocation counts |
| **On-Demand Diagnostics** | **Thread Dump**, **Heap Dump**, and **Core Dump** buttons |
| Alert history | Last N alerts with timestamps and diagnosis text |

### Triggering a dump from the dashboard

Each target tab in the dashboard has an **On-Demand Diagnostics** section.  You do not have to wait for a CRITICAL alert — you can request a dump at any time:

1. Make sure the watchdog is connected (green status dot in the dashboard).
2. If you have multiple targets, click the tab for the JVM you want to diagnose.
3. Click one of the three buttons:
   - **Thread Dump** — captures all thread stack traces (works on every JVM, always safe to run)
   - **Heap Dump** — captures the full heap object graph (`.hprof` file, can be large)
   - **Core Dump** — captures a full OS process dump (Linux / IBM J9 only)
4. The dashboard shows a green confirmation with the full file path once the dump is written, or a red error message if it could not be produced.

> **Where is the file?**  The dump is written to the `--dump-dir` directory on the **server running the watchdog**, not on your laptop.  The dashboard shows the exact path after the dump completes.

> **Core dump availability:**  Core dumps require either IBM J9/OpenJ9 or the `gcore` tool to be installed on the server.  On Windows or with standard HotSpot the button will return an error — use Thread Dump or Heap Dump instead.

---

## Scenario I — Test harness

The test harness is a separate tool that deliberately leaks memory to verify that your watchdog configuration is correct and that alerts are reaching their destination.

**Download:** Get `test-harness.jar` from the same GitHub releases page as `oom-watchdog.jar`.

### Running the test harness

The test harness runs as a separate process that you monitor using OOM Watchdog in remote daemon mode.

#### Step 1 — Start the test harness with JMX enabled

##### Mac / Linux

```bash
java -Xmx256m \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9100 \
     -Dcom.sun.management.jmxremote.rmi.port=9100 \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Djava.rmi.server.hostname=127.0.0.1 \
     -jar /opt/oom-watchdog/test-harness.jar
```

##### Windows

```cmd
java -Xmx256m ^
     -Dcom.sun.management.jmxremote ^
     -Dcom.sun.management.jmxremote.port=9100 ^
     -Dcom.sun.management.jmxremote.rmi.port=9100 ^
     -Dcom.sun.management.jmxremote.ssl=false ^
     -Dcom.sun.management.jmxremote.authenticate=false ^
     -Djava.rmi.server.hostname=127.0.0.1 ^
     -jar C:\oom-watchdog\test-harness.jar
```

#### Step 2 — Create a targets.properties for the test harness

```properties
target.harness.jmx-url      = service:jmx:rmi:///jndi/rmi://localhost:9100/jmxrmi
target.harness.warn         = 0.50
target.harness.crit         = 0.70
target.harness.poll-ms      = 1000
target.harness.dump-types   = heap,thread
target.harness.dump-dir     = ./dumps/harness
```

Using lower thresholds (`0.50` / `0.70`) means you will see alerts faster without needing to push memory all the way to 80–90%.

#### Step 3 — Start OOM Watchdog monitoring the test harness

Open a **second terminal window** and run:

##### Mac / Linux

```bash
java -jar /opt/oom-watchdog/oom-watchdog.jar \
    --daemon \
    --targets-file ./targets.properties \
    --metrics-port 9090 \
    --metrics-no-tls \
    --log-file ./harness-watchdog.log
```

##### Windows

```cmd
java -jar C:\oom-watchdog\oom-watchdog.jar ^
    --daemon ^
    --targets-file .\targets.properties ^
    --metrics-port 9090 ^
    --metrics-no-tls ^
    --log-file .\harness-watchdog.log
```

Watch the log file for WARNING and CRITICAL alerts as the test harness consumes memory.  Open `dashboard.html` in your browser pointing to `http://localhost:9090/metrics` to see it all visualised live.

---

## Complete option reference

The table below lists every available option.  You only need the ones relevant to your scenario.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--warn-threshold` | `0.0–1.0` | `0.80` | Heap used / max ratio that fires a WARNING alert |
| `--crit-threshold` | `0.0–1.0` | `0.90` | Heap used / max ratio that fires a CRITICAL alert (must be > warn) |
| `--gc-threshold` | `0.0–1.0` | `0.50` | GC CPU fraction that contributes to risk escalation |
| `--gc-dump-threshold` | `0.0–1.0` | _(off)_ | GC overhead ratio that triggers an immediate diagnostic dump |
| `--heap-dump-threshold` | `0.0–1.0` | _(off)_ | Heap ratio that triggers an immediate dump even below CRITICAL |
| `--poll-ms` | milliseconds | `5000` | How often to check memory.  Minimum: `100` |
| `--dump-dir` | path | `./dumps` | Directory where diagnostic files are written |
| `--dump-types` | list | _(none)_ | `HEAP`, `THREAD`, `CLASS_HISTOGRAM`, `CORE` — comma-separated |
| `--log-file` | path | `./oom-watchdog.log` | File where every alert is appended |
| `--log-level` | keyword | `INFO` | `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`, `FINER`, `FINEST` |
| `--qradar-host` | hostname/IP | _(off)_ | QRadar syslog destination.  QRadar is disabled if this is not set |
| `--qradar-port` | port | `514` | QRadar / syslog destination port |
| `--qradar-tcp` | flag | UDP | Add this flag to use TCP instead of UDP |
| `--metrics-port` | port | _(off)_ | Start the live metrics endpoint on this port |
| `--metrics-bind-all` | flag | loopback | Bind to all interfaces — required for remote browser access |
| `--metrics-cert` | path | _(auto)_ | Path to a PKCS#12 or JKS keystore for HTTPS |
| `--metrics-cert-password` | text | _(empty)_ | Password for the keystore file |
| `--metrics-no-tls` | flag | HTTPS | Add this flag to use plain HTTP instead of HTTPS |
| `--daemon` | flag | off | Run in multi-target remote monitoring mode |
| `--targets-file` | path | `./targets.properties` | Configuration file for daemon mode |
| `--test-mode` | flag | off | Run a built-in OOM simulation and exit |
| `--test-leak-secs` | seconds | `20` | Duration of the slow-leak phase in test mode |
| `--help` | flag | — | Print all options and exit |

---

## Frequently asked questions

**Q: Do I need to restart my application to use OOM Watchdog?**
A: Only if you want to enable remote JMX monitoring on the target application and it does not already have JMX enabled.  Adding JMX flags requires a restart of the target.  OOM Watchdog itself can be started and stopped at any time without affecting the monitored application.

**Q: Will OOM Watchdog slow down my application?**
A: No.  The watchdog reads data that the JVM already collects internally via standard management interfaces.  The overhead is negligible — a few milliseconds per poll, which by default is every 5 seconds.

**Q: I see a browser security warning when opening the dashboard.  Is that normal?**
A: Yes, when using the default self-signed certificate.  Click through the warning — it is safe for internal use.  To avoid it permanently, provide your own certificate with `--metrics-cert`.

**Q: My QRadar is not receiving events.  What should I check?**
A: (1) Confirm the `--qradar-host` IP is reachable from the watchdog server. (2) Check that port `514` (or your chosen port) is not blocked by a firewall. (3) Try adding `--qradar-tcp` — some networks block UDP syslog. (4) Check the watchdog log file for `QRadar` error messages.

**Q: How do I stop the watchdog?**
A: Press `Ctrl+C` in the terminal where it is running, or send a `SIGTERM` signal to the process.  The watchdog shuts down cleanly without any risk to the monitored application.

**Q: Where are dump files saved?**
A: In the directory specified by `--dump-dir` (default: `./dumps` relative to where you ran the command).  In daemon mode each target gets its own subfolder named after the target (e.g. `./dumps/was`).

**Q: Can I monitor multiple applications at the same time?**
A: Yes — that is exactly what daemon mode is for.  Add one block per application in `targets.properties` and start a single watchdog with `--daemon`.

---

## Quick-reference cheat sheet

```
# Test it works (safe, any machine)
java -Xmx256m -jar oom-watchdog.jar --test-mode --warn-threshold 0.50 --crit-threshold 0.70 --poll-ms 1000 --test-leak-secs 10

# Standalone monitoring with file logging
java -jar oom-watchdog.jar --warn-threshold 0.80 --crit-threshold 0.90 --log-file ./oom.log

# Add QRadar
java -jar oom-watchdog.jar --warn-threshold 0.80 --crit-threshold 0.90 --log-file ./oom.log --qradar-host 192.168.1.50

# Add live dashboard
java -jar oom-watchdog.jar --warn-threshold 0.80 --crit-threshold 0.90 --metrics-port 9090 --metrics-no-tls

# Remote daemon mode (most common for production)
java -jar oom-watchdog.jar --daemon --targets-file ./targets.properties --log-file ./oom.log

# See all options
java -jar oom-watchdog.jar --help
```
