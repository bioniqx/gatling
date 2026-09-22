# Naga's Fortune 777 — Load Test Guide

How to run the naga777 load test and how to read what comes out of it.

Everything here is specific to `naga777`. For the harness as a whole, see [`../../README.md`](../../README.md).

---

## Command cheat sheet

Copy-paste, in order. All paths are relative to the repo root (`rgp-game-load-test/`).

> Requires the naga777 backend already running in Docker — ports **3000** (health) and **9096**
> (gRPC), container `stable-naga_fortune_777`. See [Backend requirement](#1-backend-requirement).

```bash
# ---- confirm the backend is up ----------------------------------------------
curl -s http://localhost:3000/health                  # every field must read "UP"

# ---- compile only (no run) --------------------------------------------------
./gradlew :games:naga777:gatlingClasses

# ---- 1-minute smoke, floors off, no verdict ---------------------------------
./gradlew :games:naga777:grpc \
  -Dusers=10 -DdurationMinutes=1 -DrampMinutes=0 -DpaceSec=2 \
  -DrequestRate=0 -DeventCount=0

# ---- pipeline check, ~4 min, full artifacts ---------------------------------
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 200 --duration-minutes 2 --ramp-minutes 1 \
  --container stable-naga_fortune_777

# ---- production gate, ~62 min -----------------------------------------------
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-naga_fortune_777

# ---- read the results -------------------------------------------------------
open "$(ls -1td target/variants/naga777/target-*/ | head -1)summary.html"
cat "$(ls -1td target/variants/naga777/target-*/ | head -1)verdict.json"

# ---- cross-variant compliance report ----------------------------------------
python3 scripts/generate-final-report.py \
  --variants-dir target/variants/naga777 \
  --report-out target/variants/naga777/final-report.md
```

---

## What the test actually does

Naga's Fortune 777 has no REST spin endpoint — the backend exposes only `/health` and
`/health/detail` over HTTP, and every game command goes through gRPC. The simulation is therefore
pure gRPC:

1. **Join** — `ConnectAndCall` (cmd 1005) on `PluginService`, plugin name `game-naga-fortune-777`.
   Identity (`agency`, `username`) travels inside `user.parameters` as a MessagePack blob.
2. **Spin loop** — `Call` (cmd 1500) with `{betLevelId, coinValueId}`, repeated at a fixed pace
   until the run ends.

No session is seeded beforehand. When Redis has no session for the token, the backend's
`ConnectHandler` falls back to the request parameters and still registers the player, and the
balance comes from `MockWalletAdapter` (`WALLET_GATEWAY=mock` seeds 10,000,000). That is why the
journey needs no REST setup step.

> **What the latency number means.** The real spin result is pushed to the client over ZMQ, not
> returned on the gRPC call. So the response time you measure is the gRPC **ack**, not the
> full round trip a player sees. The load on the game engine is real; the latency figure is
> narrower than it looks.

---

## Prerequisites

| Need | Version | Check |
|---|---|---|
| JDK | 17 | `java -version` |
| Docker | 20+ | Must be running, with the naga777 backend stack up — see [Backend requirement](#1-backend-requirement) |
| Python | 3.9+ | `python3 --version` |

Gradle is bundled (`../gradlew`), no separate install.

---

## 1. Backend requirement

**The naga777 backend stack must already be running in Docker before you start a test.** Bringing
it up is out of scope for this guide — it lives in the sibling `Stable_NAGAS_777` repository and
is started from there.

What this harness needs from it:

| Port | Used by | Required by the load test |
|---|---|---|
| **3000** | HTTP — `/health`, `/health/detail` | **Yes.** The wrapper's health probe polls it every 2s and writes `health.csv`. |
| **9096** | gRPC — `PluginService` (Join + Spin) | **Yes.** This is the surface under test. |
| 10004 / 11004 | ZMQ publishers (spin results, cross-plugin frames) | No — but the stack won't work without them |
| 6379 / 27017 | Redis / Mongo | No — internal to the stack |
| 9099 | WSProxy HTTP + WebSocket | No — not exercised by this test |

The container running the game must be named **`stable-naga_fortune_777`** — that is the value
you pass to `--container`, and the wrapper uses it to sample CPU and memory into `resource.csv`.
A wrong or stopped container name leaves those columns empty and the verdict unscoreable.

**Confirm the backend is reachable before you run anything:**

```bash
curl -s http://localhost:3000/health
# {"status":"UP","redis":"UP","mongo":"UP","grpc":"UP","zmq":"UP"}
```

Every field must read `UP`. If the request refuses or times out, the backend is not running and
every `Join` will fail.

> **Only one game stack can run at a time on this machine.** Other backends (Stable_Mutant_Merge,
> for example) bind the same port 3000 and use the same `stable-*` container names, so they and
> naga777 are mutually exclusive. If `/health` answers but every spin fails, the odds are that a
> *different* game's backend owns port 3000 — check which stack is actually up before assuming
> the test is broken.

---

## 2. Run the test

### A. Quick smoke — fast feedback, no verdict

Use this while iterating. It runs Gatling directly and prints a summary, but produces no
`verdict.json` and no `summary.html`.

```bash
./gradlew :games:naga777:grpc \
  -Dusers=10 -DdurationMinutes=1 -DrampMinutes=0 -DpaceSec=2 \
  -DrequestRate=0 -DeventCount=0
```

`-DrequestRate=0 -DeventCount=0` switch off the two production-scale floors. Leave them on at
smoke scale and the build fails even though the test itself is fine — see
[Reading pass/fail](#4-reading-passfail).

### B. Full run — verdict, charts, summary page

```bash
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-naga_fortune_777
```

This is the production gate. A shorter shape for checking the pipeline end to end
(~4 minutes):

```bash
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 200 --duration-minutes 2 --ramp-minutes 1 \
  --container stable-naga_fortune_777
```

The wrapper starts CPU/memory sampling and a health probe, runs Gatling, copies the HTML report,
scores the run, and writes a one-page summary.

**`--variant` accepts only `baseline`, `target`, `stress`, `critical`.** Anything else (`smoke`,
say) is rejected by the verifier, which leaves `verdict.json` empty and makes `summary.html`
generation fail. The variant selects the CPU/memory ceilings the run is judged against:

| Variant | CPU p95 ceiling | Mem p95 ceiling | Use for |
|---|---|---|---|
| `baseline` | 50% | 60% | Headroom validation |
| `target` | 70% | 80% | **Production gate** |
| `stress` | 85% | 90% | Near saturation |
| `critical` | 95% | 95% | Pre-failure behaviour |

### C. More recipes

**Full variant sweep** — populates every row of the compliance report. Each run is a full
`duration + ramp`, so four runs at gate scale is roughly four hours; drop the numbers while
rehearsing.

```bash
for v in baseline target stress critical; do
  ./scripts/run-variant.sh --game naga777 \
    --variant "$v" --simulation Grpc \
    --users 1000 --duration-minutes 60 --ramp-minutes 2 \
    --container stable-naga_fortune_777
done
python3 scripts/generate-final-report.py \
  --variants-dir target/variants/naga777 \
  --report-out target/variants/naga777/final-report.md
```

The loop keeps going even when a run exits non-zero, which is what you want — the wrapper
forwards Gatling's exit code, and at reduced scale that is non-zero by design.

**Change the bet** — server derives `coinValue x coinPerLine x 5`, so this is 100 x 10 x 5 = 5000:

```bash
./gradlew :games:naga777:grpc \
  -Dusers=50 -DdurationMinutes=5 -DrampMinutes=1 \
  -DcoinValue=100 -DcoinPerLine=10 \
  -DrequestRate=0 -DeventCount=0
```

**Heavier spin rate** — `paceSec=1` makes each VU spin once per second, so 100 VUs is 100 req/s:

```bash
./gradlew :games:naga777:grpc \
  -Dusers=100 -DdurationMinutes=5 -DrampMinutes=1 -DpaceSec=1 \
  -DrequestRate=0 -DeventCount=0
```

**Point at a non-local backend** — the wrapper does not expose these, so use Gradle:

```bash
./gradlew :games:naga777:grpc \
  -Dusers=500 -DdurationMinutes=30 -DrampMinutes=2 \
  -DgrpcHost=staging.internal -DgrpcPort=9096 \
  -Dhost=staging.internal -Dport=3000
```

**Long run in the background**, so the terminal stays free:

```bash
nohup ./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-naga_fortune_777 > /tmp/naga-gate.log 2>&1 &

tail -f /tmp/naga-gate.log            # follow progress
```

**Keep results before cleaning** — `./gradlew clean` deletes `../../target`:

```bash
cp -r "$(ls -1td target/variants/naga777/target-*/ | head -1)" ~/naga-run-$(date +%Y%m%d-%H%M%S)
```

### Knobs

Of the properties below, the wrapper only sets `users`, `durationMinutes`, `rampMinutes` and
`port`. The rest have to go through Gradle directly (option A):

| Property | Default | Meaning |
|---|---|---|
| `-Dusers` | 1000 | Concurrent VUs (closed model) |
| `-DdurationMinutes` | 60 | Hold time after the ramp |
| `-DrampMinutes` | 2 | Ramp from 0 to `users` |
| `-DpaceSec` | 5 | Seconds between spins per VU |
| `-DgrpcHost` / `-DgrpcPort` | localhost / 9096 | gRPC endpoint |
| `-DcoinValue` | 5 | Coin value id — one of 1, 5, 20, 50, 100, 200, 500 |
| `-DcoinPerLine` | 3 | Bet level id, 1–10 |
| `-DrequestRate` | 50 | Throughput floor, req/s |
| `-DeventCount` | 100000 | Successful-request floor |

The server derives the bet as `coinValue x coinPerLine x 5` — the defaults give 75. Sending a raw
`betAmount` instead is rejected with `BET_INPUT_REJECTED`.

Throughput follows directly from `users` and `paceSec`: `users / paceSec` requests per second.
1000 VUs at `paceSec=5` is 200 req/s.

---

## 3. Where the results land

```
target/variants/naga777/target-<timestamp>/
├── summary.html              ← start here: verdict banner, CPU/mem charts, key numbers
├── verdict.json              ← the same verdict, machine-readable
├── gatling-report/index.html ← full Gatling charts (latency over time, percentiles, active users)
├── resource.csv              ← docker stats sample every 5s: timestamp,cpu_pct,mem_pct,...
├── health.csv                ← health probe every 2s: timestamp,http_status,total_seconds
└── gatling.log               ← raw Gatling console output
```

Open the summary:

```bash
open target/variants/naga777/target-*/summary.html
```

> **`summary.html` is written last**, after Gatling finishes and the verifier has scored the run.
> While a run is still going the directory holds only `gatling.log`, `health.csv` and
> `resource.csv` — that is normal, not a failure.

> **These live under `target`, so `./gradlew clean` deletes them.** Copy anything you want to
> keep out of that directory first.

### Cross-run compliance report

The wrapper does not generate this; run it yourself once the variants you care about have run:

```bash
python3 scripts/generate-final-report.py \
  --variants-dir target/variants/naga777 \
  --report-out target/variants/naga777/final-report.md
```

It aggregates every variant directory into a PR-1…PR-7 table. Variants you never ran show as
`N/A` rows, and PR-1/PR-2 (1000 concurrent sessions, 60 minutes) fail unless the run actually had
that shape.

### Reading `verdict.json`

```json
{
  "variant": "target",          "game_name": "naga777",
  "users": 200,                 "duration_sec": 307,
  "cpu_p95": 3.43,              "cpu_ceil": 70,     "cpu_pass": true,
  "mem_p95": 7.78,              "mem_ceil": 80,     "mem_pass": true,
  "health_failures": 0,         "max_consecutive_failures": 0, "crash_pass": true,
  "http_total": 10400,          "http_ok": 10400,   "http_ko": 0,
  "mean_response_ms": 14,       "mean_ms_ceil": 500, "pr4_pass": true,
  "http_ko_percent": 0.0,       "http_ko_ceil": 1.0, "pr5_pass": true,
  "p95_response_ms": 27,
  "gatling_exit_code": 1,
  "verdict": "PASS"
}
```

| Field | Fails when |
|---|---|
| `cpu_pass` / `mem_pass` | p95 CPU or memory exceeds the variant's ceiling |
| `crash_pass` | 3+ consecutive failed health probes (~6s of downtime) |
| `pr4_pass` | Mean response time above 500 ms |
| `pr5_pass` | Error rate above 1% |
| `verdict` | Any of the above fails |

Thresholds come from `../config/sla-thresholds.yml`, with per-game overrides in
`src/gatling/resources/sla-thresholds.yml` (currently empty — naga777 inherits the
core values). Edit the YAML and re-run; no recompile needed.

---

## 4. Reading pass/fail

**Two independent layers score every run, and they can disagree.**

**Layer 1 — Gatling assertions.** Evaluated inside the simulation. If any fail, Gatling exits
non-zero, Gradle prints `BUILD FAILED`, and the wrapper exits non-zero too (it forwards Gatling's
exit code). The assertions are:

| Assertion | Threshold |
|---|---|
| Global mean response time | ≤ 500 ms |
| Global error rate | ≤ 1% |
| Global throughput | > `requestRate` (default 50 req/s) |
| Global successful requests | > `eventCount` (default 100,000) |
| `Spin` p95 | ≤ 800 ms |
| `Spin` / `Join` error rate | ≤ 0.5% |

**Layer 2 — the threshold verifier.** Runs after Gatling and writes `verdict.json`. It scores
CPU, memory, health-probe crashes, PR-4 and PR-5 — it does **not** look at throughput or request
volume, and it does not simply mirror Gatling's exit code.

### The one that trips everyone up

The last two assertions in layer 1 — `requestRate` and `eventCount` — are calibrated for the
1000-VU / 60-minute gate. At any smaller scale they cannot pass:

```
Global: mean requests per second is greater than 50.0 : false (actual : 1.92)
Global: count of successful events is greater than 100000.0 : false (actual : 1594)
> Task :games:naga777:grpc FAILED
```

Ten VUs at `paceSec=5` is 2 req/s, and ten minutes of that is roughly 1,200–1,600 spins. Neither
floor is reachable. **This is the run being too small, not the system being slow** — in the run
above the same report showed `verdict: "PASS"` with mean 18 ms and zero errors.

So: read `verdict.json`, not the red Gradle text. If you want a clean exit at reduced scale, use
the Gradle route with `-DrequestRate=0 -DeventCount=0`. The wrapper does not expose those two, so
scaled-down wrapper runs will always exit non-zero while still producing a valid verdict.

There is no comfortable middle ground: clearing `requestsPerSec > 50` needs more than 250 VUs, and
clearing `successfulRequests > 100000` on top of that needs roughly 300 VUs for about 30 minutes.
Those floors are really only meaningful at the real gate.

---

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unknown variant: smoke. Valid: [target, stress, baseline, critical]`, then empty `verdict.json` and a `summary.html` traceback | `--variant` given a name the verifier doesn't know | Use one of the four valid variants |
| `finished with non-zero exit value 2` but artifacts look fine | Gatling assertions failed — almost always the two production floors | Check `verdict.json`; see [above](#the-one-that-trips-everyone-up) |
| `summary.html` missing | Run still in progress, or the verifier failed and left `verdict.json` empty | Wait for `[run-variant] Artifacts:`; if it never appears, check the variant name |
| All artifacts gone | `./gradlew clean` wiped `target` | Copy results out of `target` before cleaning |
| `Connection refused` on 9096, or every `Join` fails | Backend not running, or another game's stack owns the ports | Check `/health`, then make sure the naga777 stack is the one that is up |
| Health probe returns 200 but every spin fails | A *different* game's backend is answering on 3000/9096 | Confirm the running container is `stable-naga_fortune_777` |
| `gatling.log` growing into the hundreds of MB | `logback-test.xml` missing or overridden — grpc-netty logs every HTTP/2 frame at DEBUG | Restore `../games/naga777/src/gatling/resources/logback-test.xml` (root level `WARN`) |

---

## 6. About the Gatling runtime

naga777 does **not** run on the same Gatling as the REST games. Gatling 3.15's first-party gRPC
DSL is an Enterprise feature whose OSS mode aborts any run above **5 VUs or 5 minutes**, which
makes the 1000-VU gate impossible. The simulation therefore runs on Gatling **3.9.5** with the
community plugin `com.github.phisgr:gatling-grpc` 0.17.0, which has no cap.

Because `io.gatling.gradle` 3.9.5.x is incompatible with Gradle 9, `../games/naga777/build.gradle`
skips the Gatling Gradle plugin entirely: it assembles its own classpath in a `gatlingRt`
configuration and launches `io.gatling.app.Gatling` through a plain `JavaExec` task. Nothing
downstream changes — the wrapper, the verifier and `summary.html` all work the same.

One visible consequence: Gatling 3.9 prints its console summary as
`> request count  640 (OK=640  KO=0 )` rather than the pipe-delimited table 3.15 uses. The
threshold verifier understands both.

**Caveat.** The community plugin is archived upstream (last commit February 2024) and will never
support Gatling 3.10+. If the REST simulations are moved to a newer Gatling, the gRPC ones stay
on 3.9.5 unless someone forks the plugin or buys an Enterprise licence.

For the same details across all games, see [`../../README.md`](../../README.md) → gRPC runtimes.
