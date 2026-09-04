package com.rgp.loadtest.core.simulations;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.core.config.LoadTestConfig;
import io.gatling.javaapi.core.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Burst-recovery base: steady baseline + repeated atOnceUsers spikes. No SLA assertions —
 * exploratory.
 *
 * <p>Baseline is expressed users-per-minute (intuitive for ops); converted to per-second for
 * Gatling. Each spike injects {@code spikeUsers} atOnceUsers at the end of every {@code
 * cycleIntervalMinutes} window.
 */
public abstract class SpikeSimulationBase extends Simulation {

  private static final int SECONDS_PER_MINUTE = 60;
  private static final int MAX_DURATION_BUFFER_MIN = 5;

  protected static final int baseline = Integer.getInteger("baseline", 200);
  protected static final int spikeUsers = Integer.getInteger("spike", 1500);
  protected static final int spikeDurationSec = Integer.getInteger("spikeDurationSec", 30);
  protected static final int cycles = Integer.getInteger("cycles", 5);
  protected static final int cycleIntervalMinutes = Integer.getInteger("cycleIntervalMinutes", 5);

  protected static final int totalMinutes = cycles * cycleIntervalMinutes;

  protected final FeederBuilder<Object> feeder =
      LoadTestConfig.userIdFeeder(baseline + spikeUsers);

  protected abstract ScenarioBuilder baselineScenario(FeederBuilder<Object> feeder);

  protected abstract ScenarioBuilder spikeScenario(FeederBuilder<Object> feeder);

  {
    double baselinePerSec = (double) baseline / SECONDS_PER_MINUTE;

    setUp(
            baselineScenario(feeder)
                .injectOpen(
                    constantUsersPerSec(baselinePerSec).during(Duration.ofMinutes(totalMinutes))),
            spikeScenario(feeder).injectOpen(buildSpikeSteps()))
        .protocols(LoadTestConfig.httpProtocol())
        .maxDuration(Duration.ofMinutes(totalMinutes + MAX_DURATION_BUFFER_MIN));
  }

  private static OpenInjectionStep[] buildSpikeSteps() {
    List<OpenInjectionStep> steps = new ArrayList<>();
    long cycleIntervalSec = (long) cycleIntervalMinutes * SECONDS_PER_MINUTE;
    long waitBeforeBurstSec = cycleIntervalSec - spikeDurationSec;
    for (int i = 0; i < cycles; i++) {
      steps.add(nothingFor(Duration.ofSeconds(waitBeforeBurstSec)));
      steps.add(atOnceUsers(spikeUsers));
    }
    return steps.toArray(new OpenInjectionStep[0]);
  }
}
