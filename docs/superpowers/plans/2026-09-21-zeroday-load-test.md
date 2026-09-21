# Zero Day Load Test Implementation Plan

> **Execution note:** This plan is self-contained and tool-agnostic. Any AI
> agent or human engineer can execute it with only a shell, a code editor, and
> git. Follow the Execution Protocol below.

**Goal:** Add a gRPC Gatling load test for the Zero Day slot backend (`be-zero-day`, plugin `yama_01023`) as game module `zeroday`, wired into `run-variant.sh` and the docs.

**Architecture:** Copy-adapt `games/mutantmerge`: a Scala simulation on Gatling 3.9.5 + `com.github.phisgr:gatling-grpc` 0.17.0 launched via `JavaExec`, reusing the proto stubs and MessagePack `Codec` from `:core`. The backend's `Call` runs the spin synchronously but returns an empty `PluginResponse` (result and business errors go over ZMQ), so the simulation checks gRPC status only; business errors are checked by grepping the backend log.

**Tech Stack:** Java 17, Scala 2.13.12, Gatling 3.9.5, gatling-grpc 0.17.0, Gradle 9, bash.

## Execution Protocol (for any AI agent or human engineer)

1. A task may start only when every task in its **Depends** and **Runs after**
   lists is complete. Single worker: run tasks in ID order.
2. Parallel workers: follow **Execution Waves**. Tasks in the same wave touch
   disjoint files and MAY run concurrently (marked `[P]`). Never run two tasks
   that modify the same file at once.
3. Within a task, execute steps top to bottom and mark each checkbox `- [x]`
   when done. To resume, continue from the first unchecked step.
4. Run every command exactly as written and compare with **Expected**. On
   mismatch, stop and fix before continuing.
5. Code blocks are the implementation - copy them verbatim. Signatures under
   **Interfaces** are contracts with other tasks: never rename, reorder
   parameters, or change types.
6. Commit exactly where the plan says, with the given message, staging only the
   listed paths. Never batch commits across tasks.
7. **Global Constraints** apply to every task.
8. If anything is ambiguous, missing, or contradicts the codebase, STOP and ask
   the requester. Do not invent behavior.

## Global Constraints

- There are no unit tests in this repo; verification = compile (`./gradlew :games:zeroday:gatlingClasses`) + smoke run against a live backend.
- Do not modify `core/`, other game modules, or the backend repo.
- pluginName `yama_01023`, zone `MiniGame`, agency `loadtest`, gRPC default `localhost:9103`.
- Spin payload `{cmd: 1500, bet: "<bet>"}`; default `bet=1.00`; bet must be one of: 0.20, 0.40, 0.60, 0.80, 1.00, 1.20, 1.60, 2.00, 2.40, 2.80, 3.20, 3.60, 4.00, 5.00, 6.00, 8.00, 10.00, 14.00, 18.00, 24.00, 32.00, 40.00, 60.00, 80.00, 100.00 (compare with tolerance 0.001); an invalid bet throws at simulation load.
- Defaults: users=1000, durationMinutes=60, rampMinutes=2, paceSec=5, requestRate=50, eventCount=100000.
- A new `-D` flag must be listed in the module's `FORWARDED_PROPS` or the simulation silently sees the default.
- Health URL: `http://localhost:3000/api/game/zeroday/actuator/health`; container `game-zero-day`.
- Code, comments and commits in English. `docs/` is gitignored: plan/spec files are added with `git add -f`.
- SUT for smoke runs: `be-zero-day` container `game-zero-day` with `LUIGI_WALLET_ENABLED=false`, `SPRING_PROFILES_ACTIVE=dev`, `CHEAT_ENABLED=false`, logging driver `json-file` (so `docker logs` works), Mongo/Redis/RabbitMQ reachable.

## References

- `games/mutantmerge/build.gradle` - build layout to copy (gatlingRt, JavaExec alias, FORWARDED_PROPS)
- `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala` - simulation to copy-adapt

## File Structure

- `./` — settings.gradle (T01), CLAUDE.md (T03), README.md (T03)
- `games/zeroday/` — build.gradle (T01)
- `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/` — ZeroDayGrpcSimulation.scala (T01)
- `games/zeroday/src/gatling/resources/` — game.yml (T01), sla-thresholds.yml (T01), logback-test.xml (T01)
- `scripts/` — run-variant.sh (T02)

## Contracts

