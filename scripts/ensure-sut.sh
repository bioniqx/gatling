#!/usr/bin/env bash
# Make sure the game's SUT is up before a load test. If its container isn't running and the
# health probe fails, offer to start it with games/<game>/docker-compose.loadtest.yml from
# either a local source directory or a fresh clone (branch main) of a git repository.
#
# Usage:
#   ensure-sut.sh --game <name> --container <name> --port <N> --health-url <url>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
HEALTH_TIMEOUT_SEC=300

GAME=""
CONTAINER=""
PORT=""
HEALTH_URL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --game)       GAME="$2";       shift 2 ;;
    --container)  CONTAINER="$2";  shift 2 ;;
    --port)       PORT="$2";       shift 2 ;;
    --health-url) HEALTH_URL="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

COMPOSE_FILE="docker-compose.loadtest.yml"
COMPOSE_SRC="${REPO_ROOT}/games/${GAME}/${COMPOSE_FILE}"
START_CMD="HTTP_PORT=${PORT} docker compose -f ${COMPOSE_FILE} up -d --build"

log() { echo "[ensure-sut] $*"; }
die() { echo "[ensure-sut] ERROR: $*" >&2; exit 1; }

is_healthy() {
  local code
  code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$HEALTH_URL" || true)
  [[ "$code" == 2* ]]
}

container_running() {
  [[ -n "$(docker ps -q --filter "name=^${CONTAINER}$" --filter status=running 2>/dev/null)" ]]
}

# Already up — in Docker, or outside it (the health probe answers).
if container_running || is_healthy; then
  exit 0
fi

log "SUT for '${GAME}' is not running (container '${CONTAINER}' is down, ${HEALTH_URL} is not healthy)."

[[ -f "$COMPOSE_SRC" ]] || die "games/${GAME}/ has no ${COMPOSE_FILE} — start the backend manually, then re-run."
[[ -t 0 ]] || die "no terminal to ask where the source is. Start it manually, then re-run:
  cp ${COMPOSE_SRC} <source-dir>/ && cd <source-dir> && ${START_CMD}"
docker info >/dev/null 2>&1 || die "Docker is not running — start Docker, then re-run."

echo "How do you want to start it?"
echo "  1) Use a local source directory"
echo "  2) Clone a git repository (branch main) into a temp directory"
echo "  q) Quit"
read -r -p "Choice [1/2/q]: " CHOICE

case "$CHOICE" in
  1)
    # No -r: unescapes "\ " in paths dragged in from Finder.
    read -p "Source directory: " SOURCE_DIR
    SOURCE_DIR="${SOURCE_DIR//[\'\"]/}"
    SOURCE_DIR="${SOURCE_DIR/#\~/$HOME}"
    ;;
  2)
    read -r -p "Git URL: " GIT_URL
    SOURCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/${GAME}-sut.XXXXXX")"
    log "Cloning ${GIT_URL} (main) into ${SOURCE_DIR}"
    git clone --branch main --single-branch "$GIT_URL" "$SOURCE_DIR" || die "git clone failed (see above)."
    ;;
  *)
    die "aborted."
    ;;
esac

[[ -d "$SOURCE_DIR" ]] || die "not a directory: ${SOURCE_DIR}"
[[ -f "${SOURCE_DIR}/Dockerfile" ]] || die "no Dockerfile in ${SOURCE_DIR} — point at the backend repo root."

TARGET="${SOURCE_DIR}/${COMPOSE_FILE}"
if [[ -f "$TARGET" ]] && ! cmp -s "$COMPOSE_SRC" "$TARGET"; then
  mv "$TARGET" "${TARGET}.bak"
  log "Existing ${COMPOSE_FILE} differs — backed up to ${TARGET}.bak"
fi
cp "$COMPOSE_SRC" "$TARGET"

log "Starting SUT in ${SOURCE_DIR}: ${START_CMD}"
(cd "$SOURCE_DIR" && HTTP_PORT="$PORT" docker compose -f "$COMPOSE_FILE" up -d --build) ||
  die "docker compose up failed (see above). If a port is already allocated, stop the stack holding it and re-run."

log "Waiting up to ${HEALTH_TIMEOUT_SEC}s for ${HEALTH_URL}"
DEADLINE=$((SECONDS + HEALTH_TIMEOUT_SEC))
until is_healthy; do
  if (( SECONDS >= DEADLINE )); then
    docker logs --tail 50 "$CONTAINER" >&2 || true
    die "SUT not healthy after ${HEALTH_TIMEOUT_SEC}s — last 50 container log lines above."
  fi
  sleep 3
done
log "SUT is up."
