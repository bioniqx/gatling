# Naga777 Load Test Module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm module `games/naga777` bắn tải gRPC (PluginService :9096) cho backend NAGAS 777, tích hợp wrapper `run-variant.sh`.

**Architecture:** Module Gatling mới mirror pattern `games/bonanza` (Scala simulation gọi Java DSL `io.gatling.javaapi.grpc`), tái dùng proto stub + MessagePack `Codec` từ `:core`. Journey mỗi VU: REST register session → gRPC ConnectAndCall (cmd 1005) → loop Call SPIN (cmd 1500). Không sửa `core/`, không sửa `games/bonanza|silkroad`.

**Tech Stack:** Gatling 3.15.0 (OSS), Java 17 toolchain, Scala 2.13.12, gRPC 1.75.0, MessagePack (qua `com.rgp.loadtest.core.protocol.Codec`).

**Spec:** `docs/superpowers/specs/2026-07-23-naga777-load-test-design.md`

## Global Constraints

- **Repo CHƯA init git** → bỏ qua mọi bước commit. Không tự `git init`.
- **Không tạo unit test file** — verify bằng compile (`gatlingClasses`) + smoke run (quy ước repo).
- Wire contract (đã đối chiếu source naga, KHÔNG đổi):
  - `pluginName = "game-naga-fortune-777"` (`games/game-naga-fortune-777.yaml:3` bên naga)
  - zone = `"MiniGame"`
  - Session token key trong `user.parameters` msgpack: `"token"` (`ConnectHandler.java:42`)
  - Spin payload: `{cmd:1500, betLevelId:"<coinPerLine>", coinValueId:"<coinValue>"}` — betLevelId/coinValueId là **String**; raw `betAmount` bị backend reject (`SpinHandler.java:100-105`)
  - Debug register: `POST /api/v1/debug/session/register`, header `X-Debug-Token: slot-engine-debug`
- Defaults: `grpcPort=9096`, `grpcHost=localhost`, REST port 3000, `coinValue=5`, `coinPerLine=3`, `rampMinutes=2`, `paceSec=5`, `requestRate=50`, `eventCount=100000`.
- Backend SUT: `/Users/yamazaki-ethan/Documents/Projects/Stable_NAGAS_777`, container `stable-naga_fortune_777`.

---

### Task 1: Scaffold module `games/naga777` + đăng ký vào settings.gradle

**Files:**
- Create: `games/naga777/build.gradle`
- Create: `games/naga777/src/gatling/resources/game.yml`
- Create: `games/naga777/src/gatling/resources/sla-thresholds.yml`
- Modify: `settings.gradle`

**Interfaces:**
- Consumes: `:core` (proto stubs `com.rgp.loadtest.grpc.*`, `Codec`, `LoadTestConfig`, `SlaConstants`).
- Produces: Gradle task alias `:games:naga777:grpc` chạy FQCN `com.rgp.loadtest.naga777.grpc.Naga777GrpcSimulation` (Task 2 tạo class này); `FORWARDED_PROPS` forward các `-D` prop xuống Gatling fork.

- [ ] **Step 1: Tạo `games/naga777/build.gradle`**

