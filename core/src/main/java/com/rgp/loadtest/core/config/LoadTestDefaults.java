package com.rgp.loadtest.core.config;

/**
 * Strongly-typed view of {@code load-test-defaults.yml} (core defaults) + per-game
 * {@code game.yml} overrides.
 *
 * <p>Populated by {@link LoadTestConfigLoader}. All fields immutable after load.
 */
public final class LoadTestDefaults {

  public final String host;
  public final String port;
  public final String contextPath;

  public final int users;
  public final int requests;
  public final int durationMinutes;
  public final int rampMinutes;
  public final int thinkTimeMin;
  public final int thinkTimeMax;

  public LoadTestDefaults(
      String host,
      String port,
      String contextPath,
      int users,
      int requests,
      int durationMinutes,
      int rampMinutes,
      int thinkTimeMin,
      int thinkTimeMax) {
    this.host = host;
    this.port = port;
    this.contextPath = contextPath;
    this.users = users;
    this.requests = requests;
    this.durationMinutes = durationMinutes;
    this.rampMinutes = rampMinutes;
    this.thinkTimeMin = thinkTimeMin;
    this.thinkTimeMax = thinkTimeMax;
  }

  /** Hardcoded fallback so build never breaks on missing YAML. Matches load-test-defaults.yml. */
  public static LoadTestDefaults fallback() {
    return new LoadTestDefaults("localhost", "3000", "", 1000, 10000, 60, 5, 1, 3);
  }
}
