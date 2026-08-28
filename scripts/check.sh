#!/usr/bin/env bash
# TradingSagaTCC — verification entry (portable; Linux/macOS)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
cd "$SCRIPT_DIR/.."
if [[ -x ./gradlew ]]; then
  ./gradlew check --no-daemon
else
  echo "gradlew not found" >&2
  exit 1
fi
echo "TradingSagaTCC check OK"