#### T01: zeroday gRPC module
- Files: `settings.gradle`, `games/zeroday/build.gradle`, `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala`, `games/zeroday/src/gatling/resources/game.yml`, `games/zeroday/src/gatling/resources/sla-thresholds.yml`, `games/zeroday/src/gatling/resources/logback-test.xml`
- Produces: `class ZeroDayGrpcSimulation extends Simulation`
- Spec: L7-63, L74-96

#### T02: run-variant.sh support
- Depends: T01
- Files: `scripts/run-variant.sh`
- Spec: L64-73

#### T03: Docs
- Depends: T01
- Files: `CLAUDE.md`, `README.md`
- Spec: L64-73, L97-109
- Tier: light

<!-- WAVES -->
## Execution Waves

Every task in a wave has all its Depends/Runs-after tasks in earlier waves. Tasks in the
same wave touch disjoint files, so a wave's `[P]` tasks may all run at once.

- **Wave 1:** T01
- **Wave 2:** T02 [P], T03 [P]
<!-- /WAVES -->

<!-- TASKS -->

### T01: zeroday gRPC module

**Depends:** —

**Interfaces:**
- Produces: `class ZeroDayGrpcSimulation extends Simulation`

**Files:**
- Modify: `settings.gradle`
- Create: `games/zeroday/build.gradle`
- Create: `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala`
- Create: `games/zeroday/src/gatling/resources/game.yml`
- Create: `games/zeroday/src/gatling/resources/sla-thresholds.yml`
- Create: `games/zeroday/src/gatling/resources/logback-test.xml`

- [ ] **Step 1: Confirm the module does not exist yet**

Run: `./gradlew :games:zeroday:gatlingClasses`
Expected: FAIL with "Project 'games:zeroday' not found" (or "project 'games' does not contain ... zeroday").

- [ ] **Step 2: Register the module in `settings.gradle`**

Add the line right after `include ':games:mutantmerge'`:

```groovy
include ':games:zeroday'
```

- [ ] **Step 3: Create `games/zeroday/build.gradle`**

```groovy
// Zero Day load test — gRPC-only (the game has no REST spin endpoint).
//
// Same runtime as games/mutantmerge: Gatling 3.9.5 + com.github.phisgr:gatling-grpc via JavaExec,
// because Gatling 3.15's OSS gRPC DSL is capped at 5 VU / 5 minutes and io.gatling.gradle 3.9.5
// breaks on Gradle 9.
//
// Run examples (from repo root) — needs the be-zero-day backend running locally:
//   ./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
//                                 -DrequestRate=0 -DeventCount=0
//   ./gradlew :games:zeroday:grpc -Dusers=1000 -DdurationMinutes=60 -Dbet=1.00

plugins {
    id 'java'
    id 'scala'
}

def gatlingOssVersion = '3.9.5'
def gatlingGrpcVersion = '0.17.0'

configurations {
    gatlingRt
}

dependencies {
    gatlingRt "io.gatling.highcharts:gatling-charts-highcharts:${gatlingOssVersion}"
    gatlingRt "com.github.phisgr:gatling-grpc:${gatlingGrpcVersion}"
    gatlingRt 'org.scala-lang:scala-library:2.13.12'

    // Proto stubs + MessagePack Codec from :core, without the Gatling 3.15 it exports.
    gatlingRt(project(':core')) {
        exclude group: 'io.gatling'
        exclude group: 'io.gatling.highcharts'
    }
}

sourceSets {
    gatling {
        scala.srcDirs = ['src/gatling/scala']
        java.srcDirs = []
        resources.srcDirs = ['src/gatling/resources']
        compileClasspath = configurations.gatlingRt
        runtimeClasspath = output + configurations.gatlingRt
    }
}

// -D flags the simulation reads at runtime; Gradle does not forward them to the JavaExec fork.
def FORWARDED_PROPS = [
    'users', 'durationMinutes', 'rampMinutes', 'host', 'port', 'paceSec',
    'requestRate', 'eventCount', 'grpcHost', 'grpcPort', 'bet',
]

def SIMULATIONS = [
    grpc: 'com.rgp.loadtest.zeroday.grpc.ZeroDayGrpcSimulation',
]

SIMULATIONS.each { alias, fqcn ->
    tasks.register(alias, JavaExec) {
        group = 'gatling'
        description = "Run ${alias} simulation (${fqcn})"
        dependsOn tasks.named('gatlingClasses')
        classpath = sourceSets.gatling.runtimeClasspath
        mainClass = 'io.gatling.app.Gatling'
        args '-s', fqcn,
             '-rf', layout.buildDirectory.dir('reports/gatling').get().asFile.absolutePath
        jvmArgs = [
            '-server',
            '-Xmx2G',
            '--add-opens=java.base/java.lang=ALL-UNNAMED',
            '--add-opens=java.base/java.util=ALL-UNNAMED',
            '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
        ]
        // Load tests always run — no input fingerprint can detect "same SUT state"
        outputs.upToDateWhen { false }
        doFirst {
            // Lets SlaConfigLoader find games/<gameName>/src/gatling/resources/sla-thresholds.yml.
            systemProperty 'gameName', project.name
            FORWARDED_PROPS.each { name ->
                def v = System.getProperty(name)
                if (v != null) systemProperty(name, v)
            }
        }
    }
}
```

