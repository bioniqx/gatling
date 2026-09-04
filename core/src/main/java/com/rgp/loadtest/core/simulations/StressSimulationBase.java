package com.rgp.loadtest.core.simulations;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.core.config.LoadTestConfig;
import io.gatling.javaapi.core.*;
import java.time.Duration;

/**
 * Capacity-discovery base: ramp from {@code usersStart} to {@code usersEnd} users-per-minute over
 * {@code durationMinutes}. No SLA assertions — purpose is to find where p95/p99 explodes.
 *
 * <p>{@code -DusersStart} / {@code -DusersEnd} are expressed per-minute (intuitive for ops);
 * Gatling needs per-second so we divide by 60.
 */
public abstract class StressSimulationBase extends Simulation {

  private static final int SECONDS_PER_MINUTE = 60;
  private static final int MAX_DURATION_BUFFER_MIN = 5;

  protected static final int usersStart = Integer.getInteger("usersStart", 500);
  protected static final int usersEnd = Integer.getInteger("usersEnd", 2500);
  protected static final int durationMinutes = LoadTestConfig.durationMinutes;

  protected final FeederBuilder<Object> feeder = LoadTestConfig.userIdFeeder(usersEnd);

  protected abstract ScenarioBuilder scenario(FeederBuilder<Object> feeder);

  {
    double startPerSec = (double) usersStart / SECONDS_PER_MINUTE;
    double endPerSec = (double) usersEnd / SECONDS_PER_MINUTE;

    setUp(
            scenario(feeder)
                .injectOpen(
                    rampUsersPerSec(startPerSec)
                        .to(endPerSec)
                        .during(Duration.ofMinutes(durationMinutes))))
        .protocols(LoadTestConfig.httpProtocol())
        .maxDuration(Duration.ofMinutes(durationMinutes + MAX_DURATION_BUFFER_MIN));
  }
}
