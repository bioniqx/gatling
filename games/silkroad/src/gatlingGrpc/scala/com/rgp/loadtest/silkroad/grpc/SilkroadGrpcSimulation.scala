package com.rgp.loadtest.silkroad.grpc

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
 * gRPC counterpart to silkroad's REST simulations — exercises the WSProxy plugin path
 * (`ConnectAndCall` + `Call`) against be-silk-road-caravans instead of its REST surface. Same
 * runtime split bonanza used to have (see build.gradle): Gatling 3.9.5 + community
 * `com.github.phisgr:gatling-grpc` via JavaExec, because Gatling 3.15's OSS gRPC DSL is
 * Enterprise-gated above 5 VUs / 5 minutes.
 *
 * Wire contract, derived from be-silk-road-caravans:
 *   - `pluginName` = `SlotConstants.PLUGIN_NAME` = "game-silk-road-caravans"; `zone` =
 *     `SlotConstants.ZONE_DEFAULT` = "default" (SlotConstants.java).
 *   - Join (`ConnectAndCall`): `ConnectAndCallRequest.user.parameters` is a MessagePack map decoded
 *     by `ConnectFlowUseCaseImpl.connectAndCall` — `agencyId`, `memberId`, and any of
 *     `userId`/`uid`/`user_id` become the stable `effectiveUserId = agencyId:uid`
 *     (ConnectFlowUseCaseImpl.java, connectAndCall()).
 *   - Session identity on `Call`: the backend's own proto reserves `PluginRequest.sessionId`
 *     (field 5) as the primary key, falling back to `username` when blank
 *     (`PluginHandler.call`, `CallIdentityResolver.resolveUserId`). This harness's shared
 *     `plugin_service.proto` has no `sessionId` field on `PluginRequest`, so every Call always
 *     takes the fallback path — `username` here must equal the value set as `PluginUser.sessionId`
 *     on Join, or the backend's single-session guard treats every Spin as coming from a superseded
 *     device and drops it.
 *   - Spin (`Call`): `PluginRequest.data` is a MessagePack map with `cmd=1500`
 *     (`PluginCommand.SPIN`) and `betAmount` (Number or numeric String) — `PluginHandler.handleSpin`.
 *     `Call` always returns an empty `PluginResponse`; the actual result (and any business error)
 *     is published over ZMQ only, so Gatling KO only catches transport failures — after a run also
 *     grep the backend log for spin/business errors.
 *   - Bet ladder: `application.yml` `game.client.bet-levels` (24 steps, 0.10 .. 100.00); default
 *     `-Dbet=1.00` (id "9", tier mid) is a valid step.
 *
 * Defaults (override via `-D…`): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9093, bet=1.00.
 */
class SilkroadGrpcSimulation extends Simulation {

  private val BetLadder = Seq(
    "0.10", "0.20", "0.30", "0.40", "0.50", "0.60", "0.80", "0.90", "1.00", "1.50", "2.00",
    "3.00", "4.00", "5.00", "7.50", "10.00", "15.00", "20.00", "30.00", "40.00", "50.00",
    "60.00", "80.00", "100.00"
  ).map(BigDecimal(_))

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9093).intValue()
  private val bet             = sys.props.getOrElse("bet", "1.00")

  // Off-ladder bets are silently rejected server-side (data only goes out over ZMQ), so fail fast
  // here and send the matched ladder step rather than the raw property.
  private val betStep: String = scala.util.Try(BigDecimal(bet)).toOption
    .flatMap(b => BetLadder.find(step => (step - b).abs <= BigDecimal("0.001")))
    .map(_.toString)
    .getOrElse(throw new IllegalArgumentException(
      s"-Dbet=$bet is not on the Silk Road bet ladder: ${BetLadder.mkString(", ")}"))

  private val PluginName = "game-silk-road-caravans"
  private val Zone       = "default"
  private val Agency     = "1"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    // LinkedHashMap preserves insertion order — matches the MessagePack byte sequence the backend
    // was tested against.
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agencyId", Agency)
    params.put("memberId", Integer.valueOf(0))
    params.put("uid", userId)

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
    data.put("betAmount", betStep)
    PluginRequest.newBuilder()
      .setZone(Zone)
      .setPluginName(PluginName)
      // No `sessionId` field on this shared proto — must match Join's PluginUser.sessionId so the
      // backend's CallIdentityResolver username-fallback resolves to the same user (see doc above).
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
    scenario("silkroad-grpc-player-journey")
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