```groovy
// Naga's Fortune 777 load test — gRPC-only (game không có REST spin endpoint).
//
// Simulation Scala dùng Gatling 3.15 first-party Java DSL (io.gatling.javaapi.grpc),
// cùng pattern với games/bonanza. Proto stub + MessagePack Codec tái dùng từ :core.
//
// Run examples (from repo root) — cần backend NAGAS 777 chạy local (docker compose):
//   ./gradlew :games:naga777:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
//                                 -DrequestRate=0 -DeventCount=5
//   ./gradlew :games:naga777:grpc -Dusers=1000 -DdurationMinutes=60 \
//                                 -DgrpcHost=localhost -DgrpcPort=9096

plugins {
    id 'java'
    id 'scala'
    id 'io.gatling.gradle' version "${gatlingGradleVersion}"
}

dependencies {
    gatlingImplementation project(':core')

    // Scala runtime để compile + load simulation Scala trong Gatling fork.
    gatlingImplementation "org.scala-lang:scala-library:2.13.12"
}

gatling {
    jvmArgs = [
        '-server',
        '-Xmx2G',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    ]
}

// -D… system properties Gatling simulations read at runtime. They aren't forwarded automatically
// from the Gradle JVM to the gatlingRun fork, so we plumb them through explicitly.
def FORWARDED_PROPS = [
    'users', 'durationMinutes', 'rampMinutes', 'host', 'port', 'paceSec',
    'requestRate', 'eventCount', 'grpcHost', 'grpcPort', 'coinValue', 'coinPerLine',
]

def SIMULATIONS = [
    grpc: 'com.rgp.loadtest.naga777.grpc.Naga777GrpcSimulation',
]

SIMULATIONS.each { alias, fqcn ->
    tasks.register(alias, io.gatling.gradle.GatlingRunTask) {
        group = 'gatling'
        description = "Run ${alias} simulation (${fqcn})"
        simulationClassName = fqcn
        // Load tests always run — no input fingerprint can reliably detect "same SUT state"
        outputs.upToDateWhen { false }
        // gameName lets SlaConfigLoader find this game's per-game SLA override at
        // games/<gameName>/src/gatling/resources/sla-thresholds.yml.
        def fwd = ['gameName': project.name]
        FORWARDED_PROPS.each { name ->
            def v = System.getProperty(name)
            if (v != null) fwd[name] = v
        }
        systemProperties = fwd
    }
}
```

- [ ] **Step 2: Tạo `games/naga777/src/gatling/resources/game.yml`**

```yaml
# ============================================================================
# Naga's Fortune 777 — Per-Game Override
# ============================================================================
#
# Naga777 REST (session-register + health) chạy localhost:3000 không contextPath
# → match core defaults, không cần khai gì. File giữ làm placeholder + audit trail.
#
# gRPC endpoint (spin) KHÔNG cấu hình ở đây — simulation đọc -DgrpcHost / -DgrpcPort
# (default localhost:9096).
#
# Nếu staging/prod đổi port REST, override qua CLI: -Dport=8080 -Dhost=staging.example.com
# ============================================================================
```

- [ ] **Step 3: Tạo `games/naga777/src/gatling/resources/sla-thresholds.yml`**

```yaml
# ============================================================================
# Naga's Fortune 777 — Per-Game SLA Override
# ============================================================================
#
# Chỉ list field khác config/sla-thresholds.yml (core). Field thiếu → inherit core.
# Hiện dùng nguyên core defaults — chỉnh sau khi có baseline production.
# ============================================================================
```

- [ ] **Step 4: Đăng ký module trong `settings.gradle`**

Sửa `settings.gradle`: thêm `include ':games:naga777'` và xoá dòng placeholder `naga-fortune-777`:

```groovy
include ':core'
include ':games:silkroad'
include ':games:bonanza'
include ':games:naga777'

// To enable another game, drop its directory under games/<name>/ and uncomment here.
// include ':games:fruit-respin-mania'
// include ':games:nagas-treasure'
// include ':games:apsara-paradise'
// include ':games:last-guardian-angkor'
```

- [ ] **Step 5: Verify Gradle nhận module + alias**

Run: `./gradlew projects --console=plain | grep naga777`
Expected: `+--- Project ':games:naga777'`

Run: `./gradlew :games:naga777:tasks --group gatling --console=plain | grep -A1 "^grpc"`
Expected: task `grpc` xuất hiện với description `Run grpc simulation (com.rgp.loadtest.naga777.grpc.Naga777GrpcSimulation)`

---

### Task 2: Viết `Naga777GrpcSimulation.scala`

**Files:**
- Create: `games/naga777/src/gatling/scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala`

