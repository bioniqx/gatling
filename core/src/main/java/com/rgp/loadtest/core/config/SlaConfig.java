package com.rgp.loadtest.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strongly-typed view of {@code config/sla-thresholds.yml}.
 *
 * <p>Populated by {@link SlaConfigLoader}. All callers ({@link
 * com.rgp.loadtest.core.utils.SlaConstants}, {@code ThresholdVerifier}) read from this — never edit
 * Java files to change numbers.
 */
public final class SlaConfig {

  /** PR-4: mean response time threshold (ms). */
  public final int pr4MeanResponseMsMax;

  /** PR-5: HTTP error rate threshold (%). */
  public final double pr5ErrorPercentMax;

  /** Wall-clock safety buffer (min) added to Gatling {@code maxDuration}. */
  public final int maxDurationBufferMin;

  /** PR-3: max consecutive failed health-probe samples before declaring crash. */
  public final int crashMaxConsecutiveFailures;

  /** Variant ceiling: name → {cpu_ceil, mem_ceil}. */
  public final Map<String, VariantCeiling> variants;

  public SlaConfig(
      int pr4MeanResponseMsMax,
      double pr5ErrorPercentMax,
      int maxDurationBufferMin,
      int crashMaxConsecutiveFailures,
      Map<String, VariantCeiling> variants) {
    this.pr4MeanResponseMsMax = pr4MeanResponseMsMax;
    this.pr5ErrorPercentMax = pr5ErrorPercentMax;
    this.maxDurationBufferMin = maxDurationBufferMin;
    this.crashMaxConsecutiveFailures = crashMaxConsecutiveFailures;
    this.variants = Map.copyOf(variants);
  }

  /** Hardcoded fallback when no YAML can be loaded (so build never breaks on missing config). */
  public static SlaConfig defaults() {
    Map<String, VariantCeiling> v = new LinkedHashMap<>();
    v.put("baseline", new VariantCeiling(50, 60));
    v.put("target", new VariantCeiling(70, 80));
    v.put("stress", new VariantCeiling(85, 90));
    v.put("critical", new VariantCeiling(95, 95));
    return new SlaConfig(500, 1.0, 2, 3, v);
  }

  public static final class VariantCeiling {
    public final int cpuCeil;
    public final int memCeil;

    public VariantCeiling(int cpuCeil, int memCeil) {
      this.cpuCeil = cpuCeil;
      this.memCeil = memCeil;
    }
  }
}
