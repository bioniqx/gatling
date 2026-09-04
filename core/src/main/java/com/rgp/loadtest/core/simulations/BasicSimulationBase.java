package com.rgp.loadtest.core.simulations;

import static io.gatling.javaapi.core.CoreDsl.*;

import com.rgp.loadtest.core.config.LoadTestConfig;
import com.rgp.loadtest.core.utils.SystemProps;
import io.gatling.javaapi.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Atomic / fixed-volume base — no time-based sustain. Modes via {@code -Dscenario=}:
 *
 * <ul>
 *   <li>{@code <endpoint-name>} — single endpoint × repeatsPerUser × users
 *   <li>{@code all} — N populations, one per endpoint
 *   <li>{@code chain} — 1 population, each VU runs all endpoints sequentially
 *   <li>{@code burst} — 1 population per endpoint, atOnceUsers × 1 request each
 * </ul>
 *
 * <p>Subclass returns its endpoint list via {@link #endpoints()}; the default scenario (when {@code
 * -Dscenario} is unset) is the first endpoint in the list.
 *
 * <p>Always-on assertion: zero KO. With {@code -DmaxResponseTimeMs=N} also asserts max ≤ N.
 */
public abstract class BasicSimulationBase extends Simulation {

  protected static final int users = LoadTestConfig.users;
  protected static final int repeatsPerUser = Math.max(1, LoadTestConfig.requests / Math.max(1, users));
  protected static final int maxResponseTimeMs = Integer.getInteger(SystemProps.MAX_RESPONSE_TIME_MS, 0);

  /** Subclass supplies its endpoints (ordered). The first entry is used as the default scenario. */
  protected abstract List<Map.Entry<String, ChainBuilder>> endpoints();

  // Sized at runtime once endpoints() is known — burst mode runs `users` VUs per endpoint
  // simultaneously, so the shared circular feeder must hold enough unique userIds to avoid the same
  // VU pulling a duplicate (which causes per-user lock contention on stateful endpoints).
  protected final FeederBuilder<Object> userIdFeeder =
      LoadTestConfig.userIdFeeder(users * Math.max(1, endpoints().size()));

  {
    List<Map.Entry<String, ChainBuilder>> eps = endpoints();
    if (eps.isEmpty()) {
      throw new IllegalStateException("endpoints() must return at least one entry");
    }
    String scenarioMode = System.getProperty(SystemProps.SCENARIO, eps.get(0).getKey());

    List<Assertion> assertions = new ArrayList<>();
    assertions.add(global().failedRequests().count().is(0L));
    if (maxResponseTimeMs > 0) {
      assertions.add(global().responseTime().max().lte(maxResponseTimeMs));
    }

    setUp(buildPopulations(eps, scenarioMode))
        .assertions(assertions.toArray(new Assertion[0]))
        .protocols(LoadTestConfig.httpProtocol());
  }

  private List<PopulationBuilder> buildPopulations(
      List<Map.Entry<String, ChainBuilder>> eps, String scenarioMode) {
    List<PopulationBuilder> populations = new ArrayList<>();

    // atomic single-endpoint OR all-endpoints fan-out
    boolean isAll = SystemProps.ALL.equals(scenarioMode);
    for (Map.Entry<String, ChainBuilder> ep : eps) {
      if (isAll || ep.getKey().equals(scenarioMode)) {
        populations.add(atomic(ep.getKey(), ep.getValue()));
      }
    }

    // chain: 1 VU sequentially runs all endpoints, repeated until req-budget exhausted
    if (SystemProps.CHAIN.equals(scenarioMode)) {
      List<ChainBuilder> chainSteps =
          eps.stream().map(Map.Entry::getValue).collect(Collectors.toList());
      int chainRepeats = Math.max(1, LoadTestConfig.requests / Math.max(1, users) / chainSteps.size());
      populations.add(
          scenario(SystemProps.CHAIN)
              .feed(userIdFeeder)
              .repeat(chainRepeats)
              .on(exec(chainSteps))
              .injectOpen(atOnceUsers(users)));
    }

    // burst: 1 population per endpoint, all fire atOnce simultaneously
    if (SystemProps.BURST.equals(scenarioMode)) {
      for (Map.Entry<String, ChainBuilder> ep : eps) {
        populations.add(
            scenario(SystemProps.BURST + "-" + ep.getKey())
                .feed(userIdFeeder)
                .exec(ep.getValue())
                .injectOpen(atOnceUsers(users)));
      }
    }

    if (populations.isEmpty()) {
      String valid =
          eps.stream().map(Map.Entry::getKey).collect(Collectors.joining("|"))
              + "|"
              + SystemProps.ALL
              + "|"
              + SystemProps.CHAIN
              + "|"
              + SystemProps.BURST;
      throw new IllegalArgumentException("Unknown scenario: '" + scenarioMode + "'. Valid: " + valid);
    }
    return populations;
  }

  /** Single-endpoint scenario: `users` VUs atOnceUsers, each repeats `repeatsPerUser` times. */
  private PopulationBuilder atomic(String name, ChainBuilder request) {
    return scenario(name)
        .feed(userIdFeeder)
        .repeat(repeatsPerUser)
        .on(request)
        .injectOpen(atOnceUsers(users));
  }
}