**Interfaces:**
- Consumes: `com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginServiceGrpc, PluginUser}` (proto stub từ `:core`), `com.rgp.loadtest.core.protocol.Codec` (`byte[] encode(Object)`), `com.rgp.loadtest.core.config.LoadTestConfig` (`users`, `durationMinutes`, `httpProtocol()`), `com.rgp.loadtest.core.utils.SlaConstants` (`PR4_MEAN_RESPONSE_MS_MAX`, `PR5_ERROR_PERCENT_MAX`, `MAX_DURATION_BUFFER_MIN`).
- Produces: class `com.rgp.loadtest.naga777.grpc.Naga777GrpcSimulation extends Simulation` — FQCN mà alias `grpc` (Task 1) trỏ tới.

- [ ] **Step 1: Viết simulation**

```scala
package com.rgp.loadtest.naga777.grpc

import com.google.protobuf.ByteString
import com.rgp.loadtest.core.config.LoadTestConfig
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginServiceGrpc, PluginUser}
import io.gatling.javaapi.core.CoreDsl._
import io.gatling.javaapi.core.{ScenarioBuilder, Session, Simulation}
import io.gatling.javaapi.grpc.GrpcDsl._
import io.gatling.javaapi.grpc.GrpcProtocolBuilder
import io.gatling.javaapi.http.HttpDsl._

import java.time.Duration
import java.util.UUID
import java.util.function.{Function => JFunction}

/**
 * gRPC load test cho Naga's Fortune 777 — game không có REST spin endpoint, spin chỉ đi qua
 * PluginService (port 9096). Mirror shape của bonanza `BonanzaGrpcSimulation`, thêm bước
 * RegisterSession vì naga resolve session từ Redis qua token (không nhận accessToken trực tiếp).
 *
 * Journey mỗi VU:
 *   1. RegisterSession (HTTP :3000) — seed session vào Redis, lấy `sessionToken`
 *   2. Join (gRPC ConnectAndCall, cmd 1005) — `user.parameters.token` = sessionToken
 *   3. Spin loop (gRPC Call, cmd 1500) — `{betLevelId, coinValueId}`, pace `paceSec`
 *
 * Wire contract (đối chiếu source naga):
 *   - pluginName "game-naga-fortune-777" (GameRegistry match đúng chuỗi này)
 *   - SpinHandler chỉ nhận betLevelId (= coinPerLine 1..10) + coinValueId (= coinValue
 *     ∈ {1,5,20,50,100,200,500}) dạng String; raw betAmount bị BET_INPUT_REJECTED
 *   - ⚠️ Kết quả spin thật push qua ZMQ — latency đo được là gRPC ack; tải engine vẫn là thật
 *
 * Defaults (override via `-D…`):
 *   - `users=1000`, `durationMinutes=60`, `rampMinutes=2`, `paceSec=5`
 *   - `requestRate=50` req/s floor; `eventCount=100000` successful requests floor
 *   - `grpcHost=localhost`, `grpcPort=9096`; REST register qua LoadTestConfig (port 3000)
 *   - `coinValue=5`, `coinPerLine=3` → server derive bet = 5 × 3 × 5 = 75
 */
class Naga777GrpcSimulation extends Simulation {

  private val users           = LoadTestConfig.users
  private val durationMinutes = LoadTestConfig.durationMinutes
  // Đọc trực tiếp (không qua LoadTestConfig) để default = 2 như bonanza grpc, thay vì 5 repo-wide.
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9096).intValue()
  private val coinValue       = Integer.getInteger("coinValue", 5).intValue()
  private val coinPerLine     = Integer.getInteger("coinPerLine", 3).intValue()

  private val PluginName = "game-naga-fortune-777"
  private val Zone       = "MiniGame"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String, sessionToken: String): ConnectAndCallRequest = {
    // ConnectHandler đọc key "token" để resolve session Redis; username/reconnect cho fallback path.
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("token", sessionToken)
    params.put("username", userId)
    params.put("reconnect", java.lang.Boolean.FALSE)
    val paramBytes = codec.encode(params)

    val user = PluginUser.newBuilder()
      .setId(userId)
      .setUsername(userId)
      .setSessionId(userId)
      .setIp("127.0.0.1")
      .setParameters(ByteString.copyFrom(paramBytes))
      .build()

    ConnectAndCallRequest.newBuilder()
      .setZone(Zone)
      .setUser(user)
      .setPluginName(PluginName)
      .build()
  }

  private def buildSpinRequest(userId: String): PluginRequest = {
    // SpinHandler nhận betLevelId (= coinPerLine) + coinValueId (= coinValue) dạng String.
    val data = new java.util.LinkedHashMap[String, Object]()
    data.put("cmd", Integer.valueOf(1500))
    data.put("betLevelId", String.valueOf(coinPerLine))
    data.put("coinValueId", String.valueOf(coinValue))
    val dataBytes = codec.encode(data)
    PluginRequest.newBuilder()
      .setZone(Zone)
      .setPluginName(PluginName)
      .setUsername(userId)
      .setData(ByteString.copyFrom(dataBytes))
      .build()
  }

  private val userFeeder: java.util.Iterator[java.util.Map[String, Object]] = {
    val supplier: java.util.function.Supplier[java.util.Map[String, Object]] = () => {
      val m = new java.util.HashMap[String, Object]()
      m.put("userId", "loadtest-" + UUID.randomUUID().toString)
      m
    }
    java.util.stream.Stream.generate(supplier).iterator()
  }

  private val registerBody: JFunction[Session, String] =
    (s: Session) => s"""{"agency":"loadtest","userId":"${s.getString("userId")}"}"""

  private val joinPayload: JFunction[Session, ConnectAndCallRequest] =
    (s: Session) => buildJoinRequest(s.getString("userId"), s.getString("sessionToken"))

  private val spinPayload: JFunction[Session, PluginRequest] =
    (s: Session) => buildSpinRequest(s.getString("userId"))

  private val grpcProtocol: GrpcProtocolBuilder =
    grpc.serverConfigurations(
      grpc.serverConfiguration("default")
        .forAddress(grpcHost, grpcPort)
        .usePlaintext()
        .shareChannel()
    )

  private val playerJourney: ScenarioBuilder =
    scenario("naga777-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        http("RegisterSession")
          .post("/api/v1/debug/session/register")
          .header("X-Debug-Token", "slot-engine-debug")
          .body(StringBody(registerBody))
          .check(status().is(java.lang.Integer.valueOf(200)))
          .check(jsonPath("$.sessionToken").saveAs("sessionToken"))
      )
      // Session hỏng thì dừng VU — không bắn spin gây nhiễu error-rate.
      .exitHereIfFailed()
      .exec(
        grpc("Join")
          .unary(PluginServiceGrpc.getConnectAndCallMethod)
          .send(joinPayload)
      )
      .exec(
        during(Duration.ofMinutes(durationMinutes)).on(
          exec(
            grpc("Spin")
              .unary(PluginServiceGrpc.getCallMethod)
              .send(spinPayload)
          ).pace(Duration.ofSeconds(paceSec))
        )
      )

  setUp(
    playerJourney.injectClosed(
      rampConcurrentUsers(0).to(users).during(Duration.ofMinutes(rampMinutes)),
      constantConcurrentUsers(users).during(Duration.ofMinutes(durationMinutes))
    )
  ).protocols(grpcProtocol, LoadTestConfig.httpProtocol())
    .maxDuration(Duration.ofMinutes(durationMinutes + rampMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN))
    .assertions(
      // Global SLA — cùng nguồn SlaConstants với các game khác.
      global().responseTime().mean().lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
      global().failedRequests().percent().lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
      // Throughput / volume floors — cùng shape với bonanza grpc.
      global().requestsPerSec().gt(requestRate),
      global().successfulRequests().count().gt(eventCount),
      // Per-request thresholds.
      details("Spin").responseTime().percentile3().lte(800),
      details("Spin").failedRequests().percent().lte(0.5),
      details("Join").failedRequests().percent().lte(0.5),
      details("RegisterSession").failedRequests().percent().lte(0.5)
    )
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :games:naga777:gatlingClasses --console=plain`
Expected: `BUILD SUCCESSFUL`. Nếu lỗi Scala/Java-interop ở `status().is(...)` hoặc `StringBody(...)`, đối chiếu cách gọi tương đương trong `games/bonanza/src/gatling/scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala` (cùng Java DSL) và signature trong `io.gatling.javaapi.http.HttpDsl` — sửa call-site, KHÔNG sửa core.

