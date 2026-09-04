package com.rgp.loadtest.core.config;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Runtime config exposed to simulations.
 *
 * <p>Values are resolved by {@link LoadTestConfigLoader} at class-load time. Layering (low→high):
 * hardcoded fallback → {@code classpath:load-test-defaults.yml} → {@code
 * config/load-test-defaults.yml} → {@code classpath:game.yml} (per-game) → {@code -D<key>=}
 * system properties.
 */
public final class LoadTestConfig {

  private static final LoadTestDefaults DEFAULTS = LoadTestConfigLoader.load();

  public static final String host = DEFAULTS.host;
  public static final String port = DEFAULTS.port;
  public static final String contextPath = DEFAULTS.contextPath;
  public static final int users = DEFAULTS.users;
  public static final int requests = DEFAULTS.requests;
  public static final int durationMinutes = DEFAULTS.durationMinutes;
  public static final int rampMinutes = DEFAULTS.rampMinutes;
  public static final int thinkTimeMin = DEFAULTS.thinkTimeMin;
  public static final int thinkTimeMax = DEFAULTS.thinkTimeMax;
  public static final String RUN_ID = String.valueOf(System.currentTimeMillis() / 1000);

  private LoadTestConfig() {}

  public static HttpProtocolBuilder httpProtocol() {
    return http.baseUrl("http://" + host + ":" + port + contextPath)
        .contentTypeHeader("application/json")
        .acceptHeader("application/json")
        // ulimit -n on this machine is 1,048,576 — plenty of headroom.
        // Cap at 1200 to avoid flooding the server's somaxconn backlog (macOS default: 128).
        .maxConnectionsPerHost(1200);
  }

  /**
   * Circular feeder with `userId` and `userIndex` attributes. `userIndex` (1...size) enables
   * deterministic modulo routing in SessionJourneyTemplate.
   */
  public static FeederBuilder<Object> userIdFeeder(int size) {
    return listFeeder(
            IntStream.rangeClosed(1, size)
                .mapToObj(
                    i -> Map.<String, Object>of("userId", "lt-" + RUN_ID + "-" + i, "userIndex", i))
                .collect(Collectors.toList()))
        .circular();
  }
}
