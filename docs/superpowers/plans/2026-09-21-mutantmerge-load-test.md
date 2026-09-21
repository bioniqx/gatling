# Mutant Merge gRPC Load Test Implementation Plan

> **Execution note:** This plan is self-contained and tool-agnostic. Any AI
> agent or human engineer can execute it with only a shell, a code editor, and
> git. Follow the Execution Protocol below.

**Goal:** Add a `games/mutantmerge` module that load-tests the Mutant Merge backend over gRPC (Join cmd 1005 + Spin loop cmd 1500) and plug it into `run-variant.sh` and the docs.

**Architecture:** Copy-adapt the `games/naga777` module: a Scala simulation on Gatling 3.9.5 + `com.github.phisgr:gatling-grpc` 0.17.0, run through a `JavaExec` task, reusing the proto stubs and MessagePack `Codec` from `:core`. Unlike naga777, the Spin call decodes `PluginResponse.result` and fails the request when the backend error envelope `c != 0`.

**Tech Stack:** Java 17, Scala 2.13.12, Gradle 9, Gatling 3.9.5, gatling-grpc 0.17.0, MessagePack (`core/libs/common-data-1.0.0.jar`).

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

- Do not modify `core/`, `games/naga777/`, `games/bonanza/`, `games/silkroad/` or the backend repo.
- pluginName is exactly `yama_01024`; zone `MiniGame`; agency `loadtest`.
- gRPC default endpoint `localhost:9104`; health URL `http://localhost:3000/api/game/mutant-merge/health`.
- `betLevelId` is a 1-based index into the ladder `[0.25, 0.75, 1.00, 1.25, 1.50, 2.00, 2.50, 3.00, 3.50, 4.00, 5.00, 6.00, 7.00, 10.00]`; default `3` (= $1.00); sent on the wire as a String.
- Business errors come back with gRPC status OK and a msgpack body where `c` is a non-zero number; `message` holds the error name.
- Every new `-D` flag the simulation reads must be listed in the module's `FORWARDED_PROPS`.
- There are no unit tests in this repo: verification = compile + a short smoke run against the live backend.
- Code comments in new files: English, short, only what the code cannot say.
- SUT for smoke runs (started separately, in `/Users/yamazaki-ethan/Documents/Projects/Stable_Mutant_Merge/be-mutant-merge`): Mongo + Redis via `make up-full`, env `WALLET_GATEWAY=mock`, `CHEAT_ENABLED=false`, `GRPC_PORT=9104`, container `game-mutant-merge`.

## References

- `games/naga777/build.gradle` - module build to copy-adapt.
- `games/naga777/src/gatling/scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala` - simulation to copy-adapt.

## File Structure

- `./` — settings.gradle (T01), CLAUDE.md (T03), README.md (T03)
- `games/mutantmerge/` — build.gradle (T01)
- `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/` — MutantMergeGrpcSimulation.scala (T01)
- `games/mutantmerge/src/gatling/resources/` — game.yml (T01), sla-thresholds.yml (T01), logback-test.xml (T01)
- `scripts/` — run-variant.sh (T02)

## Contracts

#### T01: mutantmerge module + gRPC simulation
- Files: `settings.gradle`, `games/mutantmerge/build.gradle`, `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala`, `games/mutantmerge/src/gatling/resources/game.yml`, `games/mutantmerge/src/gatling/resources/sla-thresholds.yml`, `games/mutantmerge/src/gatling/resources/logback-test.xml`
- Produces: `class MutantMergeGrpcSimulation extends Simulation`
- Spec: L7-62, L77-89

#### T02: run-variant.sh wiring
- Depends: T01
- Files: `scripts/run-variant.sh`
- Spec: L63-72

#### T03: Docs
- Depends: T02
- Files: `CLAUDE.md`, `README.md`
- Spec: L63-76

<!-- WAVES -->
## Execution Waves

Every task in a wave has all its Depends/Runs-after tasks in earlier waves. Tasks in the
same wave touch disjoint files, so a wave's `[P]` tasks may all run at once.

