# Architecture

A single Spring Boot 4.1 application (Java 25) behind the Next.js frontend. It owns the PostgreSQL database and
talks to a handful of outside services.

```
Browser ──► Next.js frontend (Amplify) ──► this backend ──► PostgreSQL 17 + pgvector
   │            proxy on service credentials       │  ├──► Redis (rate limits, live notes, login state)
   │                                                │  ├──► S3 (documents, transcripts), SES (e-mail)
   └──── bearer token / WebSocket ─────────────────►│  ├──► Firebase Auth + Cloud Messaging
                                                    │  ├──► OpenAI or OpenRouter (AI features)
                                                    │  └──► meeting bot provider, Moodle (LTI), OTG
```

## Two editions

One codebase runs as two deployments, picked by `APP_EDITION`:

- **TEKNOKENT** (default): startup mentoring: programs, teams, mentors, mentorship requests, meetings and the meeting
  assistant.
- **ODTU**: ODTÜ office hours (launched from Moodle over LTI 1.3) and İŞKUR peer mentoring with daily, weekly and
  meeting reports.

Each edition answers 404 for the other's endpoints. The paths that belong to one edition are listed in
`domain/Edition.java`; anything not listed is served by both. `filter/EditionPathFilter` enforces it.

## Code layout

Everything is under `src/main/java/com/dps/teknokent/`. `ArchitectureTest` enforces the dependencies.

| Package | Owns |
|---|---|
| `controller` | HTTP mapping only: resolve the caller, validate the body, call one service, return its DTO |
| `service` | Rules, permissions, transactions, and calls to repositories and outside services |
| `repository` | Spring Data interfaces and queries |
| `entity` | JPA entities; they never leave through a controller |
| `domain` | Enums and value types; no Spring |
| `dto/requests`, `dto/responses` | Records for API payloads; `dto/provider` for outside APIs |
| `security` | Sign-in (Firebase, LTI, institution logins), token checks, `CurrentActor`, `AdminAccess` |
| `config`, `filter` | Spring configuration and servlet filters (security chain, rate limits, editions) |
| `exception` | The shared exceptions and `GlobalExceptionHandler` |
| `util` | Small shared functions: texts, e-mail domains, JSON schemas, model output, downloads, PDF checks |

Every package except `entity` and `dto/requests` is `@NullMarked`: types are non-null unless written `@Nullable`,
and NullAway checks it at compile time.

## A request

1. `RateLimitingFilter` spends from the caller's budget (AI-heavy paths have their own, smaller budgets).
2. The Spring Security chain authenticates the request: a Firebase bearer token on the paths the browser calls
   directly, otherwise the frontend proxy's service credentials. See [Security](security.md).
3. `EditionPathFilter` answers 404 for the other edition's paths.
4. The controller resolves the acting user with `CurrentActor.resolve(...)`, validates the body with `@Valid` and
   calls a service.
5. The service checks what that user may do, does the work in a transaction and returns a response record.
6. Errors are `InvalidRequestException` (400), `ResourceNotFoundException` (404), `ConflictException` (409) or a
   `ResponseStatusException` (403, 502, 503); `GlobalExceptionHandler` turns them into one JSON shape
   (`ApiErrorResponse`), with the text in the caller's language (`util/Texts`, `i18n/{tr,en}`).

## Data

- **PostgreSQL** holds everything durable. Flyway migrations in `src/main/resources/db/migration`; Hibernate
  validates the entities against the schema at start-up. Mentor profile embeddings live in a pgvector column for
  semantic matching.
- **Redis** keeps short-lived shared state: rate-limit windows, the live collaborative meeting note, LTI and OTG
  login state. The app keeps working without Redis for rate limits (in-memory fallback).
- **S3** stores uploaded documents, meeting transcripts and reports. Downloads use presigned URLs or go through
  `Downloads.attachment` (attachment, `nosniff`, `no-store`).

## Realtime

`/ws` is a STOMP WebSocket endpoint for meeting rooms (live notes, to-dos). The browser connects with a short-lived
token signed with `REALTIME_TOKEN_SECRET`; `RealtimeHandshakeInterceptor` and `MeetingRoomAccessInterceptor` check
it and the room it subscribes to.

## Background work

| Job | Class | Does |
|---|---|---|
| Transcript bot | `MeetingTranscriptBotScheduler` | Sends the meeting bot near each recorded meeting's start; the watchdog stops it and stores the transcript and report |
| Reminders | `NotificationReminderScheduler` | Meeting reminders 30 minutes before, task deadlines the day before (push + in-app) |
| User deletion cleanup | `UserDeletionCleanupScheduler` | Finishes deletions whose outside cleanup (Firebase, S3) failed |

The scheduler pool has a thread per job, so a slow job does not delay the others. Long AI work (meeting reports,
task handover) runs on the `aiTaskExecutor`, outside database transactions. Jobs assume a single app instance; they
take no distributed lock.
