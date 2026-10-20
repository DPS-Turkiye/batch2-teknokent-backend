// Phase 3c load test: replays the /api calls real menton.com.tr screens make, read-only.
//
// Each virtual user is one person online. It signs in as one of three test accounts (shared by all
// users, logged in once in setup), opens screens recorded from production, and on messaging screens
// keeps polling like the open tab would. Only GET requests: nothing is written, sent or charged.
//
// Run (env and recorded screens live outside the repo, see README.md):
//   set -a; . ~/.config/menton-loadtest/prod.env; set +a
//   TEST=T0 K6_WEB_DASHBOARD=true k6 run --include-system-env-vars scripts/loadtest/menton.js
import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL;
const FIREBASE_API_KEY = __ENV.FIREBASE_API_KEY;
const TEST = __ENV.TEST || "T0";
const POLL_SECONDS = 15;
const ROLE_WEIGHTS = [
  ["mentee", 0.8],
  ["mentor", 0.15],
  ["admin", 0.05],
];
const ACCOUNTS = {
  mentee: [__ENV.MENTEE_EMAIL, __ENV.MENTEE_PASSWORD],
  mentor: [__ENV.MENTOR_EMAIL, __ENV.MENTOR_PASSWORD],
  admin: [__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD],
};

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
  T2: {
    executor: "constant-vus",
    vus: Number(__ENV.VUS || 100),
    duration: __ENV.DURATION || "60m",
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
};

export const options = {
  scenarios: { [TEST]: SCENARIOS[TEST] },
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
  for (const [role, [email, password]] of Object.entries(ACCOUNTS)) {
    const signIn = http.post(
      `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${FIREBASE_API_KEY}`,
      JSON.stringify({ email, password, returnSecureToken: true }),
      { headers: { "Content-Type": "application/json", Referer: `${BASE_URL}/` }, tags: { name: "firebase sign-in" } },
    );
    if (signIn.status !== 200) throw new Error(`${role}: Firebase sign-in failed (${signIn.status})`);
    const { idToken, refreshToken, expiresIn } = signIn.json();
    const session = http.post(`${BASE_URL}/api/session`, JSON.stringify({ rememberMe: false }), {
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` },
      tags: { name: "POST /api/session" },
    });
    const cookie = session.cookies.teknokent_session && session.cookies.teknokent_session[0];
    if (session.status !== 200 || !cookie) throw new Error(`${role}: session failed (${session.status})`);
    sessions[role] = {
      cookie: cookie.value,
      idToken,
      refreshToken,
      expiresAt: Date.now() + Number(expiresIn) * 1000,
    };
  }
  return sessions;
}

// Each VU keeps its own copy, so a refreshed token stays with that VU.
let mine = null;

export default function (sessions) {
  if (!mine) {
    const role = pickRole();
    mine = { role, ...sessions[role] };
  }
  refreshTokenIfDue();

  const screen = pick(SCREENS[mine.role]);
  request(screen.load);
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

function request(urls) {
  const responses = http.batch(urls.map(toRequest));
  for (const response of responses) {
    check(response, { "status < 400": (r) => r.status > 0 && r.status < 400 });
  }
}

function toRequest(url) {
  const name = url.split("?")[0].replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, ":id");
  // The notification bell calls the backend directly with the Firebase token; the rest go through
  // the Next.js proxy with the session cookie, as in the browser.
  if (url.startsWith("http")) {
    return ["GET", url, null, { headers: { Authorization: `Bearer ${mine.idToken}`, Origin: BASE_URL }, tags: { name: `direct ${name.replace(/^https?:\/\/[^/]+/, "")}` } }];
  }
  return ["GET", `${BASE_URL}${url}`, null, { headers: { Cookie: `teknokent_session=${mine.cookie}` }, tags: { name } }];
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
