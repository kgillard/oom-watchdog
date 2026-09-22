#!/usr/bin/env bash
# =============================================================================
# oom-watchdog-service.sh – Start / stop / restart / status wrapper
#
# Usage:
#   oom-watchdog-service.sh start   [-- watchdog-args...]
#   oom-watchdog-service.sh stop
#   oom-watchdog-service.sh restart [-- watchdog-args...]
#   oom-watchdog-service.sh status
#
# The script manages exactly the resources this application creates:
#   • The watchdog Java process  (tracked via PID file)
#   • The --metrics-port HTTP server (opened by this process)
#   • Any --dump-api-port HTTP server (opened by this process)
#
# It does NOT touch:
#   • Port 514 (QRadar syslog receive port — belongs to QRadar, not this app)
#   • JMX ports on monitored target JVMs (belong to those applications)
#   • Any other system ports
#
# Environment variables (can be set before calling this script):
#   OOM_JAR         Path to oom-watchdog.jar      (default: ./oom-watchdog/oom-watchdog.jar)
#   OOM_PID_FILE    PID file path                 (default: /var/run/oom-watchdog.pid)
#   OOM_LOG_FILE    Stdout/stderr log             (default: /var/log/oom-watchdog/oom-watchdog.log)
#   JAVA_OPTS       Extra JVM flags               (default: -Xmx128m)
# =============================================================================
set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
OOM_JAR="${OOM_JAR:-./oom-watchdog/oom-watchdog.jar}"
OOM_PID_FILE="${OOM_PID_FILE:-/var/run/oom-watchdog.pid}"
OOM_LOG_FILE="${OOM_LOG_FILE:-/var/log/oom-watchdog/oom-watchdog.log}"
JAVA_OPTS="${JAVA_OPTS:--Xmx128m}"

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { echo "[oom-watchdog] $*"; }
warn() { echo "[oom-watchdog] WARNING: $*" >&2; }
die()  { echo "[oom-watchdog] ERROR: $*" >&2; exit 1; }

# Extract the value of a named argument from the watchdog argument list.
# Usage: arg_value "--metrics-port" "$@"
# Returns the value after the flag, or empty string if not present.
arg_value() {
    local flag="$1"; shift
    local prev=""
    for arg in "$@"; do
        if [[ "$prev" == "$flag" ]]; then echo "$arg"; return; fi
        prev="$arg"
    done
}

# Read the PID from the PID file; echo it if the process is still alive.
running_pid() {
    [[ -f "$OOM_PID_FILE" ]] || return 0
    local pid
    pid=$(cat "$OOM_PID_FILE" 2>/dev/null) || return 0
    [[ -n "$pid" ]] || return 0
    kill -0 "$pid" 2>/dev/null && echo "$pid" || true
}

# Release a TCP port opened by this application by sending SIGTERM to the
# process listening on it — but only if that process is the one we started.
# Never called for 514 or any QRadar/JMX port.
release_app_port() {
    local port="$1"
    local our_pid="$2"
    [[ -n "$port" && "$port" =~ ^[0-9]+$ ]] || return 0
    # Only act if fuser/lsof finds our own PID on that port
    local holder=""
    if command -v fuser >/dev/null 2>&1; then
        holder=$(fuser "${port}/tcp" 2>/dev/null | tr -s ' ' '\n' | grep -v '^$' | head -1 || true)
    elif command -v lsof >/dev/null 2>&1; then
        holder=$(lsof -ti "tcp:${port}" 2>/dev/null | head -1 || true)
    fi
    if [[ -n "$holder" && "$holder" == "$our_pid" ]]; then
        log "Releasing metrics port ${port}/tcp (PID ${our_pid})"
        # The Java shutdown hook handles this; we just ensure the process stops
    else
        log "Port ${port}/tcp not held by this process — skipping"
    fi
}

# ── Commands ──────────────────────────────────────────────────────────────────

