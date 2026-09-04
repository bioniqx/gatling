package com.rgp.loadtest.silkroad.simulations;

import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.simulations.SoakSimulationBase;
import com.rgp.loadtest.silkroad.scenarios.SessionJourneyScenario;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;

/**
 * Silk Road production gate — 1000 sessions × 60 min with PR-4/PR-5 assertions.
 *
 * <p>Ramp mode (default): {@code ./mvnw gatling:test -Psoak -Dusers=1000 -DdurationMinutes=60
 * -DrampMinutes=5}<br>
 * Parallel mode: append {@code -Dparallel=true}<br>
 * Smoke: {@code -Dusers=50 -DdurationMinutes=1 -DrampMinutes=1}
 */
public class SoakSimulation extends SoakSimulationBase {

  @Override
  protected ScenarioBuilder scenario(FeederBuilder<Object> feeder) {
    return SessionJourneyScenario.build(
        feeder,
        Duration.ofMinutes(durationMinutes),
        LoadTestConfig.thinkTimeMin,
        LoadTestConfig.thinkTimeMax);
  }
}