- **Wave 1:** T01
- **Wave 2:** T02
- **Wave 3:** T03
<!-- /WAVES -->

<!-- TASKS -->

### T01: mutantmerge module + gRPC simulation

**Depends:** —

**Interfaces:**
- Produces: `class MutantMergeGrpcSimulation extends Simulation`

**Files:**
- Modify: `settings.gradle`
- Create: `games/mutantmerge/build.gradle`
- Create: `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala`
- Create: `games/mutantmerge/src/gatling/resources/game.yml`
- Create: `games/mutantmerge/src/gatling/resources/sla-thresholds.yml`
- Create: `games/mutantmerge/src/gatling/resources/logback-test.xml`

- [ ] **Step 1: Register the module**

In `settings.gradle`, add this line directly after `include ':games:naga777'`:

```groovy
include ':games:mutantmerge'
```

- [ ] **Step 2: Create `games/mutantmerge/build.gradle`**

```groovy
// Mutant Merge load test — gRPC-only (the game has no REST spin endpoint).
//
// Same runtime as games/naga777: Gatling 3.9.5 + com.github.phisgr:gatling-grpc via JavaExec,
// because Gatling 3.15's OSS gRPC DSL is capped at 5 VU / 5 minutes and io.gatling.gradle 3.9.5
// breaks on Gradle 9.
//
// Run examples (from repo root) — needs the be-mutant-merge backend running locally:
//   ./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
//                                     -DrequestRate=0 -DeventCount=0
//   ./gradlew :games:mutantmerge:grpc -Dusers=1000 -DdurationMinutes=60 -DbetLevelId=3

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
    'requestRate', 'eventCount', 'grpcHost', 'grpcPort', 'betLevelId', 'superBet',
]

def SIMULATIONS = [
    grpc: 'com.rgp.loadtest.mutantmerge.grpc.MutantMergeGrpcSimulation',
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
        outputs.upToDateWhen { false }
        doFirst {
            systemProperty 'gameName', project.name
            FORWARDED_PROPS.each { name ->
                def v = System.getProperty(name)
                if (v != null) systemProperty(name, v)
            }
        }
    }
}
```

- [ ] **Step 3: Create the resources**

`games/mutantmerge/src/gatling/resources/game.yml` (same `http:` nesting as `games/bonanza/src/gatling/resources/game.yml`):

```yaml
http:
  port: 3000                           # REST is only the health probe; gRPC uses -DgrpcHost/-DgrpcPort
  contextPath: /api/game/mutant-merge
```

`games/mutantmerge/src/gatling/resources/sla-thresholds.yml` is the naga777 placeholder (comments only, inherits core defaults) with the game name swapped:

```bash
sed "s/Naga's Fortune 777/Mutant Merge/" games/naga777/src/gatling/resources/sla-thresholds.yml > games/mutantmerge/src/gatling/resources/sla-thresholds.yml
```

`games/mutantmerge/src/gatling/resources/logback-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  grpc-java-netty logs every HTTP/2 frame at DEBUG; at 1000 VU x 60 min that floods gatling.log.
  Root WARN keeps the console summary and assertion output that run-variant.sh parses.
-->
<configuration>

	<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
		<encoder>
			<pattern>%d{HH:mm:ss.SSS} [%-5level] %logger{15} - %msg%n%rEx</pattern>
		</encoder>
		<immediateFlush>false</immediateFlush>
	</appender>

	<!-- uncomment and set to DEBUG to log failing gRPC calls only -->
	<!--<logger name="com.github.phisgr.gatling.grpc" level="DEBUG" />-->

	<root level="WARN">
		<appender-ref ref="CONSOLE" />
	</root>

</configuration>
```

- [ ] **Step 4: Create the simulation**

`games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala`:

