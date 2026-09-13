#!/usr/bin/env bash
#
# AeroGel dev deploy loop: build -> deploy the client jar -> (re)start the dev server.
#
# Usage:
#   scripts/deploy.sh              # stop server, build, deploy client jar, start server
#   scripts/deploy.sh all          # same as above
#   scripts/deploy.sh build        # assemble + deploy the client jar only
#   scripts/deploy.sh stop         # stop the dev server
#   scripts/deploy.sh start        # start the dev server (no build)
#   scripts/deploy.sh restart      # stop + start (no build)
#   scripts/deploy.sh status       # is the dev server up? show last "Done"
#   scripts/deploy.sh logs         # tail -f the dev server log
#
# Env overrides:
#   JAVA_HOME          JDK 21 used to build/run (default: Prism java-runtime-delta)
#   AEROGEL_INSTANCE   Prism instance to copy the client jar into (default: AeroGelDemo)
#
# Notes:
#   - The dev server (gradlew :runServer) loads AeroGel from build/classes (sourceSet), so it needs
#     NO jar copy. Rebuilding is enough for the next server start.
#   - The Prism client (AeroGelDemo) loads the built jar, so `build` copies it into that instance's
#     mods/ folder. Restart the client in Prism to pick up the new jar.
#   - The launched game process is a child of the persistent Gradle daemon, NOT of the gradle
#     wrapper, so we stop it by matching its command line (serverRunProgramArgs.txt). Killing the
#     wrapper alone leaves the server running.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/PrismLauncher/java/java-runtime-delta}"
export JAVA_HOME

INSTANCE="${AEROGEL_INSTANCE:-AeroGelDemo}"
MODS_DIR="$HOME/.local/share/PrismLauncher/instances/$INSTANCE/.minecraft/mods"

GRADLEW="$REPO_ROOT/gradlew"
RUN_DIR="$REPO_ROOT/run/server"
SERVER_LOG="$REPO_ROOT/run/aerogel-server.log"
SERVER_PID_FILE="$REPO_ROOT/run/aerogel-server.pid"

GAME_MATCH='serverRunProgramArgs.txt'
WRAPPER_MATCH='gradle-wrapper.jar :runServer'
READY_TIMEOUT="${AEROGEL_READY_TIMEOUT:-180}"

log()  { printf '\033[1;36m[aerogel]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[aerogel]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[aerogel]\033[0m %s\n' "$*" >&2; exit 1; }

server_pids() { pgrep -f "$GAME_MATCH" || true; }
server_up()   { [ -n "$(server_pids)" ]; }

stop_server() {
  local pids
  pids="$(server_pids)"
  if [ -z "$pids" ]; then
    log "server not running"
    pkill -f "$WRAPPER_MATCH" 2>/dev/null || true
    rm -f "$SERVER_PID_FILE"
    return 0
  fi
  log "stopping server (pids: $(echo "$pids" | tr '\n' ' '))"
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 60); do
    server_up || break
    sleep 0.5
  done
  if server_up; then
    warn "server did not exit within 30s; killing"
    kill -9 $(server_pids) 2>/dev/null || true
    sleep 1
  fi
  pkill -f "$WRAPPER_MATCH" 2>/dev/null || true
  rm -f "$SERVER_PID_FILE"
  log "server stopped"
}

build_and_deploy() {
  if server_up; then
    die "server is running; Gradle holds the project lock, so a build would block. Run 'scripts/deploy.sh' (stop -> build -> start) or stop first."
  fi
  log "building (gradlew :assemble)"
  "$GRADLEW" :assemble -q
  local jar
  jar="$(ls -t "$REPO_ROOT"/build/libs/aerogel-*.jar 2>/dev/null | head -1 || true)"
  [ -n "$jar" ] || die "no jar found in build/libs"
  [ -d "$MODS_DIR" ] || die "instance mods dir not found: $MODS_DIR"
  rm -f "$MODS_DIR"/aerogel-*.jar
  cp -f "$jar" "$MODS_DIR/"
  log "deployed $(basename "$jar") -> $MODS_DIR"
}

start_server() {
  if server_up; then
    warn "server already running (pids: $(server_pids | tr '\n' ' ')); use 'restart'"
    return 0
  fi
  mkdir -p "$RUN_DIR" "$(dirname "$SERVER_LOG")"
  : > "$SERVER_LOG"
  log "starting dev server (log: $SERVER_LOG)"
  setsid bash -c "cd '$REPO_ROOT' && exec '$GRADLEW' :runServer" \
    > "$SERVER_LOG" 2>&1 < /dev/null &
  echo $! > "$SERVER_PID_FILE"
  local i
  for i in $(seq 1 "$READY_TIMEOUT"); do
    if grep -q 'Done (' "$SERVER_LOG" 2>/dev/null; then
      log "server ready: $(grep 'Done (' "$SERVER_LOG" | tail -1 | sed 's/.*INFO\] \[//')"
      return 0
    fi
    if grep -qE 'FAILURE:|BUILD FAILED|Exception in thread "main"' "$SERVER_LOG" 2>/dev/null; then
      die "server failed to start; see $SERVER_LOG"
    fi
    sleep 1
  done
  die "server did not report 'Done (' within ${READY_TIMEOUT}s; see $SERVER_LOG"
}

status_server() {
  local pids
  pids="$(server_pids)"
  if [ -n "$pids" ]; then
    log "server running (pids: $(echo "$pids" | tr '\n' ' '))"
    grep 'Done (' "$SERVER_LOG" 2>/dev/null | tail -1 | sed 's/^/  /' || true
  else
    log "server not running"
  fi
}

usage() {
  cat <<'EOF'
AeroGel dev deploy loop: build -> deploy the client jar -> (re)start the dev server.

  scripts/deploy.sh              stop server, build, deploy client jar, start server
  scripts/deploy.sh build        assemble + deploy the client jar only (server must be stopped)
  scripts/deploy.sh stop         stop the dev server
  scripts/deploy.sh start        start the dev server (no build)
  scripts/deploy.sh restart      stop + start (no build)
  scripts/deploy.sh status       is the dev server up? show last "Done"
  scripts/deploy.sh logs         tail -f the dev server log

Env: JAVA_HOME (default Prism java-runtime-delta), AEROGEL_INSTANCE (default AeroGelDemo).
EOF
}

cmd="${1:-all}"
case "$cmd" in
  all)     stop_server; build_and_deploy; start_server ;;
  build)   build_and_deploy ;;
  stop)    stop_server ;;
  start)   start_server ;;
  restart) stop_server; start_server ;;
  status)  status_server ;;
  logs)    exec tail -f "$SERVER_LOG" ;;
  -h|--help|help) usage ;;
  *) die "unknown command '$cmd' (try: all|build|stop|start|restart|status|logs)" ;;
esac
