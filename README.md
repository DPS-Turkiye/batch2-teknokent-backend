# Teknokent Backend

Spring Boot REST API for the Teknokent platform. Runs with Docker; Maven wrapper commands run inside Docker (no local Java required).

Works on macOS, Linux, and Windows (Docker Desktop).

## Quick Start

```bash
cp .env.example .env   # Windows: copy .env.example .env
docker compose up --build
```

- Health: http://localhost:8080/api/health
- Docs: http://localhost:8080/api/docs

All endpoints except health and API docs use HTTP Basic authentication. The
default local credentials from `.env.example` are `teknokent` / `change-me`.

```bash
docker compose down      # stop
docker compose down -v   # stop + reset database
```

## Development

Mac / Linux / Git Bash:

```bash
./mvnw test
./mvnw clean package
```

Windows (CMD or PowerShell):

```bat
mvnw.cmd test
mvnw.cmd clean package
```

Configuration lives in `.env` (see `.env.example`).

To check how much the configured OpenRouter key has spent, export the key and run
the dependency-free usage script:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
python3 scripts/openrouter_usage.py
```

It reports lifetime, daily, weekly, and monthly spend, together with the key's
configured limit and remaining amount. Add `--json` to print OpenRouter's current
key usage object. The script never prints the API key itself.

## Stack

Java 25, Spring Boot 4.1, PostgreSQL 17 + pgvector, Apache PDFBox,
Tesseract OCR, Flyway, Spring Security, springdoc-openapi, Lombok.

Tests use H2; local/production use PostgreSQL. Migrations: `src/main/resources/db/migration/`.

Mentor recommendations use pgvector cosine retrieval as a shortlist before the
request-specific Java ranking layer. Confirmed mentor profile embeddings are cached in
`mentor_profile_embedding` and refreshed whenever their source profile changes.
If pgvector or the configured embedding API is unavailable, the deterministic
keyword/evidence fallback keeps the flow usable.

## Implemented MVP and AI features

- Database-backed mentee users, organizations, mentor profiles, mentorship
  requests, request messages, meetings, progress, bottlenecks, actions, and
  dashboard totals.
- A five-stage mentorship-request workflow with urgency, format, meeting-mode,
  mentor selection, submission, status changes, withdrawal, alternative mentors,
  and meeting scheduling.
- Request-specific mentor recommendations that combine the request topic and
  summary with the mentee/startup profile; eligibility is applied before ranking.
- Explainable recommendations with matching reasons and evidence snippets from
  confirmed mentor profiles. The generated ranking and selected mentor are saved
  as an auditable recommendation snapshot with the request.
- PostgreSQL/pgvector cosine retrieval for the first mentor shortlist, followed by
  the semantic/deterministic matcher. The flow remains usable when pgvector or the
  embedding provider is unavailable.
- Consent-gated PDF profile parsing for startup pitch decks and individual CVs,
  including local OCR (`off`, `auto`, `force`), local PII redaction before the AI
  call, editable review, explicit confirmation, and a manual-entry fallback.
- Evidence-based technology inference for technologies implied by projects and
  experience, without silently replacing the user's confirmed profile.
- Parsing and matching run directly inside Spring Boot; no Python service or
  localhost ports 8765/8766 are required at runtime.

Authentication remains a shared HTTP Basic development gate. Real user auth is
outside this MVP branch.

## Docker

Compose services: `app` (8080), `postgres` (host and container port 5432 by default).
Production image: multi-stage `Dockerfile`.

```bash
docker build -t backend:latest .
```

CI runs on `main` via GitHub Actions (`./mvnw test`).

## Prototype API

The first backend slice supports organizations, mentorships, progress updates,
bottlenecks, meetings, action items, and dashboard totals.

| Area | Endpoints |
|---|---|
| Users | `GET /api/users` |
| Mentors | `GET /api/mentors`, `GET /api/mentors/{id}` |
| Organizations | `GET /api/organizations`, `GET /api/organizations/{id}`, `POST /api/organizations` |
| Mentorships | `GET /api/mentorships`, `POST /api/mentorships` |
| Progress | `GET /api/organizations/{id}/progress`, `POST /api/organizations/{id}/progress` |
| Bottlenecks | `GET /api/organizations/{id}/bottlenecks`, `POST /api/organizations/{id}/bottlenecks`, `PATCH /api/bottlenecks/{id}` |
| Meetings | `GET /api/meetings`, `POST /api/meetings` |
| Actions | `GET /api/actions`, `POST /api/actions`, `PATCH /api/actions/{id}` |
| Dashboard | `GET /api/dashboard/summary` |
| Mentor requests | `GET/POST /api/mentorship-requests`, `GET /api/mentorship-requests/{id}`, decision, message, withdrawal, availability, meeting, and alternatives subresources |
| Recommendations | `POST /api/mentor-recommendations` |
| Profile analysis | `GET /api/profile-analyses/latest`, `GET /api/profile-analyses/latest/confirmed`, `POST /api/profile-analyses`, `POST /api/profile-analyses/manual`, `PATCH /api/profile-analyses/{id}/confirmation`, `POST /api/profile-analyses/{id}/recommendations` |

Flyway seeds three prototype users and one organization:

| Record | ID |
|---|---|
| Demo administrator | `00000000-0000-0000-0000-000000000001` |
| Demo mentor | `00000000-0000-0000-0000-000000000002` |
| Demo founder | `00000000-0000-0000-0000-000000000003` |
| Demo Startup | `10000000-0000-0000-0000-000000000001` |

Example:

```bash
curl -u teknokent:change-me \
  http://localhost:8080/api/dashboard/summary
```

## Using the AI-assisted flow

1. Copy `.env.example` to `.env`, choose `AI_PROVIDER=openai` or
   `AI_PROVIDER=openrouter`, and set the matching API key. Never commit `.env`.
2. Start Spring Boot and PostgreSQL. The image already contains Turkish and
   English Tesseract language data:

   ```bash
   cd /path/to/Backend
   cp .env.example .env
   docker compose up --build
   ```

3. Open `http://localhost:8080/api/docs` for Swagger or run the frontend and
   open `/profile`. Accept the processing consent, choose the profile type and
   OCR mode, upload a PDF, review/edit the extracted fields, and confirm them.
4. Create a mentorship request. At stage 4 choose **Önerileri Hazırla**. The
   backend ranks eligible mentors using the current request plus the confirmed
   mentee/startup profile and returns reasons/evidence. Select a mentor and submit
   the request at stage 5.

The native parsing path is: PDFBox text extraction → Tesseract for scanned pages
when OCR is `auto` or `force` → local PII redaction → structured OpenAI/OpenRouter
response → evidence-based technology inference → editable confirmation. Mentor
matching embeds the request and confirmed mentor profiles directly through the
configured provider, stores vectors in PostgreSQL, and falls back to deterministic
request/profile overlap if semantic retrieval is unavailable.

To inspect persisted requests directly:

```sql
select * from mentorship_request order by created_at desc;
select * from mentorship_request_recommendation_run order by created_at desc;
```

The database users are domain records used as owners, mentors, and authors.
HTTP Basic authentication is intentionally a single prototype gate; Firebase
or another identity provider will replace it before real-user deployment.
