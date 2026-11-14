# Configuration

All settings are environment variables, read by `src/main/resources/application.yml`. Locally they come from
`.env` (copy `.env.example`, which documents each one and holds safe local defaults); in production from the
server's environment. A new setting goes into `.env.example` with a safe default.

Never commit `.env`: it holds real keys.

## Core

| Variable | Purpose |
|---|---|
| `APP_EDITION` | `TEKNOKENT` (default) or `ODTU`. Leave it out rather than blank; blank stops the app. |
| `SPRING_PROFILES_ACTIVE` | `dev` locally (permissive CORS for localhost). |
| `SERVER_PORT`, `APP_PORT` | Port inside the container, and on the host for compose. |
| `SECURITY_USERNAME`, `SECURITY_PASSWORD` | The frontend proxy's service credentials (HTTP Basic). |
| `SWAGGER_ENABLED` | `true` serves the API docs at `/api/docs`. Off in production. |
| `APP_PUBLIC_URL` | The frontend's address, used in e-mail links. |
| `APP_VERSION` | Set by the deploy workflow on released images; turns on the placeholder-secret check. |

## Data

| Variable | Purpose |
|---|---|
| `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL. |
| `DATABASE_POOL_SIZE` | Connections per instance; keep (instances × this) under the database's limit. |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis (no password in compose; set one in production). |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | S3 and SES. |
| `MAIL_FROM` | SES sender; empty sends no e-mail. |

## Sign-in

| Variable | Purpose |
|---|---|
| `FIREBASE_PROJECT_ID`, `FIREBASE_CREDENTIALS_JSON` | Firebase Auth and push; the service-account JSON on one line. |
| `REGISTRATION_ALLOWED_EMAIL_DOMAINS` | Comma-separated domains allowed to sign up (subdomains count); empty allows any. |
| `INSTITUTION_LOGIN_DEV_STUB` | `true` replaces the ODTÜ login with a persona picker. Local only. |
| `OTG_LOGIN_ENABLED`, `OTG_ISSUER`, `OTG_USERINFO_URL`, `OTG_CLIENT_ID`, `OTG_CLIENT_SECRET`, `OTG_REDIRECT_URI` | "OTG ile giriş yap". The secret is server-side only. |
| `LTI_PLATFORM_ISSUER`, `LTI_CLIENT_ID`, `LTI_DEPLOYMENT_IDS`, `LTI_PLATFORM_AUTH_URL`, `LTI_PLATFORM_JWKS_URL`, `LTI_TOOL_LAUNCH_URL`, `LTI_TOOL_PRIVATE_KEY` | Office hours from Moodle over LTI 1.3; an empty issuer keeps LTI off. |

## AI

| Variable | Purpose |
|---|---|
| `AI_PROVIDER` | `openai` (default) or `openrouter`; anything else stops start-up. |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`, `OPENAI_EMBEDDING_MODEL` | OpenAI. |
| `OPENROUTER_API_KEY`, `OPENROUTER_BASE_URL`, `OPENROUTER_MODEL`, `OPENROUTER_REPORT_MODEL`, `OPENROUTER_EMBEDDING_MODEL`, `OPENROUTER_APP_NAME` | OpenRouter. |
| `AI_PROFILE_PARSING_ENABLED`, `AI_SYLLABUS_ENABLED`, `AI_MATCHING_ENABLED`, `AI_MEETING_REPORT_ENABLED`, `AI_TASK_HANDOVER_ENABLED` | Per-feature switches, all on when unset (see [AI features](ai.md)). |
| `MATCHING_PGVECTOR_ENABLED` | Use the pgvector shortlist before ranking. |
| `TESSERACT_EXECUTABLE`, `TESSERACT_LANGUAGES`, `TESSERACT_TIMEOUT_SECONDS` | Local OCR for scanned PDFs; the image ships Turkish and English. |

## Meetings, realtime and notifications

| Variable | Purpose |
|---|---|
| `MEETING_PROVIDER_BASE_URL`, `MEETING_PROVIDER_API_KEY` | The meeting bot provider. |
| `MEETING_TRANSCRIPT_BOT_ENABLED`, `MEETING_TRANSCRIPT_BOT_INTERVAL_MS`, `MEETING_TRANSCRIPT_BOT_MAX_CONCURRENT` | The transcript bot job and how many bots may run at once. |
| `REALTIME_TOKEN_SECRET`, `REALTIME_TOKEN_TTL_SECONDS`, `REALTIME_ALLOWED_ORIGINS` | WebSocket tokens and allowed origins. |
| `REALTIME_DOCUMENT_TTL_HOURS`, `REALTIME_SEED_LOCK_SECONDS` | The live meeting note kept in Redis. |
| `DOCUMENTS_ALLOWED_ORIGINS` | Origins allowed to upload straight to the API. |
| `NOTIFICATION_REMINDERS_ENABLED`, `NOTIFICATION_REMINDERS_INTERVAL_MS` | The reminder job. |