- [ ] **Step 4: Create `ZeroDayGrpcSimulation.scala`**

Path: `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala`

```scala
package com.rgp.loadtest.zeroday.grpc

import com.github.phisgr.gatling.grpc.Predef._
import com.google.protobuf.ByteString
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginServiceGrpc, PluginUser}
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioBuilder

import java.util.UUID
import scala.concurrent.duration._

/**
 * gRPC load test for Zero Day (be-zero-day, pluginName "yama_01023").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — the backend creates the Redis session from
 *      user.parameters (agency + userId required, token not checked).
 *   2. Spin loop (Call, cmd 1500) — `{bet}` as a decimal string from the bet ladder.
 *
 * Call runs the spin synchronously, then returns an EMPTY PluginResponse: the result and any
 * business error (`c != 0`) are published over ZMQ only. Response time is the real spin time,
 * but Gatling KO covers transport failures only — check the backend log for
 * "[gRPC] Call business error" after every run.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9103, bet=1.00.
 */
class ZeroDayGrpcSimulation extends Simulation {

  private val BetLadder = Seq(
    "0.20", "0.40", "0.60", "0.80", "1.00", "1.20", "1.60", "2.00", "2.40", "2.80", "3.20",
    "3.60", "4.00", "5.00", "6.00", "8.00", "10.00", "14.00", "18.00", "24.00", "32.00",
    "40.00", "60.00", "80.00", "100.00"
  ).map(BigDecimal(_))

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9103).intValue()
  private val bet             = sys.props.getOrElse("bet", "1.00")

  // The backend rejects an off-ladder bet over ZMQ only (gRPC still OK), so fail fast here.
  require(
    BetLadder.exists(step => (step - BigDecimal(bet)).abs <= BigDecimal("0.001")),
    s"-Dbet=$bet is not on the Zero Day bet ladder: ${BetLadder.mkString(", ")}"
  )

  private val PluginName = "yama_01023"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agency", Agency)
    params.put("userId", userId)
    params.put("memberId", userId)
    params.put("username", userId)
    params.put("reconnect", java.lang.Boolean.FALSE)

    val user = PluginUser.newBuilder()
      .setId(userId)
      .setUsername(userId)
      .setSessionId(userId)
      .setIp("127.0.0.1")
      .setParameters(ByteString.copyFrom(codec.encode(params)))
      .build()

    ConnectAndCallRequest.newBuilder()
      .setZone(Zone)
      .setUser(user)
      .setPluginName(PluginName)
      .build()
  }

  private def buildSpinRequest(userId: String): PluginRequest = {
    val data = new java.util.LinkedHashMap[String, Object]()
    data.put("cmd", Integer.valueOf(1500))
    data.put("bet", bet)
    PluginRequest.newBuilder()
      .setZone(Zone)
      .setPluginName(PluginName)
      .setUsername(userId)
      .setData(ByteString.copyFrom(codec.encode(data)))
      .build()
  }

  private val userFeeder: Iterator[Map[String, Any]] =
    Iterator.continually(Map("userId" -> ("loadtest-" + UUID.randomUUID().toString)))

  private val joinPayload: Expression[ConnectAndCallRequest] =
    session => session("userId").validate[String].map(buildJoinRequest)

  private val spinPayload: Expression[PluginRequest] =
    session => session("userId").validate[String].map(buildSpinRequest)

  private val grpcProtocol =
    grpc(managedChannelBuilder(name = grpcHost, port = grpcPort).usePlaintext())
      .shareChannel

  private val playerJourney: ScenarioBuilder =
    scenario("zeroday-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
          // Every non-transport outcome carries the session ZMQ topic; no topic = unexpected reply.
          .extract(res => Some(res.getMetadata.getTopicsCount).success)(_.gt(0))
      )
      // A failed Join stops the VU so it does not pollute the Spin error rate; the pause keeps
      // the closed-model injector from hammering Join when every Join is rejected.
      .doIf(session => session.isFailed)(pause(paceSec.seconds))
      .exitHereIfFailed
      .during(durationMinutes.minutes) {
        exec(
          grpc("Spin")
            .rpc(PluginServiceGrpc.getCallMethod)
            .payload(spinPayload)
        ).pace(paceSec.seconds)
      }

  setUp(
    playerJourney.inject(
      rampConcurrentUsers(0).to(users).during(rampMinutes.minutes),
      constantConcurrentUsers(users).during(durationMinutes.minutes)
    )
  ).protocols(grpcProtocol)
    .maxDuration((durationMinutes + rampMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN).minutes)
    .assertions(
      global.responseTime.mean.lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
      global.failedRequests.percent.lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
      global.requestsPerSec.gt(requestRate),
      global.successfulRequests.count.gt(eventCount),
      details("Spin").responseTime.percentile3.lte(800),
      details("Spin").failedRequests.percent.lte(0.5),
      details("Join").failedRequests.percent.lte(0.5)
    )
}
```

