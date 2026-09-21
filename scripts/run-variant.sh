#!/usr/bin/env bash
# Orchestrate a full load-test variant run: monitoring + Gatling + threshold verification.
#
# Usage:
#   run-variant.sh --game <silkroad|bonanza|naga777|mutantmerge|zeroday> \
#                  --variant <name> --simulation <Soak|Stress|Spike|Basic> \
#                  --users <N> --duration-minutes <N> --ramp-minutes <N> \
#                  --container <docker-container-name>
#
# Game can also be set via the GAME env var. There is NO default — running without specifying
# the game will exit with an error (previous default of "silkroad" was a footgun for bonanza runs).
#
# Variants: baseline | target | stress | critical
# Output:   target/variants/<game>/<variant>-<timestamp>/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

VARIANT=""
SIMULATION=""
USERS=1000
DURATION_MINUTES=60
RAMP_MINUTES=5
CONTAINER=""
PARALLEL=false
PORT=""
REQUESTS=""
SCENARIO=""
GAME_ARG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --game)              GAME_ARG="$2";            shift 2 ;;
    --variant)           VARIANT="$2";            shift 2 ;;
    --simulation)        SIMULATION="$2";          shift 2 ;;
    --users)             USERS="$2";               shift 2 ;;
    --duration-minutes)  DURATION_MINUTES="$2";    shift 2 ;;
    --ramp-minutes)      RAMP_MINUTES="$2";        shift 2 ;;
    --container)         CONTAINER="$2";           shift 2 ;;
    --parallel)          PARALLEL=true;            shift 1 ;;
    --port)              PORT="$2";                shift 2 ;;
    --requests)          REQUESTS="$2";            shift 2 ;;
    --scenario)          SCENARIO="$2";            shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -z "$VARIANT" ]]    && { echo "--variant required" >&2;    exit 1; }
[[ -z "$SIMULATION" ]] && { echo "--simulation required" >&2; exit 1; }
[[ -z "$CONTAINER" ]]  && { echo "--container required" >&2;  exit 1; }

# Game must be specified explicitly via --game or GAME env var. No silent default.
GAME="${GAME_ARG:-${GAME:-}}"
if [[ -z "$GAME" ]]; then
  echo "ERROR: --game <silkroad|bonanza|naga777|mutantmerge|zeroday> required (or set GAME env var)" >&2
  echo "       Example: --game bonanza  OR  GAME=bonanza $0 ..." >&2
  exit 1
fi
if [[ ! -d "${REPO_ROOT}/games/${GAME}" ]]; then
  echo "ERROR: unknown game '${GAME}' — no such directory: games/${GAME}/" >&2
  echo "       Available games: $(ls "${REPO_ROOT}/games" | tr '\n' ' ')" >&2
  exit 1
fi

# Default port per game when --port not passed. The port is used in THREE places:
#   1. Forwarded to Gatling via `-Dport=...` (overrides games/<g>/src/gatling/resources/game.yml)
#   2. Used to build the health-poll probe URL (game-specific path: /actuator/health for silkroad,
#      /golden/api/configs/bet-levels for bonanza)
#   3. Used as monitor-resources.sh `--fallback-port` when docker stats can't find $CONTAINER
if [[ -z "$PORT" ]]; then
  case "$GAME" in
    bonanza)   PORT=3005 ;;
    naga777)  PORT=3000 ;;
    mutantmerge) PORT=3000 ;;
    zeroday)  PORT=3000 ;;
    silkroad) PORT=3000 ;;
    *)        PORT=3000 ;;
  esac
fi

