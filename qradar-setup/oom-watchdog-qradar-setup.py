#!/usr/bin/env python3
"""
oom-watchdog-qradar-setup.py
─────────────────────────────────────────────────────────────────────────────
Idempotent setup script for OOM Watchdog → QRadar LEEF integration.

Run this script once on every new QRadar environment before deploying
OOM Watchdog.  It is safe to re-run: it checks whether each resource
already exists before creating or modifying it.

What it does
────────────
  1.  Creates (or verifies) four custom QID event records:

        QID name       QID      Severity  Category
        ─────────────  ───────  ────────  ─────────────────────────────
        OOM_WARNING    2000001  5         System / Warning
        OOM_CRITICAL   2000002  9         System / Critical
        OOM_FIRING     2000003  10        System / Emergency
        OOM-Watchdog   2000004  7         System / Alert  ← catch-all

      IMPORTANT: QRadar's Universal LEEF DSM (type_id=212) does NOT
      use dsm_event_mappings for EventID lookup — it always assigns
      QID 55000002 ("unknown") to every event.  The three per-risk-level
      QIDs above are used only when the Log Source Extension XML is
      attached (see Step 4 below and OomWatchdog-LogSourceExtension.xml).
      Without the extension, all events show as "OOM-Watchdog" once the
      extension is attached with the catch-all match.

  2.  Creates (or verifies) the log source "QRadar_OOM @ <hostname>"
      of type Universal LEEF (type_id=212) with the correct sending_ip
      and identifier so events from OOM Watchdog are mapped to this
      log source rather than an auto-discovered catch-all or another
      existing log source (e.g. McAfee NSP) that has parsing_order=1.

  3.  Scans ALL existing log sources that share the same sending_ip and
      reports any that are enabled with parsing_order=1 and a type that
      is NOT Universal LEEF — these will intercept OOM Watchdog events
      before the correct log source can match them.  You are given the
      choice to disable them.

  4.  (Manual — requires QRadar Console UI)
      Upload OomWatchdog-LogSourceExtension.xml as a Log Source Extension
      and attach it to the log source.  This is the ONLY way to make
      QRadar's Universal LEEF DSM assign named QIDs per EventID.

      Steps:
        Admin → Log Source Extensions → Add
          Name: OomWatchdog
          Upload: qradar-setup/OomWatchdog-LogSourceExtension.xml
        Admin → Log Sources → open "QRadar_OOM @ <host>"
          Set "Log Source Extension" = OomWatchdog → Save
        Admin → Deploy Changes

Usage
─────
    python3 oom-watchdog-qradar-setup.py \\
        --qradar-host  <QRadar console/EP IP>  \\
        --token        <QRadar API token>      \\
        --watchdog-host <IP or hostname of the machine running OOM Watchdog>

    Optional flags:
        --sending-ip   <IP>     Override the sending_ip to register.
                                Default: resolve from --watchdog-host.
        --log-source-name <n>   Override the log source name.
                                Default: "QRadar_OOM @ <watchdog-host>"
        --dry-run               Print what would be done, but make no changes.
        --skip-conflict-scan    Skip the interfering log source scan (step 3).

Requirements
────────────
    Python 3.6+, no third-party libraries.
    The API token must have "Admin" or "Security Admin" privileges.

Author:  Kristen Gillard <kristen.gillard@gmail.com>
Version: 1.7.13.32
"""

import argparse
import json
import socket
import sys
import urllib.error
import urllib.request
import ssl


# ─────────────────────────────────────────────────────────────────────────────
# QRadar low-level category IDs used for the custom QID records.
# These are standard built-in categories present on every QRadar deployment.
#   8054 = "Warning"   under high-level category "System" (8000)
#   8056 = "Critical"  under high-level category "System" (8000)
#   8060 = "Alert"     under high-level category "System" (8000)
#   8061 = "Emergency" under high-level category "System" (8000)
#
# NOTE: QRadar's Universal LEEF DSM (type_id=212) does NOT consult the
# dsm_event_mappings table for EventID-based QID lookup — it always assigns
# QID 55000002 ("unknown").  The QID records below are referenced by the
# OomWatchdog-LogSourceExtension.xml file which overrides this behaviour
# when uploaded via the QRadar Console UI (Admin → Log Source Extensions).
# ─────────────────────────────────────────────────────────────────────────────
QID_DEFINITIONS = [
    {
        "name":                "OOM_WARNING",
        "description":        "JVM heap approaching OOM threshold",
        "severity":           5,
        "low_level_category_id": 8054,   # System / Warning
    },
    {
        "name":                "OOM_CRITICAL",
        "description":        "JVM heap at critical level - OOM imminent",
        "severity":           9,
        "low_level_category_id": 8056,   # System / Critical
    },
    {
        "name":                "OOM_FIRING",
        "description":        "JVM out-of-memory kill is occurring or imminent",
        "severity":           10,
        "low_level_category_id": 8061,   # System / Emergency
    },
    {
        "name":                "OOM-Watchdog",
        "description":        "JVM OOM risk alert from OOM Watchdog. Inspect riskLevel, heapPct, and msg attributes.",
        "severity":           7,
        "low_level_category_id": 8060,   # System / Alert (catch-all)
    },
]

