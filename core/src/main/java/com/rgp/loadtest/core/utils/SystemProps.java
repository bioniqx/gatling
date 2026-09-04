package com.rgp.loadtest.core.utils;

/**
 * Names of `-D…` system properties consumed by simulations, plus shared scenario-mode literals.
 *
 * <p>Replaces the bogus {@code System.getenv(...)} calls that used to live in the old
 * {@code silkroad.utils.Constants}. These are simple string keys/values; runtime access uses
 * {@link System#getProperty(String, String)} or {@link Boolean#getBoolean(String)}.
 */
public final class SystemProps {

  private SystemProps() {}

  // -D… property keys
  public static final String PARALLEL = "parallel";
  public static final String SCENARIO = "scenario";
  public static final String MAX_RESPONSE_TIME_MS = "maxResponseTimeMs";

  // Shared scenario-mode literals (recognised by BasicSimulationBase)
  public static final String ALL = "all";
  public static final String CHAIN = "chain";
  public static final String BURST = "burst";
}
