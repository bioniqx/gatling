package com.rgp.loadtest.core.verify;

import com.rgp.loadtest.core.config.SlaConfig;
import com.rgp.loadtest.core.config.SlaConfigLoader;
import com.rgp.loadtest.core.utils.SlaConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Variant-gated load-test verifier — Java port of the original {@code verify-thresholds.py}.
 *
 * <p>Reads CPU/Mem samples from {@code resource.csv}, health samples from {@code health.csv}, and
 * (optionally) the final {@code > Global ... | total | OK | KO} row from {@code gatling.log}.
 * Applies per-variant CPU/Mem ceilings (baseline / target / stress / critical) plus crash-window
 * + HTTP-KO checks, then emits a {@code verdict.json} payload identical in shape to the Python
 * version.
 *
 * <p>Invocation (via the {@code :verifyVariant} Gradle task):
 *
 * <pre>{@code
 * ./gradlew verifyVariant \
 *   -DresourceCsv=target/variants/.../resource.csv \
 *   -DhealthCsv=target/variants/.../health.csv \
 *   -Dvariant=target -Dusers=50 -DdurationSec=120 \
 *   -DgatlingLog=target/variants/.../gatling.log -DgatlingExit=0
 * }</pre>
 *
 * <p>Exit code: {@code 0} on PASS, {@code 1} on FAIL. JSON is printed to stdout regardless.
 */
public final class ThresholdVerifier {

  private static final SlaConfig SLA = SlaConfigLoader.load();

  private ThresholdVerifier() {}

  public static void main(String[] args) {
    String resourceCsv = required("resourceCsv");
    String healthCsv = required("healthCsv");
    String variant = required("variant");
    int users = Integer.parseInt(required("users"));
    int durationSec = Integer.parseInt(required("durationSec"));
    String gatlingLog = System.getProperty("gatlingLog");
    Integer gatlingExit = parseIntOrNull(System.getProperty("gatlingExit"));
    // SLA ceilings — default to project-wide SlaConstants (single source of truth).
    // Override via -DhttpKoCeil / -DmeanMsCeil if a specific game needs tighter/looser bounds.
    double httpKoCeil =
        Double.parseDouble(
            System.getProperty(
                "httpKoCeil", String.valueOf(SlaConstants.PR5_ERROR_PERCENT_MAX)));
    long meanMsCeil =
        Long.parseLong(
            System.getProperty(
                "meanMsCeil", String.valueOf(SlaConstants.PR4_MEAN_RESPONSE_MS_MAX)));

    SlaConfig.VariantCeiling ceiling = SLA.variants.get(variant);
    if (ceiling == null) {
      System.err.println("Unknown variant: " + variant + ". Valid: " + SLA.variants.keySet());
      System.exit(2);
    }
    int cpuCeil = ceiling.cpuCeil;
    int memCeil = ceiling.memCeil;
    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

    double[][] samples = parseResourceCsv(Path.of(resourceCsv), cores);
    List<Double> cpuVals = toList(samples[0]);
    List<Double> memVals = toList(samples[1]);

    int[] health = parseHealthCsv(Path.of(healthCsv));
    int healthFailures = health[0];
    int maxConsecutive = health[1];

    GatlingStats gatlingStats = (gatlingLog != null) ? parseGatlingLog(Path.of(gatlingLog)) : null;

    Double cpuP95 = cpuVals.isEmpty() ? null : round2(percentile(cpuVals, 95));
    Double memP95 = memVals.isEmpty() ? null : round2(percentile(memVals, 95));

    boolean cpuPass = cpuP95 != null && cpuP95 <= cpuCeil;
    boolean memPass = memP95 != null && memP95 <= memCeil;
    // PR-3: max consecutive failed health probes (configured in sla-thresholds.yml)
    boolean crashPass = maxConsecutive < SLA.crashMaxConsecutiveFailures;

    Double httpKoPercent = gatlingStats != null ? gatlingStats.koPercent : null;
    boolean pr5Pass; // PR-5: error rate ≤ httpKoCeil
    if (httpKoPercent != null) {
      pr5Pass = httpKoPercent <= httpKoCeil;
    } else if (gatlingExit != null) {
      pr5Pass = gatlingExit == 0;
    } else {
      pr5Pass = true;
    }

    // PR-4: mean response time ≤ meanMsCeil. Skip check if Gatling didn't surface a mean
    // (e.g., very short run with only rolling-line stats) — defer to Gatling assertion via exit code.
    Long meanResponseMs =
        (gatlingStats != null && gatlingStats.meanResponseMs >= 0)
            ? gatlingStats.meanResponseMs
            : null;
    Long p95ResponseMs =
        (gatlingStats != null && gatlingStats.p95ResponseMs >= 0)
            ? gatlingStats.p95ResponseMs
            : null;
    boolean pr4Pass = (meanResponseMs == null) || meanResponseMs <= meanMsCeil;

    String verdict =
        (cpuPass && memPass && crashPass && pr4Pass && pr5Pass) ? "PASS" : "FAIL";

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("variant", variant);
    // Game context — null if -DgameName not set (e.g., silkroad smoke without wrapper).
    result.put("game_name", System.getProperty("gameName"));
    result.put("users", users);
    result.put("duration_sec", durationSec);
    result.put("cpu_p95", cpuP95);
    result.put("mem_p95", memP95);
    result.put("cpu_ceil", cpuCeil);
    result.put("mem_ceil", memCeil);
    result.put("cpu_pass", cpuPass);
    result.put("mem_pass", memPass);
    result.put("health_failures", healthFailures);
    result.put("max_consecutive_failures", maxConsecutive);
    result.put("crash_max_consecutive_ceil", SLA.crashMaxConsecutiveFailures);
    result.put("crash_pass", crashPass);
    result.put("http_total", gatlingStats != null ? gatlingStats.total : null);
    result.put("http_ok", gatlingStats != null ? gatlingStats.ok : null);
    result.put("http_ko", gatlingStats != null ? gatlingStats.ko : null);
    // PR-4 (mean response time)
    result.put("mean_response_ms", meanResponseMs);
    result.put("p95_response_ms", p95ResponseMs);
    result.put("mean_ms_ceil", meanMsCeil);
    result.put("pr4_pass", pr4Pass);
    // PR-5 (error rate / HTTP KO)
    result.put("http_ko_percent", httpKoPercent);
    result.put("http_ko_ceil", httpKoCeil);
    result.put("pr5_pass", pr5Pass);
    // Back-compat alias for older summary.html versions that still read http_pass.
    result.put("http_pass", pr5Pass);
    result.put("gatling_exit_code", gatlingExit);
    result.put("host_cores", cores);
    // Audit metadata: which YAML provided the SLA values above.
    result.put("sla_config_source", SlaConfigLoader.loadedSource());
    result.put("verdict", verdict);

    System.out.println(toJson(result));
    System.exit(verdict.equals("PASS") ? 0 : 1);
  }

