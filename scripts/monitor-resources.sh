#!/usr/bin/env bash
# Sample server CPU/Mem and write CSV.
# Primary:  docker stats on --container <name>
# Fallback: host process listening on --fallback-port <N> (used when container not found)
#
# Usage:
#   monitor-resources.sh --container <name> [--fallback-port <N>] \
#                        [--interval-sec N] --out <path> --duration-sec N
set -euo pipefail

CONTAINER=""
FALLBACK_PORT=""
INTERVAL=5
OUT=""
DURATION=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --container)       CONTAINER="$2";      shift 2 ;;
    --fallback-port)   FALLBACK_PORT="$2";  shift 2 ;;
    --interval-sec)    INTERVAL="$2";       shift 2 ;;
    --out)             OUT="$2";            shift 2 ;;
    --duration-sec)    DURATION="$2";       shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

[[ -z "$CONTAINER" ]] && { echo "--container required" >&2; exit 1; }
[[ -z "$OUT" ]]       && { echo "--out required" >&2;       exit 1; }
[[ "$DURATION" -le 0 ]] && { echo "--duration-sec must be > 0" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

# Detect monitor mode once at startup
MODE="docker"
if ! docker inspect "$CONTAINER" &>/dev/null; then
  if [[ -n "$FALLBACK_PORT" ]]; then
    FALLBACK_PID=$(lsof -ti :"$FALLBACK_PORT" -sTCP:LISTEN 2>/dev/null | head -1 || true)
    if [[ -n "$FALLBACK_PID" ]]; then
      MODE="process"
      echo "[monitor] Container '$CONTAINER' not found — monitoring host process PID $FALLBACK_PID (port $FALLBACK_PORT)" >&2
    else
      echo "[monitor] WARN: container '$CONTAINER' not found and no process on port $FALLBACK_PORT — data will be N/A" >&2
      MODE="none"
    fi
  else
    echo "[monitor] WARN: container '$CONTAINER' not found and --fallback-port not set — data will be N/A" >&2
    MODE="none"
  fi
fi

# Total system memory in MB (for mem_pct calculation in process mode)
total_mem_mb=1
if [[ "$MODE" == "process" ]]; then
  total_mem_mb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 1073741824) / 1048576 ))
fi

header_written=0
start_ts=$(date +%s)

cleanup() { exit 0; }
trap cleanup SIGTERM SIGINT

while true; do
  now_ts=$(date +%s)
  elapsed=$(( now_ts - start_ts ))
  [[ "$elapsed" -ge "$DURATION" ]] && break

  ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

  case "$MODE" in
    docker)
      raw=$(docker stats --no-stream \
        --format '{{.CPUPerc}},{{.MemPerc}},{{.MemUsage}},{{.NetIO}},{{.BlockIO}}' \
        "$CONTAINER" 2>/dev/null || echo "N/A,N/A,N/A,N/A,N/A")
      row=$(echo "$raw" | tr -d '%')
      ;;
    process)
      # Re-resolve PID each tick in case process restarted
      FALLBACK_PID=$(lsof -ti :"$FALLBACK_PORT" -sTCP:LISTEN 2>/dev/null | head -1 || true)
      if [[ -n "$FALLBACK_PID" ]]; then
        read -r cpu_pct mem_pct rss_kb <<< \
          "$(ps -p "$FALLBACK_PID" -o %cpu=,%mem=,rss= 2>/dev/null || echo "N/A N/A N/A")"
        mem_used_mb=$(( ${rss_kb:-0} / 1024 ))
        row="${cpu_pct},${mem_pct},${mem_used_mb}MiB,N/A,N/A"
      else
        row="N/A,N/A,N/A,N/A,N/A"
      fi
      ;;
    *)
      row="N/A,N/A,N/A,N/A,N/A"
      ;;
  esac

  if [[ "$header_written" -eq 0 ]]; then
    echo "timestamp,cpu_pct,mem_pct,mem_used,net_io,block_io" >> "$OUT"
    header_written=1
  fi

  echo "${ts},${row}" >> "$OUT"
  sleep "$INTERVAL"
done
