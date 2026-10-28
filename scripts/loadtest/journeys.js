// Browser journeys: a real Chromium signs in as each test role and walks its read features the way a person
// does (clicking the menu), timing how long each feature takes to show. Read-only: it only follows links,
// never presses a button that saves, sends or starts anything (Phase 0 inventory, W1–W18 / L1–L7).
//
//   baseline (default): one browser at a time, no added load, ITERATIONS rounds of every journey.
//   HYBRID=1:           BROWSER_VUS browsers loop the journeys while menton.js runs its TEST shape beside them.
//
// Both start with menton.js's warm-up (2 min of light protocol load, left out of every result).
// Run: see README.md ("Browser journeys").
import { browser } from "k6/browser";
import { check } from "k6";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";
import protocol, { heavy, options as protocolOptions, rush, setup as protocolSetup } from "./menton.js";

const BASE_URL = __ENV.BASE_URL;
const HYBRID = __ENV.HYBRID === "1";
const QUIET_MS = 500; // no /api call in flight for this long = the screen has what it asked for
const STEP_TIMEOUT_MS = 20000;
const CLICK_TIMEOUT_MS = 8000;
// No X-Load-Test header here: on a browser it turns every cross-origin call (Firebase sign-in, the backend's
// notification calls) into a CORS preflight those servers refuse. Browser traffic shows as HeadlessChrome.

const featureVisible = new Trend("feature_visible", true);
const journeyGaps = new Counter("journey_gap");
const knownApiErrors = new Counter("known_api_errors");

// Errors prod already gives at no load (found 28 Oct, Phase 3 report): bugs to fix, not load effects. Counted in
// known_api_errors and printed, but they don't fail the check, so any new error still does. Keep this list short.
const KNOWN_API_ERRORS = {
  "403 /api/programs": "P3-F1: mentee pages ask for the program list, which mentees may not read",
  "403 /api/programs/directory": "P3-F1",
  "400 /api/organizations/invite-code": "P3-F2: the invite-code panel asks for a code this account can't get",
};

const DESKTOP = { width: 1440, height: 900 };
const PHONE = { width: 390, height: 844 };

// A step is a page: `href` is clicked when the current page links to it (a soft navigation, as a person
// would), else loaded directly. `prefix` follows the first link starting with it (a detail page); none on
// the page = the test account has no such data (a gap, not a failure). Order matters: links come from the
// page before. Left out on purpose: meeting notes (L5), program/coordinator threads (L4), phone chat
// threads (L3), the two legacy pages (Q4), and everything behind a submit (W1–W18).
const JOURNEYS = {
  mentee: {
    login: "/login",
    viewport: DESKTOP,
    steps: [
      { href: "/startup/process" },
      { href: "/startup" },
      { href: "/startup/files" },
      { href: "/startup/team" },
      { href: "/mentorships" },
      { href: "/mentorships?tab=requests" },
      { prefix: "/mentor-request/detail" },
      { href: "/mentorships?tab=mentor-pool" }, // a mentee's /mentors redirects here
      { prefix: "/mentors/", skip: ["/mentors/compare"] },
      { href: "/calendar" },
      { href: "/messages" },
      { href: "/programs" },
      { prefix: "/programs/" },
      { href: "/profile/personal" },
      { href: "/profile" },
      { href: "/mentor-request" }, // step 1 only: recommendations (W3, AI cost) start on a button
      { href: "/dashboard" },
    ],
  },
  mentor: {
    login: "/login",
    viewport: DESKTOP,
    steps: [
      { prefix: "/mentorships/requests/" }, // request cards on the mentor home, where sign-in lands
      { href: "/mentorships" },
      { href: "/mentorships?tab=regular" },
      { prefix: "/mentorships/regular/" },
      { href: "/mentor-dashboard/calendar" },
      { href: "/mentor-dashboard/messages" },
      { href: "/mentor-profile" },
      { href: "/programs" },
      { href: "/mentor-dashboard" },
    ],
  },
  admin: {
    login: "/login",
    viewport: DESKTOP,
    steps: [
      { href: "/admin/programs" },
      { href: "/admin/users" },
      { href: "/admin/users/mentors" },
      { href: "/admin/users/teams" },
      { href: "/admin/mentorships" },
      { href: "/admin/mentorships/interventions" },
      { href: "/admin/mentorships/matching" },
      { href: "/admin/reporting" },
      { href: "/admin" },
    ],
  },
  "phone-mentee": {
    account: "mentee",
    login: "/m/login",
    viewport: PHONE,
    steps: [
      { href: "/m/calendar" },
      { href: "/m/messages" },
      { href: "/m/startups" },
      { href: "/m/tasks" },
      { href: "/m/notifications", ready: "body" },
      { href: "/m/account", ready: "body" },
      { href: "/m" },
    ],
  },
  "phone-mentor": {
    account: "mentor",
    login: "/m/login",
    viewport: PHONE,
    steps: [
      { href: "/m/calendar" },
      { href: "/m/messages" },
      { href: "/m/startups" },
      { prefix: "/m/startups/", ready: "body" },
      { href: "/m/notifications", ready: "body" },
      { href: "/m/account", ready: "body" },
      { href: "/m" },
    ],
  },
};