# Universal LEEF DSM type id — present on all QRadar deployments >= 7.3
UNIVERSAL_LEEF_TYPE_ID = 212

# Syslog protocol id (0 = Syslog)
SYSLOG_PROTOCOL_ID = 0


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def qradar_request(host, token, method, path, body=None):
    """Issue an authenticated QRadar REST API request, return parsed JSON."""
    url = f"https://{host}/api{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("SEC", token)
    req.add_header("Accept", "application/json")
    req.add_header("Version", "14.0")
    if data:
        req.add_header("Content-Type", "application/json")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(req, context=ctx) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return json.loads(raw)
        except Exception:
            raise RuntimeError(f"HTTP {e.code} from {url}: {raw[:300]}")


def ok(msg):    print(f"  ✅  {msg}")
def warn(msg):  print(f"  ⚠️   {msg}")
def info(msg):  print(f"  ℹ️   {msg}")
def err(msg):   print(f"  ❌  {msg}")
def h1(msg):    print(f"\n{'─'*70}\n  {msg}\n{'─'*70}")


def resolve_ip(hostname):
    """Resolve hostname to its first IPv4 address."""
    try:
        return socket.gethostbyname(hostname)
    except socket.gaierror:
        return hostname   # fall back to the string as-is


# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — QID records
# ─────────────────────────────────────────────────────────────────────────────

def setup_qid_records(host, token, dry_run):
    h1("Step 1 — Custom QID event records")
    print("""
  WHY THIS MATTERS
  ────────────────
  QRadar stores named event types in QID records.  Each incoming event is
  assigned a QID which determines the Event Name, category, and default
  severity shown in Log Activity.

  OOM Watchdog sends three LEEF EventID values:
    • OOM_WARNING  — heap is above the warning threshold
    • OOM_CRITICAL — heap is above the critical threshold
    • OOM_FIRING   — the JVM is actively being killed by the OS

  IMPORTANT LIMITATION: QRadar's Universal LEEF DSM (type_id=212) does NOT
  use the dsm_event_mappings table for LEEF EventID → QID lookup.  It always
  hardcodes QID 55000002 ("unknown") for every event regardless of EventID.

  The QID records created here are prerequisites for the Log Source Extension
  XML (OomWatchdog-LogSourceExtension.xml) which DOES perform the correct
  per-EventID mapping when attached via the QRadar Console UI.

  A fourth catch-all QID "OOM-Watchdog" is also created.  If you choose not
  to install the Log Source Extension, you can use the catch-all match in
  the XML variant to show "OOM-Watchdog" for all events instead of "unknown".
""")

    # Fetch all existing QID records to check for name collisions
    existing = qradar_request(host, token, "GET",
        "/data_classification/qid_records?range=0-5000")
    if isinstance(existing, dict) and "http_response" in existing:
        err(f"Failed to list QID records: {existing.get('message')}")
        return False

    existing_by_name = {r["name"]: r for r in existing}

    for defn in QID_DEFINITIONS:
        name = defn["name"]
        if name in existing_by_name:
            r = existing_by_name[name]
            ok(f"QID record '{name}' already exists  "
               f"(qid={r['qid']}  sev={r['severity']}  "
               f"llcat={r['low_level_category_id']}  id={r['id']})")
        else:
            info(f"Creating QID record '{name}' …")
            if dry_run:
                info("  [dry-run] would POST to /data_classification/qid_records")
                continue
            result = qradar_request(host, token, "POST",
                "/data_classification/qid_records", defn)
            if "id" in result:
                ok(f"Created '{name}'  qid={result['qid']}  id={result['id']}")
            else:
                err(f"Failed to create '{name}': {result.get('message', result)}")
                return False

    return True


# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — Log source
# ─────────────────────────────────────────────────────────────────────────────

