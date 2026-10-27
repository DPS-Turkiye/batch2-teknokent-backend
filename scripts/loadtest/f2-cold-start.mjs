// F2 cold-start check: after idle, time a direct backend call vs the Next.js-only /api/session vs a full page.
// Usage: node scripts/loadtest/f2-cold-start.mjs ~/.config/menton-loadtest/prod.env  (GET only, except POST /api/session = sign-in)
import { readFileSync } from "node:fs";

const env = Object.fromEntries(
  readFileSync(process.argv[2], "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim().replace(/^["']|["']$/g, "")]),
);
const BASE = env.BASE_URL;
const BACKEND = env.BACKEND_URL || "https://d2ulpskrb4nc9m.cloudfront.net";
const screen = JSON.parse(readFileSync(`${env.CAPTURE_DIR.replace(/^~/, process.env.HOME)}/capture-mentee.json`, "utf8"))["mentee:/dashboard"];

async function timed(label, url, opts = {}) {
  const t = performance.now();
  try {
    const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(30000), ...opts });
    await r.arrayBuffer();
    return { label, status: r.status, ms: Math.round(performance.now() - t), res: r };
  } catch (e) {
    return { label, status: "ERR " + e.name, ms: Math.round(performance.now() - t) };
  }
}
const show = (round, rows) => rows.forEach((r) => console.log(`${round}\t${r.ms}\t${r.status}\t${r.label}`));

const signIn = await (await fetch(
  `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${env.FIREBASE_API_KEY}`,
  { method: "POST", headers: { "Content-Type": "application/json", Referer: `${BASE}/` },
    body: JSON.stringify({ email: env.MENTEE_EMAIL, password: env.MENTEE_PASSWORD, returnSecureToken: true }) },
)).json();
if (!signIn.idToken) throw new Error("Firebase sign-in failed");
const bearer = { Authorization: `Bearer ${signIn.idToken}` };

console.log(`start ${new Date().toISOString()}\nround\tms\tstatus\twhat`);
for (const round of ["cold", "warm1", "warm2"]) {
  // The three layers at the same instant, so none warms another first.
  show(round, await Promise.all([
    timed("A direct backend GET /api/notifications (CloudFront→EC2, no Next.js)", `${BACKEND}/api/notifications`, { headers: bearer }),
    timed("B Next.js only GET /api/session (no cookie, no backend)", `${BASE}/api/session`),
    timed("C SSR page GET /login (HTML only)", `${BASE}/login`),
  ]));

  const s = await timed("D POST /api/session (Next.js → backend /api/auth/me)", `${BASE}/api/session`, {
    method: "POST", headers: { "Content-Type": "application/json", ...bearer }, body: JSON.stringify({ rememberMe: false }),
  });
  show(round, [s]);
  const cookie = (s.res?.headers.getSetCookie() || []).map((c) => c.split(";")[0]).join("; ");
  if (!cookie) throw new Error(`no session cookie (${s.status})`);

  const t = performance.now();
  const page = await Promise.all([
    timed("E full page: GET /dashboard HTML", `${BASE}/dashboard`, { headers: { cookie } }),
    ...screen.map((c) => c.url.startsWith("http")
      ? timed(`  E call ${c.url.replace(BACKEND, "[direct]")}`, c.url, { headers: bearer })
      : timed(`  E call ${c.url.slice(0, 70)}`, `${BASE}${c.url}`, { headers: { cookie } })),
  ]);
  show(round, page.sort((a, b) => b.ms - a.ms).slice(0, 5));
  console.log(`${round}\t${Math.round(performance.now() - t)}\t-\tE full page wall time (HTML + ${screen.length} calls in parallel), errors: ${page.filter((r) => r.status !== 200).map((r) => r.status + " " + r.label.trim()).join(" | ") || "none"}`);
  await new Promise((r) => setTimeout(r, 5000));
}
