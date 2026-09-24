package com.rgp.loadtest.candy.grpc

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
 * gRPC load test for Candy Frenzy (be-candy-frenzy, pluginName "yama_01018", see
 * src/main/resources/games/candy_frenzy.yaml).
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — `ConnectHandler.handle` reads identity from
 *      `user.parameters`: agency from `agencyId`/`agentId`, the player id from `username`/`userId`,
 *      plus `memberId` and `token` (token is not checked). It registers the identity in
 *      `TokenRegistry` under BOTH `user.sessionId` and `user.username`, so this sim sets both to
 *      the same generated userId.
 *   2. Spin loop (Call, cmd 1500) — `SpinHandler.handle` prefers the coin/level pair over a raw
 *      `betAmount`: `{coinValue, bet}` (bet = the 1..10 level), server computes
 *      `Total Bet = coinValue * level * 20` via `CandyBet.totalBet` and rejects anything off the
 *      grid (`CandyBet.COIN_VALUES`, `MIN_LEVEL..MAX_LEVEL`) — mirrored below to fail fast.
 *
 * Identity on Call: the load-test harness's shared `PluginRequest` proto has no `sessionId` field
 * (be-candy-frenzy's own proto added one — field 5 — but :core's does not), so
 * `StandaloneGrpcService.call` falls back to `PluginRequest.username`, which `TokenRegistry`
 * resolves directly (registered above under the same userId).
 *
 * Call runs the spin synchronously, then returns an EMPTY PluginResponse: the result and any
 * business error are published over ZMQ only (`StandaloneGrpcService.normalizeError` /
 * `MsgPackCodec.encode` stamp `c:0` on success). Response time is the real spin time, but Gatling
 * KO covers transport failures only — after every run grep the backend log for "[grpc] cmd=1500
 * declined" and "[spin] PLUGIN_NOT_FOUND" lines.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9098, coinValue=0.10, betLevel=1
 * (Total Bet = 0.10 * 1 * 20 = 2.00).
 */
class CandyGrpcSimulation extends Simulation {

  // CandyBet.COIN_VALUES / MIN_LEVEL / MAX_LEVEL mirrored here (not on this classpath) so an
  // off-grid -D value fails fast instead of getting BET_INPUT rejected over ZMQ only.
  private val CoinValues = Seq(0.01, 0.05, 0.10, 0.20, 0.50, 1.20)
  private val MinLevel   = 1
  private val MaxLevel   = 10

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9098).intValue()
  private val coinValue       = sys.props.getOrElse("coinValue", "0.10").toDouble
  private val betLevel        = Integer.getInteger("betLevel", 1).intValue()

  require(
    CoinValues.exists(c => (c - coinValue).abs <= 1e-9),
    s"-DcoinValue=$coinValue is not on the Candy Frenzy coin grid: ${CoinValues.mkString(", ")}"
  )
  require(
    betLevel >= MinLevel && betLevel <= MaxLevel,
    s"-DbetLevel=$betLevel is outside the Candy Frenzy bet-level range $MinLevel..$MaxLevel"
  )

  private val PluginName = "yama_01018"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agencyId", Agency)
    params.put("username", userId)
    params.put("memberId", userId)
    params.put("token", "loadtest-token")

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
    data.put("coinValue", java.lang.Double.valueOf(coinValue))
    data.put("bet", Integer.valueOf(betLevel))
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
    scenario("candy-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
          // ConnectHandler only publishes the ZMQ topic when the pluginName resolves in
          // GameRegistry; no topic means the plugin route is misconfigured.
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