def setup_log_source(host, token, watchdog_host, sending_ip, log_source_name, dry_run):
    h1("Step 2 — Log source (Universal LEEF)")
    print(f"""
  WHY THIS MATTERS
  ────────────────
  QRadar routes each incoming syslog/LEEF event to a log source by matching
  two things:

    1.  The TCP/UDP source IP of the connection  →  log source 'sending_ip'
    2.  The HOSTNAME field in the syslog header  →  log source 'identifier'

  OOM Watchdog puts the machine's FQDN/hostname in the syslog HOSTNAME field
  (from InetAddress.getLocalHost().getHostName()).  The log source 'identifier'
  must match this value exactly, including case.

  The sending_ip must match the IP address that QRadar's ecs syslog listener
  sees as the source of the TCP/UDP connection:
    • Remote host  →  use the watchdog machine's real NIC IP
    • Same-host    →  OOM Watchdog auto-routes TCP through loopback, so
                      QRadar sees source IP = 127.0.0.1

  Watchdog host:     {watchdog_host}
  Sending IP:        {sending_ip}
  Log source name:   {log_source_name}
  Identifier:        {watchdog_host}
""")

    all_sources = qradar_request(host, token, "GET",
        "/config/event_sources/log_source_management/log_sources?range=0-500")
    if isinstance(all_sources, dict) and "http_response" in all_sources:
        err(f"Failed to list log sources: {all_sources.get('message')}")
        return None

    # Look for an existing log source that already has this name or identifier
    existing = None
    for s in all_sources:
        if s.get("name") == log_source_name:
            existing = s
            break
        for ident in s.get("log_source_identifiers", []):
            if ident.get("identifier") == watchdog_host and \
               s.get("type_id") == UNIVERSAL_LEEF_TYPE_ID:
                existing = s
                break

    if existing:
        ok(f"Log source already exists: '{existing['name']}' (id={existing['id']})")
        ok(f"  type_id={existing['type_id']}  sending_ip={existing['sending_ip']}")
        ok(f"  enabled={existing['enabled']}  parsing_order={existing['parsing_order']}")
        ok(f"  status={existing['status']['status']}")

        # Check if sending_ip needs correction
        if existing["sending_ip"] != sending_ip:
            warn(f"  sending_ip is '{existing['sending_ip']}' but should be '{sending_ip}'")
            info("  Updating sending_ip …")
            if not dry_run:
                patched = qradar_request(host, token, "PUT",
                    f"/config/event_sources/log_source_management/log_sources/{existing['id']}",
                    {"sending_ip": sending_ip})
                if "id" in patched:
                    ok(f"  Updated sending_ip to {sending_ip}")
                else:
                    err(f"  Update failed: {patched.get('message', patched)}")
        return existing["id"]

    # Create the log source
    info(f"Log source '{log_source_name}' not found — creating …")
    payload = {
        "name":                    log_source_name,
        "type_id":                 UNIVERSAL_LEEF_TYPE_ID,
        "protocol_type_id":        SYSLOG_PROTOCOL_ID,
        "sending_ip":              sending_ip,
        "enabled":                 True,
        "coalesce_events":         False,
        "store_event_payload":     True,
        "credibility":             5,
        "target_event_collector_id": 7,
        "protocol_parameters": [
            {"name": "identifier", "id": 0, "value": watchdog_host},
            {"name": "incomingPayloadEncoding", "id": 1, "value": "UTF-8"},
        ],
    }

    if dry_run:
        info("[dry-run] would POST to /config/event_sources/log_source_management/log_sources")
        info(f"  payload: {json.dumps(payload, indent=4)}")
        return None

    result = qradar_request(host, token, "POST",
        "/config/event_sources/log_source_management/log_sources", payload)
    if "id" in result:
        ok(f"Created log source '{log_source_name}' (id={result['id']})")
        warn("  NOTE: run 'Deploy Changes' in the QRadar console (Admin → Deploy Changes)")
        warn("  or wait for the next auto-deploy cycle before events will map to this source.")
        return result["id"]
    else:
        err(f"Failed to create log source: {result.get('message', result)}")
        return None


# ─────────────────────────────────────────────────────────────────────────────
# Step 3 — Interfering log source scan
# ─────────────────────────────────────────────────────────────────────────────

