package com.rgp.loadtest.silkroad.simulations;

import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.simulations.SpikeSimulationBase;
import com.rgp.loadtest.silkroad.scenarios.SessionJourneyScenario;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;

/**
 * Silk Road burst-recovery — steady baseline + repeated spike bursts. Uses the same session
 * journey for both populations so spikes simulate flash-crowd arrivals on the live floor.
 *
 * <p>Run: {@code ./mvnw gatling:test -Pspike -Dbaseline=200 -Dspike=1500 -Dcycles=5
 * -DcycleIntervalMinutes=5 -DspikeDurationSec=30}
 */
public class SpikeSimulation extends SpikeSimulationBase {

  private ScenarioBuilder journey(String name, FeederBuilder<Object> feeder) {
    return SessionJourneyScenario.build(
        name,
        feeder,
        Duration.ofMinutes(totalMinutes),
        LoadTestConfig.thinkTimeMin,
        LoadTestConfig.thinkTimeMax);
  }

  @Override
  protected ScenarioBuilder baselineScenario(FeederBuilder<Object> feeder) {
    return journey("silkroad-spike-baseline", feeder);
  }

  @Override
  protected ScenarioBuilder spikeScenario(FeederBuilder<Object> feeder) {
    return journey("silkroad-spike-burst", feeder);
  }
}