```scala
package com.rgp.loadtest.mutantmerge.grpc

import com.github.phisgr.gatling.grpc.Predef._
import com.google.protobuf.ByteString
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginResponse, PluginServiceGrpc, PluginUser}
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioBuilder

import java.util.UUID
import scala.concurrent.duration._

/**
 * gRPC load test for Mutant Merge (be-mutant-merge, pluginName "yama_01024").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — no Redis session: ConnectHandler falls back to
 *      user.parameters (agency/username/memberId) and still registers the TokenRegistry entry.
 *   2. Spin loop (Call, cmd 1500) — `{betLevelId}` (1-based ladder index, String) plus
 *      `superBet: true` when -DsuperBet=true. The response carries the full spin result.
 *
 * Business errors return gRPC OK with `c != 0` in the msgpack body, so Spin decodes the body
 * and fails the request on a non-zero `c`.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9104, betLevelId=3 ($1.00),
 * superBet=false.
 */
class MutantMergeGrpcSimulation extends Simulation {

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9104).intValue()
  private val betLevelId      = Integer.getInteger("betLevelId", 3).intValue()
  private val superBet        = java.lang.Boolean.getBoolean("superBet")

  private val PluginName = "yama_01024"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val Ok         = "OK"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agency", Agency)
    params.put("username", userId)
    params.put("memberId", userId)
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
    data.put("betLevelId", String.valueOf(betLevelId))
    if (superBet) data.put("superBet", java.lang.Boolean.TRUE)
    PluginRequest.newBuilder()
      .setZone(Zone)
      .setPluginName(PluginName)
      .setUsername(userId)
      .setData(ByteString.copyFrom(codec.encode(data)))
      .build()
  }

  // "OK", or "<code>:<message>" for the backend error envelope, so failures group by error name.
  private def spinStatus(res: PluginResponse): String = {
    val body = codec.decodeToMap(res.getResult.toByteArray)
    if (body.isEmpty) "UNDECODABLE_RESPONSE"
    else body.get("c") match {
      case c: Number if c.longValue() != 0 => s"${c.longValue()}:${body.get("message")}"
      case _                               => Ok
    }
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
    scenario("mutantmerge-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
      )
      .exitHereIfFailed
      .during(durationMinutes.minutes) {
        exec(
          grpc("Spin")
            .rpc(PluginServiceGrpc.getCallMethod)
            .payload(spinPayload)
            .extract(res => Some(spinStatus(res)).success)(_.is(Ok))
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

- [ ] **Step 5: Compile**

Run: `./gradlew :games:mutantmerge:gatlingClasses`
Expected: `BUILD SUCCESSFUL`. If `extract` does not type-check, check the `extract` signature of `com.github.phisgr.gatling.grpc.action.GrpcCallActionBuilder` 0.17.0 (`javap -cp <gatling-grpc-0.17.0.jar> com.github.phisgr.gatling.grpc.action.GrpcCallActionBuilder`) and adapt only that one line, keeping the `_.is(Ok)` validation.

- [ ] **Step 6: Smoke run against the local backend**

Start the SUT per Global Constraints, then:

Run: `./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -DrequestRate=0 -DeventCount=0`
Expected: `BUILD SUCCESSFUL`; the Gatling console shows `Join` and `Spin` with `KO=0` and roughly 12 Spin requests; backend log shows `[spin] << OK`.

- [ ] **Step 7: Negative check — body errors are counted as KO**

Run: `./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 -DrequestRate=0 -DeventCount=0 -DbetLevelId=99`
Expected: Gradle task fails on assertions; the Gatling error table lists every Spin as KO with a message containing `INVALID_BET`.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle games/mutantmerge/build.gradle games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala games/mutantmerge/src/gatling/resources/game.yml games/mutantmerge/src/gatling/resources/sla-thresholds.yml games/mutantmerge/src/gatling/resources/logback-test.xml
git commit -m "feat(mutantmerge): add gRPC load-test module"
```

---

### T02: run-variant.sh wiring

**Depends:** T01

**Interfaces:**
- Consumes: `class MutantMergeGrpcSimulation extends Simulation`