def scan_interfering_sources(host, token, sending_ip, our_log_source_id, dry_run):
    h1("Step 3 — Interfering log source scan")
    print(f"""
  WHY THIS MATTERS
  ────────────────
  QRadar processes log sources in 'parsing_order' sequence.  When multiple
  log sources share the same sending_ip AND their identifier also matches the
  syslog HOSTNAME, the source with the LOWEST parsing_order wins.

  Auto-discovered log sources (e.g. McAfee NSP, Cisco ASA, generic Syslog)
  are frequently assigned parsing_order=1 because they were created before
  the OOM Watchdog log source.  When an auto-discovered source with
  parsing_order=1 is enabled and shares the same sending_ip, it consumes
  ALL events from that host — including OOM Watchdog LEEF events — before
  the correct log source ever sees them.

  Scanning for enabled log sources with sending_ip={sending_ip}
  that are NOT type_id={UNIVERSAL_LEEF_TYPE_ID} (Universal LEEF) …
""")

    all_sources = qradar_request(host, token, "GET",
        "/config/event_sources/log_source_management/log_sources?range=0-500")
    if isinstance(all_sources, dict) and "http_response" in all_sources:
        err(f"Failed to list log sources: {all_sources.get('message')}")
        return

    conflicts = []
    for s in all_sources:
        if s["id"] == our_log_source_id:
            continue
        if s.get("sending_ip") != sending_ip:
            continue
        if not s.get("enabled", False):
            info(f"  Already disabled: '{s['name']}' (id={s['id']}  type={s['type_id']}  po={s['parsing_order']})")
            continue
        if s.get("type_id") == UNIVERSAL_LEEF_TYPE_ID:
            info(f"  Same type (Universal LEEF), no conflict: '{s['name']}' (id={s['id']})")
            continue
        conflicts.append(s)

    if not conflicts:
        ok("No interfering log sources found.")
        return

    warn(f"Found {len(conflicts)} potentially interfering log source(s):")
    for s in conflicts:
        print(f"\n    id={s['id']}  type={s['type_id']}  parsing_order={s['parsing_order']}")
        print(f"    name:        {s['name']}")
        print(f"    sending_ip:  {s.get('sending_ip')}")
        print(f"    auto_disc:   {s.get('auto_discovered')}")
        print(f"    last_event:  {s.get('last_event_time')}")

    print()
    print("  These sources may intercept OOM Watchdog LEEF events before the")
    print("  Universal LEEF log source matches them, causing events to be")
    print("  parsed by the wrong DSM and show as unknown or misclassified.")
    print()

    if dry_run:
        info("[dry-run] would prompt to disable the above sources")
        return

    answer = input(
        f"  Disable these {len(conflicts)} interfering log source(s)? [y/N] "
    ).strip().lower()

    if answer == "y":
        for s in conflicts:
            result = qradar_request(host, token, "POST",
                f"/config/event_sources/log_source_management/log_sources/{s['id']}",
                {"enabled": False})
            if isinstance(result, dict) and result.get("enabled") is False:
                ok(f"  Disabled '{s['name']}' (id={s['id']})")
            else:
                err(f"  Failed to disable '{s['name']}': {result.get('message', result)}")
    else:
        warn("Skipped.  Events from OOM Watchdog may still be intercepted by these sources.")
        warn("You can manually disable them in QRadar: Admin → Log Sources → find by name.")


# ─────────────────────────────────────────────────────────────────────────────
# Summary
# ─────────────────────────────────────────────────────────────────────────────

