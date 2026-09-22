#!/usr/bin/env bash
# Interactive menu for load tests: pick a game, simulation and load profile, then run
# scripts/run-variant.sh with the matching flags. Written for macOS's bash 3.2.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# One row per game: id | display name | container | simulations | Basic scenarios
GAMES=(
  "silkroad|Silk Road Caravans|game-silk-road-caravans|Soak Stress Spike Basic|spin last-spin history-summary all chain burst"
  "bonanza|Golden Boat Bonanza|game-golden-boat-bonanza|Soak Basic Grpc|BetLevels ReelStrips CreateSession Spin JackpotPools HistorySessions"
  "naga777|Naga's Fortune 777|stable-naga_fortune_777|Grpc|"
  "mutantmerge|Mutant Merge|stable-game-mutant-merge|Grpc|"
  "zeroday|Zero Day|game-zero-day|Grpc|"
)

# Numbered list of the given options; prints the 1-based index picked.
choose() {
  local prompt="$1" i n opt
  shift
  while true; do
    i=1
    for opt in "$@"; do
      echo "  $i) $opt" >&2
      i=$((i + 1))
    done
    read -r -p "$prompt [1-$#]: " n || exit 1
    if [[ "$n" =~ ^[1-9][0-9]*$ ]] && (( n >= 1 && n <= $# )); then
      echo "$n"
      return
    fi
    echo "  Please type a number from 1 to $#." >&2
  done
}

# Whole number >= min; Enter keeps the default.
ask_number() {
  local prompt="$1" default="$2" min="$3" value
  while true; do
    read -r -p "$prompt [$default]: " value || exit 1
    value="${value:-$default}"
    if [[ "$value" =~ ^(0|[1-9][0-9]*)$ ]] && (( value >= min )); then
      echo "$value"
      return
    fi
    echo "  Please type a whole number (at least $min)." >&2
  done
}

# Yes/no question; Enter picks the default (y or n).
ask_yes_no() {
  local hint="y/N" answer
  [[ "$2" == y ]] && hint="Y/n"
  read -r -p "$1 [$hint]: " answer || exit 1
  answer="${answer:-$2}"
  [[ "$answer" =~ ^[Yy] ]]
}

container_running() {
  [[ -n "$(docker ps -q --filter "name=^$1$" --filter status=running 2>/dev/null)" ]]
}

sim_label() {
  case "$1" in
    Soak)   echo "Soak    steady load for a while (finds slowdowns and memory leaks)" ;;
    Stress) echo "Stress  keep adding players until the server struggles" ;;
    Spike)  echo "Spike   normal load with sudden rushes of players" ;;
    Basic)  echo "Basic   call one API many times" ;;
    Grpc)   echo "gRPC    steady load over gRPC, the way the real game client connects" ;;
  esac
}

variant_label() {
  case "$1" in
    baseline) echo "CPU under 50%, memory under 60%  (baseline: lots of spare room)" ;;
    target)   echo "CPU under 70%, memory under 80%  (target: release standard)" ;;
    stress)   echo "CPU under 85%, memory under 90%  (stress: heavy load)" ;;
    critical) echo "CPU under 95%, memory under 95%  (critical: near the limit)" ;;
  esac
}

[[ -t 0 ]] || { echo "loadtest.sh needs a terminal to ask questions. In CI, run scripts/run-variant.sh directly." >&2; exit 1; }

echo "== Which game do you want to test? ==  (● server running  ○ server not running)"
LABELS=()
for row in "${GAMES[@]}"; do
  IFS='|' read -r id name container _ <<< "$row"
  mark="○"
  if container_running "$container"; then mark="●"; fi
  LABELS+=("$mark $name")
done
i=$(choose "Game" "${LABELS[@]}")
IFS='|' read -r GAME GAME_NAME CONTAINER SIMS SCENARIOS <<< "${GAMES[i-1]}"

