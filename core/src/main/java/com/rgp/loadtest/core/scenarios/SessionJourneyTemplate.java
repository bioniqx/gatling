package com.rgp.loadtest.core.scenarios;

import static io.gatling.javaapi.core.CoreDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;
import java.util.List;

/**
 * Generic session-journey: 1 VU = 1 logical session looping for the full duration.
 *
 * <p>Each iteration runs {@code primary} always, then optionally invokes each {@link RatioRequest}
 * when {@code userIndex % oneInN == 0} (deterministic mix, no RNG).
 *
 * <p>Game-specific code supplies the ChainBuilders and the modulo ratios — this class owns the
 * loop, think-time, and scenario naming convention.
 */
public final class SessionJourneyTemplate {

  /** A secondary endpoint executed for 1-in-{@code oneInN} VUs. */
  public static final class RatioRequest {
    private final int oneInN;
    private final ChainBuilder chain;

    public RatioRequest(int oneInN, ChainBuilder chain) {
      this.oneInN = oneInN;
      this.chain = chain;
    }

    public int oneInN() {
      return oneInN;
    }

    public ChainBuilder chain() {
      return chain;
    }
  }

  /** Short pause after each secondary request, separate from primary think-time. */
  private static final int SECONDARY_PAUSE_SEC = 1;

  private SessionJourneyTemplate() {}

  public static ScenarioBuilder build(
      String scenarioName,
      FeederBuilder<?> feeder,
      Duration sessionLength,
      int thinkMinSec,
      int thinkMaxSec,
      ChainBuilder primary,
      List<RatioRequest> secondary) {

    ChainBuilder iteration = exec(primary).pause(thinkMinSec, thinkMaxSec);
    for (RatioRequest sec : secondary) {
      iteration =
          iteration
              .doIf(session -> session.getInt("userIndex") % sec.oneInN() == 0)
              .then(exec(sec.chain()).pause(SECONDARY_PAUSE_SEC));
    }

    return scenario(scenarioName).feed(feeder).during(sessionLength).on(iteration);
  }
}
