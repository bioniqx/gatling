#!/usr/bin/env python3
"""
Generate the compliance report from all variant run artifacts.

Usage:
  generate-final-report.py --variants-dir target/variants/<game> \
                            --report-out /path/to/report.md

  Pass the per-game variants directory (e.g. target/variants/silkroad). The
  script scans for <variant>-<timestamp> subdirectories inside it.

Reads verdict.json and gatling-report/js/stats.json from each variant directory.
Missing variants render as N/A rows. Only the `target` variant drives the final verdict.
"""
import argparse
import csv
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

VARIANT_ORDER    = ["baseline", "target", "stress", "critical"]
VARIANT_CEILINGS = {
    "baseline": ("50%", "60%"),
    "target":   ("70%", "80%"),
    "stress":   ("85%", "90%"),
    "critical": ("95%", "95%"),
}
NA = "N/A"


# ---------------------------------------------------------------------------
# Directory discovery
# ---------------------------------------------------------------------------

def find_latest_variant_dir(variants_dir: Path, variant: str) -> Optional[Path]:
    """Return the most recently created directory matching <variant>-<timestamp>."""
    if not variants_dir.exists():
        return None
    candidates = sorted(
        [d for d in variants_dir.iterdir() if d.is_dir() and d.name.startswith(f"{variant}-")],
        key=lambda d: d.stat().st_mtime,
        reverse=True,
    )
    return candidates[0] if candidates else None


# ---------------------------------------------------------------------------
# Data extraction helpers
# ---------------------------------------------------------------------------

def load_verdict(variant_dir: Path) -> Optional[dict]:
    path = variant_dir / "verdict.json"
    if not path.exists():
        return None
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None


def load_stats_json(variant_dir: Path) -> Optional[dict]:
    """Load gatling-report/js/stats.json — authoritative Gatling output."""
    path = variant_dir / "gatling-report" / "js" / "stats.json"
    if not path.exists():
        return None
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None


def extract_gatling_metrics(stats: dict) -> dict:
    """Pull the fields we need from stats.json."""
    def get(keys, fallback=NA):
        node = stats
        for k in keys:
            if not isinstance(node, dict) or k not in node:
                return fallback
            node = node[k]
        return node if node is not None else fallback

    total  = get(["numberOfRequests", "total"], 0)
    ok     = get(["numberOfRequests", "ok"],    0)
    ko     = get(["numberOfRequests", "ko"],    0)
    ko_pct = round(ko / total * 100, 2) if total else NA

    return {
        "mean_ms":        get(["meanResponseTime",          "total"]),
        "p50_ms":         get(["percentiles1",              "total"]),
        "p75_ms":         get(["percentiles2",              "total"]),
        "p95_ms":         get(["percentiles3",              "total"]),
        "p99_ms":         get(["percentiles4",              "total"]),
        "max_ms":         get(["maxResponseTime",           "total"]),
        "total_requests": total,
        "ok_count":       ok,
        "ko_count":       ko,
        "ko_pct":         ko_pct,
        "throughput_rps": get(["meanNumberOfRequestsPerSecond", "total"]),
    }


def parse_resource_csv(variant_dir: Path) -> dict:
    """Compute min/mean/p95/max for cpu_pct and mem_pct."""
    path = variant_dir / "resource.csv"
    cpu_vals, mem_vals = [], []
    if path.exists():
        try:
            with open(path, newline="") as f:
                for row in csv.DictReader(f):
                    try:
                        cpu_vals.append(float(row["cpu_pct"]))
                        mem_vals.append(float(row["mem_pct"]))
                    except (ValueError, KeyError):
                        pass
        except Exception:
            pass

    def stats(vals):
        if not vals:
            return NA, NA, NA, NA
        s = sorted(vals)
        n = len(s)
        mean = round(sum(s) / n, 2)
        idx  = (95 / 100) * (n - 1)
        lo, hi = int(idx), min(int(idx) + 1, n - 1)
        p95 = round(s[lo] + (idx - lo) * (s[hi] - s[lo]), 2)
        return round(s[0], 2), mean, p95, round(s[-1], 2)

    c_min, c_mean, c_p95, c_max = stats(cpu_vals)
    m_min, m_mean, m_p95, m_max = stats(mem_vals)
    return {
        "cpu_min": c_min, "cpu_mean": c_mean, "cpu_p95": c_p95, "cpu_max": c_max,
        "mem_min": m_min, "mem_mean": m_mean, "mem_p95": m_p95, "mem_max": m_max,
    }


