#!/usr/bin/env python3
"""Watches the load generator (this Mac) while k6 runs: CPU, and the network path, once a second.

Usage: [BACKEND_URL=…] [RUN_ID=…] python3 -I scripts/loadtest/genprobe.py reports/<run>.probe.txt   (stop: kill/Ctrl-C)

Writes one line per sample, `<epoch> <kind> <value>`:
  cpu <percent busy, whole Mac>    from `top`
  ping <ms> | ping lost            1.1.1.1, not AWS: slow here = our own line, not the app
  health <seconds> | health fail   GET /api/health with a 5 s timeout, one new request every second

The checks run independently, so a hanging health call never delays the next ping or CPU sample. analyze.py --probe
reads the file and marks steps where the generator, not the app, was the limit.
"""
import os
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.request

BACKEND = os.environ.get("BACKEND_URL", "https://d2ulpskrb4nc9m.cloudfront.net")
HEADERS = {"X-Load-Test": f"{os.environ.get('RUN_ID', 'menton-k6')}-probe"}
PING_HOST = os.environ.get("PING_HOST", "1.1.1.1")

out = open(sys.argv[1], "a", buffering=1)
lock = threading.Lock()


def write(kind, value, when=None):
    with lock:
        out.write(f"{when or time.time():.3f} {kind} {value}\n")


def cpu(top):
    first = True
    for line in top.stdout:
        m = re.match(r"CPU usage: .*?([\d.]+)% idle", line)
        if m:
            if not first:  # top's first sample averages since boot
                write("cpu", f"{100 - float(m.group(1)):.1f}")
            first = False


def ping(proc):
    for line in proc.stdout:
        m = re.search(r"time=([\d.]+) ms", line)
        if m:
            write("ping", m.group(1))
        elif line.startswith("Request timeout"):
            write("ping", "lost")


def health_once():
    sent = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(f"{BACKEND}/api/health", headers=HEADERS), timeout=5) as r:
            r.read()
        write("health", f"{time.time() - sent:.3f}", sent)
    except Exception:  # noqa: BLE001 - any failure (timeout, reset, 5xx) is a failed probe
        write("health", "fail", sent)


signal.signal(signal.SIGTERM, signal.default_int_handler)  # a plain `kill` stops it like Ctrl-C
children = [subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            for cmd in (["top", "-l", "0", "-s", "1", "-n", "0"], ["ping", "-i", "1", PING_HOST])]
for target, child in zip((cpu, ping), children):
    threading.Thread(target=target, args=(child,), daemon=True).start()
try:
    while True:
        threading.Thread(target=health_once, daemon=True).start()
        time.sleep(1)
except KeyboardInterrupt:
    pass
finally:
    for child in children:  # top and ping would otherwise outlive the probe
        child.terminate()
