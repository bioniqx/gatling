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
    echo "  Pick a number between 1 and $#." >&2
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
    echo "  Enter a whole number >= $min." >&2
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
    Soak)   echo "Soak    ramp up, then hold steady load" ;;
    Stress) echo "Stress  keep ramping to find the breaking point" ;;
    Spike)  echo "Spike   steady load plus bursts of extra users" ;;
    Basic)  echo "Basic   hammer a single endpoint" ;;
    Grpc)   echo "Grpc    gRPC soak on the plugin path the FE uses" ;;
  esac
}

[[ -t 0 ]] || { echo "loadtest.sh is interactive — in CI, call scripts/run-variant.sh directly." >&2; exit 1; }

echo "== Game ==  (● running  ○ not running)"
LABELS=()
for row in "${GAMES[@]}"; do
  IFS='|' read -r id name container _ <<< "$row"
  mark="○"
  if container_running "$container"; then mark="●"; fi
  LABELS+=("$mark $name ($id)")
done
i=$(choose "Game" "${LABELS[@]}")
IFS='|' read -r GAME GAME_NAME CONTAINER SIMS SCENARIOS <<< "${GAMES[i-1]}"

echo
echo "== Simulation =="
read -r -a SIM_LIST <<< "$SIMS"
if (( ${#SIM_LIST[@]} == 1 )); then
  SIMULATION="${SIM_LIST[0]}"
  echo "  $(sim_label "$SIMULATION")  (the only one $GAME_NAME has)"
else
  SIM_LABELS=()
  for sim in "${SIM_LIST[@]}"; do SIM_LABELS+=("$(sim_label "$sim")"); done
  i=$(choose "Simulation" "${SIM_LABELS[@]}")
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
echo "== Load =="
if [[ "$SIMULATION" == Basic ]]; then
  read -r -a SCENARIO_LIST <<< "$SCENARIOS"
  i=$(choose "Scenario" "${SCENARIO_LIST[@]}")
  SCENARIO="${SCENARIO_LIST[i-1]}"
  USERS=$(ask_number "Users" 500 1)
  REQUESTS=$(ask_number "Total requests" 5000 1)
  DURATION=1
  RAMP=0
else
  i=$(choose "Load profile" \
    "Smoke             10 users,  1 min, no ramp     (variant target)" \
    "Quick check      200 users,  5 min, 1 min ramp  (variant baseline)" \
    "Production gate 1000 users, 60 min, ${DEFAULT_RAMP} min ramp  (variant target)" \
    "Custom")
  case "$i" in
    1) USERS=10;   DURATION=1;  RAMP=0 ;;
    2) USERS=200;  DURATION=5;  RAMP=1; VARIANT=baseline ;;
    3) USERS=1000; DURATION=60; RAMP=$DEFAULT_RAMP ;;
    4)
      USERS=$(ask_number "Users" 1000 1)
      DURATION=$(ask_number "Duration (minutes)" 60 1)
      RAMP=$(ask_number "Ramp-up (minutes)" "$DEFAULT_RAMP" 0)
      if [[ "$SIMULATION" == Soak ]] && ask_yes_no "Start all users at once (skips the ramp)?" n; then
        PARALLEL=true
      fi
      VARIANTS=(baseline target stress critical)
      i=$(choose "Variant (pass/fail CPU / Mem p95 ceiling)" \
        "baseline  CPU 50% / Mem 60%  headroom check" \
        "target    CPU 70% / Mem 80%  production gate" \
        "stress    CPU 85% / Mem 90%  saturation study" \
        "critical  CPU 95% / Mem 95%  pre-failure check")
      VARIANT="${VARIANTS[i-1]}"
      while true; do
        read -r -p "HTTP port [game default]: " PORT || exit 1
        [[ -z "$PORT" || "$PORT" =~ ^[0-9]+$ ]] && break
        echo "  Enter a port number, or press Enter for the default." >&2
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
  LOAD="$USERS users, $REQUESTS requests on '$SCENARIO'"
elif [[ "$PARALLEL" == true ]]; then
  LOAD="$USERS users all at once, $DURATION min (~$DURATION min total)"
else
  LOAD="$USERS users, $DURATION min + $RAMP min ramp (~$((DURATION + RAMP)) min total)"
fi

echo
echo "== Ready =="
echo "  Game        $GAME_NAME ($GAME), container $CONTAINER"
echo "  Simulation  $SIMULATION"
echo "  Variant     $VARIANT"
echo "  Load        $LOAD"
echo "  Command     ./scripts/run-variant.sh $(printf '%q ' "${ARGS[@]}")"
ask_yes_no "Start the load test?" y || { echo "Cancelled."; exit 0; }

MARKER=$(mktemp)
EXIT=0
"$REPO_ROOT/scripts/run-variant.sh" "${ARGS[@]}" || EXIT=$?

SUMMARY=$(find "$REPO_ROOT/target/variants/$GAME" -name summary.html -newer "$MARKER" 2>/dev/null | head -1 || true)
rm -f "$MARKER"
if [[ -n "$SUMMARY" ]] && ask_yes_no "Open summary.html?" y; then
  if command -v open >/dev/null; then open "$SUMMARY"; else xdg-open "$SUMMARY"; fi
fi
exit "$EXIT"
