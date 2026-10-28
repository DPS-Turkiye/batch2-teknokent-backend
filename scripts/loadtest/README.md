# Load tests

A read-only k6 test against production that replays the `/api` calls recorded from real screens, plus the scripts
that keep it safe and turn its output into a report.

Nothing secret lives here. Test logins, the Firebase web key and the recorded screens stay outside the repo in
`~/.config/menton-loadtest/` (`prod.env`, `capture-{mentee,mentor,admin}.json`); run output goes to its `reports/`.

## Run a test

```bash
set -a; . ~/.config/menton-loadtest/prod.env; set +a
export RUN_ID=T1-$(date -u +%Y%m%dT%H%M%SZ) R=~/.config/menton-loadtest/reports
python3 -I scripts/loadtest/genprobe.py $R/$RUN_ID.probe.txt & PROBE=$!
TEST=T1 STEPS=100,250,500 STEP_MINUTES=5 K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_OPEN=true \
  K6_WEB_DASHBOARD_EXPORT=$R/$RUN_ID.html \
  k6 run --include-system-env-vars --out json=$R/$RUN_ID.json scripts/loadtest/menton.js
kill $PROBE
```

Shapes (`TEST=`): `T0` smoke (3 VUs, 2 min) · `T1` steps (`STEPS`, `STEP_MINUTES`; 1 min ramp + hold each) ·
`T2` soak (`VUS`, `DURATION`) · `T3` rush (`VUS` in 1 min, hold 5) · `T4` one endpoint at a fixed rate
(`T4_TARGET`, `RATE`, `DURATION`) · `T5` breakpoint: screens per second rising from 1 to `T5_MAX` over `DURATION`,
no think time (`MAX_VUS` caps the VUs; the watchers end it).

- **Account pool:** `MENTEE_EMAIL=a@x,b@x` (same for `MENTOR_`/`ADMIN_`), with one shared password or a list in the
  same order (a password can't contain a comma). Each VU keeps one account.
- **Screen time + goodput:** `screen_time{screen}` is the wall time until all of a screen's parallel calls
  answered; `goodput_screens / screens` is the share where every call was < 400 and that time stayed within the
  screen's band (its no-load p90 from `baselines/screens.json`, else `SCREEN_BAND_MS`, default 1000).

- **Warm-up:** every shape starts with 2 min of light load (`WARMUP=2m`, `WARMUP_VUS=5`; `WARMUP=0` turns it off).
  It is its own k6 scenario, so thresholds and `analyze.py` leave it out. A fresh backend (27 Oct: median 3.2 s in
  the first minute after a deploy) or an idle Amplify (~3–4 s cold start) is slow at first, and that is not what a
  step measures.
- **`X-Load-Test: $RUN_ID`** goes on every request to our app (default `menton-k6`; the probe sends
  `$RUN_ID-probe`). Nothing logs it yet: see the proposal in the Phase 2 report.
- **`genprobe.py`** watches the generator once a second: whole-Mac CPU, ping 1.1.1.1, `GET /api/health` (5 s
  timeout), each independent of the others. Start it before k6, stop it after.

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

## Browser journeys

`journeys.js` signs a real Chromium in as each role and walks its read features the way a person does (clicking the
menu; a page with no link to it is loaded directly). Journeys: `mentee`, `mentor`, `admin`, `phone-mentee`,
`phone-mentor` (`JOURNEYS=` picks some). Per feature: `feature_visible{feature,nav}`, the time from the click until
the page's main area is on screen and every `/api` call it started has answered; the browser's Web Vitals per page
(`browser_web_vital_lcp/cls/inp/…`); and a check that fails on any `/api` answer ≥ 400 or a page that never settles.
A detail page with no link to follow (the account has no such data) counts in `journey_gap`, not as a failure.
Errors prod already gives at no load are listed in `KNOWN_API_ERRORS` and counted in `known_api_errors`.

**Read-only:** it only follows links. It never presses a button that saves, sends, books or starts anything, and it
leaves out meeting notes, program threads and phone chat threads, which mark things read or open a live document
(Phase 0 report, W1–W18 and L1–L7). Browser traffic has no `X-Load-Test` header (on a browser it triggers CORS
preflights Firebase and the backend refuse); it shows up as `HeadlessChrome`.

```bash
# baseline: warm-up, then one browser, ITERATIONS rounds of every journey, nothing else running
export RUN_ID=J-baseline-$(date -u +%Y%m%dT%H%M%SZ)
python3 -I scripts/loadtest/genprobe.py $R/$RUN_ID.probe.txt & PROBE=$!
ITERATIONS=5 SCREENSHOT_DIR=$R k6 run --include-system-env-vars --out json=$R/$RUN_ID.json scripts/loadtest/journeys.js
kill $PROBE
python3 -I scripts/loadtest/baseline.py $R/$RUN_ID.json --write   # → baselines/features.json, vitals.json

# hybrid: BROWSER_VUS browsers looping the journeys beside menton.js's TEST shape (watchers as above)
HYBRID=1 TEST=T0 BROWSER_VUS=2 k6 run --include-system-env-vars --out json=$R/$RUN_ID.json scripts/loadtest/journeys.js
```

`SCREENSHOT_DIR` saves a screenshot when a journey fails (test accounts' data only; keep it outside the repo).
Re-take the baseline after a frontend change to caching (e.g. FE #194's 30 s `staleTime` makes revisits faster).

## Other helpers

- `collect-aws-metrics.sh`: every CloudWatch number for one window into a text file (read-only;
  `START`/`END` in UTC, `OUT_DIR`).
- `f2-cold-start.mjs`: after the app was idle, times a direct backend call vs Next.js vs a full page
  (`node scripts/loadtest/f2-cold-start.mjs ~/.config/menton-loadtest/prod.env`).
- `BACKEND_URL` overrides the backend host in `watch-prod.py` and `f2-cold-start.mjs`.