def print_summary(watchdog_host, sending_ip, log_source_name):
    h1("Summary — QRadar configuration checklist")
    print(f"""
  ✅  QID records created/verified (via API):
        OOM_WARNING   (qid=2000001, severity=5,  category=System/Warning)
        OOM_CRITICAL  (qid=2000002, severity=9,  category=System/Critical)
        OOM_FIRING    (qid=2000003, severity=10, category=System/Emergency)
        OOM-Watchdog  (qid=2000004, severity=7,  category=System/Alert)

  ✅  Log source created/verified (via API):
        Name:         {log_source_name}
        Type:         Universal LEEF (type_id=212)
        Sending IP:   {sending_ip}
        Identifier:   {watchdog_host}

  ⚠️   MANUAL STEP REQUIRED — Log Source Extension (QRadar Console UI):
  ─────────────────────────────────────────────────────────────────────────
  QRadar's Universal LEEF DSM always assigns QID 55000002 ("unknown") to
  every event.  The dsm_event_mappings API does NOT override this — the DSM
  bypasses the mapping table entirely.

  To get named event types (OOM_WARNING / OOM_CRITICAL / OOM_FIRING) or a
  friendly catch-all name (OOM-Watchdog), you must attach a Log Source
  Extension XML to the log source via the QRadar Console UI:

    1.  Admin → Log Source Extensions → Add
          Name:        OomWatchdog
          Description: OOM Watchdog LEEF EventID to QID mapping
          Upload:      qradar-setup/OomWatchdog-LogSourceExtension.xml
        → Save

    2.  Admin → Log Sources → find "{log_source_name}"
          Log Source Extension: OomWatchdog
        → Save

    3.  Admin → Deploy Changes

  After deploy, events will show:
    OOM_WARNING / OOM_CRITICAL / OOM_FIRING  (three distinct event names)
  or "OOM-Watchdog" for all events if you use the SingleQID variant in the XML.

  Start OOM Watchdog on {watchdog_host}:
  ─────────────────────────────────────────────────────────────────────────
        java -jar oom-watchdog.jar \\
            --daemon \\
            --targets-file targets.properties \\
            --qradar-host {watchdog_host if watchdog_host != '127.0.0.1' else '<QRADAR_IP>'} \\
            --qradar-port 514 \\
            --log-level INFO \\
            --warn-threshold 0.80 \\
            --crit-threshold 0.90

  Create an offense rule (optional) — Offenses → Rules → Add:
  ─────────────────────────────────────────────────────────────────────────
        Type:   Event
        When:   Event Name contains "OOM_CRITICAL" OR "OOM_FIRING"
              (or "OOM-Watchdog" if using the single-QID XML variant)
        Action: Assign Magnitude=8, enable Notify

  Troubleshooting
  ─────────────────────────────────────────────────────────────────────────
  Events still "unknown" after attaching the extension?
    → Confirm Deploy Changes completed (Admin → System Notifications).
    → Verify the extension is attached: Admin → Log Sources → open the
      log source → check "Log Source Extension" field is set to "OomWatchdog".
    → Re-run this script to confirm the QID records (2000001–2000004) exist.

  Events not appearing at all?
    → Re-run step 3 of this script — another log source may be intercepting.
    → Check OOM Watchdog log for "Failed to send LEEF event" errors.
    → On same-host: verify OOM Watchdog auto-upgraded to TCP (log shows /TCP).
    → Confirm QRadar port 514 is open/listening: ss -tlnup | grep 514

  Wrong log source shown?
    → The identifier on the log source must match the FQDN that
      InetAddress.getLocalHost().getHostName() returns on the watchdog host.
      Run: python3 -c "import socket; print(socket.getfqdn())"  on that host.
""")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description="Idempotent QRadar setup for OOM Watchdog LEEF integration.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--qradar-host",     required=True,
                    help="QRadar console or Event Processor IP/hostname")
    ap.add_argument("--token",           required=True,
                    help="QRadar REST API security token")
    ap.add_argument("--watchdog-host",   required=True,
                    help="Hostname/FQDN of the machine running OOM Watchdog "
                         "(must match InetAddress.getLocalHost().getHostName())")
    ap.add_argument("--sending-ip",      default=None,
                    help="Override: IP QRadar sees as the event source. "
                         "Default: resolved from --watchdog-host. "
                         "Use 127.0.0.1 for same-host (watchdog on the QRadar box).")
    ap.add_argument("--log-source-name", default=None,
                    help="Override log source name. "
                         "Default: 'QRadar_OOM @ <watchdog-host>'")
    ap.add_argument("--dry-run",         action="store_true",
                    help="Print planned actions without making any API calls")
    ap.add_argument("--skip-conflict-scan", action="store_true",
                    help="Skip the interfering log source scan (step 3)")
    args = ap.parse_args()

    sending_ip       = args.sending_ip or resolve_ip(args.watchdog_host)
    log_source_name  = args.log_source_name or f"QRadar_OOM @ {args.watchdog_host}"

    print("=" * 70)
    print("  OOM Watchdog → QRadar LEEF integration setup")
    print("=" * 70)
    print(f"  QRadar host:     {args.qradar_host}")
    print(f"  Watchdog host:   {args.watchdog_host}")
    print(f"  Sending IP:      {sending_ip}")
    print(f"  Log source name: {log_source_name}")
    if args.dry_run:
        print("  MODE:            DRY RUN (no changes will be made)")

    # Step 1 — QID records
    if not setup_qid_records(args.qradar_host, args.token, args.dry_run):
        err("Step 1 failed — aborting.")
        sys.exit(1)

    # Step 2 — Log source
    ls_id = setup_log_source(
        args.qradar_host, args.token,
        args.watchdog_host, sending_ip, log_source_name,
        args.dry_run,
    )

    # Step 3 — Conflict scan
    if not args.skip_conflict_scan:
        scan_interfering_sources(
            args.qradar_host, args.token,
            sending_ip, ls_id or -1,
            args.dry_run,
        )

    print_summary(args.watchdog_host, sending_ip, log_source_name)


if __name__ == "__main__":
    main()
