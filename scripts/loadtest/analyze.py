#!/usr/bin/env python3
"""Turn one menton.js run (k6 --out json) into a report: per-step numbers, stalls, slow endpoints, AWS, regressions.

Usage:
  TEST=T1 STEPS=600,700,800,1000 STEP_MINUTES=5 python3 -I scripts/loadtest/analyze.py reports/<run>.json \
      [--backend-version v1.6.26] [--aws] [--history ~/menton-perf-history] [--no-history]

The test shape is read from the same variables menton.js uses (TEST, STEPS, STEP_MINUTES, VUS, DURATION), or from
the matching flags. Only the steady hold of each step is measured; ramps are cut. The file is streamed, so 1 GB runs
are fine. --aws adds read-only CloudWatch metrics and the backend's slow-request log (needs AWS_PROFILE).

Exit code: 0 pass, 1 regression against the last accepted run, 2 inconclusive (a step had a stall).
"""
import argparse
import bisect
import collections
import datetime as dt
import json
import math
import os
import subprocess
import sys
from array import array

STALL_WAIT_MS = 3000  # a request "waits" when its time to first byte is above this
STALL_SHARE = 0.20  # ... and a 10 s window is a stall when this share of its requests wait
STALL_MIN_REQUESTS = 20
# Never reaches the backend: Next.js answers it from the cookie. Slow here = not the app.
NEXT_ONLY = {"/api/session"}
# Until Phase 3 measures the run-to-run noise, a step regresses when it moves more than this.
P95_BAND = (0.25, 50)  # 25 % and at least 50 ms
P50_BAND = (0.15, 20)
ERROR_BAND = 0.005  # half a percentage point
ACCEPT_MAX_ERRORS = 0.01

WANTED = ("http_req_duration", "http_req_waiting", "http_req_failed", "http_req_receiving",
          "vus", "iterations", "iteration_duration")
PREFIXES = tuple(f'{{"metric":"{m}","type":"Point"'.encode() for m in WANTED)

REGION = "eu-central-1"
RDS_ID = "teknokent-postgres"
REDIS_ID = "teknokent-redis-001"
LOG_GROUP = "/batch2/teknokent/app"


def seconds(text):
    units = {"s": 1, "m": 60, "h": 3600}
    return int(float(text[:-1]) * units[text[-1]]) if text[-1] in units else int(text)


def holds(test, steps, step_minutes, vus, duration):
    """(target VUs, hold start, hold end) in seconds from the scenario start, as menton.js builds its stages."""
    if test == "T0":
        return [(3, 0, 120)]
    if test == "T1":
        out, t = [], 0
        for target in steps:
            t += 60
            out.append((target, t, t + step_minutes * 60))
            t += step_minutes * 60
        return out
    if test == "T2":
        return [(vus, 120, 120 + duration)]
    if test == "T3":
        return [(vus, 60, 360)]
    if test == "T4":
        return [(None, 0, duration)]
    raise SystemExit(f"unknown TEST {test}")


def pct(sorted_values, p):
    if not sorted_values:
        return None
    return sorted_values[min(len(sorted_values) - 1, int(len(sorted_values) * p))]


class Step:
    def __init__(self, target, start, end):
        self.target, self.start, self.end = target, start, end
        self.durations = array("d")
        self.by_name = collections.defaultdict(lambda: array("d"))
        self.failed = 0
        self.iterations = 0
        self.iteration_ms = 0.0
        self.vus = []
        self.stalls = []


def parse_time(text):
    return dt.datetime.fromisoformat(text).timestamp()