---

### Task 3: Tích hợp `run-variant.sh` + cập nhật docs

**Files:**
- Modify: `scripts/run-variant.sh:72-87` (2 case blocks PORT + HEALTH_URL)
- Modify: `README.md` (bảng Games today, câu đếm stub, bảng The simulations, mục Verify SUT, Standard examples)
- Modify: `CLAUDE.md` (dòng aliases + 1 lệnh ví dụ)

**Interfaces:**
- Consumes: alias `:games:naga777:grpc` (Task 1). Wrapper build task từ `--simulation Grpc` → lowercase `grpc`.
- Produces: `run-variant.sh --game naga777` chạy được end-to-end với PORT/HEALTH_URL đúng.

- [ ] **Step 1: Thêm case PORT trong `run-variant.sh`**

Sửa block `if [[ -z "$PORT" ]]` (dòng ~72):

```bash
if [[ -z "$PORT" ]]; then
  case "$GAME" in
    bonanza)   PORT=3005 ;;
    naga777)  PORT=3000 ;;
    silkroad) PORT=3000 ;;
    *)        PORT=3000 ;;
  esac
fi
```

- [ ] **Step 2: Thêm case HEALTH_URL trong `run-variant.sh`**

Sửa block `case "$GAME"` health probe (dòng ~83):

