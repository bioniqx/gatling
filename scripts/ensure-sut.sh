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

log "The ${GAME} game server isn't running (no running container '${CONTAINER}', and ${HEALTH_URL} doesn't answer)."

[[ -f "$COMPOSE_SRC" ]] || die "${GAME} can't be started automatically (games/${GAME}/ has no ${COMPOSE_FILE}). Start the game server yourself, then run again."
[[ -t 0 ]] || die "can't ask where the source code is (no terminal). Start the game server yourself, then run again:
  cp ${COMPOSE_SRC} <source-dir>/ && cd <source-dir> && ${START_CMD}"
docker info >/dev/null 2>&1 || die "Docker isn't running. Open Docker Desktop, wait until it's ready, then run again."

echo "How do you want to start it?"
echo "  1) The game's source code is on this machine (you paste the folder path)"
echo "  2) Download the source code from git, branch main (you paste the git URL)"
echo "  q) Cancel"
read -r -p "Choice [1/2/q]: " CHOICE

case "$CHOICE" in
  1)
    # No -r: unescapes "\ " in paths dragged in from Finder.
    read -p "Folder with the game's source code: " SOURCE_DIR
    SOURCE_DIR="${SOURCE_DIR//[\'\"]/}"
    SOURCE_DIR="${SOURCE_DIR/#\~/$HOME}"
    ;;
  2)
    read -r -p "Git URL: " GIT_URL
    SOURCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/${GAME}-sut.XXXXXX")"
    log "Downloading ${GIT_URL} (branch main) into ${SOURCE_DIR}"
    git clone --branch main --single-branch "$GIT_URL" "$SOURCE_DIR" || die "couldn't download the source code (see the git error above)."
    ;;
  *)
    log "Cancelled."; exit 1
    ;;
esac

[[ -d "$SOURCE_DIR" ]] || die "folder not found: ${SOURCE_DIR}"
[[ -f "${SOURCE_DIR}/Dockerfile" ]] || die "no Dockerfile in ${SOURCE_DIR}. Paste the game backend's top folder (the one that has the Dockerfile)."

TARGET="${SOURCE_DIR}/${COMPOSE_FILE}"
if [[ -f "$TARGET" ]] && ! cmp -s "$COMPOSE_SRC" "$TARGET"; then
  mv "$TARGET" "${TARGET}.bak"
  log "That folder already had a different ${COMPOSE_FILE}; the old one is kept as ${TARGET}.bak"
fi
cp "$COMPOSE_SRC" "$TARGET"

log "Starting the game server (the first time can take a few minutes): ${START_CMD}"
(cd "$SOURCE_DIR" && HTTP_PORT="$PORT" docker compose -f "$COMPOSE_FILE" up -d --build) ||
  die "couldn't start the game server (see the error above). If it says a port is already in use, stop the other game's containers and run again."

log "Waiting for the server to answer ${HEALTH_URL} (up to ${HEALTH_TIMEOUT_SEC}s)"
DEADLINE=$((SECONDS + HEALTH_TIMEOUT_SEC))
until is_healthy; do
  if (( SECONDS >= DEADLINE )); then
    docker logs --tail 50 "$CONTAINER" >&2 || true
    die "the server didn't come up within ${HEALTH_TIMEOUT_SEC}s. Its last 50 log lines are above."
  fi
  sleep 3
done
log "Game server is up."
