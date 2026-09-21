# RGP Game Load Test

A load-test harness for RGP slot-game backends, built on Gatling 3.15. It simulates hundreds-to-thousands of players hitting a game's API at the same time, measures response time and errors, and writes a pass/fail verdict that gates production deploys.

**One script does everything:** `./scripts/run-variant.sh` starts CPU/memory monitoring, probes the backend's health, runs Gatling, verifies thresholds, and produces a one-page `summary.html` you can open in a browser. Use it for any run that needs a pass/fail verdict. Direct `./gradlew :games:…` invocations exist for fast developer iteration but produce no verdict — see [Advanced runs](#advanced-runs-without-the-wrapper).

New here? Jump to [Quick start](#quick-start) — first test takes ~5 minutes.

## Who reads what

| You are…                          | Read sections                                                                                                            |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| **QC / PM / DevOps (non-tech)**   | [Setup](#setup) → [Quick start](#quick-start) → [Running tests](#running-tests) → [Understanding outputs](#understanding-outputs) → [Troubleshooting](#troubleshooting) |
| **Developer adding a new game**   | Above + [Configuration](#configuration), [Project layout](#project-layout), [Add a new game](#add-a-new-game)             |

Vietnamese long-form walkthrough: [`docs/getting-started.md`](docs/getting-started.md).
Naga777 run-and-read-results guide: [`naga777-load-test-guide.md`](naga777-load-test-guide.md).

---

## Games today

Use this as the source of truth for the four shell variables every command below references. Confirm container names against `docker ps` — they vary by deploy.

| Game                | `GAME`     | `BACKEND_DIR`               | `CONTAINER`                  | `PORT` | Wrapper supports                              |
|---------------------|------------|-----------------------------|------------------------------|--------|-----------------------------------------------|
| Silk Road Caravans  | `silkroad` | `../be-silk-road-caravans`  | `game-silk-road-caravans`    | `3000` | `Basic`, `Soak`, `Stress`, `Spike`            |
| Golden Boat Bonanza | `bonanza`   | `../be-golden-boat-bonanza` | `game-golden-boat-bonanza`   | `3005` | `Basic`, `Soak`, `Grpc`                       |
| Naga's Fortune 777  | `naga777`  | `../Stable_NAGAS_777`       | `stable-naga_fortune_777`    | `3000` | `Grpc`                                        |
| Mutant Merge        | `mutantmerge` | `../Stable_Mutant_Merge` | `stable-game-mutant-merge`   | `3000` | `Grpc`                                        |
| Zero Day            | `zeroday`  | `../be-zero-day`            | `game-zero-day`              | `3000` | `Grpc`                                        |

Backends live in **sibling repos** and aren't built from here. Four more games are stubbed in `settings.gradle` (commented out).

---

## Setup

### Requirements

- **JDK 17** — Gradle's toolchain auto-downloads if missing.
- **Docker** — to bring the backend up locally.
- **Python 3.9+** — `run-variant.sh` uses it to generate `summary.html`.
- macOS or Linux. (Windows untested.)

`./gradlew` bundles Gradle 9.2.1, so no separate Gradle install is needed.

### Verify

```bash
java -version        # 17.x
./gradlew --version  # Gradle 9.2.1, JVM 17
docker --version     # 20+
python3 --version    # 3.9+
```

---

## Quick start

A **VU (virtual user)** = one simulated player. A **smoke test** = the smallest possible run.

Pick your game's row in [Games today](#games-today) and export its variables:

```bash
# Example — Silk Road
export GAME=silkroad
export BACKEND_DIR=../be-silk-road-caravans
export CONTAINER=game-silk-road-caravans
export PORT=3000
```

### 1. Start the backend

```bash
cd "$BACKEND_DIR"
docker compose up -d
```

(Silk Road additionally accepts `ZMQ_PUBLISHER_MOCK=true docker compose up -d` to skip its ZMQ-publisher link.)

(Zero Day's `be-zero-day` container `game-zero-day` needs `LUIGI_WALLET_ENABLED=false` (its `.env.staging` enables the Luigi wallet; any non-`trial` profile then uses the mock wallet), `CHEAT_ENABLED=false`, logging driver `json-file`. Gatling can't see business or internal errors — after each run `docker logs game-zero-day 2>&1 | grep -E "\[gRPC\] (ConnectAndCall|Call) (business )?error" | grep -vc "c=1362"` must print `0`; `c=1362` jackpot-pending rejections block a VU's spins for ~60 s and are expected occasionally, so they are excluded.)

Wait ~30 s for Spring Boot to start.

### 2. Verify the SUT is up

```bash
# Silk Road
curl -s -X POST "http://localhost:$PORT/api/game/caravans/v1/slot/spin" \
  -H 'content-type: application/json' \
  -d '{"userId":"smoke","gameId":"silk_road_usecase","betAmount":1.0,"isBuyFeature":false,"isCheatJackpot":false,"freeGameSplittingSymbol":"A"}'

# Bonanza
curl -s "http://localhost:$PORT/golden/api/configs/bet-levels"

# Naga's Fortune 777
curl -s "http://localhost:$PORT/health"
```

`200 OK` + JSON body = the SUT is up. Else → [Troubleshooting](#troubleshooting).

### 3. Run a 1-minute smoke

```bash
cd -                                  # back to the load-test repo

./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Soak \
  --users 50 --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

(The wrapper also accepts `$GAME` from the exported env as an alternative — either form works.)

### 4. Open the summary

```bash
open "$(ls -td target/variants/$GAME/target-* | head -1)/summary.html"
```

A green **PASS** banner = the test cleared every threshold. Done.

---

## Running tests

### Wrapper flags

```bash
./scripts/run-variant.sh \
  --game <silkroad|bonanza|naga777|mutantmerge|zeroday> \
  --variant <baseline|target|stress|critical> \
  --simulation <Soak|Stress|Spike|Basic|Grpc> \
  --container <name> \
  [--users N] [--duration-minutes N] [--ramp-minutes N] \
  [--port N] [--parallel] [--requests N] [--scenario <name>]
```

| Flag                  | Default     | Required | Notes                                                                |
|-----------------------|-------------|----------|----------------------------------------------------------------------|
| `--game`              | —           | ✅       | `silkroad`, `bonanza`, `naga777`, `mutantmerge`, or `zeroday`. May also be set via the `GAME` env var. **No default** — running without it fails fast (previously a silent silkroad default was a footgun for bonanza runs). |
| `--variant`           | —           | ✅       | Picks the CPU/Mem ceiling — see [Variant ceilings](#variant-ceilings). |
| `--simulation`        | —           | ✅       | Case-insensitive.                                                    |
| `--container`         | —           | ✅       | Falls back to `--port` if the container doesn't exist.               |
| `--users`             | `1000`      |          | Concurrent VU count.                                                 |
| `--duration-minutes`  | `60`        |          | Steady-state duration.                                               |
| `--ramp-minutes`      | `5`         |          | Ramp-up duration.                                                    |
| `--port`              | `3005` for bonanza, `3000` for silkroad, naga777, mutantmerge and zeroday |          | Forwarded to Gatling as `-Dport=`, used to build the health probe URL, and used as monitor-resources fallback port. Default derives from `--game`. |
| `--parallel`          | off         |          | Soak: all VUs at once (no ramp).                                     |
| `--requests`          | unset       |          | Basic: total requests across all VUs.                                |
| `--scenario`          | unset       |          | Basic: endpoint or mode — see [Test one endpoint](#test-one-endpoint-at-a-time). |

`--game` can also come from the `GAME` env var (`export GAME=bonanza` once, or prefix individual commands). The flag wins if both are set.

> **Bonanza:** pass `--game bonanza`. The per-game SLA (PR-4=500 ms / PR-5=0.99 %) and the fallback monitoring port (3005) auto-apply because the wrapper forwards `-DgameName=$GAME` to the verifier and derives `--port` from the game when not passed.

### The simulations

| Simulation | What it does                                                                          | When to use                            | Pass/fail gate                                 |
|------------|---------------------------------------------------------------------------------------|----------------------------------------|------------------------------------------------|
| `Basic`    | Fires `--requests` across `--users` VUs.                                              | Smoke a single endpoint.               | `KO` must be `0`.                              |
| `Soak`     | Ramps to `--users` over `--ramp-minutes`, holds for `--duration-minutes`.             | Catch memory leaks / degradation.      | PR-4 (mean response) + PR-5 (error rate).      |
| `Stress`   | Ramps injection across the run (ramp shape uses simulation defaults).                 | Find the breaking point.               | Measurement only.                              |
| `Spike`    | Steady baseline + N bursts of extra VUs (burst shape uses simulation defaults).       | Test recovery from surges.             | Measurement only.                              |
| `Grpc`     | **Bonanza & Naga777.** Closed-model soak against the WSProxy gRPC plugin (`ConnectAndCall` + `Call`) instead of REST. Naga777 is gRPC-only (no REST spin); spin latency measured is the gRPC ack — the full result is pushed over ZMQ. | Exercise the gRPC surface that the FE uses in production. | PR-4 + PR-5 + global `requestsPerSec ≥ requestRate` + `successfulRequests > eventCount`. |

> **Bonanza doesn't ship `Stress` / `Spike`** — waiting for production baseline to set meaningful defaults. Use silkroad's `Basic --scenario burst` or a `Soak` with high `--users` for burst-shaped traffic.
>
> **Stress / Spike ramp shape isn't tunable through the wrapper** — only `--users / --duration / --ramp` are forwarded. For `usersStart / usersEnd / baseline / spike`, use [Advanced runs](#advanced-runs-without-the-wrapper).
>
> **`Grpc` runs on a different Gatling runtime than the REST simulations** (3.9.5 + the community gRPC plugin, no VU cap) — see [gRPC runtimes](#grpc-runtimes).
>
> **`Grpc` defaults differ:** `rampMinutes=2` (not 5), `paceSec=5`, `requestRate=50` req/s floor, `eventCount=100000` successful-request floor. Defaults to `grpcHost=localhost`; `grpcPort=9091` (bonanza) / `9096` (naga777) / `9104` (mutantmerge) / `9103` (zeroday). The wrapper doesn't expose `--grpc-host` / `--grpc-port` — if you need to override the gRPC endpoint, [run via Gradle directly](#advanced-runs-without-the-wrapper).

### Standard examples

```bash
# Silk Road — production gate (60 min, 1000 VU)
./scripts/run-variant.sh --game silkroad \
  --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 \
  --container game-silk-road-caravans

# Silk Road — headroom check (5 min, 200 VU)
./scripts/run-variant.sh --game silkroad \
  --variant baseline --simulation Soak \
  --users 200 --duration-minutes 5 --ramp-minutes 1 \
  --container game-silk-road-caravans

# Bonanza — production gate (port 3005 auto-derived from --game)
./scripts/run-variant.sh --game bonanza \
  --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 \
  --container game-golden-boat-bonanza

# Bonanza — gRPC soak (WSProxy plugin path, port 9091; bypasses REST)
./scripts/run-variant.sh --game bonanza \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container game-golden-boat-bonanza

# Silk Road — stress
./scripts/run-variant.sh --game silkroad \
  --variant stress --simulation Stress \
  --users 500 --duration-minutes 10 --ramp-minutes 1 \
  --container game-silk-road-caravans

# Naga777 — gRPC production gate (game gRPC-only; port 3000 + health /health auto-derived)
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-naga_fortune_777

# Mutant Merge — gRPC production gate (port 3000 + health /health auto-derived)
./scripts/run-variant.sh --game mutantmerge \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-game-mutant-merge

# Zero Day — gRPC production gate (port 3000 + health /api/game/zeroday/actuator/health auto-derived)
./scripts/run-variant.sh --game zeroday \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container game-zero-day
```

---

## Test one endpoint at a time

Use `--simulation Basic --scenario <name>` to hammer a single endpoint instead of the full player journey.

| Game     | Valid `--scenario` values                                                            |
|----------|---------------------------------------------------------------------------------------|
| silkroad | `spin`, `last-spin`, `history-summary`, plus modes: `all`, `chain`, `burst`           |
| bonanza   | `BetLevels`, `ReelStrips`, `CreateSession`, `Spin`, `JackpotPools`, `HistorySessions` |

> Bonanza's stateful endpoints (`BonusReveal`, `RoundDetail`, …) can't be smoke-tested in isolation — they need state from a prior `Spin`. They run inside `Soak`. To list all valid names: `cat games/<game>/src/gatling/java/com/rgp/loadtest/<game>/utils/Endpoints.java`.

### Template

Assumes `GAME`, `CONTAINER`, `PORT` are exported (Quick start did this); otherwise pass `--game <name>` and replace the `$` variables with literal values.

```bash
./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Basic --scenario <NAME> \
  --users <USERS> --requests <REQUESTS> \
  --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

### Suggested load per endpoint

| Game     | `--scenario`        | `--users` | `--requests` | Notes                                             |
|----------|---------------------|-----------|--------------|---------------------------------------------------|
| silkroad | `spin`              | 500       | 5 000        | Main game action.                                 |
| silkroad | `last-spin`         | 500       | 5 000        | Read-only.                                        |
| silkroad | `history-summary`   | 500       | 5 000        | Read-only.                                        |
| bonanza   | `BetLevels`         | 1 000     | 10 000       | Static config — cheap.                            |
| bonanza   | `ReelStrips`        | 1 000     | 10 000       | Static config — cheap.                            |
| bonanza   | `CreateSession`     | 500       | 2 000        | Heavier — one session created per request.        |
| bonanza   | `Spin`              | 500       | 5 000        | Returns HTTP 201, not 200.                        |
| bonanza   | `JackpotPools`      | 1 000     | 10 000       | Read-only.                                        |
| bonanza   | `HistorySessions`   | 500       | 5 000        | Read-only.                                        |

---

## Understanding outputs

Two run modes produce different output paths:

| Run via                       | Output                                                                          |
|-------------------------------|---------------------------------------------------------------------------------|
| `./scripts/run-variant.sh`    | `target/variants/<game>/<variant>-<timestamp>/` — full bundle (see below).      |
| `./gradlew :games:…` directly | `games/<game>/build/reports/gatling/<simulation>-<timestamp>/` — Gatling HTML only. |

### What's in the bundle

| File                        | What it is                                              | Open when                                      |
|-----------------------------|---------------------------------------------------------|------------------------------------------------|
| `summary.html`              | One-page verdict + charts + embedded Gatling report.    | **Always start here.**                         |
| `verdict.json`              | Same numbers as `summary.html`, machine-readable.       | Automating decisions (CI / dashboards).        |
| `gatling-report/index.html` | Full Gatling HTML report (per-endpoint percentiles).    | Drilling into a specific endpoint / error.     |
| `resource.csv`              | CPU/Mem samples, one row every 5 s.                     | Plotting CPU/Mem timeline.                     |
| `health.csv`                | HTTP-probe samples, one row every 2 s.                  | Checking downtime windows.                     |
| `gatling.log`               | Full Gatling stdout/stderr.                             | Debugging a Gatling failure.                   |

### `summary.html` — top to bottom

1. **Verdict banner** — big green `PASS` / red `FAIL` with game, variant, users, duration, cores.
2. **Run Info** — same fields, table form.
3. **Threshold Check** — the six pass/fail rows (next subsection).
4. **HTTP Requests Summary** — Total / OK / KO / Gatling exit code.
5. **CPU % over time** — sparkline. Red dashed line = ceiling. Y-axis normalised by host core count.
6. **Memory % over time** — same shape.
7. **Gatling HTTP Report** — full report in an iframe. *Fullscreen* button to expand.

### The six threshold rows

**All six must be PASS for the verdict to be PASS.**

| Row                              | `verdict.json` field        | Checks                                                                | Default ceiling                |
|----------------------------------|-----------------------------|-----------------------------------------------------------------------|--------------------------------|
| **PR-4: Mean response**          | `pr4_pass`, `mean_response_ms` | Gatling overall mean ≤ `mean_ms_ceil`.                              | 500 ms (silkroad: 300).        |
| **PR-5: Error rate (KO%)**       | `pr5_pass`, `http_ko_percent`  | HTTP KO % ≤ `http_ko_ceil` **or** Gatling exit code = 0.            | 1.0 % (bonanza: 0.99).          |
| **CPU p95**                      | `cpu_pass`, `cpu_p95`          | 95th-percentile CPU % ≤ variant ceiling.                            | 70 % at `target`.              |
| **Mem p95**                      | `mem_pass`, `mem_p95`          | 95th-percentile Mem % ≤ variant ceiling.                            | 80 % at `target`.              |
| **PR-3: Crash detection**        | `crash_pass`, `max_consecutive_failures` | < N consecutive 2-second probe failures (≈ 6 s downtime). | < 3.                            |
| Response time p95                | `p95_response_ms`              | Informational — not a gate.                                          | —                              |

> **CPU normalisation:** `docker stats` reports container CPU aggregated across cores (600 % on a 12-core box). The verifier divides by host cores first — 600 % / 12 = 50 %.

### When a row fails — where to look

| Failing row              | First file to open               | Look for…                                                            |
|--------------------------|----------------------------------|----------------------------------------------------------------------|
| `pr4_pass` (slow mean)   | `gatling-report/index.html`      | Per-endpoint table — endpoint with high mean or p95.                 |
| `pr5_pass` (errors)      | `gatling-report/index.html`      | "Errors" tab — error type + originating endpoint.                    |
| `cpu_pass` / `mem_pass`  | `resource.csv` in Excel          | Plot `cpu_pct` / `mem_pct` over `timestamp` — spikes match slow window. |
| `crash_pass`             | `health.csv`                     | Rows with `http_status=0` or `total_seconds≥5.0`. Three in a row = crash. |

### CSV columns

`resource.csv` — header `timestamp,cpu_pct,mem_pct,mem_used,net_io,block_io`, one row per 5 s.
- `cpu_pct` — aggregated across container cores. Divide by host cores to compare with the ceiling.
- `mem_pct` — % of host total. `mem_used` is human-readable (`512MiB`).
- `N/A` rows = the container wasn't found at that tick.

`health.csv` — header `timestamp,http_status,total_seconds`, one row per 2 s.
- `http_status=0` = network error / timeout. `total_seconds=5.000` = `curl` hit its 5 s timeout.
- A row is a "failure" if `http_status` is non-2xx **or** `total_seconds ≥ 5.0`.

### Reading the Gatling HTML report

| Tab / section               | Use for                                                                       |
|-----------------------------|-------------------------------------------------------------------------------|
| **Global statistics**       | Per-endpoint table: count, OK/KO, min / mean / p50–p99 / max response.        |
| **Response time ranges**    | Bar chart: `<800ms` / `<1200ms` / `>1200ms` / failed.                         |
| **Response time over time** | Mean-response line chart. Trends = memory leak; spikes = GC pauses.           |
| **Active users over time**  | Confirms the injection profile matched expectations.                          |
| **Errors**                  | Each error type + count + sample message. Fastest path to a KO diagnosis.     |
| **Requests / endpoint**     | Per-endpoint drill-down: RT-over-time + percentile table.                     |

### Reading the console mid-run

Every ~5 s while Gatling runs:

```
> Global                                          (OK=12500   KO=0     )
silk-road-session-journey: 50.0 / 50.0
```

`OK` = HTTP 2xx; `KO` = timeout / 4xx / 5xx / failed check. **`KO=0`** is what you want.

At the end, the `Global Information` block shows columns `Total | OK | KO`. The verifier reads `request count`, `mean response time`, and `response time 95th percentile` from this block.

---

## Configuration

### Variant ceilings

| Variant    | CPU% p95 ceiling | Mem% p95 ceiling | When to use                                |
|------------|------------------|------------------|--------------------------------------------|
| `baseline` | 50               | 60               | Headroom check — server has plenty of room.|
| `target`   | 70               | 80               | **Production gate** — default for deploys. |
| `stress`   | 85               | 90               | Saturation-behaviour study.                |
| `critical` | 95               | 95               | Pre-failure validation.                    |

### Change a pass/fail threshold (no code edit)

Thresholds live in YAML — edit, save, re-run.

[`config/sla-thresholds.yml`](config/sla-thresholds.yml) sets repo-wide defaults:

```yaml
sla:
  pr4_mean_response_ms_max: 500   # PR-4 mean response ceiling (ms)
  pr5_error_percent_max: 1.0      # PR-5 error rate ceiling (%)
crash:
  max_consecutive_failures: 3     # PR-3 crash threshold
variants:
  target:
    cpu_ceil: 70                  # production-gate CPU
    mem_ceil: 80
```

**Per-game override:** `games/<game>/src/gatling/resources/sla-thresholds.yml` — list only fields you want different. Shipped:

- silkroad: `pr4_mean_response_ms_max: 300` (tighter).
- bonanza:   `pr4_mean_response_ms_max: 500`, `pr5_error_percent_max: 0.99` (matches legacy).

Load order (top wins, deep-merged with layers below):

1. `-DslaConfig=/abs/path.yml` (full override).
2. `games/<game>/src/gatling/resources/sla-thresholds.yml` (loaded when `-DgameName` is set; the wrapper sets it via `GAME=`).
3. `config/sla-thresholds.yml`.
4. `classpath:sla-thresholds.yml` (bundled fallback).

The merged source path is recorded in `verdict.json` as `sla_config_source`.

### Direct-Gradle `-D…` flags (advanced)

These reach simulations only when you call `./gradlew :games:…` directly. Only names in each game's `FORWARDED_PROPS` (in `games/<g>/build.gradle`) are passed through — otherwise silently dropped.

**Shared:** `users`, `requests`, `durationMinutes`, `rampMinutes`, `host`, `port`, `contextPath`, `thinkTimeMin`, `thinkTimeMax`, `scenario`, `parallel`, `maxResponseTimeMs`.

**Silkroad-only:** `usersStart`, `usersEnd` (Stress); `baseline`, `spike`, `spikeDurationSec`, `cycles`, `cycleIntervalMinutes` (Spike).

**Bonanza, Naga777, Mutant Merge & Zero Day:** `grpcHost`, `grpcPort` (gRPC); `paceSec`, `requestRate`, `eventCount` (bonanza Soak + all gRPC sims). **Naga777-only:** `coinValue`, `coinPerLine` (bet shape — server derives bet = coinValue × coinPerLine × 5). **Mutant Merge-only:** `betLevelId` (1-based index into the bet ladder, default 3 = $1.00), `superBet` (`true` = super bet, debit × 1.2). **Zero Day-only:** `bet` (decimal string on the bet ladder 0.20–100.00, default 1.00; off-ladder values fail at load).

> `usersStart` / `usersEnd` / `baseline` are users **per minute** — the simulation divides by 60 internally.

---

## Advanced runs (without the wrapper)

Use `./gradlew :games:<game>:<alias>` directly only when you need:

- **Tuning Stress / Spike ramp shape** (`-DusersStart`, `-Dbaseline`, …) that the wrapper doesn't pass through.
- **Overriding the gRPC endpoint** (`-DgrpcHost`, `-DgrpcPort`) — the wrapper doesn't expose these flags.
- **Running an arbitrary simulation by FQCN(Fully Qualified Class Name).**
- **Fast iteration while developing a game** — skip the monitor startup, accept no verdict.

You get the Gatling HTML report at `games/<game>/build/reports/gatling/<sim>-<timestamp>/index.html`, but **no `summary.html`, no `verdict.json`, no CSVs.**

```bash
# Silkroad
./gradlew :games:silkroad:basic  -Dscenario=spin -Dusers=1 -Drequests=1
./gradlew :games:silkroad:stress -DusersStart=10 -DusersEnd=200 -DdurationMinutes=5
./gradlew :games:silkroad:spike  -Dbaseline=5  -Dspike=50 -Dcycles=3 -DcycleIntervalMinutes=1

# Bonanza REST (port 3005 + contextPath /golden come from games/bonanza/src/gatling/resources/game.yml;
#              override with -Dport / -DcontextPath only when targeting staging/prod)
./gradlew :games:bonanza:soak -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1

# Bonanza gRPC (separate gRPC channel — no contextPath; override host/port for staging)
./gradlew :games:bonanza:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=localhost -DgrpcPort=9091

# Arbitrary simulation by FQCN
./gradlew :games:silkroad:gatlingRun \
  --simulation com.rgp.loadtest.silkroad.simulations.SoakSimulation

# Re-run the verifier on existing CSVs
./gradlew verifyVariant \
  -DresourceCsv=target/variants/silkroad/target-…/resource.csv \
  -DhealthCsv=target/variants/silkroad/target-…/health.csv \
  -DgatlingLog=target/variants/silkroad/target-…/gatling.log \
  -Dvariant=target -Dusers=1000 -DdurationSec=3900 -DgameName=silkroad
```

---

## Troubleshooting

| Symptom                                                   | Likely cause / fix                                                                                                  |
|-----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| `Connection refused` / curl smoke fails                   | SUT isn't up. `docker ps --filter name=<container>` and `docker logs <container> 2>&1 \| tail -50`. Usually Mongo/Redis still starting — wait 30 s. |
| Test runs but ~51 % return HTTP 400                       | Body JSON uses `${userId}` instead of Gatling EL `#{userId}` (Gatling 3.7+ Java DSL). `grep -r '\${' games/<game>/src/gatling/resources/` to find them. |
| `--game required` / `--container required` / `--variant required` / `--simulation required` | The wrapper requires all four. `--game` can also come from `GAME` env var. Pass any container name + `--port N` to monitor a non-Docker process. |
| `BUILD FAILED: simulation class not found`                | FQCN in `build.gradle`'s `SIMULATIONS` map doesn't match the Java file's `package`. Verify with `grep "^package" …`. |
| `InaccessibleObjectException` at simulation init          | JDK 17 module access. Confirm `./gradlew --version` says JVM 17 — `--add-opens` is already set in `gatling.jvmArgs`. |
| Port 3000 / 3005 already in use                           | `lsof -ti :<port> -sTCP:LISTEN \| xargs kill -9`.                                                                  |
| `resource.csv` is all `N/A`                               | Container name passed to `--container` doesn't exist. `docker ps --filter name=<your-container>`, or rely on `--port` fallback. |
| `health.csv` shows constant `000` / `5.000`               | Probe URL doesn't return 2xx. `run-variant.sh` derives the URL per game: silkroad → `http://localhost:$PORT/actuator/health`; bonanza → `http://localhost:$PORT/golden/api/configs/bet-levels`. Verify the SUT is on that port + path, or pass `--port N` to override. |
| `summary.html` missing after a run                        | Python 3 not installed, or `generate-summary-html.py` failed. `verdict.json` is still authoritative.                |
| gRPC needs a non-default endpoint                          | The wrapper has no `--grpc-host` / `--grpc-port` flags. Run via `./gradlew :games:bonanza:grpc -DgrpcHost=… -DgrpcPort=…` directly (no `summary.html` / `verdict.json`). |

---

## Project layout

### Tech stack & versions

Pinned in `build.gradle` at the repo root.

| Component                | Version    | Where it's used                                                                 |
|--------------------------|------------|---------------------------------------------------------------------------------|
| Java                     | **17**     | Every simulation + the `:core` library.                                         |
| Gatling (REST sims)      | **3.15.0** | silkroad, and bonanza's `Soak` / `Basic`.                                       |
| Gatling Gradle plugin    | 3.15.0.2   | REST test-run wiring (`io.gatling.gradle`) — the gRPC sims don't use it.        |
| Gradle wrapper           | 9.2.1      | Bundled — no separate install.                                                  |
| Scala library            | **2.13.12** | Only by the bonanza, naga777, mutantmerge & zeroday gRPC simulations — see [Scala / gRPC](#scala--grpc-simulation). |
| Gatling (gRPC sims)      | **3.9.5**  | bonanza, naga777, mutantmerge & zeroday `Grpc` — see [gRPC runtimes](#grpc-runtimes).           |
| gRPC DSL                 | `com.github.phisgr:gatling-grpc` 0.17.0 | Community plugin, no VU cap. Replaced Gatling's Enterprise-gated gRPC DSL. |
| gRPC core / Protobuf     | 1.75.0 / 4.32.1 | Generated stubs in `:core` (used only by the gRPC sim).                    |
| Python                   | 3.9+       | `generate-summary-html.py` (post-run report).                                   |

### gRPC runtimes

All gRPC simulations run on **Gatling 3.9.5** with the community plugin
`com.github.phisgr:gatling-grpc` — not on the Gatling 3.15 used by the REST simulations.

The reason is a hard limit. Gatling 3.15's first-party gRPC DSL (`io.gatling:gatling-grpc-java`)
is a Gatling Enterprise feature; without a licence it runs in trial mode and **aborts the
simulation above 5 concurrent VUs or 5 minutes**. At 10 VUs the run dies within seconds with
`Some of the simulations crashed`, never reaching user #6 — so the 1000-VU production gate
documented above was impossible for either gRPC game.

The community plugin is Apache 2.0 and has no cap, but its last release (0.17.0) targets Gatling
3.9.5, and the matching `io.gatling.gradle` 3.9.5.x fails on Gradle 9
(`unknown property 'reportsDir'`). So the gRPC simulations skip the Gatling Gradle plugin
entirely: each builds its own Gatling 3.9.5 classpath in a dedicated configuration and launches
`io.gatling.app.Gatling` through a plain `JavaExec` task.

- **naga777** is gRPC-only, so its whole module is on 3.9.5 (`gatlingRt` configuration).
- **mutantmerge** is gRPC-only too, same layout as naga777.
- **zeroday** is gRPC-only too, same layout as naga777.
- **bonanza** ships both, so it is split: `src/gatling/java` (REST) stays on 3.15 under the
  Gatling Gradle plugin, while `src/gatlingGrpc/scala` compiles and runs against 3.9.5
  (`gatlingGrpcRt` configuration). The two classpaths never mix.
- **silkroad** is REST-only and untouched — Gatling's HTTP DSL is fully OSS and uncapped, so
  there is no reason to move it.

Everything downstream works unchanged: the wrapper, the threshold verifier, `summary.html`.
Because Gatling 3.9 prints its console summary as
`> request count  640 (OK=640  KO=0 )` instead of the pipe-delimited table 3.15 uses, the
threshold verifier parses both shapes.

**Caveat:** the community plugin is **archived upstream** (last commit Feb 2024) and will never
support Gatling 3.10+. If the REST simulations are ever moved to a newer Gatling, the gRPC ones
stay behind on 3.9.5 unless someone forks the plugin or buys an Enterprise licence.

### Directory tree

```
.
├── build.gradle                  shared Java 17 + version aliases
├── settings.gradle               include :core + each game subproject
├── config/sla-thresholds.yml     repo-wide pass/fail defaults
├── core/                         shared library — DON'T edit when adding a game
│   └── src/main/java/com/rgp/loadtest/core/
│       ├── config/               LoadTestConfig + SlaConfig + SlaConfigLoader (YAML merge)
│       ├── scenarios/            SessionJourneyTemplate (generic loop + ratio)
│       ├── simulations/          {Soak,Stress,Spike,Basic}SimulationBase
│       ├── utils/                SlaConstants, SystemProps
│       └── verify/               ThresholdVerifier (writes verdict.json)
├── games/
│   ├── silkroad/                 open-model, stateless, REST only — Java only
│   │   └── src/gatling/java/     Java sources (simulations, scenarios, requests, utils)
│   └── bonanza/                   closed-model, stateful — REST (Java) + gRPC (Scala)
│       └── src/gatling/
│           ├── java/             Java sources (REST sim + everything else)
│           └── scala/            BonanzaGrpcSimulation.scala — gRPC sim (Scala)
├── scripts/
│   ├── run-variant.sh            ⭐ canonical entry point
│   ├── monitor-resources.sh      docker stats → resource.csv
│   ├── health-poll.sh            HTTP probe → health.csv
│   └── generate-summary-html.py  builds summary.html
├── docs/                         long-form guides
└── target/variants/<game>/<variant>-<ts>/   per-run output bundle
```

### Layers inside a game subproject

| Layer            | Responsibility                                                                  |
|------------------|---------------------------------------------------------------------------------|
| `simulations/`   | Injection profile, SLA assertions, HTTP protocol.                               |
| `scenarios/`     | Compose requests into a journey (loop + think-time + ratio / randomSwitch).     |
| `requests/`      | One HTTP call: URL + method + body + check + session captures.                  |
| `bodies/*.json`  | Body templates — Gatling EL `#{userId}` syntax.                                 |

### Two journey patterns

| Aspect       | Silkroad (template)                              | Bonanza (stateful)                                              |
|--------------|--------------------------------------------------|----------------------------------------------------------------|
| Injection    | Open (`rampUsers` / `atOnceUsers`)               | Closed (`rampConcurrentUsers` + `constantConcurrentUsers`)     |
| Routing      | Ratio-modulo (`userIndex % N == 0`)              | `randomSwitch` weighted (80/8/5/3/2/2)                         |
| State        | Stateless                                        | Captures `sessionId` / `roundId` / `gameId` / `bonusTriggered` |
| Init         | None                                             | Per-VU: `BetLevels` → `ReelStrips` → `CreateSession`, then loop |
| Endpoints    | 3                                                | 10                                                             |
| Spin status  | `200`                                            | **`201`**                                                      |
| Languages    | Java only                                        | Java (REST) + Scala (gRPC) — see next subsection                |

### Scala / gRPC simulation

The whole harness is Java except for **four files** — the bonanza, naga777, mutantmerge and zeroday gRPC tests:

```
games/bonanza/src/gatlingGrpc/scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala
games/naga777/src/gatling/scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala
games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala
games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala
```

**Why a Scala file at all?** The gRPC sims use the community plugin `com.github.phisgr:gatling-grpc` on Gatling 3.9.5 (see [gRPC runtimes](#grpc-runtimes)), and that plugin only ships a Scala DSL — so these simulations are written in Scala. Their payloads are still the Java proto stubs and the MessagePack `Codec` from `:core`.

**Versions:** Scala `2.13.12` (binary), Gatling `3.15.0`, `gatling-grpc:3.15.0`. Pinned in [`games/bonanza/build.gradle`](games/bonanza/build.gradle).

**How it's wired** (`games/bonanza/build.gradle`):

```groovy
plugins {
    id 'java'
    id 'scala'                      // adds the Scala source set
    id 'io.gatling.gradle' version "${gatlingGradleVersion}"
}

dependencies {
    gatlingImplementation project(':core')
    gatlingImplementation "org.scala-lang:scala-library:2.13.12"          // Scala runtime
    gatlingImplementation "io.gatling:gatling-grpc:${gatlingVersion}"     // Gatling's Scala-side gRPC artifact
}
```

The Gatling plugin then compiles both `src/gatling/java/` and `src/gatling/scala/` into the same run classpath. (The Project layout tree above shows both source sets.)

**Run it** — either through the wrapper (gets `summary.html` + verdict) or direct Gradle (faster, no verdict):

```bash
# Wrapper — defaults gRPC endpoint to localhost:9091
./scripts/run-variant.sh --game bonanza --variant target --simulation Grpc \
  --users 10 --duration-minutes 5 --ramp-minutes 1 \
  --container game-golden-boat-bonanza

# Gradle direct — needed to override the gRPC host/port (wrapper has no flag for these)
./gradlew :games:bonanza:grpc \
  -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=staging.example.com -DgrpcPort=9091
```

**Wire format:** uses MessagePack-encoded blobs inside `ConnectAndCallRequest.user.parameters` and `PluginRequest.data` (matches the WSProxy plugin's GaaS decoder). Wire-format drift surfaces server-side as `UserContext.fromPuObject` NPEs — keep the `LinkedHashMap` insertion order intact when changing payloads.

To add a gRPC sim for a new game, see [Add a new game](#add-a-new-game) — clone `games/bonanza/` (keep the Scala source set).

---

## Add a new game

**Don't edit `:core`.** Clone the closest existing game, then walk through each phase below.

The example below adds `fruit-respin-mania` running on `localhost:4000` with context path `/fruit`. The new game has three endpoints (`spin`, `last-spin`, `history-summary`) and follows the stateless silkroad pattern. Substitute your real values everywhere you see `fruit-respin-mania` / `fruitrespinmania` / `4000` / `/fruit`.

### Phase 0 — Plan (don't skip this)

Before touching code, write the following down. Every later step depends on these decisions.

| Decision                       | Where it shows up                                                                       | Example                            |
|--------------------------------|------------------------------------------------------------------------------------------|------------------------------------|
| **Gradle project name**        | `:games:<NAME>` task path; `settings.gradle`; container output dir                       | `fruit-respin-mania` (kebab-case)  |
| **Java package name**          | `com.rgp.loadtest.<PKG>` ; resource subdir `games/<PKG>/bodies/`                         | `fruitrespinmania` (lowercase, no separators — Java identifiers can't contain `-`) |
| **Pattern**                    | Which game to clone — see decision table below                                           | silkroad (stateless REST)          |
| **Host / port / contextPath**  | `games/<NAME>/src/gatling/resources/game.yml`                                            | `localhost` / `4000` / `/fruit`     |
| **Endpoint list**              | `utils/Endpoints.java` constants; `requests/SlotRequests.java` URL paths                 | `spin`, `last-spin`, `history-summary` |
| **Spin response status code**  | `status().is(...)` in `SlotRequests.spin()`                                              | `200` (silkroad) or `201` (bonanza) |
| **Auth shape**                 | Body JSON template (`bodies/*.json`)                                                     | `{userId, gameId, betAmount}` for silkroad-shaped; `{playerId, ...}` for bonanza-shaped |
| **Docker container name**      | `--container` flag at run time                                                           | `game-fruit-respin-mania`          |
| **Per-game SLA?**              | `games/<NAME>/src/gatling/resources/sla-thresholds.yml` (optional)                       | PR-4 ≤ 400 ms                      |

> ⚠️ **Gradle name vs Java package name diverge on purpose.** Gradle accepts kebab-case (`fruit-respin-mania`); Java identifiers cannot contain `-`. Keep the kebab name for the Gradle project + container + run-variant `--game`; keep the lowercase no-separator form for Java packages + resource subdirs. The body file path inside `ElFileBody(...)` uses the **Java package** form.

#### Decision table — which template to clone

| Your game's shape                                                          | Clone…                                                                            | Languages              |
|----------------------------------------------------------------------------|-----------------------------------------------------------------------------------|------------------------|
| Stateless REST — each request is independent, no session state            | `games/silkroad/`                                                                  | Java only.             |
| Stateful REST — init phase (create session) then loop with captured state | `games/bonanza/` and **delete `src/gatling/scala/`** (also drop `id 'scala'` + Scala/grpc deps from `build.gradle`) | Java only.             |
| gRPC plugin path (with or without REST alongside)                          | `games/bonanza/` and **keep the Scala source set**                                 | Java + Scala 2.13.12.  |

Where to find the backend's endpoint contract: read `../be-<game>/app/src/main/java/.../presentation/http/*Controller.java` (or the equivalent), or the FE integration doc if one exists.

---

### Phase 1 — Clone the template

```bash
# Variables — set once, used throughout the steps below.
NEW=fruit-respin-mania      # kebab-case, for Gradle: `:games:fruit-respin-mania`
PKG=fruitrespinmania        # lowercase, no separators — Java package + resource subdir
TEMPLATE=silkroad           # or `bonanza` if you need the stateful pattern

# 1.1  Copy the template directory
cp -r games/$TEMPLATE games/$NEW

# 1.2  Drop any stale build output from the template
rm -rf games/$NEW/build

# 1.3  Rename the Java package directory
mv games/$NEW/src/gatling/java/com/rgp/loadtest/$TEMPLATE \
   games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG

# 1.4  Rename the resource directory (body templates live here — path is referenced from Java)
mv games/$NEW/src/gatling/resources/games/$TEMPLATE \
   games/$NEW/src/gatling/resources/games/$PKG

# 1.5  If you cloned bonanza, also rename the Scala dir (skip for silkroad — there's no Scala there)
[[ -d games/$NEW/src/gatling/scala/com/rgp/loadtest/$TEMPLATE ]] && \
  mv games/$NEW/src/gatling/scala/com/rgp/loadtest/$TEMPLATE \
     games/$NEW/src/gatling/scala/com/rgp/loadtest/$PKG

# 1.6  Bulk-rewrite the template name to the new Java package name in all source files
#      (BSD sed -i '' — works on macOS; Linux is `sed -i` without the empty arg)
grep -rl "$TEMPLATE" games/$NEW/src | xargs sed -i '' "s/$TEMPLATE/$PKG/g"

# 1.7  Sanity check — no template name should remain in the cloned source
grep -rln "$TEMPLATE" games/$NEW/src && echo "still references $TEMPLATE — investigate" || echo "OK clean"
```

> **What sed step 1.6 touched:** package declarations, imports, ElFileBody path strings, scenario-name constants. If the template's name appeared inside Javadoc/comments, those got renamed too — that's fine.

---

### Phase 2 — Wire into Gradle

**2.1** Open `settings.gradle` and add the new subproject (alongside the existing `include` lines, **not** under the commented stubs):

```groovy
include ':core'
include ':games:silkroad'
include ':games:bonanza'
include ':games:fruit-respin-mania'          // ← add this line
```

**2.2** Open `games/$NEW/build.gradle` and update the `SIMULATIONS` map so the FQCNs point at the new package. If you cloned silkroad the map already has four aliases — change every `silkroad` segment to `$PKG`:

```groovy
def SIMULATIONS = [
    soak  : 'com.rgp.loadtest.fruitrespinmania.simulations.SoakSimulation',
    stress: 'com.rgp.loadtest.fruitrespinmania.simulations.StressSimulation',
    spike : 'com.rgp.loadtest.fruitrespinmania.simulations.SpikeSimulation',
    basic : 'com.rgp.loadtest.fruitrespinmania.simulations.BasicSimulation',
]
```

If your new game needs a `-D<flag>` that the template doesn't already forward (e.g. a custom rate, an auth-token override), append the name to `FORWARDED_PROPS` in the same file — otherwise Gradle silently drops the flag before Gatling sees it.

**2.3** Verify Gradle can now load the project:

```bash
./gradlew :games:$NEW:tasks --group=gatling
```

Expected output: a `gatling` task group listing each alias from your `SIMULATIONS` map (e.g. `basic`, `soak`, `stress`, `spike`). If you see `Project :games:$NEW not found`, you forgot step 2.1. If a task is missing, check 2.2.

---

### Phase 3 — Configure runtime (host / port / context path)

If your game runs on the default `localhost:3000` with no context path you can skip this. Otherwise edit `games/$NEW/src/gatling/resources/game.yml`:

```yaml
http:
  host: localhost            # only set if not localhost
  port: 4000                 # only set if not 3000
  contextPath: /fruit        # only set if not empty
```

Only list fields you want to override — `LoadTestConfigLoader` deep-merges this onto `core/src/main/resources/load-test-defaults.yml`. Anything you put here can still be overridden at run time with `-Dhost=…`, `-Dport=…`, `-DcontextPath=…`.

---

### Phase 4 — Endpoint definitions and request layer

**4.1** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/utils/Endpoints.java` — these constants name your endpoints in the Gatling report and act as values for `--scenario`:

```java
public static final String SPIN = "spin";                        // POST  /fruit/v1/slot/spin
public static final String LAST_SPIN = "last-spin";              // POST  /fruit/v1/slot/last-spin
public static final String HISTORY_SUMMARY = "history-summary";  // GET   /fruit/v1/history/summary
```

Use short, dash-separated names — they show up in CLI flags. Add one constant per endpoint your game exposes.

**4.2** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/requests/SlotRequests.java` — one method per endpoint. For each method update **all** of:

| Edit                        | Example                                                                                  |
|-----------------------------|------------------------------------------------------------------------------------------|
| HTTP method                 | `.post(...)` vs `.get(...)`                                                              |
| URL path (after context)    | `.post("/v1/slot/spin")` — leading `/` only, no host/contextPath (those come from `game.yml`) |
| Body file path              | `.body(ElFileBody("games/fruitrespinmania/bodies/spin.json"))` — the literal contains the **`$PKG`** form, **not** `$NEW`. The sed pass in Phase 1.6 already substituted this if the original used the template's name. |
| Response status check       | `.check(status().is(200))` (silkroad-style 200; bonanza spin returns **201**)            |
| Stateful captures (optional) | `.check(jsonPath("$.data.sessionId").saveAs("sessionId"))` — needed only if a later step reads `#{sessionId}` |

**4.3** `games/$NEW/src/gatling/resources/games/$PKG/bodies/*.json` — one file per endpoint that POSTs a body. Update each:

```json
{
  "userId": "#{userId}",
  "gameId": "fruit_respin_mania_usecase",
  "betAmount": 1.0
}
```

Two rules that come up over and over:

- **Use Gatling EL `#{userId}`, never `${userId}`.** The latter ships a literal string `${userId}` to the backend — every VU sends the same value → distributed lock collisions → ~51 % HTTP 400.
- **Match the backend's exact field types.** A JSON string vs JSON number is a different request. Bonanza's `/spin` accepts `betAmount` as a **string** but `/jackpot/*` accepts it as a **number** — both are valid in their respective contexts, neither is interchangeable.

---

### Phase 5 — Scenario layer

`games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/scenarios/SessionJourneyScenario.java` (if you cloned silkroad) or `PlayerJourneyScenario.java` (bonanza).

**5.1** Rename `DEFAULT_NAME` (silkroad) / `NAME` (bonanza) to be **globally unique** across all games:

```java
public static final String DEFAULT_NAME = "fruit-respin-session-journey";
```

Gatling 3.15 refuses to register two scenarios with the same name in the same `setUp()` block. If two games use the same default name, anyone running both concurrently — e.g. a CI matrix run — hits the conflict. Format: `<game>-<purpose>`.

**5.2** If you're adapting the silkroad ratio-modulo routing, edit the conditional that picks secondary endpoints (e.g. "every 5th VU calls `last-spin`"). The template uses `userIndex % N == 0` against the feeder's `userIndex`. Update `N` and the secondary requests to match your game.

**5.3** If you're adapting the bonanza pattern, edit the init chain (`betLevels → reelStrips → createSession`) and the `randomSwitch` weights. Drop entries that don't apply.

---

### Phase 6 — Simulation layer

The `simulations/` classes are usually thin wrappers around the base. Most edits are already done by the sed pass in Phase 1.6. Re-check these specifically:

| File (if cloned from silkroad)        | What to verify                                                                          |
|---------------------------------------|------------------------------------------------------------------------------------------|
| `SoakSimulation.java`                  | Compiles. The `DEFAULT_NAME` from §5.1 is what shows up in reports.                     |
| `StressSimulation.java`                | Compiles. Same.                                                                          |
| `SpikeSimulation.java`                 | **Has two scenario names hardcoded** — `"silkroad-spike-baseline"` / `"silkroad-spike-burst"`. Rename both to `"$NEW-spike-baseline"` / `"$NEW-spike-burst"`. Sed missed them only if the original string wasn't `silkroad` (which it is by default — sed should catch it; double-check anyway). |
| `BasicSimulation.java`                 | The `endpoints()` list must match what's in `Endpoints.java` and `SlotRequests.java`.   |

For the bonanza pattern, you only need `SoakSimulation` + `BasicSimulation` (+ optionally `BonanzaGrpcSimulation.scala` if you kept the Scala source set). The Soak class **extends `Simulation` directly** (not `SoakSimulationBase`) because the base hard-codes open-model injection — leave that alone.

---

### Phase 7 — Per-game SLA threshold overrides (optional)

If the new game has different performance characteristics, drop a YAML at `games/$NEW/src/gatling/resources/sla-thresholds.yml` listing only the fields you want different. Anything you don't list inherits from `config/sla-thresholds.yml`:

```yaml
sla:
  pr4_mean_response_ms_max: 400   # tighter than the default 500 ms
  pr5_error_percent_max: 0.5      # tighter than the default 1.0 %
```

The wrapper auto-applies these because Gradle injects `-DgameName=$NEW` for the matching subproject task.

---

### Phase 8 — Wire the wrapper script

`scripts/run-variant.sh` has two per-game switches you must extend, otherwise the wrapper either picks the wrong port or probes the wrong health URL.

**8.1** Default port — find the `case "$GAME"` block that sets `PORT` and add your new game:

```bash
case "$GAME" in
  bonanza)            PORT=3005 ;;
  silkroad)           PORT=3000 ;;
  fruit-respin-mania) PORT=4000 ;;       # ← add
  *)                  PORT=3000 ;;
esac
```

**8.2** Health probe URL — find the `case "$GAME"` block that builds `HEALTH_URL` and add a row that returns 2xx for your game:

```bash
case "$GAME" in
  bonanza)            HEALTH_URL="http://localhost:${PORT}/golden/api/configs/bet-levels" ;;
  silkroad)           HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
  fruit-respin-mania) HEALTH_URL="http://localhost:${PORT}/fruit/actuator/health" ;;   # ← add
  *)                  HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
esac
```

Pick a path that's cheap (under 50 ms typical) and returns a `2xx` status on a healthy backend. Spring Boot's `/actuator/health` is the safe default if it's exposed; otherwise pick a small read-only endpoint like a static config fetch.

---

### Phase 9 — Update the docs

**9.1** README.md → `## Games today` table — add a row:

```markdown
| Fruit Respin Mania | `fruit-respin-mania` | `../be-fruit-respin-mania` | `game-fruit-respin-mania` | `4000` | `Basic`, `Soak`, `Stress`, `Spike` |
```

**9.2** If your team uses HUONG-DAN.md or `docs/getting-started.md`, mention the new game there too (the simulations table and the per-game CONTAINER/PORT examples).

---

### Phase 10 — Smoke test and verify

Run these in order. If any step fails, go back to the phase that produced that artifact.

**10.1** Compile — catches package, import, and FQCN errors:

```bash
./gradlew :games:$NEW:compileGatlingJava :games:$NEW:compileGatlingScala
```

Expected: `BUILD SUCCESSFUL`. If you cloned silkroad there's no Scala source so `compileGatlingScala` will report `NO-SOURCE` — that's fine.

**10.2** Confirm `--scenario` resolves — `Basic` mode against your first endpoint, 1 VU × 1 request. Backend doesn't need to be up yet; this only verifies the simulation initializes:

```bash
./gradlew :games:$NEW:basic -Dscenario=spin -Dusers=1 -Drequests=1
```

If this fails with `Unknown scenario:`, the name in `Endpoints.java` doesn't match what you typed for `-Dscenario`. If it fails with `simulation class not found`, the FQCN in `build.gradle`'s `SIMULATIONS` map doesn't match the package declaration in the Java file.

**10.3** Bring up the backend, then smoke end-to-end through the wrapper:

```bash
# Backend (in a separate terminal, from the sibling repo)
cd ../be-fruit-respin-mania && docker compose up -d
cd -

# Smoke through the wrapper
./scripts/run-variant.sh --game $NEW \
  --variant target --simulation Basic --scenario spin \
  --users 1 --requests 1 \
  --duration-minutes 1 --ramp-minutes 0 \
  --container game-fruit-respin-mania
```

A green `PASS` verdict at `target/variants/$NEW/target-<timestamp>/summary.html` means every piece is wired: Gradle subproject → simulation class → HTTP requests with correct paths → bodies with the right schema → health probe → resource monitor → verdict.

**10.4** Once the smoke passes, ramp up: a 5-minute Soak at moderate VU count, then the production-gate Soak.

---

### Developer gotchas (quick reference)

- **Gatling EL: `#{userId}`**, not `${userId}` (Gatling 3.7+ Java DSL). Wrong form ships a literal string and triggers lock collisions on the backend — manifests as ~50 % HTTP 400.
- **Bonanza spin returns HTTP 201**, silkroad returns 200. `status().is(…)` is per-game.
- **Bonanza field names** are inconsistent on purpose (matches the backend): `/sessions` and `/spin` use `playerId`; `/jackpot/*` and `/history/*` use `userId`. `betAmount` is a JSON string for `/spin` and a number for `/jackpot/*`.
- **Scenario names must be globally unique within a `setUp()`.** Spike sims have two populations — pass distinct names (Phase 6).
- **A new `-D` flag silently dropped?** Add it to `FORWARDED_PROPS` in `games/<game>/build.gradle` (Phase 2.2). `gameName` is auto-injected by the Gradle alias task; you don't need to add it.
- **Body file path uses `$PKG`, not `$NEW`.** `ElFileBody("games/" + PKG + "/bodies/spin.json")` — the resource directory is renamed to the Java-package form in Phase 1.4 because that's what gets bundled at the classpath root.
- **JDK 17 `--add-opens` flags are mandatory.** Already set in the cloned `build.gradle` under `gatling.jvmArgs`. Don't drop them — Gatling 3.15 reflects into `StringInternals` and you'll see `InaccessibleObjectException` at simulation init.
- **No git repo at this directory.** `git status` won't work here; the parent workspace tracks history elsewhere.

---

## Where to learn more

- [`docs/getting-started.md`](docs/getting-started.md) — Vietnamese long-form walkthrough.
- [`docs/silk-road-load-test-technical-document.md`](docs/silk-road-load-test-technical-document.md) — Silk Road technical doc.
- Gatling — [EL syntax](https://docs.gatling.io/reference/script/core/session/el/) · [Gradle plugin](https://docs.gatling.io/reference/integrations/build-tools/gradle-plugin/) · [gRPC DSL](https://docs.gatling.io/reference/script/protocols/grpc/).