- [ ] **Step 5: Create the resources**

`sla-thresholds.yml` and `logback-test.xml` are the mutantmerge files with the game name swapped; `game.yml` is comment-only (health path and gRPC endpoint are not read from it).

```bash
d=games/zeroday/src/gatling/resources
mkdir -p "$d"
sed 's/Mutant Merge/Zero Day/' games/mutantmerge/src/gatling/resources/sla-thresholds.yml > "$d/sla-thresholds.yml"
cp games/mutantmerge/src/gatling/resources/logback-test.xml "$d/logback-test.xml"
printf '%s\n' \
  '# ============================================================================' \
  '# Zero Day — Per-Game Override' \
  '# ============================================================================' \
  '#' \
  "# gRPC-only: REST is used only by run-variant.sh's health probe, which builds" \
  '# its own URL (http://localhost:3000/api/game/zeroday/actuator/health).' \
  '#' \
  '# gRPC endpoint (join + spin) is NOT configured here — the simulation reads' \
  '# -DgrpcHost / -DgrpcPort (default localhost:9103).' \
  '# ============================================================================' \
  > "$d/game.yml"
```

Run: `sed -n 2p games/zeroday/src/gatling/resources/sla-thresholds.yml`
Expected: a comment line containing "Zero Day — Per-Game SLA Override"

- [ ] **Step 6: Compile**

Run: `./gradlew :games:zeroday:gatlingClasses`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Negative check — off-ladder bet fails at load**

Run: `./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -Dbet=0.33`
Expected: FAIL; output contains "-Dbet=0.33 is not on the Zero Day bet ladder".

- [ ] **Step 8: Smoke against the live backend**

Needs the SUT from Global Constraints running (gRPC on 9103).

Run: `./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -DrequestRate=0 -DeventCount=0`
Expected: `BUILD SUCCESSFUL`; Gatling stats show `Join` OK=1 KO=0 and `Spin` OK≈12 KO=0.

- [ ] **Step 9: Business-error log check (ack-only, so this is mandatory)**

Run: `docker logs game-zero-day 2>&1 | grep -c "\[gRPC\] Call business error"`
Expected: `0` (only `c=1362` lines are acceptable, and only if a jackpot triggered).

- [ ] **Step 10: Commit**

```bash
git add settings.gradle games/zeroday/build.gradle games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala games/zeroday/src/gatling/resources/game.yml games/zeroday/src/gatling/resources/sla-thresholds.yml games/zeroday/src/gatling/resources/logback-test.xml
git commit -m "feat(zeroday): add gRPC load-test module"
```

---

### T02: run-variant.sh support [P]

**Depends:** T01

**Interfaces:**
- Consumes: `class ZeroDayGrpcSimulation extends Simulation`

**Files:**
- Modify: `scripts/run-variant.sh`

- [ ] **Step 1: Confirm the wrapper does not know zeroday's health path yet**

Run: `grep -c "zeroday" scripts/run-variant.sh`
Expected: `0`

- [ ] **Step 2: Add zeroday to the usage comment and the missing-game error**