echo
echo "== What kind of test? =="
read -r -a SIM_LIST <<< "$SIMS"
if (( ${#SIM_LIST[@]} == 1 )); then
  SIMULATION="${SIM_LIST[0]}"
  echo "  $(sim_label "$SIMULATION")"
  echo "  (the only test type $GAME_NAME has)"
else
  SIM_LABELS=()
  for sim in "${SIM_LIST[@]}"; do SIM_LABELS+=("$(sim_label "$sim")"); done
  i=$(choose "Test type" "${SIM_LABELS[@]}")
  SIMULATION="${SIM_LIST[i-1]}"
fi

VARIANT=target
PORT=""
REQUESTS=""
SCENARIO=""
PARALLEL=false
DEFAULT_RAMP=5
[[ "$SIMULATION" == Grpc ]] && DEFAULT_RAMP=2

echo
echo "== How big should the test be? =="
if [[ "$SIMULATION" == Basic ]]; then
  read -r -a SCENARIO_LIST <<< "$SCENARIOS"
  i=$(choose "Which API" "${SCENARIO_LIST[@]}")
  SCENARIO="${SCENARIO_LIST[i-1]}"
  USERS=$(ask_number "Number of players (virtual users)" 500 1)
  REQUESTS=$(ask_number "Total number of requests" 5000 1)
  DURATION=1
  RAMP=0
else
  i=$(choose "Size" \
    "Smoke         10 players for 1 minute (just checks that it works)" \
    "Quick check   200 players for 5 minutes" \
    "Full test     1000 players for 60 minutes (the release check)" \
    "Custom        choose the numbers yourself")
  case "$i" in
    1) USERS=10;   DURATION=1;  RAMP=0 ;;
    2) USERS=200;  DURATION=5;  RAMP=1; VARIANT=baseline ;;
    3) USERS=1000; DURATION=60; RAMP=$DEFAULT_RAMP ;;
    4)
      USERS=$(ask_number "Number of players (virtual users)" 1000 1)
      DURATION=$(ask_number "Test length in minutes (not counting warm-up)" 60 1)
      RAMP=$(ask_number "Warm-up minutes (players join gradually)" "$DEFAULT_RAMP" 0)
      if [[ "$SIMULATION" == Soak ]] && ask_yes_no "Let all players join at once instead of gradually?" n; then
        PARALLEL=true
      fi
      echo "  When does the test pass? The server's CPU and memory must stay:" >&2
      VARIANTS=(baseline target stress critical)
      i=$(choose "Pass limit" \
        "$(variant_label baseline)" \
        "$(variant_label target)" \
        "$(variant_label stress)" \
        "$(variant_label critical)")
      VARIANT="${VARIANTS[i-1]}"
      while true; do
        read -r -p "Game server HTTP port [Enter = usual port for this game]: " PORT || exit 1
        [[ -z "$PORT" || "$PORT" =~ ^[0-9]+$ ]] && break
        echo "  Please type a port number, or just press Enter." >&2
      done
      ;;
  esac
fi

ARGS=(--game "$GAME" --variant "$VARIANT" --simulation "$SIMULATION"
      --users "$USERS" --duration-minutes "$DURATION" --ramp-minutes "$RAMP"
      --container "$CONTAINER")
[[ -n "$PORT" ]] && ARGS+=(--port "$PORT")
[[ -n "$SCENARIO" ]] && ARGS+=(--scenario "$SCENARIO" --requests "$REQUESTS")
[[ "$PARALLEL" == true ]] && ARGS+=(--parallel)

if [[ "$SIMULATION" == Basic ]]; then
  SIZE="$USERS players sending $REQUESTS requests in total to '$SCENARIO'"
elif [[ "$PARALLEL" == true ]]; then
  SIZE="$USERS players, all joining at once, for $DURATION min"
elif (( RAMP == 0 )); then
  SIZE="$USERS players for $DURATION min, no warm-up"
else
  SIZE="$USERS players for $DURATION min after a $RAMP-min warm-up (about $((DURATION + RAMP)) min in total)"
fi

echo
echo "== Check before starting =="
echo "  Game         $GAME_NAME (server container: $CONTAINER)"
echo "  Test type    ${SIMULATION/Grpc/gRPC}"
echo "  Size         $SIZE"
echo "  Pass limit   $(variant_label "$VARIANT")"
echo "  Same as      ./scripts/run-variant.sh $(printf '%q ' "${ARGS[@]}")"
ask_yes_no "Start now?" y || { echo "Cancelled — nothing was run."; exit 0; }

MARKER=$(mktemp)
EXIT=0
"$REPO_ROOT/scripts/run-variant.sh" "${ARGS[@]}" || EXIT=$?

SUMMARY=$(find "$REPO_ROOT/target/variants/$GAME" -name summary.html -newer "$MARKER" 2>/dev/null | head -1 || true)
rm -f "$MARKER"
if [[ -n "$SUMMARY" ]] && ask_yes_no "Open the result report (summary.html) in your browser?" y; then
  if command -v open >/dev/null; then open "$SUMMARY"; else xdg-open "$SUMMARY"; fi
fi
exit "$EXIT"
