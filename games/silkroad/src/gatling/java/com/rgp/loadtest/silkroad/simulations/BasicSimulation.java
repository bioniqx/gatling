package com.rgp.loadtest.silkroad.simulations;

import com.rgp.loadtest.core.simulations.BasicSimulationBase;
import com.rgp.loadtest.silkroad.requests.SlotRequests;
import com.rgp.loadtest.silkroad.utils.Endpoints;
import io.gatling.javaapi.core.ChainBuilder;
import java.util.List;
import java.util.Map;

/**
 * Silk Road atomic / fixed-volume simulation. See {@link BasicSimulationBase} for mode semantics.
 *
 * <p>Run examples:
 *
 * <pre>
 * -Dscenario=spin    -Dusers=1 -Drequests=1
 * -Dscenario=chain   -Dusers=1000 -Drequests=3000
 * -Dscenario=burst   -Dusers=1000 -DmaxResponseTimeMs=2000
 * </pre>
 */
public class BasicSimulation extends BasicSimulationBase {

  @Override
  protected List<Map.Entry<String, ChainBuilder>> endpoints() {
    return List.of(
        Map.entry(Endpoints.SPIN, SlotRequests.spin()),
        Map.entry(Endpoints.LAST_SPIN, SlotRequests.lastSpin()),
        Map.entry(Endpoints.HISTORY_SUMMARY, SlotRequests.historySummary()));
  }
}