cmd_start() {
    # Remaining args after "start" and optional "--" separator are passed to the watchdog
    local watchdog_args=("$@")

    local existing_pid
    existing_pid=$(running_pid)
    if [[ -n "$existing_pid" ]]; then
        log "Already running (PID ${existing_pid}). Use 'restart' to restart."
        exit 0
    fi

    [[ -f "$OOM_JAR" ]] || die "JAR not found: $OOM_JAR  (set OOM_JAR or run the installer first)"
    command -v java >/dev/null 2>&1 || die "java not found on PATH"

    # Ensure log directory exists
    mkdir -p "$(dirname "$OOM_LOG_FILE")"
    # Ensure PID file directory exists
    mkdir -p "$(dirname "$OOM_PID_FILE")"

    log "Starting OOM Watchdog..."
    log "  JAR:      $OOM_JAR"
    log "  Log:      $OOM_LOG_FILE"
    log "  PID file: $OOM_PID_FILE"
    [[ ${#watchdog_args[@]} -gt 0 ]] && log "  Args:     ${watchdog_args[*]}"

    # shellcheck disable=SC2086
    nohup java $JAVA_OPTS -jar "$OOM_JAR" "${watchdog_args[@]}" \
        >> "$OOM_LOG_FILE" 2>&1 &
    local pid=$!
    echo "$pid" > "$OOM_PID_FILE"
    log "Started (PID ${pid})"
}

cmd_stop() {
    local watchdog_args=("$@")   # original start args, used only to identify app-owned ports

    local pid
    pid=$(running_pid)
    if [[ -z "$pid" ]]; then
        log "Not running (no PID file or process not found)."
        rm -f "$OOM_PID_FILE"
        return 0
    fi

    log "Stopping OOM Watchdog (PID ${pid})..."

    # --- Only inspect ports this application opens ----------------------------
    # --metrics-port: the HTTP metrics server opened by this process
    local metrics_port
    metrics_port=$(arg_value "--metrics-port" "${watchdog_args[@]:-}")
    if [[ -n "$metrics_port" ]]; then
        log "Releasing metrics port ${metrics_port}/tcp (owned by this process)"
        release_app_port "$metrics_port" "$pid"
    fi

    # --dump-api-port: any embedded DumpApiServer opened by this process
    local dump_api_port
    dump_api_port=$(arg_value "--dump-api-port" "${watchdog_args[@]:-}")
    if [[ -n "$dump_api_port" ]]; then
        log "Releasing dump-api port ${dump_api_port}/tcp (owned by this process)"
        release_app_port "$dump_api_port" "$pid"
    fi

    # Send SIGTERM — the Java shutdown hook will stop the metrics server,
    # close JMX connections, and shut down the poll threads cleanly.
    kill -TERM "$pid" 2>/dev/null || true

    # Wait up to 15 seconds for a clean exit
    local waited=0
    while kill -0 "$pid" 2>/dev/null && [[ $waited -lt 15 ]]; do
        sleep 1
        (( waited++ )) || true
    done

    if kill -0 "$pid" 2>/dev/null; then
        warn "Process did not exit after ${waited}s — sending SIGKILL"
        kill -KILL "$pid" 2>/dev/null || true
    else
        log "Stopped cleanly after ${waited}s"
    fi

    rm -f "$OOM_PID_FILE"
    log "Done."
}

cmd_status() {
    local pid
    pid=$(running_pid)
    if [[ -n "$pid" ]]; then
        log "Running (PID ${pid})"
        exit 0
    else
        log "Not running"
        exit 1
    fi
}

# ── Argument parsing ──────────────────────────────────────────────────────────
[[ $# -ge 1 ]] || { echo "Usage: $0 {start|stop|restart|status} [-- watchdog-args...]" >&2; exit 1; }

COMMAND="$1"; shift

# Strip a bare "--" separator if present
if [[ $# -gt 0 && "$1" == "--" ]]; then shift; fi

case "$COMMAND" in
    start)
        cmd_start "$@"
        ;;
    stop)
        # For stop we don't have the original start args on hand unless the caller passes them.
        # Port cleanup is best-effort; the Java shutdown hook is the primary mechanism.
        cmd_stop "$@"
        ;;
    restart)
        cmd_stop "$@"
        sleep 1
        cmd_start "$@"
        ;;
    status)
        cmd_status
        ;;
    *)
        die "Unknown command: $COMMAND  (use start|stop|restart|status)"
        ;;
esac
