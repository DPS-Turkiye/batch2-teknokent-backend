#!/usr/bin/env python3
"""Per-feature baselines from a journeys.js run (k6 --out json): how long each feature takes to show at no load.

Usage: python3 -I scripts/loadtest/baseline.py reports/<run>.json [--probe reports/<run>.probe.txt] [--write]

Reads feature_visible (journeys), screen_time (menton.js screens) and the browser's Web Vitals, leaves out the
warm-up and every point within 10 s of a probe stall (genprobe.py; <run>.probe.txt is picked up by itself), and
prints p50 / p75 / p90 and the spread (p75 - p25) per feature. --write saves them to scripts/loadtest/baselines/:
features.json and vitals.json, and screens.json (the bands menton.js's goodput uses) when the run had screens.
A feature with fewer than 5 samples is printed but marked: the PRD wants 5 runs or more.
"""
import argparse
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # analyze.py's readers; -I leaves the script's folder off the path
from analyze import PROBE_BAD_SAMPLES, parse_time, read_probe  # noqa: E402

MIN_SAMPLES = 5
VITALS = ("lcp", "fcp", "cls", "inp", "ttfb")
WANTED = tuple(f'{{"metric":"{m}","type":"Point"'.encode() for m in
               ("feature_visible", "screen_time", *(f"browser_web_vital_{v}" for v in VITALS)))
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f-]{27}")


def stall_spans(bad):
    """Same rule as analyze.py: PROBE_BAD_SAMPLES bad seconds within 10 s."""
    k = PROBE_BAD_SAMPLES
    return [(bad[i], bad[i + k - 1]) for i in range(len(bad) - k + 1) if bad[i + k - 1] - bad[i] < 10]


def stats(values):
    v = sorted(values)

    def p(q):
        return round(v[min(len(v) - 1, int(len(v) * q))], 1)
    return {"n": len(v), "p50": p(0.5), "p75": p(0.75), "p90": p(0.9), "spread": round(p(0.75) - p(0.25), 1)}


def collect(path, stalls):
    features, screens, vitals = (collections.defaultdict(list) for _ in range(3))
    dropped = 0
    with open(path, "rb") as f:
        for line in f:
            if not line.startswith(WANTED):
                continue
            point = json.loads(line)
            data, tags = point["data"], point["data"].get("tags") or {}
            if tags.get("scenario") == "warmup":
                continue
            t = parse_time(data["time"])
            if any(a - 10 <= t <= b + 10 for a, b in stalls):
                dropped += 1
                continue
            metric = point["metric"]
            if metric == "feature_visible":
                features[tags["feature"]].append(data["value"])
            elif metric == "screen_time":
                screens[tags["screen"]].append(data["value"])
            else:
                page = UUID.sub("[id]", re.sub(r"^https?://[^/]+", "", tags.get("url", "?")).split("?")[0])
                vitals[(page, metric.rsplit("_", 1)[1])].append(data["value"])
    return features, screens, vitals, dropped


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("run_json")
    p.add_argument("--probe")
    p.add_argument("--write", action="store_true", help="save to scripts/loadtest/baselines/")
    p.add_argument("--out-dir", default=os.path.join(HERE, "baselines"))
    a = p.parse_args()

    probe_path = a.probe or a.run_json.removesuffix(".json") + ".probe.txt"
    has_probe = os.path.isfile(probe_path)
    stalls = stall_spans(read_probe(probe_path)["bad"]) if has_probe else []
    features, screens, vitals, dropped = collect(a.run_json, stalls)

    feature_stats = {k: stats(v) for k, v in sorted(features.items())}
    vital_stats = collections.defaultdict(dict)
    for (page, vital), values in sorted(vitals.items()):
        vital_stats[page][vital] = stats(values)
    screen_stats = {k: stats(v) for k, v in sorted(screens.items())}

    print(f"Baseline from {os.path.basename(a.run_json)}: {len(feature_stats)} features, {len(screen_stats)} screens; "
          f"{dropped} point(s) dropped near {len(stalls)} probe stall(s)"
          + ("" if has_probe else " (no probe file: nothing checked)") + ".\n")
    print("| Feature | n | p50 | p75 | p90 | spread |\n|---|---|---|---|---|---|")
    for name, s in feature_stats.items():
        flag = "" if s["n"] >= MIN_SAMPLES else f" (< {MIN_SAMPLES})"
        print(f"| `{name}` | {s['n']}{flag} | {s['p50']:.0f} | {s['p75']:.0f} | {s['p90']:.0f} | {s['spread']:.0f} |")
    if vital_stats:
        print("\n| Page | LCP p75 | CLS p75 | INP p75 |\n|---|---|---|---|")
        for page, v in vital_stats.items():
            cell = {k: (f"{v[k]['p75']:g}" if k in v else "—") for k in ("lcp", "cls", "inp")}
            print(f"| `{page}` | {cell['lcp']} | {cell['cls']} | {cell['inp']} |")
    if screen_stats:
        print("\n| Screen | n | p50 | p90 |\n|---|---|---|---|")
        for name, s in screen_stats.items():
            print(f"| `{name}` | {s['n']} | {s['p50']:.0f} | {s['p90']:.0f} |")

    if a.write:
        os.makedirs(a.out_dir, exist_ok=True)
        for name, body in (("features.json", feature_stats), ("vitals.json", vital_stats),
                           ("screens.json", screen_stats)):
            if body:
                with open(os.path.join(a.out_dir, name), "w") as f:
                    json.dump(body, f, indent=1, sort_keys=True)
                    f.write("\n")
        print(f"\nSaved to {a.out_dir}", file=sys.stderr)


if __name__ == "__main__":
    main()