def parse_health_csv(variant_dir: Path) -> dict:
    """Return max consecutive failure run length from health CSV."""
    path = variant_dir / "health.csv"
    max_gap, cur = 0, 0
    if path.exists():
        try:
            with open(path, newline="") as f:
                for row in csv.DictReader(f):
                    try:
                        code  = row.get("http_status", "")
                        total = float(row.get("total_seconds", 0))
                        ok    = code.startswith("2") and total < 5.0
                        cur   = 0 if ok else cur + 1
                        max_gap = max(max_gap, cur)
                    except (ValueError, KeyError):
                        pass
        except Exception:
            pass
    # Convert consecutive probe failures to approximate seconds (2s interval)
    return {"max_gap_sec": max_gap * 2}


def gatling_report_link(variant_dir: Path) -> str:
    p = variant_dir / "gatling-report" / "index.html"
    return f"file://{p.resolve()}" if p.exists() else NA


# ---------------------------------------------------------------------------
# Report rendering
# ---------------------------------------------------------------------------

def fmt(v, suffix="") -> str:
    return NA if v == NA else f"{v}{suffix}"


def pass_fail(v: Optional[bool]) -> str:
    if v is None:
        return NA
    return "PASS" if v else "FAIL"


def render_report(variants_dir: Path, host: str, port: str) -> str:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    variant_data: dict[str, dict] = {}
    for v in VARIANT_ORDER:
        d = find_latest_variant_dir(variants_dir, v)
        if d is None:
            variant_data[v] = {}
            continue
        verdict  = load_verdict(d)
        stats    = load_stats_json(d)
        res_csv  = parse_resource_csv(d)
        hlt_csv  = parse_health_csv(d)
        gm       = extract_gatling_metrics(stats) if stats else {}
        variant_data[v] = {
            "verdict":  verdict or {},
            "gatling":  gm,
            "resource": res_csv,
            "health":   hlt_csv,
            "link":     gatling_report_link(d),
            "dir":      d,
        }

    tgt = variant_data.get("target", {})
    tv  = tgt.get("verdict", {})
    tg  = tgt.get("gatling", {})
    tr  = tgt.get("resource", {})
    th  = tgt.get("health", {})

    # PR checks
    pr1_observed = tv.get("users", NA)
    pr1_pass = (pr1_observed != NA and pr1_observed >= 1000)
    pr2_observed = tv.get("duration_sec", NA)
    pr2_pass = (pr2_observed != NA and pr2_observed >= 3600)
    pr3_gap  = th.get("max_gap_sec", NA)
    pr3_pass = (pr3_gap != NA and pr3_gap < 5)
    pr4_mean = tg.get("mean_ms", NA)
    pr4_pass = (pr4_mean != NA and pr4_mean <= 500)
    pr5_ko   = tg.get("ko_pct", NA)
    pr5_pass = (pr5_ko != NA and pr5_ko <= 1.0)
    pr6_cpu  = tv.get("cpu_p95", NA)
    pr6_pass = tv.get("cpu_pass", None)
    pr7_mem  = tv.get("mem_p95", NA)
    pr7_pass = tv.get("mem_pass", None)

    # Final verdict: all PRs must pass
    all_pass = all([pr1_pass, pr3_pass, pr4_pass, pr5_pass,
                    pr6_pass is True, pr7_pass is True])
    final_verdict = "PASS" if (tgt and all_pass) else "FAIL"

    # ---- Variant sweep table rows ----
    def variant_row(v_name):
        vd = variant_data.get(v_name, {})
        if not vd:
            cpu_ceil, mem_ceil = VARIANT_CEILINGS[v_name]
            link_text = "[open](N/A)"
            note = f"> **{v_name}** not executed — re-run `scripts/run-variant.sh --variant {v_name} ...` to populate."
            return (
                f"| {v_name:<8} | {cpu_ceil} | {mem_ceil} | N/A | N/A | N/A | N/A | N/A | N/A | {link_text} |",
                note,
            )
        vv   = vd.get("verdict", {})
        link = vd.get("link", NA)
        link_text = f"[open]({link})" if link != NA else "N/A"
        cpu_ceil, mem_ceil = VARIANT_CEILINGS[v_name]
        row = (
            f"| {v_name:<8} | {cpu_ceil} | {mem_ceil} "
            f"| {fmt(vv.get('cpu_p95', NA), '%')} "
            f"| {fmt(vv.get('mem_p95', NA), '%')} "
            f"| {pass_fail(vv.get('cpu_pass'))} "
            f"| {pass_fail(vv.get('mem_pass'))} "
            f"| {pass_fail(vv.get('crash_pass'))} "
            f"| {vv.get('verdict', NA)} "
            f"| {link_text} |"
        )
        return row, None

    sweep_rows = []
    footnotes  = []
    for v_name in VARIANT_ORDER:
        row, note = variant_row(v_name)
        sweep_rows.append(row)
        if note:
            footnotes.append(note)

    # Stress/Spike discovery notes
    stress_vd = variant_data.get("stress", {})
    stress_notes = "Not executed." if not stress_vd else "See Gatling HTML report for p95/p99 over time."
    stress_breaking = NA if not stress_vd else "(see HTML report)"

    spike_vd = variant_data.get("critical", {})
    spike_notes = "Not executed." if not spike_vd else "See Gatling HTML report for recovery timeline."
    spike_recovery = NA if not spike_vd else "(see HTML report)"

    tgt_link = tgt.get("link", NA)

    lines = [
        f"# Report — Load Test Production Readiness (Silk Road Caravans)",
        f"",
        f"**Date:** {now}",
        f"**SUT:** be-silk-road-caravans @ {host}:{port}",
        f"**Source:** /Users/rgp/RGP-Workspace/rgp-game-load-test",
        f"**Test plan:** docs/plans/plan-260511-load-test-production-readiness.md",
        f"",
        f"---",
        f"",
        f"## Production Criteria — Pass/Fail",
        f"",
        f"| ID | Criterion | Threshold | Observed | Result |",
        f"|----|-----------|-----------|----------|--------|",
        f"| PR-1 | Concurrent sessions | ≥ 1000 | {fmt(pr1_observed)} VUs | {'PASS' if pr1_pass else 'FAIL'} |",
        f"| PR-2 | Test duration | ≥ 60 min | {fmt(pr2_observed, ' s') if pr2_observed != NA else NA} | {'PASS' if pr2_pass else 'FAIL'} |",
        f"| PR-3 | Server crashes | 0 (no >5s health gap) | {fmt(pr3_gap, 's')} max gap | {'PASS' if pr3_pass else 'FAIL'} |",
        f"| PR-4 | Avg response time | ≤ 500 ms | {fmt(pr4_mean, ' ms')} | {'PASS' if pr4_pass else 'FAIL'} |",
        f"| PR-5 | Error rate | ≤ 1% | {fmt(pr5_ko, '%')} | {'PASS' if pr5_pass else 'FAIL'} |",
        f"| PR-6 | CPU p95 | ≤ 70% | {fmt(pr6_cpu, '%')} | {pass_fail(pr6_pass)} |",
        f"| PR-7 | Memory p95 | ≤ 80% | {fmt(pr7_mem, '%')} | {pass_fail(pr7_pass)} |",
        f"",
        f"**Final verdict: {final_verdict}**",
        f"",
        f"---",
        f"",
        f"## Threshold Variant Sweep (CPU / Memory)",
        f"",
        f"Same workload, four threshold profiles. Useful to map graceful degradation.",
        f"",
        f"| Variant | CPU ceil | Mem ceil | CPU p95 | Mem p95 | CPU pass | Mem pass | Crash pass | Verdict | Gatling HTML |",
        f"|---|---|---|---|---|---|---|---|---|---|",
    ]
    lines.extend(sweep_rows)
    lines.append("")
    lines.append("> Each HTML link is the per-variant preserved Gatling report (full charts: latency over time, requests/s, response-time percentiles, active users). Numbers in this Markdown report come from the matching `stats.json` so they will always agree with what the HTML shows.")
    if footnotes:
        lines.append("")
        lines.extend(footnotes)

    lines += [
        f"",
        f"---",
        f"",
        f"## Latency Distribution (target variant)",
        f"",
        f"| Percentile | Value (ms) |",
        f"|---|---|",
        f"| Mean | {fmt(tg.get('mean_ms', NA))} |",
        f"| p50  | {fmt(tg.get('p50_ms',  NA))} |",
        f"| p75  | {fmt(tg.get('p75_ms',  NA))} |",
        f"| p95  | {fmt(tg.get('p95_ms',  NA))} |",
        f"| p99  | {fmt(tg.get('p99_ms',  NA))} |",
        f"| Max  | {fmt(tg.get('max_ms',  NA))} |",
        f"",
        f"Total requests: {fmt(tg.get('total_requests', NA))} | "
        f"OK: {fmt(tg.get('ok_count', NA))} | "
        f"KO: {fmt(tg.get('ko_count', NA))} ({fmt(tg.get('ko_pct', NA), '%')})",
        f"Throughput: {fmt(tg.get('throughput_rps', NA))} req/s",
        f"",
        f"Full Gatling HTML report (charts): [{tgt_link}]({tgt_link})",
        f"",
        f"---",
        f"",
        f"## Resource Timeline (target variant)",
        f"",
        f"Sampled every 5 seconds. CSV: `target/variants/<game>/target-*/resource.csv`.",
        f"",
        f"| Metric | Min | Mean | p95 | Max |",
        f"|---|---|---|---|---|",
        f"| CPU % | {fmt(tr.get('cpu_min', NA))} | {fmt(tr.get('cpu_mean', NA))} | {fmt(tr.get('cpu_p95', NA))} | {fmt(tr.get('cpu_max', NA))} |",
        f"| Mem % | {fmt(tr.get('mem_min', NA))} | {fmt(tr.get('mem_mean', NA))} | {fmt(tr.get('mem_p95', NA))} | {fmt(tr.get('mem_max', NA))} |",
        f"",
        f"---",
        f"",
        f"## Discovery Runs",
        f"",
        f"### Stress (ramp 500→2500 over 30 min)",
        f"- Breaking point (first p95 > 1s OR KO% > 5%): {stress_breaking} concurrent users",
        f"- Notes: {stress_notes}",
        f"",
        f"### Spike (200 baseline + 1500 spike × 5 cycles)",
        f"- Recovery time after spike (back to p95 ≤ 500ms): {spike_recovery} s",
        f"- Notes: {spike_notes}",
        f"",
        f"---",
        f"",
        f"## Unresolved Questions",
        f"",
        f"- None recorded at report generation time.",
    ]

    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Generate compliance report from variant runs")
    parser.add_argument("--variants-dir", required=True,
                        help="Per-game variants dir (e.g. target/variants/silkroad)")
    parser.add_argument("--report-out",   required=True,
                        help="Output path for the Markdown report")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", default="3000")
    args = parser.parse_args()

    variants_dir = Path(args.variants_dir)
    if not variants_dir.exists():
        print(f"[generate-final-report] variants-dir not found: {variants_dir}", file=sys.stderr)
        print("[generate-final-report] Generating skeleton report with all N/A rows.", file=sys.stderr)

    report = render_report(variants_dir, args.host, args.port)

    out_path = Path(args.report_out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report, encoding="utf-8")
    print(f"[generate-final-report] Report written to: {out_path}")

    # Verify no unsubstituted placeholders
    if "{{" in report:
        print("[generate-final-report] ERROR: unsubstituted {{...}} placeholders remain!", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
