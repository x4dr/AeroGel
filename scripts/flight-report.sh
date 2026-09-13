#!/usr/bin/env bash
#
# Summarise a FlightProbe run from the server log (and optionally the Prism client log).
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

echo "================ server ================"
echo "-- flight run --"
grep -E "flight: (pilot|run complete)|flight probe" "$SERVER_LOG" || echo "(no flight probe lines)"

echo
echo "-- server tick (from flight: lines) --"
grep -E "^\[.*\] \[Server thread/INFO\] \[ae\.flight" "$SERVER_LOG" \
  | sed -E 's/.*tick ([0-9.]+) ms, backlog ([0-9]+).*/\1 \2/' \
  | grep -E '^[0-9]' \
  | awk '
      { n++; s+=$1; b+=$2; if (n==1||$1<min) min=$1; if ($1>max) max=$1; if ($2>maxb) maxb=$2 }
      END { if (n) printf "samples %d | tick min %.1f avg %.1f max %.1f ms | backlog avg %.0f max %d\n", n, min, s/n, max, b/n, maxb }
    ' || true

echo
echo "-- enforcing samples --"
grep -c "ENFORCING" "$SERVER_LOG" 2>/dev/null | sed 's/^/ENFORCING lines: /' || true

echo
echo "-- server tick stalls (>100ms) --"
grep -c "server tick stall" "$SERVER_LOG" 2>/dev/null | sed 's/^/count: /' || true
grep "server tick stall" "$SERVER_LOG" | tail -5 || true

echo
echo "-- stream perf (last 5) --"
grep "stream perf" "$SERVER_LOG" | tail -5 || true

echo
if [ -f "$CLIENT_LOG" ]; then
  echo "================ client ($CLIENT_LOG) ================"
  echo "-- render perf (last 5) --"
  grep "render perf" "$CLIENT_LOG" | tail -5 || true
  echo
  echo "-- client frame/tick stalls --"
  grep -E "client frame stall|client tick stall|render hitch" "$CLIENT_LOG" | tail -10 || true
else
  echo "================ client ================"
  echo "(no client log at $CLIENT_LOG)"
fi
