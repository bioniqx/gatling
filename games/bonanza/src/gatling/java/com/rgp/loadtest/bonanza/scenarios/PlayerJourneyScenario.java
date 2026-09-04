package com.rgp.loadtest.bonanza.scenarios;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.bonanza.requests.SlotRequests;
import com.rgp.loadtest.core.config.LoadTestConfig;
import io.gatling.javaapi.core.Choice;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;

/**
 * Stateful player journey for Golden Boat Bonanza.
 *
 * <p>Shape (closed-model — same VU runs the whole flow then loops the randomSwitch):
 *
 * <pre>
 *   Init phase (once per VU)
 *     ├─ feed userId (from LoadTestConfig)
 *     ├─ BetLevels       (saves bet tier into session)
 *     ├─ ReelStrips
 *     └─ CreateSession
 *   Loop for `durationMinutes`, paced every `paceSec` seconds:
 *     randomSwitch weighted:
 *       80% → Spin (captures jackpotTriggered/sessionId/roundId for later choices)
 *        8% → JackpotPools
 *        5% → HistorySessions
 *        3% → HistoryRounds (gated on sessionId)
 *        2% → RoundDetail   (gated on roundId)
 *        2% → BonusFlow     (gated on jackpotTriggered=true && sessionId — BonusStart + 3× BonusReveal)
 * </pre>
 *
 * <p>Weights + composition ported verbatim from the legacy {@code BonanzaRestSimulation}. The
 * bonus flow is its own 2% choice — NOT chained inline after Spin — because the source treats it
 * as an independent randomSwitch arm that only fires when a prior Spin set {@code
 * bonusTriggered=true}. Don't inline it under Spin or weights drift from the standalone repo.
 */
public final class PlayerJourneyScenario {

  /** Scenario name — unique cross-game to keep Gatling report tags distinct. */
  public static final String NAME = "bonanza-player-journey";

  private PlayerJourneyScenario() {}

  public static ScenarioBuilder build(Duration loopDuration, int paceSec, int feederSize) {
    return scenario(NAME)
        .feed(LoadTestConfig.userIdFeeder(feederSize))
        .exec(SlotRequests.betLevels())
        .exec(SlotRequests.reelStrips())
        .exec(SlotRequests.createSession())
        .exec(
            during(loopDuration)
                .on(
                    randomSwitch()
                        .on(
                            new Choice.WithWeight(80.0, exec(SlotRequests.spin())),
                            new Choice.WithWeight(8.0, exec(SlotRequests.jackpotPools())),
                            new Choice.WithWeight(5.0, exec(SlotRequests.historySessions())),
                            new Choice.WithWeight(3.0, exec(SlotRequests.historyRounds())),
                            new Choice.WithWeight(2.0, exec(SlotRequests.roundDetail())),
                            new Choice.WithWeight(2.0, exec(SlotRequests.bonusFlowIfTriggered())))
                        .pace(Duration.ofSeconds(paceSec))));
  }
}