const ROLES = (__ENV.JOURNEYS || Object.keys(JOURNEYS).join(",")).split(",");
const warmup = protocolOptions.scenarios.warmup;
const startTime = warmup ? warmup.duration : "0s";
const browserOptions = { browser: { type: "chromium" } };

export const options = {
  scenarios: {
    ...(warmup && { warmup: { ...warmup, exec: "protocolLoop" } }),
    journeys: HYBRID
      ? {
          executor: "constant-vus",
          exec: "journeys",
          vus: Number(__ENV.BROWSER_VUS || 2),
          duration: protocolDuration(),
          gracefulStop: "2m", // let the last journey finish rather than cut it mid-page
          startTime,
          options: browserOptions,
        }
      : {
          executor: "per-vu-iterations",
          exec: "journeys",
          vus: 1,
          iterations: Number(__ENV.ITERATIONS || 1) * ROLES.length, // ITERATIONS rounds of every journey
          maxDuration: "2h",
          startTime,
          options: browserOptions,
        },
    ...(HYBRID && protocolScenario()),
  },
  thresholds: {
    ...(HYBRID ? protocolOptions.thresholds : {}),
    "checks{scenario:journeys}": ["rate==1"],
  },
  summaryTrendStats: protocolOptions.summaryTrendStats,
};

function protocolScenario() {
  const [name, scenario] = Object.entries(protocolOptions.scenarios).find(([key]) => key !== "warmup");
  return { [name]: { ...scenario, exec: scenario.exec ? scenario.exec : "protocolLoop" } };
}

function protocolDuration() {
  const scenario = Object.values(protocolScenario())[0];
  if (scenario.duration) return scenario.duration;
  const seconds = (scenario.stages || []).reduce((sum, s) => sum + parseDuration(s.duration), 0);
  return `${seconds}s`;
}

function parseDuration(text) {
  const units = { s: 1, m: 60, h: 3600 };
  return Number(text.slice(0, -1)) * units[text.slice(-1)];
}

export function setup() {
  return protocolSetup();
}

export function protocolLoop(sessions) {
  protocol(sessions);
}

export { heavy, rush };

// One journey per iteration, going round the roles; in hybrid mode each browser starts on a different one.
export async function journeys() {
  const offset = HYBRID ? __VU - 1 : 0;
  const role = ROLES[(exec.vu.iterationInScenario + offset) % ROLES.length];
  await walk(role, JOURNEYS[role]);
}

async function walk(role, journey) {
  const account = (journey.account || role).toUpperCase();
  const context = await browser.newContext({ viewport: journey.viewport });
  const page = await context.newPage();
  const net = watchNetwork(page);
  try {
    let step = { feature: `${role}:login` };
    await page.goto(`${BASE_URL}${journey.login}`);
    await page.locator('input[autocomplete="email"]').first().fill(firstOf(__ENV[`${account}_EMAIL`]));
    await page.locator('input[autocomplete="current-password"]').first().fill(firstOf(__ENV[`${account}_PASSWORD`]));
    net.reset();
    let started = Date.now();
    await page.locator('button[type="submit"]').first().click();
    await page.waitForFunction((path) => !location.pathname.startsWith(path), { timeout: STEP_TIMEOUT_MS }, journey.login);
    await measure(page, net, step, started);

    for (const s of journey.steps) {
      step = { feature: `${role}:${s.href || `${s.prefix}[id]`}`, ready: s.ready };
      const target = s.href || (await firstLink(page, s.prefix, s.skip || []));
      if (!target) {
        journeyGaps.add(1, { feature: step.feature });
        console.warn(`gap: ${step.feature} (no link on ${pathOf(page.url())})`);
        continue;
      }
      net.reset();
      started = Date.now();
      const path = target;
      const link = page.locator(`a[href="${target}"]`).first();
      step.nav = "click";
      if (!((await link.isVisible()) && (await link.click(), await reached(page, path, CLICK_TIMEOUT_MS)))) {
        // No usable link here (or it sits in a closed menu): load the page directly and time that instead.
        step.nav = "load";
        net.reset();
        started = Date.now();
        await page.goto(`${BASE_URL}${target}`);
        if (!(await reached(page, path, STEP_TIMEOUT_MS))) throw new Error(`never reached ${path}`);
      }
      await measure(page, net, step, started);
    }
  } catch (error) {
    check(null, { "journey finished": () => false }, { feature: `${role}` });
    console.error(`${role} at ${pathOf(page.url())}: ${String(error).slice(0, 200)}`);
    // Test accounts' own data only; keep the folder outside the repo (reports/).
    if (__ENV.SCREENSHOT_DIR) await page.screenshot({ path: `${__ENV.SCREENSHOT_DIR}/${role}-${Date.now()}.png` });
  } finally {
    await page.close();
    await context.close();
  }
}