```bash
case "$GAME" in
  bonanza)   HEALTH_URL="http://localhost:${PORT}/golden/api/configs/bet-levels" ;;
  naga777)  HEALTH_URL="http://localhost:${PORT}/health" ;;
  silkroad) HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
  *)        HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
esac
```

- [ ] **Step 3: Verify syntax script**

Run: `bash -n scripts/run-variant.sh && grep -n "naga777" scripts/run-variant.sh`
Expected: exit 0, in ra 2 dòng vừa thêm.

- [ ] **Step 4: Cập nhật `README.md`**

4a. Bảng **Games today** — thêm hàng sau hàng bonanza:

```markdown
| Naga's Fortune 777  | `naga777`  | `../Stable_NAGAS_777`       | `stable-naga_fortune_777`    | `3000` | `Grpc`                                        |
```

4b. Câu ngay dưới bảng: đổi `Five more games are stubbed` → `Four more games are stubbed`.

4c. Bảng **The simulations** — hàng `Grpc`: đổi mô tả `**Bonanza-only.** Closed-model soak against the WSProxy gRPC plugin...` thành `**Bonanza & Naga777.** Closed-model soak against the WSProxy gRPC plugin (\`ConnectAndCall\` + \`Call\`) instead of REST. Naga777 is gRPC-only (no REST spin); spin latency measured is the gRPC ack — the full result is pushed over ZMQ.`

4d. Mục **2. Verify the SUT is up** — thêm sau block curl Bonanza:

```bash
# Naga's Fortune 777
curl -s "http://localhost:$PORT/health"
```

4e. Mục **Standard examples** — thêm cuối block code:

```bash
# Naga777 — gRPC production gate (game gRPC-only; port 3000 + health /health auto-derived)
./scripts/run-variant.sh --game naga777 \
  --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 \
  --container stable-naga_fortune_777
```

- [ ] **Step 5: Cập nhật `CLAUDE.md`**

Sửa dòng aliases:

```markdown
Simulation aliases per game: silkroad has `soak/stress/spike/basic`; bonanza has `soak/basic/grpc` (no Stress/Spike yet); naga777 has `grpc` only (no REST spin — gRPC :9096, bet via `-DcoinValue`/`-DcoinPerLine`).
```

Và thêm vào block lệnh chạy simulation (sau dòng bonanza grpc):

