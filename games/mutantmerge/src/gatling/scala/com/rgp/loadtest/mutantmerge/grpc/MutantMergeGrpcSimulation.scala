package com.rgp.loadtest.mutantmerge.grpc

import com.github.phisgr.gatling.grpc.Predef._
import com.google.protobuf.ByteString
import com.rgp.loadtest.core.protocol.Codec
import com.rgp.loadtest.core.utils.SlaConstants
import com.rgp.loadtest.grpc.{ConnectAndCallRequest, PluginRequest, PluginResponse, PluginServiceGrpc, PluginUser}
import io.gatling.commons.validation._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioBuilder

import java.util.UUID
import scala.concurrent.duration._

/**
 * gRPC load test for Mutant Merge (be-mutant-merge, pluginName "yama_01024").
 *
 * Journey per VU:
 *   1. Join (ConnectAndCall, cmd 1005) — no Redis session: ConnectHandler falls back to
 *      user.parameters (agency/username/memberId) and still registers the TokenRegistry entry.
 *   2. Spin loop (Call, cmd 1500) — `{betLevelId}` (1-based ladder index, String) plus
 *      `superBet: true` when -DsuperBet=true. The response carries the full spin result.
 *
 * Business errors return gRPC OK with `c != 0` in the msgpack body, so Spin decodes the body
 * and fails the request on a non-zero `c`.
 *
 * Defaults (override via -D): users=1000, durationMinutes=60, rampMinutes=2, paceSec=5,
 * requestRate=50, eventCount=100000, grpcHost=localhost, grpcPort=9104, betLevelId=3 ($1.00),
 * superBet=false.
 */
class MutantMergeGrpcSimulation extends Simulation {

  private val users           = Integer.getInteger("users", 1000).intValue()
  private val durationMinutes = Integer.getInteger("durationMinutes", 60).intValue()
  private val rampMinutes     = Integer.getInteger("rampMinutes", 2).intValue()
  private val paceSec         = Integer.getInteger("paceSec", 5).intValue()
  private val requestRate     = sys.props.getOrElse("requestRate", "50").toDouble
  private val eventCount      = sys.props.getOrElse("eventCount", "100000").toLong
  private val grpcHost        = sys.props.getOrElse("grpcHost", "localhost")
  private val grpcPort        = Integer.getInteger("grpcPort", 9104).intValue()
  private val betLevelId      = Integer.getInteger("betLevelId", 3).intValue()
  private val superBet        = java.lang.Boolean.getBoolean("superBet")

  private val PluginName = "yama_01024"
  private val Zone       = "MiniGame"
  private val Agency     = "loadtest"
  private val Ok         = "OK"
  private val codec      = new Codec()

  private def buildJoinRequest(userId: String): ConnectAndCallRequest = {
    val params = new java.util.LinkedHashMap[String, Object]()
    params.put("agency", Agency)
    params.put("username", userId)
    params.put("memberId", userId)
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
    data.put("betLevelId", String.valueOf(betLevelId))
    if (superBet) data.put("superBet", java.lang.Boolean.TRUE)
    PluginRequest.newBuilder()
      .setZone(Zone)
      .setPluginName(PluginName)
      .setUsername(userId)
      .setData(ByteString.copyFrom(codec.encode(data)))
      .build()
  }

  // "OK", or "<code>:<message>" for the backend error envelope, so failures group by error name.
  private def spinStatus(res: PluginResponse): String = {
    val body = codec.decodeToMap(res.getResult.toByteArray)
    if (body.isEmpty) "UNDECODABLE_RESPONSE"
    else body.get("c") match {
      case c: Number if c.longValue() != 0 => s"${c.longValue()}:${body.get("message")}"
      case _                               => Ok
    }
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
    scenario("mutantmerge-grpc-player-journey")
      .feed(userFeeder)
      .exec(
        grpc("Join")
          .rpc(PluginServiceGrpc.getConnectAndCallMethod)
          .payload(joinPayload)
          // A wrong pluginName still returns gRPC OK, just with no ZMQ topic subscribed.
          .extract(res => Some(res.getMetadata.getTopicsCount).success)(_.gt(0))
      )
      // A failed Join stops the VU so it does not pollute the Spin error rate.
      .exitHereIfFailed
      .during(durationMinutes.minutes) {
        exec(
          grpc("Spin")
            .rpc(PluginServiceGrpc.getCallMethod)
            .payload(spinPayload)
            .extract(res => Some(spinStatus(res)).success)(_.is(Ok))
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