  private static String required(String key) {
    String v = System.getProperty(key);
    if (v == null || v.isBlank()) {
      System.err.println("Missing required -D" + key);
      System.exit(2);
    }
    return v;
  }

  private static Integer parseIntOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double percentile(List<Double> values, double p) {
    if (values.isEmpty()) return 0.0;
    List<Double> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    double idx = (p / 100.0) * (sorted.size() - 1);
    int lo = (int) idx;
    int hi = Math.min(lo + 1, sorted.size() - 1);
    double frac = idx - lo;
    return sorted.get(lo) + frac * (sorted.get(hi) - sorted.get(lo));
  }

  private static double round2(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  private static List<Double> toList(double[] arr) {
    List<Double> out = new ArrayList<>(arr.length);
    for (double v : arr) out.add(v);
    return out;
  }

  /** Returns {cpuVals[], memVals[]} normalised by host core count. */
  private static double[][] parseResourceCsv(Path path, int cores) {
    List<Double> cpu = new ArrayList<>();
    List<Double> mem = new ArrayList<>();
    if (!Files.isReadable(path)) {
      return new double[][] {new double[0], new double[0]};
    }
    try {
      List<String> lines = Files.readAllLines(path);
      if (lines.isEmpty()) return new double[][] {new double[0], new double[0]};
      Map<String, Integer> header = parseHeader(lines.get(0));
      Integer cpuIdx = header.get("cpu_pct");
      Integer memIdx = header.get("mem_pct");
      if (cpuIdx == null || memIdx == null) {
        return new double[][] {new double[0], new double[0]};
      }
      for (int i = 1; i < lines.size(); i++) {
        String[] cols = lines.get(i).split(",", -1);
        if (cols.length <= Math.max(cpuIdx, memIdx)) continue;
        try {
          double cpuV = Double.parseDouble(cols[cpuIdx]) / cores;
          double memV = Double.parseDouble(cols[memIdx]);
          cpu.add(cpuV);
          mem.add(memV);
        } catch (NumberFormatException skip) {
          // malformed row — skip silently, matches Python behaviour
        }
      }
    } catch (IOException e) {
      // empty result — same as Python's FileNotFoundError catch
    }
    return new double[][] {unbox(cpu), unbox(mem)};
  }

  /** Returns {totalFailures, maxConsecutiveFailures}. */
  private static int[] parseHealthCsv(Path path) {
    int failures = 0;
    int maxConsecutive = 0;
    int consecutive = 0;
    if (!Files.isReadable(path)) return new int[] {0, 0};
    try {
      List<String> lines = Files.readAllLines(path);
      if (lines.isEmpty()) return new int[] {0, 0};
      Map<String, Integer> header = parseHeader(lines.get(0));
      Integer statusIdx = header.get("http_status");
      Integer totalIdx = header.get("total_seconds");
      if (statusIdx == null || totalIdx == null) return new int[] {0, 0};

      for (int i = 1; i < lines.size(); i++) {
        String[] cols = lines.get(i).split(",", -1);
        if (cols.length <= Math.max(statusIdx, totalIdx)) continue;
        try {
          String code = cols[statusIdx];
          double total = Double.parseDouble(cols[totalIdx]);
          boolean isOk = code.startsWith("2") && total < 5.0;
          if (!isOk) {
            failures++;
            consecutive++;
            maxConsecutive = Math.max(maxConsecutive, consecutive);
          } else {
            consecutive = 0;
          }
        } catch (NumberFormatException skip) {
          // malformed row
        }
      }
    } catch (IOException e) {
      // ignore
    }
    return new int[] {failures, maxConsecutive};
  }

  /**
   * Parse the final "Global Information" stats block in gatling.log. Captures count/OK/KO plus
   * mean and p95 response time. Returns null if not found.
   */
  static GatlingStats parseGatlingLog(Path path) {
    if (!Files.isReadable(path)) return null;
    String content;
    try {
      content = Files.readString(path);
    } catch (IOException e) {
      return null;
    }

    // Final summary lines look like:
    //   > request count                          |   1499 |   1499 |      0
    //   > mean response time (ms)                |    101 |    101 |      -
    //   > response time 95th percentile (ms)     |    199 |    199 |      -
    long total = parseStat(content, "request count");
    long ok = parseStat(content, "request count", 1);
    long ko = parseStat(content, "request count", 2);
    long mean = parseStat(content, "mean response time \\(ms\\)");
    long p95 = parseStat(content, "response time 95th percentile \\(ms\\)");

    // Gatling's final summary writes "-" in the KO column when there are no failures, which
    // parseStat returns as -1. Derive ko from total-ok in that case so percentages stay sane.
    if (total >= 0 && ok >= 0 && ko < 0) ko = total - ok;

    if (total < 0) {
      // naga777 runs on Gatling 3.9 (community gRPC plugin — see games/naga777/build.gradle),
      // which prints the summary as "> request count  640 (OK=640  KO=0 )" instead of the
      // pipe-delimited table, and omits the "(ms)" suffix on the response-time labels.
      total = parseLegacyStat(content, "request count", 0);
      ok = parseLegacyStat(content, "request count", 1);
      ko = parseLegacyStat(content, "request count", 2);
      mean = parseLegacyStat(content, "mean response time", 0);
      p95 = parseLegacyStat(content, "response time 95th percentile", 0);
      if (total >= 0 && ok >= 0 && ko < 0) ko = total - ok;
    }

    if (total < 0) {
      // Fall back to the rolling "> Global | total | OK | KO" line (no mean available).
      Pattern p =
          Pattern.compile(
              "^> Global\\s+\\|\\s*([\\d,]+)\\s*\\|\\s*([\\d,]+)\\s*\\|\\s*([\\d,]+)\\s*$",
              Pattern.MULTILINE);
      Matcher m = p.matcher(content);
      while (m.find()) {
        total = Long.parseLong(m.group(1).replace(",", ""));
        ok = Long.parseLong(m.group(2).replace(",", ""));
        ko = Long.parseLong(m.group(3).replace(",", ""));
      }
    }

    if (total < 0) {
      // Rolling line, Gatling 3.9 shape: "> Global   (OK=99   KO=0   )" — no total column.
      Pattern p =
          Pattern.compile(
              "^> Global\\s+\\(OK=([\\d,]+)\\s+KO=([\\d,]+)\\s*\\)", Pattern.MULTILINE);
      Matcher m = p.matcher(content);
      while (m.find()) {
        ok = Long.parseLong(m.group(1).replace(",", ""));
        ko = Long.parseLong(m.group(2).replace(",", ""));
        total = ok + ko;
      }
      if (total < 0) return null;
    }
    double koPercent = total > 0 ? round2(100.0 * ko / total) : 0.0;
    return new GatlingStats(total, ok, ko, koPercent, mean, p95);
  }

  /** Default: extract Total column (group 1) for a {@code > <label> | total | ok | ko} stat row. */
  private static long parseStat(String content, String labelPattern) {
    return parseStat(content, labelPattern, 0);
  }

  /** {@code colIdx}: 0=Total, 1=OK, 2=KO. Returns -1 if not found. */
  private static long parseStat(String content, String labelPattern, int colIdx) {
    Pattern p =
        Pattern.compile(
            "^> " + labelPattern + "\\s+\\|\\s*([\\d,-]+)\\s*\\|\\s*([\\d,-]+)\\s*\\|\\s*([\\d,-]+)",
            Pattern.MULTILINE);
    Matcher m = p.matcher(content);
    long val = -1;
    while (m.find()) {
      String raw = m.group(colIdx + 1).replace(",", "");
      if ("-".equals(raw)) continue;
      try {
        val = Long.parseLong(raw);
      } catch (NumberFormatException ignored) {
        // skip
      }
    }
    return val;
  }

  /**
   * Gatling 3.9 summary row: {@code > <label>   640 (OK=640    KO=0     )}. {@code colIdx}: 0=Total,
   * 1=OK, 2=KO. Returns -1 if not found or the column holds "-".
   */
  private static long parseLegacyStat(String content, String labelPattern, int colIdx) {
    Pattern p =
        Pattern.compile(
            "^> " + labelPattern + "\\s+([\\d,.-]+)\\s+\\(OK=([\\d,.-]+)\\s+KO=([\\d,.-]+)\\s*\\)",
            Pattern.MULTILINE);
    Matcher m = p.matcher(content);
    long val = -1;
    while (m.find()) {
      String raw = m.group(colIdx + 1).replace(",", "");
      if ("-".equals(raw)) continue;
      try {
        val = (long) Double.parseDouble(raw);
      } catch (NumberFormatException ignored) {
        // skip
      }
    }
    return val;
  }

  private static Map<String, Integer> parseHeader(String headerLine) {
    Map<String, Integer> out = new LinkedHashMap<>();
    String[] cols = headerLine.split(",", -1);
    for (int i = 0; i < cols.length; i++) out.put(cols[i].trim(), i);
    return out;
  }

  private static double[] unbox(List<Double> in) {
    double[] out = new double[in.size()];
    for (int i = 0; i < in.size(); i++) out[i] = in.get(i);
    return out;
  }

  /** Minimal JSON serializer for the flat verdict map — values are String/Number/Boolean/null. */
  static String toJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{\n");
    int i = 0, n = map.size();
    for (Map.Entry<String, Object> e : map.entrySet()) {
      sb.append("  \"").append(e.getKey()).append("\": ").append(jsonValue(e.getValue()));
      if (++i < n) sb.append(',');
      sb.append('\n');
    }
    sb.append('}');
    return sb.toString();
  }

  private static String jsonValue(Object v) {
    if (v == null) return "null";
    if (v instanceof Boolean) return v.toString();
    if (v instanceof Number) {
      double d = ((Number) v).doubleValue();
      if (d == Math.floor(d) && !Double.isInfinite(d) && v instanceof Integer
          || v instanceof Long) {
        return v.toString();
      }
      return v.toString();
    }
    return "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static final class GatlingStats {
    final long total;
    final long ok;
    final long ko;
    final double koPercent;
    final long meanResponseMs; // -1 if not available (rolling-line fallback)
    final long p95ResponseMs; //  -1 if not available

    GatlingStats(
        long total, long ok, long ko, double koPercent, long meanResponseMs, long p95ResponseMs) {
      this.total = total;
      this.ok = ok;
      this.ko = ko;
      this.koPercent = koPercent;
      this.meanResponseMs = meanResponseMs;
      this.p95ResponseMs = p95ResponseMs;
    }
  }
}
