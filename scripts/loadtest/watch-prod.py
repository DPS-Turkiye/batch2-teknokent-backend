# Follows a running k6 JSON output and prints one line per finished minute: VUs, requests, median/p95,
# failures, backend health. Stops k6 (SIGINT, so it still writes its report) the first minute that breaks
# the safety rule: p95 > 3 s or errors > 5 %. k6's own thresholds are cumulative and react late at high load.
# Usage: [BACKEND_URL=…] python3 -I scripts/loadtest/watch-prod.py reports/<name>.json [HH:MM to resume from, UTC]
import collections
import datetime
import json
import os
import subprocess
import sys
import time
import urllib.request

path = sys.argv[1]
HEALTH = os.environ.get("BACKEND_URL", "https://d2ulpskrb4nc9m.cloudfront.net") + "/api/health"
mins = collections.defaultdict(lambda: {"d": [], "fail": 0, "vus": 0})
printed = set()


def health():
    try:
        with urllib.request.urlopen(HEALTH, timeout=5) as r:
            return json.load(r).get("status", "?")
    except Exception as e:  # noqa: BLE001 - any failure is the answer here
        return f"DOWN({type(e).__name__})"


def report(minute):
    m = mins[minute]
    ds = sorted(m["d"])
    if not ds:
        return False
    med, p95 = ds[len(ds) // 2], ds[int(len(ds) * 0.95)]
    fail = m["fail"] / len(ds)
    # The first minute is left out, as in k6's delayAbortEval: an Amplify cold start alone takes ~4 s.
    bad = len(printed) > 1 and len(ds) >= 50 and (p95 > 3000 or fail > 0.05)
    print(f"{minute}Z vus={m['vus']} req={len(ds)} med={med:.0f} p95={p95:.0f} fail={fail:.1%} health={health()}"
          + (" SAFETY-STOP" if bad else ""), flush=True)
    return bad


with open(path) as f:
    buf = ""
    while True:
        chunk = f.read()
        if not chunk:
            if subprocess.run(["pgrep", "-f", "k6 run"], capture_output=True).returncode != 0:
                for minute in sorted(set(mins) - printed):
                    report(minute)
                print("k6 exited", flush=True)
                break
            time.sleep(2)
            continue
        buf += chunk
        *lines, buf = buf.split("\n")
        for line in lines:
            try:
                p = json.loads(line)
            except ValueError:
                continue
            if p.get("type") != "Point":
                continue
            d, tags = p["data"], p["data"].get("tags", {})
            minute = datetime.datetime.fromisoformat(d["time"]).astimezone(datetime.timezone.utc).strftime("%H:%M")
            if p["metric"] == "vus":
                mins[minute]["vus"] = max(mins[minute]["vus"], int(d["value"]))
            elif tags.get("scenario", "setup") == "setup":
                continue
            elif p["metric"] == "http_req_duration":
                mins[minute]["d"].append(d["value"])
            elif p["metric"] == "http_req_failed" and d["value"]:
                mins[minute]["fail"] += 1
        # A minute is finished once points from two minutes later arrive (k6 writes in batches).
        done = sorted(mins)[:-2]
        for minute in done:
            if minute not in printed:
                printed.add(minute)
                # Re-armed watch: minutes before argv[2] (HH:MM) were already reported.
                if len(sys.argv) > 2 and minute < sys.argv[2]:
                    continue
                if report(minute):
                    subprocess.run(["pkill", "-INT", "-f", "k6 run"])
