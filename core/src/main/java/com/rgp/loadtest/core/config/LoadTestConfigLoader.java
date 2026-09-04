package com.rgp.loadtest.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads {@link LoadTestDefaults} from a stack of layers (each layer overlays fields onto the
 * previous):
 *
 * <ol>
 *   <li>{@link LoadTestDefaults#fallback()} — hardcoded base.
 *   <li>{@code classpath:load-test-defaults.yml} — bundled core defaults.
 *   <li>{@code <cwd>/config/load-test-defaults.yml} — optional repo-level override.
 *   <li>{@code classpath:game.yml} — per-game override (bundled in each game's gatling sourceSet).
 *   <li>{@code System.getProperty("host"/"port"/"contextPath"/...)} — CLI override (highest).
 * </ol>
 *
 * <p>Per-game lookup uses the classpath (not filesystem) because each game's Gatling fork has its
 * own gatling-sourceSet classpath: {@code games/<game>/src/gatling/resources/game.yml} is bundled
 * as {@code game.yml} at the classpath root of that game's JVM.
 *
 * <p>Result cached for JVM lifetime — Gatling reads defaults at simulation init only.
 */
public final class LoadTestConfigLoader {

  private static volatile LoadTestDefaults cached;

  private LoadTestConfigLoader() {}

  public static LoadTestDefaults load() {
    LoadTestDefaults c = cached;
    if (c != null) return c;
    synchronized (LoadTestConfigLoader.class) {
      if (cached != null) return cached;
      cached = doLoad();
      return cached;
    }
  }

  /** Force reload — used by tests, not production. */
  static void reset() {
    cached = null;
  }

  private static LoadTestDefaults doLoad() {
    LoadTestDefaults cfg = LoadTestDefaults.fallback();

    // 1. Core classpath defaults.
    cfg = layerClasspath("load-test-defaults.yml", cfg, "[LoadTestConfig]");

    // 2. Optional repo-level override at <cwd>/config/load-test-defaults.yml.
    cfg = layerFile(Path.of("config", "load-test-defaults.yml").toAbsolutePath(), cfg);

    // 3. Per-game override — bundled at classpath root by each game's gatling sourceSet.
    cfg = layerClasspath("game.yml", cfg, "[LoadTestConfig:game]");

    // 4. System properties override everything (e.g., -Dport=3000 for staging).
    cfg = layerSystemProperties(cfg);

    return cfg;
  }

  private static LoadTestDefaults layerClasspath(String resource, LoadTestDefaults base, String tag) {
    try (InputStream in =
        LoadTestConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) return base;
      LoadTestDefaults merged = parse(new Yaml().load(in), base);
      if (merged == null) return base;
      System.err.println(tag + " layered classpath:" + resource);
      return merged;
    } catch (IOException ignored) {
      return base;
    }
  }

  private static LoadTestDefaults layerFile(Path p, LoadTestDefaults base) {
    if (!Files.isReadable(p)) return base;
    try (InputStream in = Files.newInputStream(p)) {
      LoadTestDefaults merged = parse(new Yaml().load(in), base);
      if (merged == null) return base;
      System.err.println("[LoadTestConfig] layered " + p);
      return merged;
    } catch (Exception e) {
      System.err.println("[LoadTestConfig] WARN: failed to parse " + p + ": " + e.getMessage());
      return base;
    }
  }

  private static LoadTestDefaults layerSystemProperties(LoadTestDefaults base) {
    String host = System.getProperty("host", base.host);
    String port = System.getProperty("port", base.port);
    String contextPath = System.getProperty("contextPath", base.contextPath);
    int users = Integer.getInteger("users", base.users);
    int requests = Integer.getInteger("requests", base.requests);
    int durationMinutes = Integer.getInteger("durationMinutes", base.durationMinutes);
    int rampMinutes = Integer.getInteger("rampMinutes", base.rampMinutes);
    int thinkTimeMin = Integer.getInteger("thinkTimeMin", base.thinkTimeMin);
    int thinkTimeMax = Integer.getInteger("thinkTimeMax", base.thinkTimeMax);
    return new LoadTestDefaults(
        host, port, contextPath, users, requests, durationMinutes, rampMinutes,
        thinkTimeMin, thinkTimeMax);
  }

  @SuppressWarnings("unchecked")
  private static LoadTestDefaults parse(Object yamlRoot, LoadTestDefaults base) {
    if (!(yamlRoot instanceof Map)) return null;
    Map<String, Object> root = (Map<String, Object>) yamlRoot;
    Map<String, Object> http = mapOf(root.get("http"));
    Map<String, Object> load = mapOf(root.get("load"));

    String host = stringOr(http.get("host"), base.host);
    String port = stringOr(http.get("port"), base.port);
    String contextPath = stringOr(http.get("contextPath"), base.contextPath);
    int users = intOr(load.get("users"), base.users);
    int requests = intOr(load.get("requests"), base.requests);
    int durationMinutes = intOr(load.get("durationMinutes"), base.durationMinutes);
    int rampMinutes = intOr(load.get("rampMinutes"), base.rampMinutes);
    int thinkTimeMin = intOr(load.get("thinkTimeMin"), base.thinkTimeMin);
    int thinkTimeMax = intOr(load.get("thinkTimeMax"), base.thinkTimeMax);
    return new LoadTestDefaults(
        host, port, contextPath, users, requests, durationMinutes, rampMinutes,
        thinkTimeMin, thinkTimeMax);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Object o) {
    return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
  }

  private static String stringOr(Object o, String fallback) {
    return (o == null) ? fallback : o.toString();
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
}
