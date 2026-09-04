package com.rgp.loadtest.bonanza.grpc

import com.google.protobuf.ByteString
import com.rgp.loadtest.core.config.LoadTestConfig
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginServiceGrpc, PluginUser}
import io.gatling.javaapi.core.CoreDsl._
import io.gatling.javaapi.core.{ScenarioBuilder, Session, Simulation}
import io.gatling.javaapi.grpc.GrpcDsl._
import io.gatling.javaapi.grpc.GrpcProtocolBuilder

import java.time.Duration
import java.util.UUID
import java.util.function.{Function => JFunction}

/**
 * gRPC counterpart to [[com.rgp.loadtest.bonanza.simulations.SoakSimulation]] — exercises the
 * WSProxy plugin path (`ConnectAndCall` + `Call`) instead of the REST surface.
 *
 * Written in Scala against Gatling 3.15's first-party Java DSL (`io.gatling.javaapi.grpc.*`)
 * because the Scala-flavored DSL in `io.gatling.grpc.*` makes session-aware closures the only
 * shape; mixing it with the rest of this module's static-payload pattern was awkward. The Java
 * DSL is `api`-exposed by `:core`, version-matches the silkroad module, and reads cleanly from
 * Scala — only minor Java-interop ceremony (`JFunction`, no extension methods on builders).
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
 * `rampMinutes` defaults to 2 (legacy bonanza value) rather than [[LoadTestConfig.rampMinutes]]'s
 * repo-wide default of 5 — matches the REST `SoakSimulation` so both gates stay in sync.
 */
class BonanzaGrpcSimulation extends Simulation {

  private val users           = LoadTestConfig.users
  private val durationMinutes = LoadTestConfig.durationMinutes
  // Read direct (not via LoadTestConfig) so the default matches the legacy bonanza value (2)
  // rather than the repo-wide LoadTestConfig.rampMinutes default of 5.
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

  private val userFeeder: java.util.Iterator[java.util.Map[String, Object]] = {
    val supplier: java.util.function.Supplier[java.util.Map[String, Object]] = () => {
      val m = new java.util.HashMap[String, Object]()
      m.put("userId", "loadtest-" + UUID.randomUUID().toString)
      m
    }
    java.util.stream.Stream.generate(supplier).iterator()
  }

  // Scala SAM conversion: `JFunction[Session, T]` here lets us pass a Scala lambda to the
  // Java DSL's `.send(JFunction)` and `.unary(...)` builders.
  private val joinPayload: JFunction[Session, ConnectAndCallRequest] =
    (s: Session) => buildJoinRequest(s.getString("userId"))

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
    scenario("bonanza-grpc-player-journey")
      .feed(userFeeder)
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
  ).protocols(grpcProtocol)
    .maxDuration(Duration.ofMinutes(durationMinutes + rampMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN))
    .assertions(
      // Global SLA — same source as REST SoakSimulation so both gates share thresholds.
      global().responseTime().mean().lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
      global().failedRequests().percent().lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
      // Throughput / volume floors — ported verbatim from legacy BonanzaGrpcSimulation.
      global().requestsPerSec().gt(requestRate),
      global().successfulRequests().count().gt(eventCount),
      // Per-RPC thresholds — ported verbatim.
      details("Spin").responseTime().percentile3().lte(800),
      details("Spin").failedRequests().percent().lte(0.5),
      details("Join").failedRequests().percent().lte(0.5)
    )
}
