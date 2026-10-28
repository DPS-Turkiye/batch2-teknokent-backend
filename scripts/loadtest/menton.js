// Phase 3c load test: replays the /api calls real menton.com.tr screens make, read-only.
//
// Each virtual user is one person online. It signs in as one of three test accounts (shared by all
// users, logged in once in setup), opens screens recorded from production, and on messaging screens
// keeps polling like the open tab would. Only GET requests: nothing is written, sent or charged.
//
// Run (env and recorded screens live outside the repo; with the probe and watchers, see README.md):
//   set -a; . ~/.config/menton-loadtest/prod.env; set +a
//   RUN_ID=T0-test TEST=T0 K6_WEB_DASHBOARD=true k6 run --include-system-env-vars scripts/loadtest/menton.js
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL;
const FIREBASE_API_KEY = __ENV.FIREBASE_API_KEY;
const TEST = __ENV.TEST || "T0";
// Sent on every request to our app, so test traffic can be told apart wherever it gets logged.
const LOAD_TEST_HEADER = { "X-Load-Test": __ENV.RUN_ID || "menton-k6" };
// Light load before the measured test: a fresh JVM, an idle Amplify and cold connections are slow for the first
// minute or two, and that is not what a step measures. Its own scenario, so thresholds and analyze.py skip it.
const WARMUP = __ENV.WARMUP || "2m";
const WARMUP_VUS = Number(__ENV.WARMUP_VUS || 5);
const POLL_SECONDS = 15;
const ROLE_WEIGHTS = [
  ["mentee", 0.8],
  ["mentor", 0.15],
  ["admin", 0.05],
];
// A pool per role: MENTEE_EMAIL=a@x,b@x with one shared MENTEE_PASSWORD or a list in the same order. Each VU
// sticks to one account, so the backend sees N people, not one person with N tabs.
const ACCOUNTS = Object.fromEntries(
  ["mentee", "mentor", "admin"].map((role) => {
    const emails = (__ENV[`${role.toUpperCase()}_EMAIL`] || "").split(",");
    const passwords = (__ENV[`${role.toUpperCase()}_PASSWORD`] || "").split(",");
    return [role, emails.map((email, i) => [email.trim(), (passwords[i] ?? passwords[0]).trim()])];
  }),
);

// Wall time until every call a screen opens with has answered (they run in parallel, as in the browser), and
// goodput: screens where every call was < 400 and that time stayed within the screen's band. The band is the
// screen's no-load p90 from baselines/screens.json (baseline.py), else SCREEN_BAND_MS.
const screenTime = new Trend("screen_time", true);
const screensOpened = new Counter("screens");
const screensGood = new Counter("goodput_screens");
const DEFAULT_BAND_MS = Number(__ENV.SCREEN_BAND_MS || 1000);
const SCREEN_BANDS = loadBands();

function loadBands() {
  try {
    return JSON.parse(open("./baselines/screens.json"));
  } catch {
    return {};
  }
}

// A capture lists, per screen, every /api call the page made with the second it started. Calls in the
// first seconds are the page load; calls later on are its polling.
const SCREENS = loadScreens(["mentee", "mentor", "admin"]);

function loadScreens(roles) {
  const screens = {};
  for (const role of roles) {
    const capture = JSON.parse(open(`${__ENV.CAPTURE_DIR}/capture-${role}.json`));
    screens[role] = Object.entries(capture).map(([key, calls]) => ({
      name: key,
      load: unique(calls.filter((c) => c.t < 10).map((c) => c.url)),
      poll: unique(calls.filter((c) => c.t >= 10).map((c) => c.url)),
    }));
  }
  return screens;
}

function unique(urls) {
  return [...new Set(urls)];
}

const T4_TARGETS = {
  requests: ["/api/mentor-requests"],
  conversations: ["/api/coordinator-conversations"],
  announcements: ["/api/program-announcements"],
  home: screen("mentee", "mentee:/dashboard").load,
  calendar: screen("mentee", "mentee:/calendar").load,
};

