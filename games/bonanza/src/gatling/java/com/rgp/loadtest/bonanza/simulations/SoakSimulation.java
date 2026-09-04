package com.rgp.loadtest.bonanza.simulations;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.bonanza.scenarios.PlayerJourneyScenario;
import com.rgp.loadtest.bonanza.utils.Endpoints;
import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.utils.SlaConstants;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import java.time.Duration;

/**
 * Bonanza production soak — closed-model injection (every VU runs the full Init → loop flow).
 *
 * <p>Why not extend {@link com.rgp.loadtest.core.simulations.SoakSimulationBase}? The base wires
 * open-model injection ({@code rampUsers} / {@code atOnceUsers}) — fine for stateless silkroad
 * where each spin is independent. Bonanza needs <b>closed</b> injection ({@code
 * rampConcurrentUsers} + {@code constantConcurrentUsers}) because each VU executes an Init phase
 * once then sustains a randomSwitch loop for {@code durationMinutes}; open-model would re-run
 * Init per "new" VU and overwhelm session creation.
 *
 * <p>Assertions: global PR-4 / PR-5 (from {@link SlaConstants}, overrideable via per-game YAML
 * {@code games/bonanza/src/gatling/resources/sla-thresholds.yml}) plus per-endpoint thresholds for
 * the two most performance-critical endpoints ({@code Spin} and {@code CreateSession}).
 *
 * <p>Default test plan (override via {@code -D…}):
 *
 * <ul>
 *   <li>{@code users=1000} concurrent
 *   <li>{@code durationMinutes=60} sustain
 *   <li>{@code rampMinutes=2} to ramp up (legacy BonanzaRestSimulation default — NOT {@link
 *       LoadTestConfig#rampMinutes}'s repo-wide default of 5; bonanza needs the steeper ramp to
 *       reach steady-state throughput before the 60-min window closes)
 *   <li>{@code paceSec=5} between loop iterations per VU
 *   <li>{@code requestRate=50} min req/s (global throughput floor — ported from legacy)
 *   <li>{@code eventCount=100000} min successful requests over the run (volume floor — ported from legacy)
 * </ul>
 */
public class SoakSimulation extends Simulation {

  private static final int users = LoadTestConfig.users;
  private static final int durationMinutes = LoadTestConfig.durationMinutes;
  // Read direct (not via LoadTestConfig) so the default matches legacy BonanzaRestSimulation's
  // RAMP_MINUTES=2 rather than the repo-wide LoadTestConfig.rampMinutes default of 5.
  private static final int rampMinutes = Integer.getInteger("rampMinutes", 2);
  private static final int paceSec = Integer.getInteger("paceSec", 5);
  // Throughput + volume floors carried over from the legacy standalone repo. Defaults match
  // BonanzaRestSimulation (REQUEST_RATE=50 req/s, EVENT_COUNT=100_000 successful requests).
  private static final double requestRate =
      Double.parseDouble(System.getProperty("requestRate", "50"));
  private static final long eventCount = Long.parseLong(System.getProperty("eventCount", "100000"));

  {
    ScenarioBuilder journey =
        PlayerJourneyScenario.build(Duration.ofMinutes(durationMinutes), paceSec, users);

    setUp(
            journey.injectClosed(
                rampConcurrentUsers(0).to(users).during(Duration.ofMinutes(rampMinutes)),
                constantConcurrentUsers(users).during(Duration.ofMinutes(durationMinutes))))
        .protocols(LoadTestConfig.httpProtocol())
        .maxDuration(
            Duration.ofMinutes(
                durationMinutes + rampMinutes + SlaConstants.MAX_DURATION_BUFFER_MIN))
        .assertions(
            // Global SLA (PR-4 + PR-5, sourced from SlaConstants / per-game YAML).
            global().responseTime().mean().lte(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX),
            global().failedRequests().percent().lte(SlaConstants.PR5_ERROR_PERCENT_MAX),
            // Throughput / volume floors — ported verbatim from legacy BonanzaRestSimulation.
            global().requestsPerSec().gte(requestRate),
            global().successfulRequests().count().gt(eventCount),
            // Bonanza-specific per-endpoint thresholds (ported from legacy standalone repo).
            details(Endpoints.SPIN).responseTime().percentile3().lte(800), // p95 ≤ 800ms
            details(Endpoints.SPIN).failedRequests().percent().lte(0.5),
            details(Endpoints.CREATE_SESSION).failedRequests().percent().lte(0.5));
  }
}
