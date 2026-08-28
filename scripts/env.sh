#!/usr/bin/env bash
# TradingSagaTCC — portable env (Linux/macOS; no hardcoded JDK paths)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORTABLE="$SCRIPT_DIR/portable-env.sh"
if [[ ! -f "$PORTABLE" ]]; then
  walk="$(cd "$SCRIPT_DIR/.." && pwd)"
  for _ in 1 2 3 4 5 6; do
    eos="$walk/EngineeringOS/eos-minimal/hooks/portable-env.sh"
    if [[ -f "$eos" ]]; then PORTABLE="$eos"; break; fi
    parent="$(dirname "$walk")"
    [[ "$parent" == "$walk" ]] && break
    walk="$parent"
  done
fi
if [[ -f "$PORTABLE" ]]; then
  # shellcheck source=/dev/null
  source "$PORTABLE"
else
  echo "WARN: portable-env.sh not found — set JAVA_HOME to JDK 21" >&2
fi