function screen(role, name) {
  return SCREENS[role].find((s) => s.name === name);
}

const T4_TARGET = T4_TARGETS[__ENV.T4_TARGET || "requests"];

const steps = (__ENV.STEPS || "25,50,100,250,500").split(",").map(Number);
const stepMinutes = __ENV.STEP_MINUTES || "10";
const SCENARIOS = {
  T0: { executor: "constant-vus", vus: 3, duration: "2m" },
  T1: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      ...steps.flatMap((target) => [
        { target, duration: "1m" },
        { target, duration: `${stepMinutes}m` },
      ]),
      { target: 0, duration: "1m" },
    ],
    gracefulRampDown: "30s",
  },
  // Ramped in: 50 users opening heavy screens in the same second is T3's job, not a soak.
  T2: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { target: Number(__ENV.VUS || 100), duration: "2m" },
      { target: Number(__ENV.VUS || 100), duration: __ENV.DURATION || "60m" },
    ],
  },
  T3: {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { target: Number(__ENV.VUS || 300), duration: "1m" },
      { target: Number(__ENV.VUS || 300), duration: "5m" },
      { target: 0, duration: "1m" },
    ],
  },
  // One heavy target at a fixed rate, for before/after comparisons of a single fix. RATE counts
  // requests, not screens: one iteration fires every call of the target, so a screen of N calls
  // starts RATE iterations every N seconds.
  T4: {
    executor: "constant-arrival-rate",
    exec: "heavy",
    rate: Number(__ENV.RATE || 5),
    timeUnit: `${T4_TARGET.length}s`,
    duration: __ENV.DURATION || "5m",
    preAllocatedVUs: 50,
    maxVUs: 200,
  },
  // Breakpoint: screens per second rising with no think time until something gives. The watchers stop it.
  T5: {
    executor: "ramping-arrival-rate",
    exec: "rush",
    startRate: 1,
    timeUnit: "1s",
    stages: [{ target: Number(__ENV.T5_MAX || 50), duration: __ENV.DURATION || "10m" }],
    preAllocatedVUs: 50,
    maxVUs: Number(__ENV.MAX_VUS || 1000),
  },
};

const warm = !/^0[a-z]*$/.test(WARMUP);

export const options = {
  scenarios: {
    // gracefulStop 0: warm-up users stop when the test starts, not after their screen's polling (up to 3 min).
    ...(warm && { warmup: { executor: "constant-vus", vus: WARMUP_VUS, duration: WARMUP, gracefulStop: "0s" } }),
    [TEST]: { ...SCENARIOS[TEST], startTime: warm ? WARMUP : "0s" },
  },
  // Scoped to the scenario, so the one-off sign-ins in setup() don't count. Stops the run before real
  // users feel it.
  thresholds: {
    [`http_req_failed{scenario:${TEST}}`]: [{ threshold: "rate<0.05", abortOnFail: true, delayAbortEval: "1m" }],
    [`http_req_duration{scenario:${TEST}}`]: [
      { threshold: "p(95)<3000", abortOnFail: true, delayAbortEval: "1m" },
      "p(95)<1000",
    ],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const sessions = {};
  for (const [role, accounts] of Object.entries(ACCOUNTS)) {
    sessions[role] = accounts.map(([email, password]) => signIn(role, email, password));
  }
  return sessions;
}

function signIn(role, email, password) {
  const firebase = http.post(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FIREBASE_API_KEY}`,
    JSON.stringify({ email, password, returnSecureToken: true }),
    { headers: { "Content-Type": "application/json", Referer: `${BASE_URL}/` }, tags: { name: "firebase sign-in" } },
  );
  if (firebase.status !== 200) throw new Error(`${role}: Firebase sign-in failed (${firebase.status})`);
  const { idToken, refreshToken, expiresIn } = firebase.json();
  const session = http.post(`${BASE_URL}/api/session`, JSON.stringify({ rememberMe: false }), {
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}`, ...LOAD_TEST_HEADER },
    tags: { name: "POST /api/session" },
  });
  const cookie = session.cookies.teknokent_session && session.cookies.teknokent_session[0];
  if (session.status !== 200 || !cookie) throw new Error(`${role}: session failed (${session.status})`);
  return { cookie: cookie.value, idToken, refreshToken, expiresAt: Date.now() + Number(expiresIn) * 1000 };
}