Replace `<silkroad|bonanza|naga777|mutantmerge>` with `<silkroad|bonanza|naga777|mutantmerge|zeroday>` in both places (header usage comment and the `ERROR: --game ...` echo).

- [ ] **Step 3: Add the default port and health URL**

In the `case "$GAME"` port block, after the `mutantmerge) PORT=3000 ;;` line:

```bash fragment
    zeroday)  PORT=3000 ;;
```

In the `HEALTH_URL` case block, after the `mutantmerge) HEALTH_URL=...` line:

```bash fragment
  zeroday)  HEALTH_URL="http://localhost:${PORT}/api/game/zeroday/actuator/health" ;;
```

- [ ] **Step 4: Syntax check**

Run: `bash -n scripts/run-variant.sh && grep -c "zeroday" scripts/run-variant.sh`
Expected: `4`

- [ ] **Step 5: Wrapper smoke against the live backend**

Run: `./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc --users 5 --duration-minutes 1 --ramp-minutes 0 --container game-zero-day`
Expected: prints `Health probe URL: http://localhost:3000/api/game/zeroday/actuator/health`; the newest `target/variants/zeroday/target-*/` contains `resource.csv`, `health.csv` (all 200), `verdict.json`, `summary.html`. A FAIL verdict from the `requestRate`/`eventCount` floors is expected at 5 users.

- [ ] **Step 6: Commit**

```bash
git add scripts/run-variant.sh
git commit -m "feat(run-variant): support zeroday game"
```

---

### T03: Docs [P]

**Depends:** T01

**Interfaces:**
- Consumes: `class ZeroDayGrpcSimulation extends Simulation`

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Update `CLAUDE.md`**

- Compile command: append ` :games:zeroday:gatlingClasses` after `:games:mutantmerge:gatlingClasses`.
- Run commands: after the mutantmerge line add

```bash
./gradlew :games:zeroday:grpc     -Dusers=5 -DdurationMinutes=1 -DrequestRate=0 -DeventCount=0 -DgrpcPort=9103
```

- Simulation aliases paragraph: after the mutantmerge sentence add `zeroday has `grpc` only (gRPC :9103, pluginName `yama_01023`, bet via `-Dbet` on the 25-step ladder, validated at load; Call returns an empty ack — business errors go over ZMQ, so grep the backend log for `[gRPC] Call business error`);`.
- gRPC runtimes list: after the mutantmerge bullet add `  - `games/zeroday/src/gatling/scala/ZeroDayGrpcSimulation.scala` — same layout as naga777.`

- [ ] **Step 2: Update `README.md`**

- Games table: after the Mutant Merge row add

```markdown
| Zero Day            | `zeroday`  | `../be-zero-day`            | `game-zero-day`              | `3000` | `Grpc`                                        |
```

- `--game` usage and `--game` / `--port` option rows: add `zeroday` next to `mutantmerge`.
- `Grpc` defaults note: append `/ `9103` (zeroday)` to the grpcPort list.
- Production-gate examples: after the Mutant Merge example add

Zero Day example (put the comment line `Zero Day — gRPC production gate (port 3000 + health /api/game/zeroday/actuator/health auto-derived)`, prefixed with `# `, above it like the other examples):

```bash
./scripts/run-variant.sh --game zeroday \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container game-zero-day
```

- Per-game props paragraph: add `**Zero Day-only:** `bet` (decimal string on the bet ladder 0.20–100.00, default 1.00; off-ladder values fail at load).`
- Versions table and gRPC runtimes: add zeroday next to mutantmerge; add bullet `- **zeroday** is gRPC-only too, same layout as naga777.`
- Scala section: change "three files" to "four files", add `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala` to the list.
- SUT setup: add a short Zero Day subsection: run `be-zero-day` container `game-zero-day` with `LUIGI_WALLET_ENABLED=false`, `SPRING_PROFILES_ACTIVE=dev`, `CHEAT_ENABLED=false`, logging driver `json-file`; after each run check `docker logs game-zero-day 2>&1 | grep -c "\[gRPC\] Call business error"` (Gatling cannot see business errors; `c=1362` jackpot-pending rejections block a VU's spins for ~60 s and are expected occasionally).

- [ ] **Step 3: Verify every zeroday mention landed**

Run: `grep -c "zeroday" CLAUDE.md && grep -c "zeroday" README.md`
Expected: CLAUDE.md ≥ 4, README.md ≥ 9

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: document zeroday load test"
```