// Visible = the page's main area (step.ready; phone detail pages have no <main>) is on screen and every /api call it started has answered (QUIET_MS with
// nothing in flight). The time runs from the click to the later of the two.
// ponytail: a page whose first call starts > QUIET_MS after the click would be timed too short; give such a
// page its own selector to wait for if one ever shows up.
async function measure(page, net, step, started) {
  await page.locator(step.ready || "main").first().waitFor({ state: "visible", timeout: STEP_TIMEOUT_MS });
  const mainAt = Date.now();
  const deadline = started + STEP_TIMEOUT_MS;
  while (Date.now() < deadline && (net.inflight > 0 || Date.now() - Math.max(net.last, mainAt) < QUIET_MS)) {
    await page.waitForTimeout(50);
  }
  // Before sign-in the page asks for a session it doesn't have yet: that 401 is the expected answer.
  if (step.feature.endsWith(":login")) net.failed = net.failed.filter((f) => f !== "401 /api/session");
  for (const call of net.failed.filter((f) => KNOWN_API_ERRORS[f])) {
    knownApiErrors.add(1, { feature: step.feature, call });
  }
  net.failed = net.failed.filter((f) => !KNOWN_API_ERRORS[f]);
  net.errors = net.failed.length;
  const settled = net.inflight === 0;
  const visible = Math.max(net.last, mainAt) - started;
  const tags = { feature: step.feature, nav: step.nav || "login" };
  featureVisible.add(visible, tags);
  check(null, { "feature visible, no /api error": () => settled && net.errors === 0 }, tags);
  if (!settled || net.errors) console.warn(`${step.feature}: settled=${settled} api errors: ${net.failed.join(", ")}`);
}

function watchNetwork(page) {
  const net = { inflight: 0, last: 0, errors: 0, failed: [] };
  net.reset = () => {
    net.inflight = 0; // calls cut off by the last navigation never answer
    net.last = 0;
    net.errors = 0;
    net.failed = [];
  };
  const isApi = (url) => url.includes("/api/");
  page.on("request", (request) => {
    if (isApi(request.url())) net.inflight += 1;
  });
  page.on("response", (response) => {
    if (!isApi(response.url())) return;
    net.inflight = Math.max(0, net.inflight - 1);
    net.last = Date.now();
    if (response.status() >= 400) {
      net.errors += 1;
      net.failed.push(`${response.status()} ${pathOf(response.url())}`);
    }
  });
  return net;
}

async function firstLink(page, prefix, skip) {
  const hrefs = await page.evaluate(
    ([p, s]) =>
      [...document.querySelectorAll("a[href]")]
        .map((a) => a.getAttribute("href"))
        .filter((h) => h.startsWith(p) && h.length > p.length && !s.some((x) => h.startsWith(x))),
    [prefix, skip],
  );
  return hrefs[0] || null;
}

function firstOf(list) {
  return (list || "").split(",")[0].trim();
}

async function reached(page, path, timeout) {
  try {
    // With a query (a tab, a detail id) the whole of it must match, or a tab switch counts as arrived early.
    await page.waitForFunction(
      (p) => (p.includes("?") ? location.pathname + location.search : location.pathname) === p,
      { timeout },
      path,
    );
    return true;
  } catch {
    return false;
  }
}

function pathOf(url) {
  return url.replace(/^https?:\/\/[^/]+/, "").split("?")[0];
}