// Each VU keeps its own copy, so a refreshed token stays with that VU.
let mine = null;

function become(sessions, role) {
  if (!mine) {
    const pool = sessions[role];
    mine = { role, ...pool[(__VU - 1) % pool.length] };
  }
  refreshTokenIfDue();
}

export default function (sessions) {
  become(sessions, mine ? mine.role : pickRole());
  const screen = pick(SCREENS[mine.role]);
  openScreen(screen);
  if (screen.poll.length) {
    const polls = randomBetween(4, 12);
    for (let i = 0; i < polls; i++) {
      sleep(POLL_SECONDS);
      request(screen.poll);
    }
  } else {
    sleep(randomBetween(5, 20));
  }
}

export function heavy(sessions) {
  become(sessions, "mentee");
  request(T4_TARGET);
}

export function rush(sessions) {
  become(sessions, mine ? mine.role : pickRole());
  openScreen(pick(SCREENS[mine.role]));
}

function openScreen(screen) {
  const started = Date.now();
  const ok = request(screen.load);
  const ms = Date.now() - started;
  screenTime.add(ms, { screen: screen.name });
  screensOpened.add(1, { screen: screen.name });
  const band = SCREEN_BANDS[screen.name] ? SCREEN_BANDS[screen.name].p90 : DEFAULT_BAND_MS;
  if (ok && ms <= band) screensGood.add(1, { screen: screen.name });
}

function request(urls) {
  const responses = http.batch(urls.map(toRequest));
  let ok = true;
  for (const response of responses) {
    ok = check(response, { "status < 400": (r) => r.status > 0 && r.status < 400 }) && ok;
    // The session re-checks the account every 10 minutes and saves a new cookie; a browser keeps
    // it, so must we, or every request after minute 10 would repeat the backend check.
    const refreshed = response.cookies.teknokent_session && response.cookies.teknokent_session[0];
    if (refreshed && refreshed.value) mine.cookie = refreshed.value;
  }
  return ok;
}

function toRequest(url) {
  const name = url.split("?")[0].replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, ":id");
  // The notification bell calls the backend directly with the Firebase token; the rest go through
  // the Next.js proxy with the session cookie, as in the browser.
  if (url.startsWith("http")) {
    return ["GET", url, null, { headers: { Authorization: `Bearer ${mine.idToken}`, Origin: BASE_URL, ...LOAD_TEST_HEADER }, tags: { name: `direct ${name.replace(/^https?:\/\/[^/]+/, "")}` } }];
  }
  return ["GET", `${BASE_URL}${url}`, null, { headers: { Cookie: `teknokent_session=${mine.cookie}`, ...LOAD_TEST_HEADER }, tags: { name } }];
}

// Firebase ID tokens last an hour; jitter so not every VU refreshes in the same second.
function refreshTokenIfDue() {
  if (Date.now() < mine.expiresAt - randomBetween(5, 9) * 60_000) return;
  const res = http.post(
    `https://securetoken.googleapis.com/v1/token?key=${FIREBASE_API_KEY}`,
    { grant_type: "refresh_token", refresh_token: mine.refreshToken },
    { headers: { Referer: `${BASE_URL}/` }, tags: { name: "firebase refresh" } },
  );
  if (res.status === 200) {
    const body = res.json();
    mine.idToken = body.id_token;
    mine.refreshToken = body.refresh_token;
    mine.expiresAt = Date.now() + Number(body.expires_in) * 1000;
  }
}

function pickRole() {
  let roll = Math.random();
  for (const [role, weight] of ROLE_WEIGHTS) {
    if ((roll -= weight) < 0) return role;
  }
  return ROLE_WEIGHTS[0][0];
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function randomBetween(min, max) {
  return min + Math.floor(Math.random() * (max - min + 1));
}