**Files:**
- Modify: `scripts/run-variant.sh`

- [ ] **Step 1: Add the default port**

In the `if [[ -z "$PORT" ]]` block, add after the `naga777)  PORT=3000 ;;` line:

```bash fragment
    mutantmerge) PORT=3000 ;;
```

- [ ] **Step 2: Add the health probe URL**

In the `case "$GAME" in` block that sets `HEALTH_URL`, add after the `naga777)` line:

```bash fragment
  mutantmerge) HEALTH_URL="http://localhost:${PORT}/api/game/mutant-merge/health" ;;
```

- [ ] **Step 3: Check the script still parses**

Run: `bash -n scripts/run-variant.sh`
Expected: no output, exit code 0.

- [ ] **Step 4: Wrapper smoke run**

With the SUT running:

Run: `./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc --users 5 --duration-minutes 1 --ramp-minutes 0 --container game-mutant-merge`
Expected: log line `Health probe URL: http://localhost:3000/api/game/mutant-merge/health`; the output dir under `target/variants/mutantmerge/` contains `resource.csv`, `health.csv` (all rows HTTP 200), `verdict.json`, `summary.html`; Join/Spin have 0 KO. The `requestRate`/`eventCount` floor assertions failing at this scale is expected (same as naga777).

- [ ] **Step 5: Commit**

```bash
git add scripts/run-variant.sh
git commit -m "feat(run-variant): support mutantmerge game"
```

---

### T03: Docs

**Depends:** T02

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Update CLAUDE.md**

Make these edits:
1. In the compile command, append `:games:mutantmerge:gatlingClasses` after `:games:naga777:gatlingClasses`.
2. After the `./gradlew :games:naga777:grpc ...` line add: `./gradlew :games:mutantmerge:grpc -Dusers=5 -DdurationMinutes=1 -DrequestRate=0 -DeventCount=0 -DgrpcPort=9104`
3. In the "Simulation aliases per game" sentence, after the naga777 clause add: `mutantmerge has `grpc` only (gRPC :9104, pluginName `yama_01024`, bet via 1-based `-DbetLevelId`, optional `-DsuperBet=true`; Spin fails on a non-zero `c` in the response body).`
4. In the gRPC runtimes list, after the naga777 bullet add: `- `games/mutantmerge/src/gatling/scala/MutantMergeGrpcSimulation.scala` — whole module on 3.9.5 (`gatlingRt`), copy of the naga777 layout.`

- [ ] **Step 2: Update README.md**

Make these edits:
1. Games table: add a row after the naga777 row:
   `| Mutant Merge        | `mutantmerge` | `../Stable_Mutant_Merge/be-mutant-merge` | `game-mutant-merge` | `3000` | `Grpc` |`
2. `--game <silkroad|bonanza|naga777>` → `--game <silkroad|bonanza|naga777|mutantmerge>`; in the `--game` option row list `mutantmerge` too; in the `--port` row say `3000` for silkroad, naga777 and mutantmerge.
3. In the "`Grpc` defaults differ" note, change the port list to `grpcPort=9091` (bonanza) / `9096` (naga777) / `9104` (mutantmerge).
4. After the naga777 `run-variant.sh` example add:

```bash
./scripts/run-variant.sh --game mutantmerge \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container game-mutant-merge
```

5. In "gRPC runtimes", add `- **mutantmerge** is gRPC-only, so its whole module is on 3.9.5 (`gatlingRt` configuration).` after the naga777 bullet; in the version table mention mutantmerge next to naga777.
6. In "Scala / gRPC simulation", change "**two files**" to "**three files**" and add `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala` to the list.

- [ ] **Step 3: Verify the documented compile command**

Run: `./gradlew :core:classes :games:silkroad:gatlingClasses :games:bonanza:gatlingClasses :games:bonanza:gatlingGrpcClasses :games:naga777:gatlingClasses :games:mutantmerge:gatlingClasses`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: document mutantmerge load test"
```
