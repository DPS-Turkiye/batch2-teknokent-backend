# Load tests

A read-only k6 test against production that replays the `/api` calls recorded from real screens, plus the scripts
that keep it safe and turn its output into a report.

Nothing secret lives here. Test logins, the Firebase web key and the recorded screens stay outside the repo in
`~/.config/menton-loadtest/` (`prod.env`, `capture-{mentee,mentor,admin}.json`); run output goes to its `reports/`.

## Run a test

```bash
set -a; . ~/.config/menton-loadtest/prod.env; set +a
RUN=T1-$(date -u +%Y%m%dT%H%M%SZ)
TEST=T1 STEPS=100,250,500 STEP_MINUTES=5 K6_WEB_DASHBOARD=true \
  K6_WEB_DASHBOARD_EXPORT=~/.config/menton-loadtest/reports/$RUN.html \
  k6 run --include-system-env-vars --out json=$HOME/.config/menton-loadtest/reports/$RUN.json \
  scripts/loadtest/menton.js
```

Shapes (`TEST=`): `T0` smoke (3 VUs, 2 min) · `T1` steps (`STEPS`, `STEP_MINUTES`; 1 min ramp + hold each) ·
`T2` soak (`VUS`, `DURATION`) · `T3` rush (`VUS` in 1 min, hold 5) · `T4` one endpoint at a fixed rate
(`T4_TARGET`, `RATE`, `DURATION`).

**Always start both safety watchers next to a run** (k6's own thresholds are cumulative and react late):

```bash
python3 -I scripts/loadtest/watch-prod.py $R/$RUN_ID.json   # p95 > 3 s or errors > 5 %/min
AWS_PROFILE=menton scripts/loadtest/watch-rds.sh            # RDS free memory < 40 MB
```

Both stop k6 with SIGINT, so it still writes its report.

## Read the result

```bash
TEST=T1 STEPS=100,250,500 STEP_MINUTES=5 python3 -I scripts/loadtest/analyze.py \
  $R/$RUN_ID.json --backend-version v1.6.26 [--aws]
```

Pass the same `TEST`/`STEPS`/`STEP_MINUTES` (or `VUS`/`DURATION`) the run used: they define the hold windows.
The probe file `$RUN_ID.probe.txt` next to the run is picked up by itself (`--probe` for another path).

- Per step, the steady hold only: users, requests/s, failures, p50–p99, and the Response Time Law check.
- **Stalls:** any 10 s window where ≥ 20 % of requests waited > 3 s makes its step invalid. Without `--aws` the cause
  comes from k6 alone (if `GET /api/session`, which never reaches the backend, waited too, it's the network path).
  With `--aws` the backend's slow-request log decides. If the probe failed in the same window, it's the network path.
- **Generator health** also makes a step invalid: Mac CPU > 80 % (10 s mean), k6's blocked + connecting time rising
  through the hold, or the probe bad (ping lost or > 100 ms, health failed or > 3 s) in two seconds within 10 s.
  Stalls on already-open connections show up only in the probe, never in k6's connect times (27 Oct 08:06: connect
  time stayed at 0.1 ms while half the requests waited 3–24 s).
- Slowest and most-degraded endpoints, CloudWatch worst values (`--aws`), and a comparison with the last accepted run.
- Saved to `~/menton-perf-history/<date>-<backend version>-<run>/` (`report.md`, `summary.json`); a run is accepted when
  every step is valid, errors ≤ 1 % and nothing regressed. Exit code 0 pass, 1 regression, 2 inconclusive.
- `--aws` only reads (`describe-instances`, `get-metric-statistics`, `filter-log-events`); use a read-only profile.

Check the script: `python3 -I scripts/loadtest/test_analyze.py`.

## Other helpers

- `collect-aws-metrics.sh`: every CloudWatch number for one window into a text file (read-only;
  `START`/`END` in UTC, `OUT_DIR`).
- `f2-cold-start.mjs`: after the app was idle, times a direct backend call vs Next.js vs a full page
  (`node scripts/loadtest/f2-cold-start.mjs ~/.config/menton-loadtest/prod.env`).
- `BACKEND_URL` overrides the backend host in `watch-prod.py` and `f2-cold-start.mjs`.
