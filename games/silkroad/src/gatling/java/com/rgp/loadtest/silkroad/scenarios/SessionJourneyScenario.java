package com.rgp.loadtest.silkroad.scenarios;

import com.rgp.loadtest.core.scenarios.SessionJourneyTemplate;
import com.rgp.loadtest.core.scenarios.SessionJourneyTemplate.RatioRequest;
import com.rgp.loadtest.silkroad.requests.SlotRequests;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import java.time.Duration;
import java.util.List;

/**
 * Silk Road session journey: spin every iteration; lastSpin 1-in-10; historySummary 1-in-25. Owns
 * the silkroad-specific request mix; everything else (loop, think-time, doIf wiring) lives in
 * {@link SessionJourneyTemplate}.
 */
public final class SessionJourneyScenario {

  public static final String DEFAULT_NAME = "silkroad-session-journey";

  private static final int LAST_SPIN_RATIO = 10;
  private static final int HISTORY_SUMMARY_RATIO = 25;

  private SessionJourneyScenario() {}

  public static ScenarioBuilder build(
      FeederBuilder<?> feeder, Duration sessionLength, int thinkMinSec, int thinkMaxSec) {
    return build(DEFAULT_NAME, feeder, sessionLength, thinkMinSec, thinkMaxSec);
  }

  /**
   * Variant that lets the caller supply a unique scenario name. Use this when the same simulation
   * declares multiple populations sharing the same journey (e.g. SpikeSimulation's baseline vs
   * burst) — Gatling requires distinct names per setUp() population.
   */
  public static ScenarioBuilder build(
      String scenarioName,
      FeederBuilder<?> feeder,
      Duration sessionLength,
      int thinkMinSec,
      int thinkMaxSec) {
    return SessionJourneyTemplate.build(
        scenarioName,
        feeder,
        sessionLength,
        thinkMinSec,
        thinkMaxSec,
        SlotRequests.spin(),
        List.of(
            new RatioRequest(LAST_SPIN_RATIO, SlotRequests.lastSpin()),
            new RatioRequest(HISTORY_SUMMARY_RATIO, SlotRequests.historySummary())));
  }
}
