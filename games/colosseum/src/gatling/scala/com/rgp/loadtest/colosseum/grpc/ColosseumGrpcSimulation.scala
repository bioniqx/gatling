package com.rgp.loadtest.colosseum.grpc

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
 * gRPC load test for Colosseum Showdown (be-colosseum-showdown, pluginName "yama_01022").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — `ConnectHandler.handle` (grpc/handler/ConnectHandler.java)
 *      decodes `user.parameters` and resolves identity as agency <- agencyId/agentId, userId <-
 *      username/userId, memberId <- memberId, token <- token (not checked by the mock wallet).
 *      It then keys `TokenRegistry` by `user.getSessionId()`.
 *   2. Spin loop (Call, cmd 1500) — `SpinHandler.handle` (grpc/handler/SpinHandler.java) prefers a
 *      `{coinValue, bet}` pair (bet = bet level, rounded from a double) over a raw `betAmount`;
 *      total bet = coinValue x level x 20 (`ColosseumRules.BetRules.totalBet`,
 *      modes/colosseumshowdown/config/ColosseumRules.java). Valid coin values: 0.03/0.10/0.30/0.90,
 *      levels 1-10.
 *
 * Identity on Call: `StandaloneGrpcService.call` (grpc/StandaloneGrpcService.java) builds
 * `sessionKey = sessionId.isBlank ? username : sessionId` and looks it up in `TokenRegistry`, which
 * Join keyed by `user.getSessionId()`. So Call must send `username` = the same value Join sent as
 * `PluginUser.sessionId` (no `sessionId` field on the Call request) — same pattern as zeroday/naga777.
 * Single-session enforcement compares the active-session lease by agency+userId, so concurrent VUs
 * MUST use distinct userIds (the UUID feeder below already does this).
 *
 * Join's ZmqResponse always carries the session topic on success (`ConnectHandler` adds it right
 * before publishing), so a missing topic means Join failed server-side even if the RPC itself is OK.
 *
 * Call runs the spin synchronously but replies with an EMPTY PluginResponse — the result and any
 * business error (`c != 0`, see grpc/ErrorCode.java) are published over ZMQ only. Response time is
 * the real spin time, but Gatling KO covers transport failures only — after every run grep the
 * backend log for "[grpc] cmd=1500 declined" lines.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9102,
 * betCoinValue=0.10, betLevel=5 (-> total bet 10.00).
 */
class ColosseumGrpcSimulation extends Simulation {

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9102).intValue()
  private val betCoinValue    = sys.props.getOrElse("betCoinValue", "0.10")
  private val betLevel        = Integer.getInteger("betLevel", 5).intValue()

  private val PluginName = "yama_01022"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agencyId", Agency)
    params.put("token", "")
    params.put("memberId", userId)
    params.put("username", userId)

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
    data.put("coinValue", betCoinValue)
    data.put("bet", String.valueOf(betLevel))
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
    scenario("colosseum-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
          // ConnectHandler always adds the session ZMQ topic on success; no topic = rejected Join.
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
