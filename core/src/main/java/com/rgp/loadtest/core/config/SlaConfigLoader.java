package com.rgp.loadtest.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads {@link SlaConfig} from a stack of YAML layers (deep-merged field-by-field):
 *
 * <ol>
 *   <li>{@code -DslaConfig=/abs/path/file.yml} — full override, short-circuits all layers below.
 *   <li>{@code games/<gameName>/src/gatling/resources/sla-thresholds.yml} — per-game override (only
 *       loaded when {@code -DgameName} is set).
 *   <li>{@code <cwd>/config/sla-thresholds.yml} — repo-level source of truth.
 *   <li>{@code classpath:sla-thresholds.yml} — bundled fallback inside the core JAR.
 *   <li>{@link SlaConfig#defaults()} — hardcoded base so the build never fails on missing config.
 * </ol>
 *
 * <p>Each layer only needs to declare the fields it wants to override; missing fields fall through
 * to the layer below. This applies at field level — e.g. a game can override {@code
 * variants.target.cpu_ceil} without touching {@code variants.target.mem_ceil} or any other
 * variant.
 *
 * <p>Result is cached for the JVM lifetime — Gatling assertions are wired at simulation init time
 * anyway, so live-reload would have no effect.
 */
public final class SlaConfigLoader {

  private static volatile SlaConfig cached;
  /** Highest-precedence layer applied. {@code "defaults"} if no YAML was found. */
  private static volatile String cachedSource = "defaults";

  private SlaConfigLoader() {}

  public static SlaConfig load() {
    SlaConfig c = cached;
    if (c != null) return c;
    synchronized (SlaConfigLoader.class) {
      if (cached != null) return cached;
      cached = doLoad();
      return cached;
    }
  }

  /**
   * Identifier of the highest-precedence YAML applied (for audit / verdict export). Either an
   * absolute path, {@code "classpath:sla-thresholds.yml"}, or {@code "defaults"}.
   */
  public static String loadedSource() {
    // Trigger load if not done yet so callers don't get "defaults" by accident.
    load();
    return cachedSource;
  }

  /** Force reload — used by tests, not production. */
  static void reset() {
    cached = null;
    cachedSource = "defaults";
  }

  private static SlaConfig doLoad() {
    // -DslaConfig: full override, short-circuits the layer stack.
    String override = System.getProperty("slaConfig");
    if (override != null && !override.isBlank()) {
      Path p = Path.of(override);
      if (Files.isReadable(p)) {
        SlaConfig parsed = tryParseFile(p, SlaConfig.defaults());
        if (parsed != null) {
          System.err.println("[SlaConfig] loaded from -DslaConfig=" + p);
          cachedSource = p.toString();
          return parsed;
        }
      } else {
        System.err.println("[SlaConfig] WARN: -DslaConfig=" + p + " not readable, falling through");
      }
    }

    // Layer stack: each layer merges its fields onto the result of the previous layer.
    SlaConfig cfg = SlaConfig.defaults();

    // Classpath fallback (bundled in core JAR).
    cfg = layerClasspath(cfg);

    // Repo-level config (canonical source of truth).
    cfg = layerFile(Path.of("config", "sla-thresholds.yml").toAbsolutePath(), cfg);

    // Per-game override (optional — only if -DgameName is set AND file exists).
    String gameName = System.getProperty("gameName");
    if (gameName != null && !gameName.isBlank()) {
      Path gameConfig =
          Path.of("games", gameName, "src", "gatling", "resources", "sla-thresholds.yml")
              .toAbsolutePath();
      cfg = layerFile(gameConfig, cfg);
    }

    return cfg;
  }

  /** Layer a filesystem YAML on top of base. Returns base unchanged if file missing or invalid. */
  private static SlaConfig layerFile(Path p, SlaConfig base) {
    if (!Files.isReadable(p)) return base;
    SlaConfig merged = tryParseFile(p, base);
    if (merged == null) return base;
    System.err.println("[SlaConfig] layered " + p);
    cachedSource = p.toString();
    return merged;
  }

  /** Layer the classpath-bundled YAML on top of base. */
  private static SlaConfig layerClasspath(SlaConfig base) {
    try (InputStream in =
        SlaConfigLoader.class.getClassLoader().getResourceAsStream("sla-thresholds.yml")) {
      if (in == null) return base;
      SlaConfig merged = parse(new Yaml().load(in), base);
      if (merged == null) return base;
      System.err.println("[SlaConfig] layered classpath:sla-thresholds.yml");
      cachedSource = "classpath:sla-thresholds.yml";
      return merged;
    } catch (IOException ignored) {
      return base;
    }
  }

  private static SlaConfig tryParseFile(Path p, SlaConfig base) {
    try (InputStream in = Files.newInputStream(p)) {
      return parse(new Yaml().load(in), base);
    } catch (Exception e) {
      System.err.println("[SlaConfig] WARN: failed to parse " + p + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Parse a YAML root onto the given base. Missing fields in the YAML are inherited from base;
   * variants are merged per-variant and per-field.
   */
  @SuppressWarnings("unchecked")
  private static SlaConfig parse(Object yamlRoot, SlaConfig base) {
    if (!(yamlRoot instanceof Map)) return null;
    Map<String, Object> root = (Map<String, Object>) yamlRoot;
    Map<String, Object> sla = mapOf(root.get("sla"));
    Map<String, Object> crash = mapOf(root.get("crash"));
    Map<String, Object> variants = mapOf(root.get("variants"));

    int pr4 = intOr(sla.get("pr4_mean_response_ms_max"), base.pr4MeanResponseMsMax);
    double pr5 = doubleOr(sla.get("pr5_error_percent_max"), base.pr5ErrorPercentMax);
    int buf = intOr(sla.get("max_duration_buffer_min"), base.maxDurationBufferMin);
    int crashMax =
        intOr(crash.get("max_consecutive_failures"), base.crashMaxConsecutiveFailures);

    // Start from base variants, then overlay any present in this YAML.
    Map<String, SlaConfig.VariantCeiling> mergedVariants = new LinkedHashMap<>(base.variants);
    for (Map.Entry<String, Object> entry : variants.entrySet()) {
      Map<String, Object> v = mapOf(entry.getValue());
      SlaConfig.VariantCeiling fallback =
          mergedVariants.getOrDefault(entry.getKey(), new SlaConfig.VariantCeiling(70, 80));
      int cpu = intOr(v.get("cpu_ceil"), fallback.cpuCeil);
      int mem = intOr(v.get("mem_ceil"), fallback.memCeil);
      mergedVariants.put(entry.getKey(), new SlaConfig.VariantCeiling(cpu, mem));
    }

    return new SlaConfig(pr4, pr5, buf, crashMax, mergedVariants);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Object o) {
    return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
  }

  private static int intOr(Object o, int fallback) {
    if (o == null) return fallback;
    if (o instanceof Number) return ((Number) o).intValue();
    try {
      return Integer.parseInt(o.toString().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double doubleOr(Object o, double fallback) {
    if (o == null) return fallback;
    if (o instanceof Number) return ((Number) o).doubleValue();
    try {
      return Double.parseDouble(o.toString().trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
