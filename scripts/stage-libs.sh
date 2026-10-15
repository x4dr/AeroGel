#!/usr/bin/env bash
#
# Stage the compile-time dependency jars into libs/ (see libs/README.md).
#
# AeroGel compiles against Create, Sable and the Create Aeronautics bundle. None of them are
# committed -- libs/ is gitignored on purpose, because the repo is public and redistributing those
# jars is not ours to do -- so a fresh clone, and CI, start with an empty libs/ and cannot compile.
# This script fills libs/ from the same upstream sources the pack consumes.
#
# It is a no-op wherever the files are already staged, so a dev who copied jars out of the test
# instance never notices it. CI calls it before the build. Nothing is redistributed either way:
# the jars are fetched from upstream at build time and never enter the repository.
#
# Upstream sources (versions read from gradle.properties):
#   create        maven.createmod.net   create-<mc>-<create_version>-<CREATE_BUILD>.jar
#   sable         maven.ryanhcode.dev   sable-neoforge-<mc>-<sable_version>.jar
#   aeronautics   api.modrinth.com      create-aeronautics-bundled-<mc>-<aeronautics_version>.jar
#     + simulated + offroad             unpacked from that jar's META-INF/jarjar/* children
#
# Aeronautics, Simulated and Offroad are published ONLY inside the bundled jar, never as separate
# maven artifacts, which is why they are unpacked here rather than resolved. The child jars carry a
# group prefix (`dev.eriksonn.aeronautics.aeronautics-neoforge-...`); libs/ holds them without it.
#
# Usage:
#   scripts/stage-libs.sh            fetch whatever is missing into libs/
#   scripts/stage-libs.sh --force    re-fetch everything
#
# Env:
#   AEROGEL_LIBS_DIR   destination directory (default: <repo>/libs)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LIBS_DIR="${AEROGEL_LIBS_DIR:-$REPO_ROOT/libs}"
FORCE=false

# Create publishes per-CI-build qualified artifacts (6.0.10-280); there is no unqualified
# create-<mc>-<create_version>.jar on the maven. Bump this alongside create_version when the pack moves.
CREATE_BUILD=280

log()  { printf '\033[1;36m[aerogel]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[aerogel]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Stage the compile-time dependency jars into libs/.

  scripts/stage-libs.sh           fetch whatever is missing into libs/
  scripts/stage-libs.sh --force   re-fetch everything

Env: AEROGEL_LIBS_DIR (default: <repo>/libs).
EOF
}

case "${1:-}" in
  '')             ;;
  --force)        FORCE=true ;;
  -h|--help|help) usage; exit 0 ;;
  *)              die "unknown argument '$1' (try: --force)" ;;
esac

command -v curl  >/dev/null || die "curl is required"
command -v unzip >/dev/null || die "unzip is required"

prop() { sed -n "s/^$1=//p" gradle.properties | head -1 | tr -d '\r'; }

MC="$(prop minecraft_version)"
CREATE_VERSION="$(prop create_version)"
SABLE_VERSION="$(prop sable_version)"
AERONAUTICS_VERSION="$(prop aeronautics_version)"
if [ -z "$MC" ] || [ -z "$CREATE_VERSION" ] || [ -z "$SABLE_VERSION" ] || [ -z "$AERONAUTICS_VERSION" ]; then
  die "could not read minecraft_version / create_version / sable_version / aeronautics_version from gradle.properties"
fi

CREATE_JAR="create-${MC}-${CREATE_VERSION}.jar"
# The maven serves the build-qualified name; libs/ holds it without the qualifier.
CREATE_REMOTE_JAR="create-${MC}-${CREATE_VERSION}-${CREATE_BUILD}.jar"
SABLE_JAR="sable-neoforge-${MC}-${SABLE_VERSION}.jar"

CREATE_URL="https://maven.createmod.net/com/simibubi/create/create-${MC}/${CREATE_VERSION}-${CREATE_BUILD}/${CREATE_REMOTE_JAR}"
SABLE_URL="https://maven.ryanhcode.dev/releases/dev/ryanhcode/sable/sable-neoforge-${MC}/${SABLE_VERSION}/${SABLE_JAR}"
BUNDLED_JAR="create-aeronautics-bundled-${MC}-${AERONAUTICS_VERSION}.jar"
BUNDLED_URL="https://api.modrinth.com/maven/maven/modrinth/create-aeronautics/${AERONAUTICS_VERSION}+mc${MC}/${BUNDLED_JAR}"

mkdir -p "$LIBS_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# have <filename>: already in libs/?   need <filename>: must be fetched?
have() { [ -f "$LIBS_DIR/$1" ]; }
need() { if $FORCE; then return 0; fi; ! have "$1"; }

# fetch <url> <temp-filename>: download into $TMP_DIR, dying loudly rather than leaving a stub
fetch() {
  log "fetching $2"
  curl -fsSL --retry 3 --retry-delay 2 -o "$TMP_DIR/$2" "$1" || die "download failed: $1"
  [ -s "$TMP_DIR/$2" ] || die "empty download: $1"
}

# take <temp-filename> <final-filename>
take() {
  mv -f "$TMP_DIR/$1" "$LIBS_DIR/$2"
  log "staged $2"
}

# --- create + sable: plain maven jars -------------------------------------------------------------
for entry in "$CREATE_URL|$CREATE_JAR" "$SABLE_URL|$SABLE_JAR"; do
  url="${entry%%|*}"; jar="${entry##*|}"
  if need "$jar"; then fetch "$url" "$jar"; take "$jar" "$jar"; else log "have $jar"; fi
done

# --- aeronautics bundle: its jarjar children are the only source of these three -------------------
want_bundle=false
for mod in aeronautics simulated offroad; do
  if need "${mod}-neoforge-${MC}-${AERONAUTICS_VERSION}.jar"; then want_bundle=true; fi
done

if $want_bundle; then
  fetch "$BUNDLED_URL" "$BUNDLED_JAR"
  log "unpacking META-INF/jarjar children"
  unzip -o -j "$TMP_DIR/$BUNDLED_JAR" 'META-INF/jarjar/*.jar' -d "$TMP_DIR/jarjar" >/dev/null \
    || die "no META-INF/jarjar/*.jar inside $BUNDLED_JAR (upstream layout changed?)"

  for mod in aeronautics simulated offroad; do
    final="${mod}-neoforge-${MC}-${AERONAUTICS_VERSION}.jar"
    if ! need "$final"; then log "have $final"; continue; fi
    # children are named <group>.<mod>-neoforge-<mc>-<version>.jar; match on the mod-name suffix
    src="$(find "$TMP_DIR/jarjar" -name "*${mod}-neoforge-${MC}-${AERONAUTICS_VERSION}.jar" | head -1)"
    [ -n "$src" ] || die "no ${mod} child at version ${AERONAUTICS_VERSION} inside $BUNDLED_JAR"
    mv -f "$src" "$LIBS_DIR/$final"
    log "staged $final"
  done
fi

# --- report ---------------------------------------------------------------------------------------
log "libs/ now holds:"
for j in "$LIBS_DIR"/*.jar; do
  [ -f "$j" ] || continue
  printf '  %s\n' "$(basename "$j")"
done
