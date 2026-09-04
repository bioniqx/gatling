package com.rgp.loadtest.bonanza.requests;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import com.rgp.loadtest.bonanza.utils.Endpoints;
import io.gatling.javaapi.core.ChainBuilder;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP request packaging for Golden Boat Bonanza endpoints.
 *
 * <p>State machine notes (different from silkroad):
 *
 * <ul>
 *   <li>{@link #betLevels()} saves smallest bet tier as session attribute {@code "bet"}.
 *   <li>{@link #spin()} captures {@code "roundId"}, {@code "sessionId"}, {@code "bonusTriggered"}
 *       — needed by history drill-downs and bonus flow.
 *   <li>{@link #bonusStart()} captures {@code "gameId"} — needed by {@link #bonusReveal()}.
 * </ul>
 *
 * <p>Field naming gotchas: {@code /sessions} and {@code /spin} use {@code playerId};
 * {@code /jackpot/*} and {@code /history/*} use {@code userId}. {@code betAmount} is a JSON
 * <b>string</b> for {@code /spin} (backend regex validates) but a JSON <b>number</b> for
 * {@code /jackpot/*}.
 */
public final class SlotRequests {

  private SlotRequests() {}

  /** GET /api/configs/bet-levels — saves smallest tier into session as "bet". */
  public static ChainBuilder betLevels() {
    return exec(
        http(Endpoints.BET_LEVELS)
            .get("/api/configs/bet-levels")
            .check(status().is(200))
            .check(jsonPath("$.data[0]").saveAs("bet")));
  }

  /** GET /api/configs/reel-strips */
  public static ChainBuilder reelStrips() {
    return exec(http(Endpoints.REEL_STRIPS).get("/api/configs/reel-strips").check(status().is(200)));
  }

  /** POST /api/sessions — opens a player session. Body uses {@code playerId}. */
  public static ChainBuilder createSession() {
    return exec(
        http(Endpoints.CREATE_SESSION)
            .post("/api/sessions")
            .body(ElFileBody("games/bonanza/bodies/create-session.json"))
            .check(status().in(200, 201)));
  }

  /**
   * POST /api/spin — gameplay request. Returns <b>201 Created</b> (not 200). Captures roundId,
   * sessionId, bonusTriggered for downstream steps (optional checks — keys may be absent).
   */
  public static ChainBuilder spin() {
    return exec(
        http(Endpoints.SPIN)
            .post("/api/spin")
            .body(ElFileBody("games/bonanza/bodies/spin.json"))
            .check(status().is(201))
            .check(jsonPath("$.data.gameRoundId").optional().saveAs("roundId"))
            .check(jsonPath("$.data.sessionId").optional().saveAs("sessionId"))
            .check(jsonPath("$.data.jackpotTriggered").optional().saveAs("bonusTriggered")));
  }

  /** GET /api/jackpot/pools — read-only. */
  public static ChainBuilder jackpotPools() {
    return exec(http(Endpoints.JACKPOT_POOLS).get("/api/jackpot/pools").check(status().is(200)));
  }

  /** POST /api/jackpot/bonus/start — captures gameId for {@link #bonusReveal()}. */
  public static ChainBuilder bonusStart() {
    return exec(
        http(Endpoints.BONUS_START)
            .post("/api/jackpot/bonus/start")
            .body(ElFileBody("games/bonanza/bodies/bonus-start.json"))
            .check(status().in(200, 201))
            .check(jsonPath("$.data.gameId").saveAs("gameId")));
  }

  /**
   * POST /api/jackpot/bonus/{gameId}/reveal — pre-step sets a random {@code boxIndex} session
   * attribute since Gatling EL has no random() built-in.
   */
  public static ChainBuilder bonusReveal() {
    return exec(session -> session.set("boxIndex", ThreadLocalRandom.current().nextInt(0, 12)))
        .exec(
            http(Endpoints.BONUS_REVEAL)
                .post("/api/jackpot/bonus/#{gameId}/reveal")
                .body(ElFileBody("games/bonanza/bodies/bonus-reveal.json"))
                .check(status().in(200, 201)));
  }

  /** Full bonus flow gated on {@code bonusTriggered=true && sessionId != null}: start + 3× reveal. */
  public static ChainBuilder bonusFlowIfTriggered() {
    return doIf(
            s ->
                "true".equalsIgnoreCase(s.getString("bonusTriggered"))
                    && s.getString("sessionId") != null)
        .then(exec(bonusStart()).repeat(3).on(exec(bonusReveal())));
  }

  /** GET /api/history/sessions?userId=#{userId} */
  public static ChainBuilder historySessions() {
    return exec(
        http(Endpoints.HISTORY_SESSIONS)
            .get("/api/history/sessions?userId=#{userId}")
            .check(status().is(200)));
  }

  /** GET /api/history/sessions/{sessionId}/rounds — gated on sessionId captured by spin. */
  public static ChainBuilder historyRounds() {
    return doIf(s -> s.getString("sessionId") != null)
        .then(
            exec(
                http(Endpoints.HISTORY_ROUNDS)
                    .get("/api/history/sessions/#{sessionId}/rounds")
                    .check(status().is(200))));
  }

  /** GET /api/history/rounds/{roundId} — gated on roundId captured by spin. */
  public static ChainBuilder roundDetail() {
    return doIf(s -> s.getString("roundId") != null)
        .then(
            exec(
                http(Endpoints.ROUND_DETAIL)
                    .get("/api/history/rounds/#{roundId}")
                    .check(status().is(200))));
  }
}
