package com.rgp.loadtest.bonanza.grpc

import com.github.phisgr.gatling.grpc.Predef._
import com.google.protobuf.ByteString
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginServiceGrpc, PluginUser}
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioBuilder

import java.util.UUID
import scala.concurrent.duration._

/**
 * gRPC counterpart to [[com.rgp.loadtest.bonanza.simulations.SoakSimulation]] — exercises the
 * WSProxy plugin path (`ConnectAndCall` + `Call`) instead of the REST surface.
 *
 * Runs on the community plugin `com.github.phisgr:gatling-grpc` (Gatling 3.9.5 core) rather than
 * Gatling 3.15's first-party gRPC DSL: that DSL is Enterprise-gated and aborts the run above
 * 5 VUs / 5 minutes, which makes the 1000-VU production gate impossible. This module's REST
 * simulations stay on Gatling 3.15 — see build.gradle for how the two classpaths are kept apart.
 *
 * Wire format: both `ConnectAndCallRequest.user.parameters` and `PluginRequest.data` are
 * MessagePack-encoded blobs (not protobuf substructures). The backend decodes via the proprietary
 * GaaS library, so wire-format drift here surfaces as `UserContext.fromPuObject` NPEs server-side.
 *
 * Defaults (override via `-D…`):
 *   - `users=1000` concurrent, `durationMinutes=60`, `rampMinutes=2`, `paceSec=5`
 *   - `requestRate=50` req/s floor; `eventCount=100000` successful requests floor
 *   - `grpcHost=localhost`, `grpcPort=9091`
 *   - Global PR-4 / PR-5 ceilings from [[SlaConstants]] (per-game YAML override)
 *
 * `rampMinutes` defaults to 2 (legacy bonanza value) rather than the repo-wide default of 5 —
 * matches the REST `SoakSimulation` so both gates stay in sync.
 */
class BonanzaGrpcSimulation extends Simulation {

  // Read straight from system properties rather than via :core's LoadTestConfig: that class is
  // compiled against Gatling 3.15's javaapi and would drag 3.15 types onto this 3.9.5 classpath.
  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  // Throughput + volume floors — carried over verbatim from the legacy standalone repo.
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9091).intValue()

  private val Bet   = "1.00"
  private val codec = new Codec()

  // Zeroed agencyId/memberId so the backend's UserContext.fromPuObject doesn't NPE.
  // LinkedHashMap preserves insertion order — matches the legacy MessagePack byte sequence.
  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agentId", "1")
    params.put("accessToken", userId)
    params.put("agencyId", Integer.valueOf(0))
    params.put("memberId", Integer.valueOf(0))
    params.put("uid", userId)
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
      .setZone("MiniGame")
      .setUser(user)
      .setPluginName("golden-boat-bonanza")
      .build()
  }

  private def buildSpinRequest(userId: String): PluginRequest = {
    val data = new java.util.LinkedHashMap[String, Object]()
    data.put("cmd", Integer.valueOf(1500))
    data.put("betAmount", Bet)
    data.put("isTrial", java.lang.Boolean.FALSE)
    val dataBytes = codec.encode(data)
    PluginRequest.newBuilder()
      .setZone("MiniGame")
      .setPluginName("golden-boat-bonanza")
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
    scenario("bonanza-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
      )
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
      // Global SLA — same source as REST SoakSimulation so both gates share thresholds.
      global.responseTime.mean.lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
      global.failedRequests.percent.lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
      // Throughput / volume floors — ported verbatim from legacy BonanzaGrpcSimulation.
      global.requestsPerSec.gt(requestRate),
      global.successfulRequests.count.gt(eventCount),
      // Per-RPC thresholds — ported verbatim.
      details("Spin").responseTime.percentile3.lte(800),
      details("Spin").failedRequests.percent.lte(0.5),
      details("Join").failedRequests.percent.lte(0.5)
    )
}
