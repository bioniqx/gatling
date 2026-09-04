# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Gatling load-test harness (Java 17, Gatling 3.15, Gradle multi-module) for RGP slot-game backends. Each game is a subproject under `games/` that extends shared infrastructure in `core/`. There are no unit tests — verification means compiling and running a short smoke simulation against a live backend (SUT runs in Docker, started separately).

Docs: `README.md` (English, authoritative reference), `HUONG-DAN.md` + `docs/getting-started.md` (Vietnamese guides).

## Commands

```bash
# Compile everything (fastest full check; includes Gatling source sets)
./gradlew :core:classes :games:silkroad:gatlingClasses :games:bonanza:gatlingClasses :games:naga777:gatlingClasses

# Run a simulation directly (dev iteration — no monitors, no verdict)
./gradlew :games:silkroad:soak   -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1
./gradlew :games:silkroad:basic  -Dscenario=spin -Dusers=1 -Drequests=1   # 1-request smoke
./gradlew :games:silkroad:stress -DusersStart=10 -DusersEnd=20 -DdurationMinutes=1
./gradlew :games:silkroad:spike  -Dbaseline=5 -Dspike=10 -Dcycles=2 -DcycleIntervalMinutes=1
./gradlew :games:bonanza:soak    -Dusers=5 -DdurationMinutes=1 -DrampMinutes=1
./gradlew :games:bonanza:grpc    -Dusers=5 -DdurationMinutes=1 -DgrpcHost=localhost -DgrpcPort=9091
./gradlew :games:naga777:grpc   -Dusers=5 -DdurationMinutes=1 -DgrpcHost=localhost -DgrpcPort=9096

# Full instrumented run (monitors + Gatling + verdict.json + summary.html)
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 50 --duration-minutes 1 --ramp-minutes 0 --container game-silk-road-caravans

# Re-run the pass/fail verifier on existing CSVs (no new load test)
./gradlew verifyVariant -DgameName=silkroad -Dvariant=target \
  -DresourceCsv=... -DhealthCsv=... -DgatlingLog=... -Dusers=1000 -DdurationSec=3900
```

Simulation aliases per game: silkroad has `soak/stress/spike/basic`; bonanza has `soak/basic/grpc` (no Stress/Spike yet); naga777 has `grpc` only (no REST spin — gRPC :9096, bet via `-DcoinValue`/`-DcoinPerLine`). Arbitrary simulations: `./gradlew :games:<g>:gatlingRun --simulation <FQCN>`.

## Architecture

**Module layout** — `:core` is a `java-library` exposing Gatling DSL types via `api` so game modules don't re-declare them. Game modules apply the `io.gatling.gradle` plugin; their code lives in the `src/gatling/` source set (not `src/main/`), depends on `:core` via `gatlingImplementation`.

- `core/src/main/java/com/rgp/loadtest/core/`
  - `simulations/` — `{Basic,Soak,Spike,Stress}SimulationBase`: abstract injection-profile templates each game subclasses.
  - `scenarios/SessionJourneyTemplate.java` — shared scenario skeleton.
  - `config/` — layered YAML loading (`SlaConfigLoader`, `LoadTestConfigLoader`).
  - `verify/ThresholdVerifier.java` — post-run pass/fail verdict (CPU/mem p95 ceilings, crash detection, Gatling exit); invoked via the root `verifyVariant` task; exit 0=PASS, 1=FAIL.
  - `protocol/Codec.java` — MessagePack codec wrapping the proprietary GaaS JAR.
- `core/src/main/proto/plugin_service.proto` — gRPC stubs shared by all games (bonanza's Scala gRPC sim reuses the generated Java classes; no ScalaPB).
- `games/<name>/src/gatling/java/...` — per-game `Endpoints`, `SlotRequests` (request builders + JSON bodies from `resources/games/<name>/bodies/`), simulations subclassing the core bases, and a scenario class.
- **gRPC simulations run on a different Gatling than the REST ones.** Gatling 3.15's first-party gRPC DSL is Enterprise-gated and aborts above 5 VUs / 5 minutes, so both gRPC sims were moved to the community plugin `com.github.phisgr:gatling-grpc` 0.17.0 on Gatling 3.9.5 (Scala core DSL, no cap). `io.gatling.gradle` 3.9.5.x breaks on Gradle 9, so each builds its own classpath and runs via `JavaExec`:
  - `games/naga777/src/gatling/scala/Naga777GrpcSimulation.scala` — whole module on 3.9.5 (`gatlingRt`).
  - `games/bonanza/src/gatlingGrpc/scala/BonanzaGrpcSimulation.scala` — 3.9.5 (`gatlingGrpcRt`); bonanza's Java REST sims in `src/gatling/java` stay on 3.15 under the Gatling Gradle plugin.
  - silkroad is REST-only and stays on 3.15 (HTTP DSL is uncapped).
  - The community plugin is archived upstream and will not support Gatling 3.10+. See README → gRPC runtimes.

**System-property plumbing** — runtime knobs (`-Dusers`, `-Dhost`, `-Dport`, …) are NOT auto-forwarded from the Gradle JVM to the Gatling fork. Each game's `build.gradle` has a `FORWARDED_PROPS` list; a new `-D` flag must be added there (and to `VERIFY_PROPS` in the root `build.gradle` for verifier flags) or the simulation will silently see the default.

**Config layering (top wins, field-level deep merge)**:
1. `-DslaConfig=<path>` / individual `-D` flags
2. `games/<gameName>/src/gatling/resources/sla-thresholds.yml` and `game.yml` (per-game; e.g. bonanza sets port 3005 + contextPath `/golden`)
3. `config/sla-thresholds.yml` (core defaults — edit thresholds here, no recompile needed)
4. `core/src/main/resources/*.yml` classpath fallback → hardcoded defaults

**run-variant.sh pipeline** — starts `monitor-resources.sh` (docker stats → resource.csv) and `health-poll.sh` (HTTP probe → health.csv) in the background, runs the Gatling gradle task, copies the newest Gatling HTML report, runs `verifyVariant` → `verdict.json`, then `generate-summary-html.py` → `summary.html`. Artifacts land in `target/variants/<game>/<variant>-<timestamp>/`. Variants (`baseline|target|stress|critical`) map to CPU/mem ceilings in `sla-thresholds.yml`; `target` is the production gate. `--game` is mandatory (no default). `generate-final-report.py` aggregates all variant dirs into a compliance report.

**GaaS JAR quirk** — `core/libs/common-data-1.0.0.jar` ships as Java 21 bytecode; the `downgradeGaasJar` task in `core/build.gradle` rewrites class-file versions to Java 17 into `build/libs-jdk17/` before any compile. If `Codec`-related classes fail to resolve, run that task / check that output dir.

**Adding a game** — create `games/<name>/` (build.gradle + `src/gatling/`), then uncomment/add its `include` line in `settings.gradle`. `gameName` is injected automatically from the project name so `SlaConfigLoader` finds the per-game override.
