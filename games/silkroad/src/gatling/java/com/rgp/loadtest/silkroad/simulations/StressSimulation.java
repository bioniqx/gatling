package com.rgp.loadtest.silkroad.simulations;

import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.simulations.StressSimulationBase;
import com.rgp.loadtest.silkroad.scenarios.SessionJourneyScenario;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;

/**
 * Silk Road capacity discovery — ramp usersStart → usersEnd users/min over durationMinutes.
 *
 * <p>Run: {@code ./mvnw gatling:test -Pstress -DusersStart=500 -DusersEnd=2500
 * -DdurationMinutes=30}<br>
 * Smoke: {@code -DusersStart=10 -DusersEnd=20 -DdurationMinutes=1}
 */
public class StressSimulation extends StressSimulationBase {

  @Override
  protected ScenarioBuilder scenario(FeederBuilder<Object> feeder) {
    return SessionJourneyScenario.build(
        feeder,
        Duration.ofMinutes(durationMinutes),
        LoadTestConfig.thinkTimeMin,
        LoadTestConfig.thinkTimeMax);
  }
}
