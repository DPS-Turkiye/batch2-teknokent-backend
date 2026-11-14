# Teknokent Backend

The Spring Boot API behind Menton: startup mentoring for ODTÜ Teknokent, and in its ODTU edition office hours
(from ODTÜClass/Moodle) and İŞKUR peer mentoring. One codebase, two deployments; `APP_EDITION` picks one.

## Quick start

Only Docker is needed; Maven runs inside a container.

```bash
make up          # creates .env from .env.example, builds and starts the app, PostgreSQL and Redis
```

- Health: http://localhost:8080/api/health
- API docs: http://localhost:8080/api/docs (set `SWAGGER_ENABLED=true` in `.env`)

`make down` stops the stack, `make reset` also deletes the database. Without `make` (e.g. Windows):
`cp .env.example .env` then `docker compose up --build`.

## Commands

| Command | What it does |
|---|---|
| `make verify` | The full gate CI runs: zero-warning build, NullAway, tests, format, lint, coverage. Run before a PR. |
| `make test` | Compile and run the tests. `make test-one TEST=FooServiceTest` for one class. |
| `make format` | Format everything the build checks. |
| `make lint` | Format and lint checks only. |
| `make ci` | Exactly the CI workflow's steps. |
| `make image` | The production image, as the deploy workflow builds it. |
| `make` | Lists every target. |

Each target is a thin wrapper around `./mvnw` or `docker compose` (on Windows use `mvnw.cmd`). With JDK 25
installed, add `LOCAL=1` to run Maven on the host instead of in Docker.

## Documentation

| | |
|---|---|
| [Development](docs/development.md) | Setup, local stacks (both editions, Moodle), tests, migrations |
| [Architecture](docs/architecture.md) | Code layout, request flow, data stores, background jobs |
| [Security](docs/security.md) | Who the caller is, access rules, abuse limits, secrets |
| [AI features](docs/ai.md) | Profile parsing, mentor matching, meeting assistant, switches and fallbacks |
| [Configuration](docs/configuration.md) | Every environment variable |
| [Deployment](docs/deployment.md) | CI, releases, production |

**Contributing:** the rules every change follows, and what the build checks, are in [AGENTS.md](AGENTS.md).

## Stack

Java 25 · Spring Boot 4.1 · Spring Security · PostgreSQL 17 + pgvector · Flyway · Redis · Jackson 3 ·
AWS S3 and SES · Firebase Auth and Cloud Messaging · OpenAI / OpenRouter · Apache PDFBox and Tesseract OCR ·
springdoc-openapi · Lombok. Quality: Spotless, Error Prone, NullAway (JSpecify), Checkstyle, ArchUnit, JaCoCo.

## Layout

```
src/main/java/com/dps/teknokent/   controller, service, repository, entity, domain, dto, security, config, ...
src/main/resources/db/migration/   Flyway migrations (never edit one that has shipped)
src/main/resources/i18n/           Turkish and English texts users read
config/                            Checkstyle rules and the formatter profile
dev/                               Local multi-edition and Moodle setups
docs/                              This documentation
```
