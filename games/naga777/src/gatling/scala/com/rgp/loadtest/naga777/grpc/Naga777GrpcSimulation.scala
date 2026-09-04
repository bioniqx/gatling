package com.rgp.loadtest.naga777.grpc

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
 * gRPC load test cho Naga's Fortune 777 — game gRPC-only, không còn REST surface nào để đo:
 * backend hiện chỉ expose `/health` + `/health/detail`, debug ops đã chuyển hẳn sang gRPC cmd 1900.
 *
 * Dùng community plugin `com.github.phisgr:gatling-grpc` (Gatling 3.9.5 core, xem build.gradle)
 * thay vì gRPC DSL first-party của Gatling 3.15 — bản OSS của DSL đó chặn cứng ở 5 VU / 5 phút.
 *
 * Journey mỗi VU:
 *   1. Join (gRPC ConnectAndCall, cmd 1005) — identity đi trong `user.parameters`
 *   2. Spin loop (gRPC Call, cmd 1500) — `{betLevelId, coinValueId}`, pace `paceSec`
 *
 * Wire contract (đối chiếu source naga):
 *   - pluginName "game-naga-fortune-777" (GameRegistry match đúng chuỗi này)
 *   - Không seed session Redis trước: `ConnectHandler` miss session thì fallback sang
 *     `user.parameters` (agency/username) rồi vẫn đăng ký TokenRegistry, nên spin chạy được.
 *     Balance đến từ MockWalletAdapter (`WALLET_GATEWAY=mock`), không cần wallet thật.
 *   - SpinHandler chỉ nhận betLevelId (= coinPerLine 1..10) + coinValueId (= coinValue
 *     ∈ {1,5,20,50,100,200,500}) dạng String; raw betAmount bị BET_INPUT_REJECTED
 *   - ⚠️ Kết quả spin thật push qua ZMQ — latency đo được là gRPC ack; tải engine vẫn là thật
 *
 * Defaults (override via `-D…`):
 *   - `users=1000`, `durationMinutes=60`, `rampMinutes=2`, `paceSec=5`
 *   - `requestRate=50` req/s floor; `eventCount=100000` successful requests floor
 *   - `grpcHost=localhost`, `grpcPort=9096`
 *   - `coinValue=5`, `coinPerLine=3` → server derive bet = 5 × 3 × 5 = 75
 */
class Naga777GrpcSimulation extends Simulation {

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
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
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    // Không có token session Redis: ConnectHandler lấy agency/userId từ đây qua fallback path.
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agency", Agency)
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
    scenario("naga777-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
      )
      // Join hỏng thì dừng VU — không bắn spin gây nhiễu error-rate.
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
      // Global SLA — cùng nguồn SlaConstants với các game khác.
      global.responseTime.mean.lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
      global.failedRequests.percent.lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
      // Throughput / volume floors — cùng shape với bonanza grpc.
      global.requestsPerSec.gt(requestRate),
      global.successfulRequests.count.gt(eventCount),
      // Per-request thresholds.
      details("Spin").responseTime.percentile3.lte(800),
      details("Spin").failedRequests.percent.lte(0.5),
      details("Join").failedRequests.percent.lte(0.5)
    )
}