# Build a per-game health probe URL. health-poll.sh's built-in fallback candidates are
# silkroad-specific (hardcoded base http://localhost:3000) — passing --url makes the probe
# game- and port-correct.
case "$GAME" in
  bonanza)   HEALTH_URL="http://localhost:${PORT}/golden/api/configs/bet-levels" ;;
  naga777)  HEALTH_URL="http://localhost:${PORT}/health" ;;
  # Unified dev stack clears the context path; the traefik deploy uses /api/game/mutant-merge/health.
  mutantmerge) HEALTH_URL="http://localhost:${PORT}/health" ;;
  zeroday)  HEALTH_URL="http://localhost:${PORT}/api/game/zeroday/actuator/health" ;;
  silkroad) HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
  *)        HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
esac

DURATION_SEC=$(( DURATION_MINUTES * 60 + RAMP_MINUTES * 60 + 120 ))  # match maxDuration + pad

echo "[run-variant] Game: ${GAME}  Variant: ${VARIANT}  Simulation: ${SIMULATION}  Container: ${CONTAINER}  Port: ${PORT}"
echo "[run-variant] Health probe URL: ${HEALTH_URL}"

# 1. Create output directory (grouped by game so multi-game runs don't collide)
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="${REPO_ROOT}/target/variants/${GAME}/${VARIANT}-${TIMESTAMP}"
mkdir -p "$OUT_DIR"
echo "[run-variant] Output dir: $OUT_DIR"

MONITOR_PID=""
HEALTH_PID=""

cleanup() {
  echo "" >&2
  echo "[run-variant] Interrupted — stopping background monitors..." >&2
  [[ -n "$MONITOR_PID" ]] && kill "$MONITOR_PID" 2>/dev/null || true
  [[ -n "$HEALTH_PID"  ]] && kill "$HEALTH_PID"  2>/dev/null || true
  wait "$MONITOR_PID" 2>/dev/null || true
  wait "$HEALTH_PID"  2>/dev/null || true
  echo "[run-variant] Artifacts (partial): $OUT_DIR" >&2
  exit 130
}
trap cleanup SIGINT SIGTERM

# 2. Start resource monitor in background
"${SCRIPT_DIR}/monitor-resources.sh" \
  --container "$CONTAINER" \
  --fallback-port "$PORT" \
  --interval-sec 5 \
  --out "${OUT_DIR}/resource.csv" \
  --duration-sec "$DURATION_SEC" &
MONITOR_PID=$!
echo "[run-variant] Resource monitor PID: $MONITOR_PID"

# 3. Start health probe in background
"${SCRIPT_DIR}/health-poll.sh" \
  --url "$HEALTH_URL" \
  --interval-sec 2 \
  --out "${OUT_DIR}/health.csv" \
  --duration-sec "$DURATION_SEC" &
HEALTH_PID=$!
echo "[run-variant] Health probe PID: $HEALTH_PID"

# 4. Run Gatling via Gradle (continue even if assertions fail — non-binding for some variants)
ALIAS_LOWER=$(echo "${SIMULATION}" | tr '[:upper:]' '[:lower:]')
GRADLE_TASK=":games:${GAME}:${ALIAS_LOWER}"
PARALLEL_FLAG=""
[[ "$PARALLEL" == "true" ]] && PARALLEL_FLAG="-Dparallel=true"
INJECT_MODE="ramp=${RAMP_MINUTES}m"
[[ "$PARALLEL" == "true" ]] && INJECT_MODE="parallel"
echo "[run-variant] Starting Gatling: ${GRADLE_TASK} (users=$USERS duration=${DURATION_MINUTES}m ${INJECT_MODE})"
set +e
GATLING_START=$SECONDS
REQUESTS_FLAG=""
[[ -n "$REQUESTS" ]] && REQUESTS_FLAG="-Drequests=${REQUESTS}"
SCENARIO_FLAG=""
[[ -n "$SCENARIO" ]] && SCENARIO_FLAG="-Dscenario=${SCENARIO}"
"${REPO_ROOT}/gradlew" "${GRADLE_TASK}" \
  "-Dusers=${USERS}" \
  "-DdurationMinutes=${DURATION_MINUTES}" \
  "-DrampMinutes=${RAMP_MINUTES}" \
  "-Dport=${PORT}" \
  ${PARALLEL_FLAG} \
  ${REQUESTS_FLAG} \
  ${SCENARIO_FLAG} \
  --console=plain \
  2>&1 | tee "${OUT_DIR}/gatling.log"
