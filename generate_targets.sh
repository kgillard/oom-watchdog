#!/usr/bin/env bash
# =============================================================================
# generate_targets.sh – Generates targets.properties entries from *.env files
#
# Scans SOURCE_DIR for *.env files that contain a JMXPORT= line and appends
# one target block per service to OUTPUT_FILE.
#
# Usage:
#   ./generate_targets.sh
#
# Environment:
#   OUTPUT_FILE   Destination properties file  (default: /etc/targets.conf)
#   SOURCE_DIR    Directory of *.env files      (default: /etc/default)
# =============================================================================

OUTPUT_FILE="${OUTPUT_FILE:-/etc/targets.conf}"
SOURCE_DIR="${SOURCE_DIR:-/etc/default}"

# Enable nullglob so if no *.env files exist, the loop won't run with a literal '*.env'
shopt -s nullglob

for filepath in "$SOURCE_DIR"/*.env; do
  [[ -f "$filepath" ]] || continue

  # Extract JMXPORT value (handles quotes, trailing comments, whitespace, and CR line endings)
  jmxport=$(grep -E '^[[:space:]]*JMXPORT=' "$filepath" | head -n 1 | cut -d'=' -f2- | tr -d '\r"' | sed -e "s/[[:space:]]*#.*$//" -e 's/^[[:space:]]*//;s/[[:space:]]*$//')

  # Proceed only if JMXPORT was found and is not empty
  if [[ -n "$jmxport" ]]; then
    # Extracts just the service name (e.g., /etc/default/arc_builder.env -> arc_builder)
    filename=$(basename "$filepath" .env)

    cat <<EOF >> "$OUTPUT_FILE"
# --------------------------------------------------
# Target configuration for: ${filename} (${filepath})
# --------------------------------------------------
target.${filename}.jmx-url          = service:jmx:rmi:///jndi/rmi://localhost:${jmxport}/jmxrmi
target.${filename}.warn             = 0.10
target.${filename}.crit             = 0.85
target.${filename}.dump-types       = heap,thread
# dump-dir: the watchdog automatically creates an oom-watchdog subdirectory here.
# Effective dump location: /var/log/oom-watchdog/
target.${filename}.dump-dir         = /var/log
target.${filename}.leef-category    = JVM_OOM_QRadar_${filename}
target.${filename}.leef-tags        = env=prod,component=${filename}

EOF
    echo "Appended target: ${filename} (Port: ${jmxport})"
  fi
done