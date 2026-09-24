#!/usr/bin/env bash
# Poll an HTTP endpoint and record status/latency. Exits non-zero on 3 consecutive failures.
# Usage: health-poll.sh [--url <url>] [--container <name>] [--interval-sec N] --out <path> --duration-sec N
#
# Fallback probe order (only when --url and --container are both empty):
#   /actuator/health (GET) → /api/game/caravans/v1/slot/last-spin (POST) → / (GET)
#
# Docker-health mode: when --url is empty and --container is given, each probe is
# `docker inspect` of the container's healthcheck status instead of an HTTP call. Rows are
# still written in the same "timestamp,http_status,total_seconds" format the HTTP mode uses
# (200/0.000 for healthy, 000/0.000 otherwise) so ThresholdVerifier's parseHealthCsv keeps working.
set -euo pipefail

URL=""
CONTAINER=""
INTERVAL=2
OUT=""
DURATION=0
BASE_URL="http://localhost:3000"
PROBE_METHOD="GET"
PROBE_POST_BODY='{"userId":"hp-probe","gameId":"silk_road_usecase"}'

# Each entry is "<METHOD> <path>"
PROBE_CANDIDATES=(
  "GET /actuator/health"
  "POST /api/game/caravans/v1/slot/last-spin"
  "GET /"
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url)          URL="$2";      shift 2 ;;
    --container)    CONTAINER="$2"; shift 2 ;;
    --interval-sec) INTERVAL="$2"; shift 2 ;;
    --out)          OUT="$2";      shift 2 ;;
    --duration-sec) DURATION="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -z "$OUT" ]]       && { echo "--out required" >&2;         exit 1; }
[[ "$DURATION" -le 0 ]] && { echo "--duration-sec must be > 0" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

# Single-shot probe returning HTTP status code only
probe_status() {
  local method="$1" url="$2"
  if [[ "$method" == "POST" ]]; then
    curl -s -o /dev/null -w '%{http_code}' -X POST \
      -H 'content-type: application/json' --data "$PROBE_POST_BODY" \
      --max-time 5 "$url" 2>/dev/null || echo "000"
  else
    curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || echo "000"
  fi
}

# Main probe returning "http_code,time_total"
probe_timed() {
  local method="$1" url="$2"
  if [[ "$method" == "POST" ]]; then
    curl -s -o /dev/null -w '%{http_code},%{time_total}' -X POST \
      -H 'content-type: application/json' --data "$PROBE_POST_BODY" \
      --max-time 5 "$url" 2>/dev/null || echo "000,5.000"
  else
    curl -s -o /dev/null -w '%{http_code},%{time_total}' \
      --max-time 5 "$url" 2>/dev/null || echo "000,5.000"
  fi
}

# Only accept a 2xx response as a valid probe.
# Returns "<METHOD> <URL>" on stdout — caller splits since subshell can't mutate parent vars.
resolve_probe() {
  local method path candidate code
  for entry in "${PROBE_CANDIDATES[@]}"; do
    method="${entry%% *}"
    path="${entry#* }"
    candidate="${BASE_URL}${path}"
    code=$(probe_status "$method" "$candidate")
    if [[ "$code" =~ ^2 ]]; then
      echo "$method $candidate"
      return
    fi
  done
  echo "GET ${BASE_URL}/"
}

# Single-shot probe of a container's Docker healthcheck status. Prints "healthy" or anything
# else (missing container, no healthcheck, starting, unhealthy) counts as a failed probe.
probe_docker_status() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$1" 2>/dev/null || true
}

MODE="http"
if [[ -z "$URL" && -n "$CONTAINER" ]]; then
  MODE="docker"
  echo "[health-poll] Docker-health mode: probing container '$CONTAINER'" >&2
elif [[ -n "$URL" ]]; then
  PROBE_URL="$URL"
  # POSIX-compatible base URL extraction (no grep -P)
  BASE_URL=$(echo "$URL" | sed -E 's,(https?://[^/]+).*,\1,')
  # Detect method for explicit URL
  if [[ "$PROBE_URL" == */slot/last-spin* ]]; then
    PROBE_METHOD="POST"
  fi
  echo "[health-poll] Using probe URL: $PROBE_URL (method: $PROBE_METHOD)" >&2
else
  echo "[health-poll] Resolving probe URL from fallback candidates..." >&2
  resolved=$(resolve_probe)
  PROBE_METHOD="${resolved%% *}"
  PROBE_URL="${resolved#* }"
  echo "[health-poll] Using probe URL: $PROBE_URL (method: $PROBE_METHOD)" >&2
fi

echo "timestamp,http_status,total_seconds" > "$OUT"

consecutive_failures=0
start_ts=$(date +%s)

cleanup() { exit 0; }
trap cleanup SIGTERM SIGINT

while true; do
  now_ts=$(date +%s)
  elapsed=$(( now_ts - start_ts ))
  [[ "$elapsed" -ge "$DURATION" ]] && break

  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

  if [[ "$MODE" == "docker" ]]; then
    status=$(probe_docker_status "$CONTAINER")
    if [[ "$status" == "healthy" ]]; then
      http_code="200"
    else
      http_code="000"
    fi
    time_total="0.000"
  else
    result=$(probe_timed "$PROBE_METHOD" "$PROBE_URL")
    http_code=$(echo "$result" | cut -d',' -f1)
    time_total=$(echo "$result" | cut -d',' -f2)
  fi

  echo "${ts},${http_code},${time_total}" >> "$OUT"

  # Count failure: non-2xx or timed-out (time >= 5s)
  is_2xx=0
  [[ "$http_code" =~ ^2 ]] && is_2xx=1

  timed_out=$(awk "BEGIN { print ($time_total >= 5.0) ? 1 : 0 }")

  if [[ "$is_2xx" -eq 0 || "$timed_out" -eq 1 ]]; then
    (( consecutive_failures++ )) || true
    echo "[health-poll] WARN consecutive_failures=${consecutive_failures} status=${http_code} time=${time_total}" >&2
    if [[ "$consecutive_failures" -ge 3 ]]; then
      echo "[health-poll] CRASH DETECTED: 3 consecutive failures. Exiting non-zero." >&2
      exit 1
    fi
  else
    consecutive_failures=0
  fi

  sleep "$INTERVAL"
done
