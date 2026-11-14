# Security

## Who is calling

Requests reach the backend in two ways, and the difference decides what the backend may trust.

| Path | How it authenticates | Who the caller is |
|---|---|---|
| **Browser, directly** | A Firebase ID token (`Authorization: Bearer ...`), checked by `FirebaseBearerFilter`. Only an allowlist of paths accepts one (`isBrowserPath`): file uploads and downloads, profile parsing, notifications, the meeting assistant, account status and language, and a few more. | The account the token belongs to. |
| **Frontend proxy** | HTTP Basic with the service credentials (`SECURITY_USERNAME` / `SECURITY_PASSWORD`), known only to the Next.js server. Every other path. | The user the proxy names (`actorId`, `organizationId`), taken from its own session. |

`CurrentActor.resolve(actorId)` is the one way to get the caller: a verified bearer token always wins, and an id
sent in the request is used only on the service credentials. Controllers never treat a request id as the caller.

A bearer token on a non-allowlisted path is refused (403), so a signed-in user cannot call proxy-only endpoints
with their own token and an arbitrary `actorId`. Accounts that are not yet approved can reach only their status,
language, own profile and onboarding paths.

Other sign-ins:

- **Firebase** sign-up and sign-in (e-mail/password, invite links). Linking a login to an existing row by e-mail
  requires a verified address; admin and coordinator rows are never claimed that way.
- **LTI 1.3** from Moodle (ODTÜClass) for office hours: signed launch, single-use nonce, platform keys from its JWKS.
- **OTG** (ODTÜ Teknokent Girişimcilik) over OpenID Connect with PKCE.
- **Dev persona picker** (`INSTITUTION_LOGIN_DEV_STUB=true`): local only, never on a shared server.

## What a caller may do

Authorization lives in the services, checked against the resolved caller:

- **Admins**: `AdminAccess.requireAdmin` is the single admin check. Only an *approved* admin passes; anyone else
  gets 403.
- **Coordinators** act only on the programs they created or were added to, and on startups in those programs
  (`ProgramCoordination`, `StartupAccessService`). Coordinating one program gives no access to another's startups.
- **Mentors** see a startup's files while they have a live request with it or an ongoing mentorship
  (`PersonAccessService` rules).
- **Team members** see their own startup's data; a person's profile is visible to people related to them.
- **Meetings**: two meetings form one room only when they share the Meet code *and* the start time. A mentor's
  personal Meet room is therefore not shared across teams or dates. Only participants of the current session on a
  code may use its assistant (live transcript, stop, report). A team member may update notes and mark a meeting
  done; changing the link, the recording choice or the status, and deleting, are for the mentor or an admin.

Some proxy-only reads (`GET /api/mentorships/{id}`, the dashboard, meeting rooms and notes) apply the per-user
check when the proxy names the caller and otherwise trust the proxy's own check. Once the frontend sends `actorId`
on those calls, the parameter can become required.

## Abuse limits

- **Rate limits** (`RateLimitingFilter`, `RateLimitingConfig`): a general budget per caller and smaller ones for the
  AI-heavy paths (CV parse, syllabus and weekly-program reads). Decisions are atomic in Redis (one Lua script), with
  a bounded in-memory fallback when Redis is down.
- **CV preview** before an account exists needs a verified e-mail and also spends a per-IP budget.
- **Basic sign-in**: after 20 failed attempts from one address in 15 minutes, that address gets 429 for Basic
  (`BasicLoginThrottleFilter`).
- **OCR** runs on at most 10 pages of a PDF; uploads are size-limited per document kind; transcript and report
  uploads are checked for size before they are read.
- **Client IP** is the last `X-Forwarded-For` entry, which CloudFront appends. The app port must accept traffic only
  from CloudFront; reached directly, the header can be forged and the per-IP limits bypassed.

## Uploads and downloads

`DocumentService` checks each file's kind, size, extension, content type and magic bytes (PDF header and trailer,
image signatures), rejects archives with executables and zip bombs in Office files, and stores under generated S3
keys. Every download is an attachment with `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`.
CSV and XLSX exports escape cells that a spreadsheet would run as formulas.

## Secrets

- `.env` holds real credentials and is git-ignored; `.env.example` is the template with safe local defaults.
- A released image (`APP_VERSION` set by the deploy workflow) refuses to start while `SECURITY_PASSWORD`,
  `DATABASE_PASSWORD` or `REALTIME_TOKEN_SECRET` is still a placeholder (`PlaceholderSecretCheck`).
- Swagger is off unless `SWAGGER_ENABLED=true`; keep it off in production, where the API map would be public.
- Error responses carry a translated message and a stable `code`, never a stack trace, SQL or an outside provider's
  message.

## Reporting a problem

Tell the team privately before opening an issue or a PR that describes a vulnerability.
