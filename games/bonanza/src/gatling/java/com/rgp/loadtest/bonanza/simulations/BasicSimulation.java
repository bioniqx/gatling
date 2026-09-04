package com.rgp.loadtest.bonanza.simulations;

import com.rgp.loadtest.bonanza.requests.SlotRequests;
import com.rgp.loadtest.bonanza.utils.Endpoints;
import com.rgp.loadtest.core.simulations.BasicSimulationBase;
import io.gatling.javaapi.core.ChainBuilder;
import java.util.List;
import java.util.Map;

/**
 * Atomic single-endpoint smoke for Bonanza — used with {@code -Dscenario=<EndpointName>}.
 *
 * <p>Only stateless endpoints are listed. Bonus / history-drill-down / round-detail endpoints
 * require captured state ({@code sessionId} / {@code roundId} / {@code gameId} from a prior Spin
 * inside the same session); atomic-testing them in isolation would just exercise the 4xx path.
 */
public class BasicSimulation extends BasicSimulationBase {

  @Override
  protected List<Map.Entry<String, ChainBuilder>> endpoints() {
    return List.of(
        Map.entry(Endpoints.BET_LEVELS, SlotRequests.betLevels()),
        Map.entry(Endpoints.REEL_STRIPS, SlotRequests.reelStrips()),
        Map.entry(Endpoints.CREATE_SESSION, SlotRequests.createSession()),
        Map.entry(Endpoints.SPIN, SlotRequests.spin()),
        Map.entry(Endpoints.JACKPOT_POOLS, SlotRequests.jackpotPools()),
        Map.entry(Endpoints.HISTORY_SESSIONS, SlotRequests.historySessions()));
  }
}
