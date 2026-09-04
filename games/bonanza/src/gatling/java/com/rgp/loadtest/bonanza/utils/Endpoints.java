package com.rgp.loadtest.bonanza.utils;

/**
 * Endpoint short-names for Golden Boat Bonanza — used as scenario tags in Gatling reports.
 *
 * <p>These names match {@code details(Endpoints.SPIN)} assertions in {@code SoakSimulation} and
 * the {@code -Dscenario=<name>} values accepted by {@code BasicSimulation}.
 */
public final class Endpoints {

  private Endpoints() {}

  // Configs (stateless, run once per session)
  public static final String BET_LEVELS = "BetLevels"; // GET /api/configs/bet-levels
  public static final String REEL_STRIPS = "ReelStrips"; // GET /api/configs/reel-strips

  // Session lifecycle
  public static final String CREATE_SESSION = "CreateSession"; // POST /api/sessions

  // Gameplay (NOTE: spin returns 201, not 200)
  public static final String SPIN = "Spin"; // POST /api/spin

  // Jackpot
  public static final String JACKPOT_POOLS = "JackpotPools"; // GET /api/jackpot/pools
  public static final String BONUS_START = "BonusStart"; // POST /api/jackpot/bonus/start
  public static final String BONUS_REVEAL = "BonusReveal"; // POST /api/jackpot/bonus/{gameId}/reveal

  // History
  public static final String HISTORY_SESSIONS = "HistorySessions"; // GET /api/history/sessions?userId=
  public static final String HISTORY_ROUNDS = "HistoryRounds"; // GET /api/history/sessions/{id}/rounds
  public static final String ROUND_DETAIL = "RoundDetail"; // GET /api/history/rounds/{id}
}