```bash
./gradlew :games:naga777:grpc   -Dusers=5 -DdurationMinutes=1 -DgrpcHost=localhost -DgrpcPort=9096
```

- [ ] **Step 6: Verify docs**

Run: `grep -c "naga777" README.md CLAUDE.md`
Expected: README.md ≥ 4 khớp, CLAUDE.md ≥ 2 khớp.

---

### Task 4: Smoke test end-to-end với SUT local

**Files:** không sửa file — chỉ chạy verify. Cần Docker.

**Interfaces:**
- Consumes: toàn bộ Task 1-3; backend stack tại `/Users/yamazaki-ethan/Documents/Projects/Stable_NAGAS_777`.

- [ ] **Step 1: Khởi động SUT**

```bash
cd /Users/yamazaki-ethan/Documents/Projects/Stable_NAGAS_777
docker compose up -d --build
```

Chờ rồi probe (retry tới ~60s):

```bash
curl -s http://localhost:3000/health
```

Expected: HTTP 200, JSON `{"status":"UP",...}`. Nếu 503 → chờ thêm; nếu connection refused sau 2 phút → `docker compose logs naga_fortune_777 | tail -50` và dừng lại báo user.

- [ ] **Step 2: Smoke 1 VU qua Gradle (floors hạ xuống smoke-scale)**

```bash
cd /Users/yamazaki-ethan/Documents/Projects/rgp-game-load-test
./gradlew :games:naga777:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=5 --console=plain
```

Expected: `BUILD SUCCESSFUL`; console Gatling: `RegisterSession` OK=1 KO=0, `Join` OK=1 KO=0, `Spin` OK≈12 KO=0, tất cả assertions PASS.
Nếu Spin KO: xem `docker compose logs` bên naga — `BET_INPUT_REJECTED` = sai betLevelId/coinValueId; `PLUGIN_NOT_FOUND` = sai pluginName; `Redis session miss` = token không vào Redis (check bước RegisterSession).

- [ ] **Step 3: Smoke qua wrapper**

```bash
./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
  --users 5 --duration-minutes 1 --ramp-minutes 0 \
  --container stable-naga_fortune_777
```

Expected:
- `target/variants/naga777/target-<timestamp>/` có đủ: `gatling.log`, `gatling-report/index.html`, `resource.csv` (>0 dòng data), `health.csv` (toàn `200`), `verdict.json`, `summary.html`.
- Trong `gatling.log`: RegisterSession/Join/Spin **0 KO**.
- **Expected-fail:** 2 assertion `requestsPerSec > 50.0` và `successfulRequests > 100000` FAIL ở smoke scale (wrapper không forward 2 prop này — ngưỡng production). Gatling exit ≠ 0 và verdict có thể FAIL vì lý do này là chấp nhận được ở smoke; các row CPU/Mem/crash/health phải PASS.

- [ ] **Step 4: Báo cáo kết quả**

Tổng hợp: số request OK/KO từng bước, p95 Spin (ack latency), CPU/Mem p95 từ `verdict.json`, đường dẫn `summary.html`. Nêu rõ caveat ZMQ-ack trong báo cáo.

---

## Self-Review (đã chạy)

1. **Spec coverage:** cấu trúc module (Task 1) ✓, simulation + journey + defaults + assertions (Task 2) ✓, wrapper + FORWARDED_PROPS + docs (Task 1 & 3) ✓, error handling `exitHereIfFailed` (Task 2) ✓, verification plan (Task 2 Step 2 + Task 4) ✓, out-of-scope không lọt vào plan ✓.
2. **Placeholder scan:** không còn TBD/TODO; mọi step code đều có code thật.
3. **Type consistency:** FQCN `com.rgp.loadtest.naga777.grpc.Naga777GrpcSimulation` khớp Task 1↔2; prop names trong `FORWARDED_PROPS` khớp `sys.props`/`Integer.getInteger` trong sim; `sessionToken` saveAs ↔ getString khớp; alias `grpc` ↔ `--simulation Grpc` lowercase khớp wrapper logic.
