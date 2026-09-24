package com.rgp.loadtest.apsara.grpc

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
 * gRPC load test for Apsara Paradise (be-apsara-paradise, pluginName "game-apsara-paradise").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — identity travels in `user.parameters`; ConnectHandler
 *      reads agency/userId/memberId via PayloadExtractors' multi-key lookup (agency, userId,
 *      memberId — same key shapes zeroday/naga777 already use) and registers TokenRegistry under
 *      both PluginUser.sessionId and PluginUser.username.
 *   2. Spin loop (Call, cmd 1500) — `{betSize, coinValue}`; PluginRequest.username is looked up
 *      via TokenRegistry.getByUsernameOrSessionId, so it must equal the Join's sessionId/username.
 *      SpinHandler (BetValidator) requires betSize to be an exact member of betConfig.betSizes and
 *      coinValue an exact member of betConfig.coinValues — see
 *      app/src/main/resources/games/apsara_paradise.yaml (betSizes 1..10, coinValues 0.01..1.20)
 *      and BetValidator.validate / SpinHandler.handle (betSize=Integer, coinValue=BigDecimal-or-
 *      numeric-String).
 *
 * Call returns the ack synchronously (200-ish shape) but the real spin result/errors are also
 * mirrored over ZMQ only — Gatling KO covers transport + gRPC-status failures; after every run
 * also grep the backend log for "REJECTED" / business exceptions on cmd 1500.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9095, betSize=1,
 * coinValue=0.10.
 */
class ApsaraGrpcSimulation extends Simulation {

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9095).intValue()
  private val betSize         = Integer.getInteger("betSize", 1).intValue()
  private val coinValue       = sys.props.getOrElse("coinValue", "0.10")

  private val PluginName = "game-apsara-paradise"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    // ConnectHandler falls back to userParams when there's no pre-seeded Redis session, reading
    // agency/userId/memberId via PayloadExtractors (agency, userId, memberId key shapes).
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
    data.put("betSize", Integer.valueOf(betSize))
    data.put("coinValue", coinValue)
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
    scenario("apsara-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
          // ConnectHandler always appends the session's ZMQ topic before returning; no topic
          // means something unexpected happened even though the RPC itself didn't error.
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