def scan(path):
    """One pass over the file: every scenario request and the VU / iteration samples, in time order or not."""
    reqs = []  # (time, duration, key); the other per-request metrics join on the same key
    waiting, receiving, failed = {}, {}, collections.Counter()
    vus, iters = [], []
    with open(path, "rb") as f:
        for line in f:
            if not line.startswith(PREFIXES):
                continue
            point = json.loads(line)
            data, metric = point["data"], point["metric"]
            tags = data.get("tags") or {}
            t = parse_time(data["time"])
            if metric == "vus":
                vus.append((t, data["value"]))
                continue
            if "scenario" not in tags:  # setup() sign-ins
                continue
            if metric == "iterations":
                iters.append((t, None))
            elif metric == "iteration_duration":
                iters.append((t, data["value"]))
            else:
                key = (data["time"], tags.get("name", "?"), tags.get("status"))
                if metric == "http_req_duration":
                    reqs.append((t, data["value"], key))
                elif metric == "http_req_waiting":
                    waiting[key] = data["value"]
                elif metric == "http_req_receiving":
                    receiving[key] = data["value"]
                elif metric == "http_req_failed" and data["value"]:
                    failed[key] += 1
    return reqs, waiting, receiving, failed, vus, iters


def analyse(path, shape):
    reqs, waiting, receiving, failed, vus, iters = scan(path)
    if not reqs:
        raise SystemExit("no scenario requests in this file")
    reqs.sort(key=lambda r: r[0])
    t0 = math.floor(reqs[0][0])  # the first request is sent within a second of the scenario start
    steps = [Step(target, t0 + a, t0 + b) for target, a, b in shape]
    starts = [s.start for s in steps]
    buckets = collections.defaultdict(lambda: {"n": 0, "slow": 0, "next_n": 0, "next_slow": 0, "max_wait": 0.0,
                                               "slow_names": set(), "names": set(), "slow_receiving": 0})

    def step_at(t):
        i = bisect.bisect_right(starts, t) - 1
        return steps[i] if i >= 0 and t < steps[i].end else None

    for t, duration, key in reqs:
        name = key[1]
        wait = waiting.get(key, duration)
        b = buckets[int(t // 10 * 10)]
        b["n"] += 1
        b["names"].add(name)
        b["max_wait"] = max(b["max_wait"], wait)
        slow = wait > STALL_WAIT_MS
        if slow:
            b["slow"] += 1
            b["slow_names"].add(name)
            if receiving.get(key, 0) > 1000:
                b["slow_receiving"] += 1
        if name in NEXT_ONLY:
            b["next_n"] += 1
            b["next_slow"] += slow
        s = step_at(t)
        if s:
            s.durations.append(duration)
            s.by_name[name].append(duration)
            s.failed += min(failed.get(key, 0), 1)
    for t, value in vus:
        s = step_at(t)
        if s:
            s.vus.append(value)
    for t, value in iters:
        s = step_at(t)
        if s:
            if value is None:
                s.iterations += 1
            else:
                s.iteration_ms += value

    stalls = []
    for start in sorted(buckets):
        b = buckets[start]
        if b["n"] >= STALL_MIN_REQUESTS and b["slow"] / b["n"] >= STALL_SHARE:
            stall = {"start": start, "end": start + 10, "requests": b["n"], "slow_share": b["slow"] / b["n"],
                     "max_wait_ms": b["max_wait"], "endpoints_slow": len(b["slow_names"]),
                     "endpoints": len(b["names"]), "next_only_slow": b["next_slow"], "next_only": b["next_n"],
                     "slow_receiving": b["slow_receiving"]}
            stall["cause"], stall["evidence"] = classify(stall)
            stalls.append(stall)
            s = step_at(start) or step_at(start + 9.999)
            if s:
                s.stalls.append(stall)
    return steps, merge_stalls(stalls), reqs[0][0], reqs[-1][0]


def classify(stall):
    """Where the wait was, from k6 data alone. --aws replaces this with the backend's own slow-request log."""
    if stall["next_only"] and stall["next_only_slow"] / stall["next_only"] >= STALL_SHARE:
        return "network path", "requests that never reach the backend (GET /api/session) waited too"
    if stall["endpoints_slow"] / max(stall["endpoints"], 1) >= 0.8:
        return "network path (likely)", "almost every endpoint waited at once"
    return "app (likely)", "only some endpoints waited"


def merge_stalls(stalls):
    """Adjacent 10 s windows are one stall."""
    merged = []
    for s in stalls:
        if merged and s["start"] == merged[-1]["end"]:
            m = merged[-1]
            m["end"] = s["end"]
            m["requests"] += s["requests"]
            m["max_wait_ms"] = max(m["max_wait_ms"], s["max_wait_ms"])
            m["slow_share"] = max(m["slow_share"], s["slow_share"])
            m["next_only_slow"] += s["next_only_slow"]
            m["next_only"] += s["next_only"]
            m["endpoints_slow"] = max(m["endpoints_slow"], s["endpoints_slow"])
            m["endpoints"] = max(m["endpoints"], s["endpoints"])
            if s["cause"] == "network path" or (m["cause"] != "network path" and s["cause"].startswith("network")):
                m["cause"], m["evidence"] = s["cause"], s["evidence"]
        else:
            merged.append(dict(s))
    return merged


def step_summary(s):
    d = sorted(s.durations)
    window = s.end - s.start
    n = len(d)
    mean_iter_s = (s.iteration_ms / s.iterations / 1000) if s.iterations else None
    per_iter = n / s.iterations if s.iterations else None
    target = s.target or (round(sum(s.vus) / len(s.vus)) if s.vus else None)
    expected = (target * per_iter / mean_iter_s) if (target and per_iter and mean_iter_s) else None
    return {
        "vus": target,
        "vus_seen": [min(s.vus), max(s.vus)] if s.vus else None,
        "window_utc": [iso(s.start), iso(s.end)],
        "requests": n,
        "req_per_s": n / window,
        "ok_req_per_s": (n - s.failed) / window,
        "failed": s.failed,
        "error_rate": s.failed / n if n else 0,
        "p50": pct(d, 0.50), "p75": pct(d, 0.75), "p90": pct(d, 0.90), "p95": pct(d, 0.95), "p99": pct(d, 0.99),
        "max": d[-1] if d else None,
        "iterations": s.iterations,
        "mean_iteration_s": mean_iter_s,
        "expected_req_per_s": expected,
        "reached": n > 0,
        "valid": n > 0 and not s.stalls,
        "stalls": [iso(x["start"]) for x in s.stalls],
    }


def endpoint_tables(steps, summaries):
    valid = [(s, x) for s, x in zip(steps, summaries) if x["valid"] and x["requests"]]
    if not valid:
        return [], []

    def stats(values):
        v = sorted(values)
        return {"count": len(v), "p50": pct(v, 0.5), "p95": pct(v, 0.95)}

    last_step, last = valid[-1]
    slowest = sorted(((name, stats(v)) for name, v in last_step.by_name.items() if len(v) >= 50),
                     key=lambda kv: -kv[1]["p95"])[:8]
    degrading = []
    if len(valid) > 1:
        first_step = valid[0][0]
        for name, v in last_step.by_name.items():
            before = first_step.by_name.get(name)
            if before and len(before) >= 50 and len(v) >= 50:
                a, b = stats(before), stats(v)
                degrading.append((name, a, b, b["p95"] / max(a["p95"], 1)))
        degrading.sort(key=lambda r: -r[3])
    return [(name, last["vus"], st) for name, st in slowest], degrading[:8]


def iso(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def aws(args):
    out = subprocess.run(["aws", *args, "--region", REGION, "--output", "json"], capture_output=True, text=True,
                         timeout=60)
    if out.returncode:
        raise RuntimeError(out.stderr.strip()[:200])
    return json.loads(out.stdout or "null")


def cloudwatch(start, end):
    """Read-only: get-metric-statistics per minute for the run window, and the instance id from describe-instances."""
    rows = []
    instances = aws(["ec2", "describe-instances", "--filters", "Name=tag:Team,Values=teknokent",
                     "Name=instance-state-name,Values=running",
                     "--query", "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name']|[0].Value]"])
    app = next((i for i, name in instances if "vexa" not in (name or "").lower()), None)
    series = [("EC2 CPU %", "AWS/EC2", "CPUUtilization", "InstanceId", app, "Maximum"),
              ("RDS CPU %", "AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", RDS_ID, "Maximum"),
              ("RDS connections", "AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", RDS_ID, "Maximum"),
              ("RDS free memory MB", "AWS/RDS", "FreeableMemory", "DBInstanceIdentifier", RDS_ID, "Minimum"),
              ("Redis CPU %", "AWS/ElastiCache", "EngineCPUUtilization", "CacheClusterId", REDIS_ID, "Maximum")]
    for label, namespace, metric, dim, value, stat in series:
        if not value:
            continue
        data = aws(["cloudwatch", "get-metric-statistics", "--namespace", namespace, "--metric-name", metric,
                    "--dimensions", f"Name={dim},Value={value}", "--start-time", iso(start), "--end-time", iso(end),
                    "--period", "60", "--statistics", stat])
        points = [p[stat] / (1048576 if "MB" in label else 1) for p in data.get("Datapoints", [])]
        if points:
            worst = min(points) if stat == "Minimum" else max(points)
            rows.append({"metric": label, "worst": round(worst, 1), "stat": stat})
    return rows


def slow_request_lines(start, end):
    data = aws(["logs", "filter-log-events", "--log-group-name", LOG_GROUP, "--start-time", str(int(start * 1000)),
                "--end-time", str(int(end * 1000)), "--filter-pattern", '"Slow request"'])
    return len(data.get("events", []))


def previous_accepted(history, test, before, run_id):
    best = None
    if not os.path.isdir(history):
        return None
    for entry in os.listdir(history):
        path = os.path.join(history, entry, "summary.json")
        if entry == run_id or not os.path.isfile(path):
            continue
        with open(path) as f:
            s = json.load(f)
        if s.get("test") == test and s.get("accepted") and s["started_utc"] < before:
            if best is None or s["started_utc"] > best["started_utc"]:
                best = s
    return best


def compare(steps, previous):
    findings = []
    if not previous:
        return findings
    old = {s["vus"]: s for s in previous["steps"] if s["valid"]}
    for s in steps:
        o = old.get(s["vus"])
        if not s["valid"] or not o or not s["requests"]:
            continue
        for key, (rel, absolute) in (("p95", P95_BAND), ("p50", P50_BAND)):
            if s[key] - o[key] > max(o[key] * rel, absolute):
                findings.append(f"{s['vus']} VUs: {key} {o[key]:.0f} → {s[key]:.0f} ms")
        if s["error_rate"] - o["error_rate"] > ERROR_BAND:
            findings.append(f"{s['vus']} VUs: errors {o['error_rate']:.2%} → {s['error_rate']:.2%}")
    return findings


def ms(v):
    return "—" if v is None else f"{v:,.0f}"


def render(run, steps, stalls, slowest, degrading, cw, previous, regressions):
    out = [f"# Load test {run['name']}", "",
           f"Test **{run['test']}**, backend **{run['backend_version']}**, {run['started_utc']} → {run['ended_utc']} "
           f"(times in UTC; the file's {run['source_offset']} offset converted).", ""]
    reached = [s for s in steps if s["reached"]]
    valid = [s for s in reached if s["valid"]]
    verdict = {"pass": "**Pass**", "fail": "**Regression**", "inconclusive": "**Inconclusive**"}[run["status"]]
    out += [f"{verdict}: {len(valid)} of {len(reached)} steps valid"
            + (f" (run ended before {len(steps) - len(reached)} more)" if len(reached) < len(steps) else "")
            + (f"; {len(regressions)} regression(s) against `{previous['run']}`" if previous else
               "; no earlier accepted run to compare with") + ".", ""]
    out += ["## Steps (steady hold only)", "",
            "| VUs | Hold (UTC) | Requests | Req/s | Failed | p50 | p75 | p90 | p95 | p99 | Max | Valid |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for s in steps:
        hold = f"{s['window_utc'][0][11:16]}–{s['window_utc'][1][11:16]}"
        out.append(f"| {s['vus']} | {hold} | {s['requests']:,} | {s['req_per_s']:.0f} | {s['failed']} | "
                   f"{ms(s['p50'])} | {ms(s['p75'])} | {ms(s['p90'])} | {ms(s['p95'])} | {ms(s['p99'])} | "
                   f"{ms(s['max'])} | {'yes' if s['valid'] else 'no (stall)' if s['reached'] else 'not reached'} |")
    out += ["", "## Response Time Law", "",
            "N users each loop: open a screen (its calls in parallel), then think. R + Z = one loop "
            "(mean iteration); the think time Z is almost all of it (a screen's calls take well under a second). "
            "Expected X = N × requests per loop / (R + Z). Measured well below expected = users were stuck "
            "(slow responses or a busy generator).", "",
            "| VUs | R + Z (s) | Requests per loop | Expected req/s | Measured req/s | Measured / expected |",
            "|---|---|---|---|---|---|"]
    for s in steps:
        if s["expected_req_per_s"]:
            out.append(f"| {s['vus']} | {s['mean_iteration_s']:.1f} | {s['requests'] / s['iterations']:.1f} | "
                       f"{s['expected_req_per_s']:.0f} | {s['req_per_s']:.0f} | "
                       f"{s['req_per_s'] / s['expected_req_per_s']:.0%} |")
    out += ["", "## Stalls", ""]
    if not stalls:
        out.append(f"None: no 10 s window had ≥ {STALL_SHARE:.0%} of requests waiting > {STALL_WAIT_MS / 1000:.0f} s.")
    for st in stalls:
        out.append(f"- **{iso(st['start'])[11:19]}–{iso(st['end'])[11:19]}Z**: up to {st['slow_share']:.0%} of "
                   f"requests waited > {STALL_WAIT_MS / 1000:.0f} s (max {st['max_wait_ms'] / 1000:.1f} s), "
                   f"{st['endpoints_slow']} of {st['endpoints']} endpoints → **{st['cause']}** ({st['evidence']}).")
    out += ["", "## Slowest endpoints (highest valid step)", ""]
    if slowest:
        out += ["| Endpoint | VUs | Calls | p50 | p95 |", "|---|---|---|---|---|"]
        out += [f"| `{name}` | {vus} | {st['count']:,} | {ms(st['p50'])} | {ms(st['p95'])} |"
                for name, vus, st in slowest]
    else:
        out.append("No valid step to rank them in.")
    if degrading:
        out += ["", "## Most degraded endpoints (first → highest valid step, p95)", "",
                "| Endpoint | p95 before | p95 after | × |", "|---|---|---|---|"]
        out += [f"| `{n}` | {ms(a['p95'])} | {ms(b['p95'])} | {r:.1f} |" for n, a, b, r in degrading]
    out += ["", "## AWS (CloudWatch, read-only)", ""]
    if cw is None:
        out.append("Not pulled (run with `--aws` and `AWS_PROFILE`).")
    elif isinstance(cw, str):
        out.append(f"Could not pull: {cw}")
    else:
        out += ["| Metric | Worst in run window |", "|---|---|"]
        out += [f"| {r['metric']} | {r['worst']} ({r['stat']}) |" for r in cw]
    out += ["", "## Against the last accepted run", ""]
    if not previous:
        out.append("No earlier accepted run of this test in the history.")
    else:
        out.append(f"Compared with `{previous['run']}` (backend {previous['backend_version']}). Band: p95 "
                   f"+{P95_BAND[0]:.0%} and ≥ {P95_BAND[1]} ms, p50 +{P50_BAND[0]:.0%} and ≥ {P50_BAND[1]} ms, "
                   f"errors +{ERROR_BAND:.1%} (defaults until baselines measure the noise).")
        out += [f"- {r}" for r in regressions] or ["- No step moved outside the band."]
    return "\n".join(out) + "\n"


def main():
    env = os.environ
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("run_json")
    p.add_argument("--test", default=env.get("TEST", "T0"))
    p.add_argument("--steps", default=env.get("STEPS", "25,50,100,250,500"))
    p.add_argument("--step-minutes", type=float, default=float(env.get("STEP_MINUTES", "10")))
    p.add_argument("--vus", type=int, default=int(env.get("VUS", "0")) or None)
    p.add_argument("--duration", default=env.get("DURATION"))
    p.add_argument("--backend-version", default=env.get("BACKEND_VERSION", "unknown"))
    p.add_argument("--history", default=env.get("PERF_HISTORY", os.path.expanduser("~/menton-perf-history")))
    p.add_argument("--no-history", action="store_true", help="don't save this run or compare it")
    p.add_argument("--aws", action="store_true", help="read-only CloudWatch metrics + slow-request log")
    a = p.parse_args()

    vus = a.vus or {"T2": 100, "T3": 300}.get(a.test)
    duration = seconds(a.duration or {"T2": "60m", "T4": "5m"}.get(a.test, "0s"))
    shape = holds(a.test, [int(x) for x in a.steps.split(",")], a.step_minutes, vus, duration)
    steps, stalls, first, last = analyse(a.run_json, shape)
    summaries = [step_summary(s) for s in steps]
    slowest, degrading = endpoint_tables(steps, summaries)

    cw = None
    if a.aws:
        try:
            cw = cloudwatch(first, last)
            for st in stalls:
                lines = slow_request_lines(st["start"] - 5, st["end"] + 5)
                st["cause"] = "app" if lines else "network path"
                st["evidence"] = f"{lines} slow-request line(s) in the backend log for this window"
        except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as error:
            cw = str(error)

    name = os.path.basename(a.run_json).removesuffix(".json")
    with open(a.run_json, "rb") as f:  # the offset of the generator's clock, to say what was converted
        offset = next((json.loads(l)["data"]["time"][-6:] for l in f if l.startswith(PREFIXES)), "?")
    run = {"run": name, "name": name, "test": a.test, "backend_version": a.backend_version,
           "started_utc": iso(first), "ended_utc": iso(last), "source_offset": offset, "steps": summaries,
           "stalls": [{**s, "start": iso(s["start"]), "end": iso(s["end"])} for s in stalls]}
    run_id = f"{iso(first)[:10]}-{a.backend_version}-{name}"
    previous = None if a.no_history else previous_accepted(a.history, a.test, run["started_utc"], run_id)
    regressions = compare(summaries, previous)
    reached = [s for s in summaries if s["reached"]]
    valid = bool(reached) and all(s["valid"] for s in reached)
    run["status"] = "inconclusive" if not valid else ("fail" if regressions else "pass")
    run["regressions"] = regressions
    run["accepted"] = valid and not regressions and len(reached) == len(summaries) and all(
        s["error_rate"] <= ACCEPT_MAX_ERRORS for s in reached)
    report = render(run, summaries, stalls, slowest, degrading, cw, previous, regressions)

    print(report)
    if not a.no_history:
        folder = os.path.join(a.history, run_id)
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "report.md"), "w") as f:
            f.write(report)
        with open(os.path.join(folder, "summary.json"), "w") as f:
            json.dump(run, f, indent=1)
        print(f"Saved to {folder}", file=sys.stderr)
    if env.get("GITHUB_STEP_SUMMARY"):
        with open(env["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(report)
    sys.exit({"pass": 0, "fail": 1, "inconclusive": 2}[run["status"]])


if __name__ == "__main__":
    main()
