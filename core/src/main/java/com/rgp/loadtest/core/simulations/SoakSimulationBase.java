package com.rgp.loadtest.core.simulations;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.utils.SlaConstants;
import com.rgp.loadtest.core.utils.SystemProps;
import io.gatling.javaapi.core.*;
import java.time.Duration;

/**
 * Production-gate Soak base: {@code users} sessions × {@code durationMinutes} min with PR-4/PR-5
 * SLA assertions. Maven build fails on breach.
 *
 * <p>Subclass contract: implement {@link #scenario(FeederBuilder)} to return the game's user
 * journey. Everything else (injection, protocol, assertions, maxDuration) is owned by this base.
 */
public abstract class SoakSimulationBase extends Simulation {

  protected static final int users = LoadTestConfig.users;
  protected static final int durationMinutes = LoadTestConfig.durationMinutes;
  protected static final int rampMinutes = LoadTestConfig.rampMinutes;
  protected static final boolean parallel = Boolean.getBoolean(SystemProps.PARALLEL);

  protected final FeederBuilder<Object> feeder = LoadTestConfig.userIdFeeder(users);

  /** Subclass returns the user-journey scenario, fed with the supplied feeder. */
  protected abstract ScenarioBuilder scenario(FeederBuilder<Object> feeder);

  {
    OpenInjectionStep injection =
        parallel ? atOnceUsers(users) : rampUsers(users).during(Duration.ofMinutes(rampMinutes));

    int maxMinutes =
        parallel
            ? durationMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN
            : durationMinutes + rampMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN;

    setUp(scenario(feeder).injectOpen(injection))
        .protocols(LoadTestConfig.httpProtocol())
        .maxDuration(Duration.ofMinutes(maxMinutes))
        .assertions(
            global().responseTime().mean().lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
            global().failedRequests().percent().lte(SlaConstants.PR5_ERROR_PERCENT_MAX));
  }
}
