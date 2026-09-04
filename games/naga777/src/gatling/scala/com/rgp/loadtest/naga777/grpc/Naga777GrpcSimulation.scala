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
