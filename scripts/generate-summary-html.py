#!/usr/bin/env python3
"""
Generate summary.html for a load-test variant run.

Combines:
- verdict.json     → PASS/FAIL banner with thresholds
- resource.csv     → CPU/Mem stats + inline SVG sparklines
- health.csv       → Health probe summary
- gatling-report/  → Embedded via iframe at the bottom

Usage:
  generate-summary-html.py --variant-dir <path>

Writes <variant-dir>/summary.html.
"""
import argparse
import csv
import json
import os
import sys
from pathlib import Path


def percentile(values: list, p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = (p / 100) * (len(s) - 1)
    lo, hi = int(idx), min(int(idx) + 1, len(s) - 1)
    frac = idx - lo
    return s[lo] + frac * (s[hi] - s[lo])


def safe_float(x):
    try:
        return float(x)
    except (ValueError, TypeError):
        return None


def parse_resource(path: Path):
    """Returns (cpu_series, mem_series) lists of float."""
    cpu, mem = [], []
    if not path.exists():
        return cpu, mem
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            c = safe_float(row.get("cpu_pct"))
            m = safe_float(row.get("mem_pct"))
            if c is not None:
                cpu.append(c)
            if m is not None:
                mem.append(m)
    return cpu, mem


def parse_health(path: Path):
    """Returns (total, failures, max_consecutive)."""
    total = failures = max_consec = consec = 0
    if not path.exists():
        return total, failures, max_consec
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            total += 1
            code = row.get("http_status", "")
            t = safe_float(row.get("total_seconds")) or 0
            ok = code.startswith("2") and t < 5.0
            if ok:
                consec = 0
            else:
                failures += 1
                consec += 1
                max_consec = max(max_consec, consec)
    return total, failures, max_consec


def stats(series: list, cores: int = 1):
    """avg / p50 / p95 / max — CPU values are normalised by core count."""
    if not series:
        return dict(avg=None, p50=None, p95=None, max=None)
    norm = [v / cores for v in series]
    return dict(
        avg=round(sum(norm) / len(norm), 2),
        p50=round(percentile(norm, 50), 2),
        p95=round(percentile(norm, 95), 2),
        max=round(max(norm), 2),
    )


def sparkline_svg(series: list, width=600, height=80, color="#3b82f6", ceil=None):
    """Inline SVG sparkline. No external deps."""
    if not series:
        return '<svg width="0" height="0"></svg>'
    vmin, vmax = min(series), max(series)
    if ceil is not None:
        vmax = max(vmax, ceil)
    span = max(0.001, vmax - vmin)
    pts = []
    for i, v in enumerate(series):
        x = i * width / max(1, len(series) - 1)
        y = height - ((v - vmin) / span) * (height - 4) - 2
        pts.append(f"{x:.1f},{y:.1f}")
    polyline = f'<polyline fill="none" stroke="{color}" stroke-width="1.5" points="{" ".join(pts)}"/>'
    ceil_line = ""
    if ceil is not None and ceil >= vmin and ceil <= vmax:
        y_ceil = height - ((ceil - vmin) / span) * (height - 4) - 2
        ceil_line = (
            f'<line x1="0" y1="{y_ceil:.1f}" x2="{width}" y2="{y_ceil:.1f}" '
            f'stroke="#ef4444" stroke-width="1" stroke-dasharray="4,3"/>'
        )
    return (
        f'<svg viewBox="0 0 {width} {height}" preserveAspectRatio="none" '
        f'style="width:100%;height:{height}px;background:#f9fafb;border-radius:4px">'
        f"{ceil_line}{polyline}</svg>"
    )


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Load Test Summary — {variant} {timestamp}</title>
<style>
  * {{ box-sizing: border-box; }}
  :root {{
    --bg: #0f172a;
    --bg-soft: #1e293b;
    --surface: #ffffff;
    --surface-alt: #f8fafc;
    --border: #e2e8f0;
    --text: #0f172a;
    --text-muted: #64748b;
    --text-soft: #94a3b8;
    --primary: #3b82f6;
    --success: #10b981;
    --success-bg: #ecfdf5;
    --danger: #ef4444;
    --danger-bg: #fef2f2;
    --warning: #f59e0b;
    --shadow-sm: 0 1px 2px rgba(15,23,42,0.04), 0 1px 3px rgba(15,23,42,0.06);
    --shadow-md: 0 4px 6px -1px rgba(15,23,42,0.06), 0 2px 4px -2px rgba(15,23,42,0.04);
    --shadow-lg: 0 10px 25px -5px rgba(15,23,42,0.10), 0 8px 10px -6px rgba(15,23,42,0.04);
    --radius: 12px;
    --radius-sm: 8px;
  }}
  html, body {{ margin:0; padding:0; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Inter, sans-serif;
    background: linear-gradient(180deg, #f1f5f9 0%, #e2e8f0 100%);
    color: var(--text);
    min-height: 100vh;
    -webkit-font-smoothing: antialiased;
  }}
  .container {{ max-width: 1280px; margin: 0 auto; padding: 32px 24px 64px; }}

  /* Hero */
  .hero {{
    position: relative;
    border-radius: var(--radius);
    padding: 32px 36px;
    margin-bottom: 28px;
    color: #fff;
    overflow: hidden;
    box-shadow: var(--shadow-lg);
  }}
  .hero.PASS {{ background: linear-gradient(135deg, #059669 0%, #10b981 60%, #34d399 100%); }}
  .hero.FAIL {{ background: linear-gradient(135deg, #b91c1c 0%, #ef4444 60%, #f87171 100%); }}
  .hero::after {{
    content: "";
    position: absolute; top: -40%; right: -10%;
    width: 360px; height: 360px;
    background: radial-gradient(circle, rgba(255,255,255,0.18), transparent 70%);
    pointer-events: none;
  }}
  .hero-row {{ display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 16px; position: relative; z-index: 1; }}
  .hero-title {{ font-size: 13px; text-transform: uppercase; letter-spacing: 2px; opacity: 0.85; margin: 0 0 8px; }}
  .hero-verdict {{ font-size: 44px; font-weight: 800; letter-spacing: 1px; margin: 0; line-height: 1; }}
  .hero-meta {{ font-size: 14px; opacity: 0.9; margin-top: 10px; }}
  .hero-stats {{ display: flex; gap: 32px; flex-wrap: wrap; }}
  .hero-stat {{ text-align: right; }}
  .hero-stat .label {{ font-size: 11px; text-transform: uppercase; letter-spacing: 1.5px; opacity: 0.8; }}
  .hero-stat .value {{ font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }}

  /* Section heading */
  .section-title {{
    font-size: 12px; text-transform: uppercase; letter-spacing: 2px;
    color: var(--text-muted); margin: 0 0 12px; font-weight: 600;
  }}

  /* Grid + cards */
  .grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 20px; }}
  @media (max-width: 880px) {{ .grid {{ grid-template-columns: 1fr; }} }}
  .card {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 20px 22px;
    box-shadow: var(--shadow-sm);
    transition: box-shadow 0.2s ease, transform 0.2s ease;
  }}
  .card:hover {{ box-shadow: var(--shadow-md); }}
  .card h2 {{
    margin: 0 0 14px 0; font-size: 12px; text-transform: uppercase;
    color: var(--text-muted); letter-spacing: 1.5px; font-weight: 600;
    display: flex; align-items: center; gap: 8px;
  }}
  .card h2::before {{
    content: ""; display: inline-block; width: 4px; height: 14px;
    background: var(--primary); border-radius: 2px;
  }}

  /* Tables */
  table {{ width: 100%; border-collapse: collapse; font-size: 14px; }}
  table th {{
    text-align: left; padding: 10px 8px; color: var(--text-muted);
    font-weight: 500; border-bottom: 1px solid var(--border); font-size: 13px;
  }}
  table td {{
    padding: 10px 8px; border-bottom: 1px solid var(--surface-alt);
    font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: 13px;
  }}
  table tr:last-child th, table tr:last-child td {{ border-bottom: 0; }}

  /* Badges */
  .badge {{
    display: inline-block; padding: 3px 10px; border-radius: 999px;
    font-size: 11px; font-weight: 700; letter-spacing: 0.5px;
    text-transform: uppercase; vertical-align: middle;
  }}
  .badge.ok {{ background: var(--success-bg); color: #047857; border: 1px solid #a7f3d0; }}
  .badge.fail {{ background: var(--danger-bg); color: #b91c1c; border: 1px solid #fecaca; }}

  /* Chart cards */
  .chart-card .chart-header {{
    display: flex; justify-content: space-between; align-items: baseline;
    margin-bottom: 12px; flex-wrap: wrap; gap: 8px;
  }}
  .chart-card .chart-subtitle {{
    font-size: 13px; color: var(--text-muted); font-variant-numeric: tabular-nums;
  }}

  /* HTTP summary mini cards */
  .stat-row {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }}
  @media (max-width: 720px) {{ .stat-row {{ grid-template-columns: repeat(2, 1fr); }} }}
  .stat {{
    background: var(--surface-alt); border: 1px solid var(--border);
    border-radius: var(--radius-sm); padding: 14px 16px;
  }}
  .stat .stat-label {{ font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: var(--text-muted); }}
  .stat .stat-value {{ font-size: 22px; font-weight: 700; margin-top: 4px; font-variant-numeric: tabular-nums; }}
  .stat.ok .stat-value {{ color: var(--success); }}
  .stat.bad .stat-value {{ color: var(--danger); }}

  .legend {{ font-size: 12px; color: var(--text-soft); margin-top: 10px; line-height: 1.5; }}
  .ceil {{ color: var(--danger); font-weight: 500; }}
  code {{ background: var(--surface-alt); padding: 1px 6px; border-radius: 4px; font-size: 12px; }}

  /* Gatling report card with fullscreen toggle */
  .report-card {{ position: relative; transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1); }}
  .report-header {{
    display: flex; justify-content: space-between; align-items: center;
    margin-bottom: 14px; flex-wrap: wrap; gap: 8px;
  }}
  .report-header h2 {{ margin: 0; }}

  .btn-fullscreen {{
    display: inline-flex; align-items: center; gap: 6px;
    background: var(--primary); color: #fff; border: 0;
    padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600;
    cursor: pointer; transition: all 0.2s ease;
    box-shadow: 0 1px 2px rgba(59,130,246,0.3);
  }}
  .btn-fullscreen:hover {{ background: #2563eb; transform: translateY(-1px); box-shadow: 0 4px 8px rgba(59,130,246,0.35); }}
  .btn-fullscreen:active {{ transform: translateY(0); }}
  .btn-fullscreen svg {{ width: 14px; height: 14px; }}

  .btn-close {{
    display: none; position: fixed; bottom: 28px; right: 28px;
    width: 56px; height: 56px; border-radius: 50%; border: 0;
    background: var(--danger); color: #fff; cursor: pointer;
    align-items: center; justify-content: center; z-index: 10000;
    backdrop-filter: blur(8px); transition: all 0.2s ease;
    box-shadow: 0 8px 24px rgba(239,68,68,0.45), 0 2px 6px rgba(0,0,0,0.2);
  }}
  .btn-close:hover {{ background: #dc2626; transform: rotate(90deg) scale(1.08); box-shadow: 0 12px 28px rgba(239,68,68,0.55); }}
  .btn-close:active {{ transform: rotate(90deg) scale(1); }}
  .btn-close svg {{ width: 24px; height: 24px; }}

  iframe.report-iframe {{
    width: 100%; height: 1400px; border: 0; border-radius: var(--radius-sm);
    background: white; transition: all 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  }}

  /* Fullscreen mode */
  body.is-fullscreen {{ overflow: hidden; }}
  body.is-fullscreen .container > *:not(.report-card) {{
    filter: blur(4px); opacity: 0.3; transition: all 0.4s ease; pointer-events: none;
  }}
  .report-card.fullscreen {{
    position: fixed; top: 0; left: 0; right: 0; bottom: 0;
    margin: 0; padding: 0; border-radius: 0; z-index: 9999;
    background: #0f172a; border: 0;
    animation: zoomIn 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  }}
  .report-card.fullscreen .report-header {{
    padding: 16px 24px; margin: 0; border-bottom: 1px solid rgba(255,255,255,0.1);
    background: rgba(15,23,42,0.95); color: #fff;
  }}
  .report-card.fullscreen .report-header h2 {{ color: #fff; }}
  .report-card.fullscreen .report-header h2::before {{ background: #60a5fa; }}
  .report-card.fullscreen .btn-fullscreen {{ display: none; }}
  .report-card.fullscreen .btn-close {{ display: inline-flex; }}
  .report-card.fullscreen iframe.report-iframe {{
    height: calc(100vh - 65px); border-radius: 0; background: #fff;
    animation: fadeInUp 0.5s cubic-bezier(0.4, 0, 0.2, 1) 0.1s both;
  }}

  @keyframes zoomIn {{
    from {{ transform: scale(0.92); opacity: 0; }}
    to {{ transform: scale(1); opacity: 1; }}
  }}
  @keyframes fadeInUp {{
    from {{ transform: translateY(20px); opacity: 0; }}
    to {{ transform: translateY(0); opacity: 1; }}
  }}

  /* Footer */
  .footer-note {{
    text-align: center; margin-top: 32px; padding-top: 20px;
    color: var(--text-soft); font-size: 12px; border-top: 1px solid var(--border);
  }}
</style>
</head>
<body>
<div class="container">

  <div class="hero {verdict}">
    <div class="hero-row">
      <div>
        <p class="hero-title">Load Test Verdict</p>
        <h1 class="hero-verdict">{verdict}</h1>
        <div class="hero-meta">{game_name} · variant <strong>{variant}</strong> · {timestamp}</div>
      </div>
      <div class="hero-stats">
        <div class="hero-stat">
          <div class="label">Users</div>
          <div class="value">{users:,}</div>
        </div>
        <div class="hero-stat">
          <div class="label">Duration</div>
          <div class="value">{duration_sec}s</div>
        </div>
        <div class="hero-stat">
          <div class="label">Cores</div>
          <div class="value">{host_cores}</div>
        </div>
      </div>
    </div>
  </div>

  <div class="grid">
    <div class="card">
      <h2>Run Info</h2>
      <table>
        <tr><th>Variant</th><td>{variant}</td></tr>
        <tr><th>Game</th><td>{game_name}</td></tr>
        <tr><th>Users</th><td>{users:,}</td></tr>
        <tr><th>Duration</th><td>{duration_sec}s</td></tr>
        <tr><th>Host cores</th><td>{host_cores}</td></tr>
        <tr><th>Timestamp</th><td>{timestamp}</td></tr>
      </table>
    </div>

    <div class="card">
      <h2>Threshold Check</h2>
      <table>
        <tr><th>PR-4: Mean response</th><td>{mean_response_ms} ms <span class="ceil">(≤ {mean_ms_ceil} ms)</span> {pr4_badge}</td></tr>
        <tr><th>PR-5: Error rate (KO%)</th><td>{http_ko_percent} % <span class="ceil">(≤ {http_ko_ceil} %)</span> {pr5_badge}</td></tr>
        <tr><th>CPU p95</th><td>{cpu_p95} % <span class="ceil">(≤ {cpu_ceil} %, variant={variant})</span> {cpu_badge}</td></tr>
        <tr><th>Mem p95</th><td>{mem_p95} % <span class="ceil">(≤ {mem_ceil} %, variant={variant})</span> {mem_badge}</td></tr>
        <tr><th>PR-3: Health probe failures</th><td>{health_failures} (max consecutive {max_consecutive_failures} <span class="ceil">&lt; {crash_max_consecutive_ceil}</span>) {crash_badge}</td></tr>
        <tr><th>Response time p95</th><td>{p95_response_ms} ms <span class="ceil">(info)</span></td></tr>
      </table>
      <div class="legend">PR-3 / PR-4 / PR-5 thresholds loaded from <code>{sla_config_source}</code>. CPU/Mem ceilings from variant=<code>{variant}</code>.</div>
    </div>
  </div>

  <div class="card" style="margin-bottom:20px">
    <h2>HTTP Requests Summary (from Gatling)</h2>
    <div class="stat-row">
      <div class="stat"><div class="stat-label">Total</div><div class="stat-value">{http_total}</div></div>
      <div class="stat ok"><div class="stat-label">OK</div><div class="stat-value">{http_ok}</div></div>
      <div class="stat bad"><div class="stat-label">KO</div><div class="stat-value">{http_ko}</div></div>
      <div class="stat"><div class="stat-label">Gatling Exit</div><div class="stat-value">{gatling_exit_code}</div></div>
    </div>
  </div>

  <div class="card chart-card" style="margin-bottom:20px">
    <div class="chart-header">
      <h2>CPU % over time</h2>
      <div class="chart-subtitle">{cpu_avg_label}</div>
    </div>
    {cpu_svg}
    <div class="legend">Y-range: {cpu_min}–{cpu_max}% (normalised by {host_cores} cores). Red dashed line = ceiling ({cpu_ceil}%).</div>
  </div>

  <div class="card chart-card" style="margin-bottom:20px">
    <div class="chart-header">
      <h2>Memory % over time</h2>
      <div class="chart-subtitle">{mem_avg_label}</div>
    </div>
    {mem_svg}
    <div class="legend">Y-range: {mem_min}–{mem_max}%. Red dashed line = ceiling ({mem_ceil}%).</div>
  </div>

  <div class="card report-card" id="reportCard">
    <div class="report-header">
      <h2>Gatling HTTP Report (Embedded)</h2>
      <button class="btn-fullscreen" id="btnFullscreen" type="button" aria-label="Open report fullscreen">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>
        <span>Fullscreen</span>
      </button>
      <button class="btn-close" id="btnClose" type="button" aria-label="Exit fullscreen">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    </div>
    <iframe class="report-iframe" src="gatling-report/index.html" loading="lazy"></iframe>
  </div>

  <div class="footer-note">Generated by rgp-game-load-test · {timestamp}</div>

</div>

<script>
  (function() {{
    var card = document.getElementById('reportCard');
    var btnOpen = document.getElementById('btnFullscreen');
    var btnClose = document.getElementById('btnClose');
    function enter() {{
      card.classList.add('fullscreen');
      document.body.classList.add('is-fullscreen');
    }}
    function exit() {{
      card.classList.remove('fullscreen');
      document.body.classList.remove('is-fullscreen');
    }}
    btnOpen.addEventListener('click', enter);
    btnClose.addEventListener('click', exit);
    document.addEventListener('keydown', function(e) {{
      if (e.key === 'Escape' && card.classList.contains('fullscreen')) exit();
    }});
  }})();
</script>
</body>
</html>
"""


def badge(passed: bool) -> str:
    return (
        '<span class="badge ok">PASS</span>'
        if passed
        else '<span class="badge fail">FAIL</span>'
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant-dir", required=True, type=Path)
    args = ap.parse_args()

    vdir = args.variant_dir
    if not vdir.is_dir():
        print(f"[summary] not a directory: {vdir}", file=sys.stderr)
        sys.exit(1)

    verdict_path = vdir / "verdict.json"
    if not verdict_path.exists():
        print(f"[summary] missing verdict.json in {vdir}", file=sys.stderr)
        sys.exit(1)

    with verdict_path.open() as f:
        v = json.load(f)

    cores = v.get("host_cores") or os.cpu_count() or 1
    cpu_series, mem_series = parse_resource(vdir / "resource.csv")
    cpu_stats = stats(cpu_series, cores)
    mem_stats = stats(mem_series, 1)

    cpu_svg = sparkline_svg(
        [c / cores for c in cpu_series], color="#3b82f6", ceil=v.get("cpu_ceil")
    )
    mem_svg = sparkline_svg(mem_series, color="#8b5cf6", ceil=v.get("mem_ceil"))

    cpu_min = min(cpu_series, default=0) / cores if cpu_series else 0
    cpu_max = max(cpu_series, default=0) / cores if cpu_series else 0
    mem_min = min(mem_series, default=0) if mem_series else 0
    mem_max = max(mem_series, default=0) if mem_series else 0

    cpu_avg_label = f"(avg {cpu_stats['avg']}%, max {cpu_stats['max']}%)" if cpu_stats["avg"] is not None else ""
    mem_avg_label = f"(avg {mem_stats['avg']}%, max {mem_stats['max']}%)" if mem_stats["avg"] is not None else ""

    html = HTML_TEMPLATE.format(
        verdict=v.get("verdict", "UNKNOWN"),
        variant=v.get("variant", ""),
        game_name=v.get("game_name") or "—",
        users=v.get("users", 0),
        duration_sec=v.get("duration_sec", 0),
        host_cores=cores,
        timestamp=vdir.name.split("-", 1)[-1] if "-" in vdir.name else "",
        crash_max_consecutive_ceil=v.get("crash_max_consecutive_ceil", 3),
        sla_config_source=v.get("sla_config_source", "defaults"),
        cpu_p95=v.get("cpu_p95", "N/A"),
        mem_p95=v.get("mem_p95", "N/A"),
        cpu_ceil=v.get("cpu_ceil", 0),
        mem_ceil=v.get("mem_ceil", 0),
        cpu_badge=badge(v.get("cpu_pass", False)),
        mem_badge=badge(v.get("mem_pass", False)),
        crash_badge=badge(v.get("crash_pass", False)),
        pr4_badge=badge(v.get("pr4_pass", v.get("http_pass", False))),
        pr5_badge=badge(v.get("pr5_pass", v.get("http_pass", False))),
        mean_response_ms=v.get("mean_response_ms") if v.get("mean_response_ms") is not None else "N/A",
        p95_response_ms=v.get("p95_response_ms") if v.get("p95_response_ms") is not None else "N/A",
        mean_ms_ceil=v.get("mean_ms_ceil", 500),
        http_ko_percent=v.get("http_ko_percent", "N/A"),
        http_ko_ceil=v.get("http_ko_ceil", "N/A"),
        http_total=f"{v.get('http_total', 0):,}" if v.get("http_total") else "N/A",
        http_ok=f"{v.get('http_ok', 0):,}" if v.get("http_ok") is not None else "N/A",
        http_ko=f"{v.get('http_ko', 0):,}" if v.get("http_ko") is not None else "N/A",
        gatling_exit_code=v.get("gatling_exit_code", "N/A"),
        health_failures=v.get("health_failures", 0),
        max_consecutive_failures=v.get("max_consecutive_failures", 0),
        cpu_svg=cpu_svg,
        mem_svg=mem_svg,
        cpu_min=round(cpu_min, 2),
        cpu_max=round(cpu_max, 2),
        mem_min=round(mem_min, 2),
        mem_max=round(mem_max, 2),
        cpu_avg_label=cpu_avg_label,
        mem_avg_label=mem_avg_label,
    )

    out = vdir / "summary.html"
    out.write_text(html)
    print(f"[summary] written: {out}")


if __name__ == "__main__":
    main()
