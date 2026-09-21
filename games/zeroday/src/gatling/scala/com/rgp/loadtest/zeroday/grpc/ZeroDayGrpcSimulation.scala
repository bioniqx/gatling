package com.rgp.loadtest.zeroday.grpc

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
 * gRPC load test for Zero Day (be-zero-day, pluginName "yama_01023").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — the backend creates the Redis session from
 *      user.parameters (agency + userId required, token not checked).
 *   2. Spin loop (Call, cmd 1500) — `{bet}` as a decimal string from the bet ladder.
 *
 * Call runs the spin synchronously, then returns an EMPTY PluginResponse: the result and any
 * business error (`c != 0`) are published over ZMQ only. Response time is the real spin time,
 * but Gatling KO covers transport failures only — after every run grep the backend log for
 * "[gRPC] (ConnectAndCall|Call) (business )?error" lines other than c=1362 (jackpot pending).
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9103, bet=1.00.
 */
class ZeroDayGrpcSimulation extends Simulation {

  private val BetLadder = Seq(
    "0.20", "0.40", "0.60", "0.80", "1.00", "1.20", "1.60", "2.00", "2.40", "2.80", "3.20",
    "3.60", "4.00", "5.00", "6.00", "8.00", "10.00", "14.00", "18.00", "24.00", "32.00",
    "40.00", "60.00", "80.00", "100.00"
  ).map(BigDecimal(_))

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9103).intValue()
  private val bet             = sys.props.getOrElse("bet", "1.00")

  // The backend rejects an off-ladder bet over ZMQ only (gRPC still OK), so fail fast here and
  // send the matched ladder step rather than the raw property.
  private val betStep: String = scala.util.Try(BigDecimal(bet)).toOption
    .flatMap(b => BetLadder.find(step => (step - b).abs <= BigDecimal("0.001")))
    .map(_.toString)
    .getOrElse(throw new IllegalArgumentException(
      s"-Dbet=$bet is not on the Zero Day bet ladder: ${BetLadder.mkString(", ")}"))

  private val PluginName = "yama_01023"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
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
    data.put("bet", betStep)
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
    scenario("zeroday-grpc-player-journey")
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
