#!/usr/bin/env bash
#
# Summarise a FlightProbe matrix run from the server log (and optionally the Prism client log).
#
# Usage:
#   scripts/flight-report.sh [server-log] [client-log]
# Defaults:
#   server-log  run/aerogel-server.log
#   client-log  ~/.local/share/PrismLauncher/instances/AeroGelDemo/.minecraft/logs/latest.log
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_LOG="${1:-$REPO_ROOT/run/aerogel-server.log}"
CLIENT_LOG="${2:-$HOME/.local/share/PrismLauncher/instances/AeroGelDemo/.minecraft/logs/latest.log}"

if [ ! -f "$SERVER_LOG" ]; then
  echo "no server log at $SERVER_LOG" >&2
  exit 1
fi

echo "================ flight matrix ================"
# the probe logs its own per-run results and the SUMMARY table; just surface them in order.
grep -E "ae\.flight" "$SERVER_LOG" \
  | grep -E "RUN [0-9]+ done|SUMMARY|^.*flight: [0-9]+ " || echo "(no completed runs yet)"
echo
echo "-- planned runs --"
grep -E "run [0-9]+ planned" "$SERVER_LOG" | tail -20 || true

echo
echo "================ server ================"
echo "-- tick stalls (>100ms) --"
grep -c "server tick stall" "$SERVER_LOG" 2>/dev/null | sed 's/^/count: /' || true
grep "server tick stall" "$SERVER_LOG" | tail -8 || true
echo
echo "-- stream perf (last 6) --"
grep "stream perf" "$SERVER_LOG" | tail -6 || true

echo
if [ -f "$CLIENT_LOG" ]; then
  echo "================ client ================"
  echo "-- render perf (last 4) --"
  grep "render perf" "$CLIENT_LOG" | tail -4 || true
  echo
  echo "-- client stalls (last 8) --"
  grep -E "client frame stall|client tick stall|render hitch" "$CLIENT_LOG" | tail -8 || true
else
  echo "================ client ================"
  echo "(no client log at $CLIENT_LOG)"
fi