GATLING_EXIT=${PIPESTATUS[0]}
GATLING_DURATION_SEC=$((SECONDS - GATLING_START))
set -e
echo "[run-variant] Gatling exited with code: $GATLING_EXIT"

# 5. Stop background processes
kill "$MONITOR_PID" 2>/dev/null || true
kill "$HEALTH_PID"  2>/dev/null || true
wait "$MONITOR_PID" 2>/dev/null || true
wait "$HEALTH_PID"  2>/dev/null || true

# 6. Copy latest Gatling HTML report into OUT_DIR (preserves per-variant charts).
# Gatling names report folders after the lowercased simulation class — e.g. `soaksimulation-…`
# for SoakSimulation, but `bonanzagrpcsimulation-…` for BonanzaGrpcSimulation. Rather than
# maintain an alias→class-name map, pick the newest subdirectory in the reports dir — the
# wrapper has just finished Gatling sequentially, so the freshest folder is this run's.
GATLING_REPORTS_DIR="${REPO_ROOT}/games/${GAME}/build/reports/gatling"
LATEST_REPORT=$(ls -1td "${GATLING_REPORTS_DIR}"/*/ 2>/dev/null | head -1 || true)
LATEST_REPORT="${LATEST_REPORT%/}"

if [[ -n "$LATEST_REPORT" && -d "$LATEST_REPORT" ]]; then
  cp -r "$LATEST_REPORT" "${OUT_DIR}/gatling-report"
  echo "[run-variant] Gatling HTML report: ${OUT_DIR}/gatling-report/index.html  (source: $(basename "$LATEST_REPORT"))"
else
  echo "[run-variant] WARN: No Gatling report folder found under ${GATLING_REPORTS_DIR}" >&2
fi

# 7. Run threshold verifier (Java — Gradle task wired in root build.gradle)
echo "[run-variant] Running threshold verifier (variant=${VARIANT})..."
"${REPO_ROOT}/gradlew" verifyVariant -q \
  "-DgameName=${GAME}" \
  "-DresourceCsv=${OUT_DIR}/resource.csv" \
  "-DhealthCsv=${OUT_DIR}/health.csv" \
  "-DgatlingLog=${OUT_DIR}/gatling.log" \
  "-DgatlingExit=${GATLING_EXIT}" \
  "-Dvariant=${VARIANT}" \
  "-Dusers=${USERS}" \
  "-DdurationSec=${GATLING_DURATION_SEC}" \
  > "${OUT_DIR}/verdict.json" || true   # exit code handled by caller; always write JSON

echo "[run-variant] Verdict:"
cat "${OUT_DIR}/verdict.json"
echo ""

# 8. Generate summary.html (verdict banner + CPU/Mem charts + embedded Gatling report)
python3 "${SCRIPT_DIR}/generate-summary-html.py" --variant-dir "${OUT_DIR}" || \
  echo "[run-variant] WARN: summary.html generation failed" >&2

# 9. Print artifact paths
echo "[run-variant] Artifacts:"
echo "  Summary HTML:  ${OUT_DIR}/summary.html"
echo "  Verdict:       ${OUT_DIR}/verdict.json"
echo "  Resources:     ${OUT_DIR}/resource.csv"
echo "  Health:        ${OUT_DIR}/health.csv"
echo "  Gatling log:   ${OUT_DIR}/gatling.log"
[[ -d "${OUT_DIR}/gatling-report" ]] && \
  echo "  Gatling HTML:  ${OUT_DIR}/gatling-report/index.html"

# Surface Gatling exit code to caller
exit "$GATLING_EXIT"
