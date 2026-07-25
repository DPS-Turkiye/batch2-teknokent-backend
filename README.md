# Teknokent Backend

Spring Boot REST API for the Teknokent platform. The application is packaged with Docker; a local Java 25 installation is not required for development — Maven commands run inside Docker by default.

## Table of Contents

- [Tech Stack](#tech-stack)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Environment Variables](#environment-variables)
- [Development](#development)
- [API](#api)
- [Database and Migrations](#database-and-migrations)
- [Docker](#docker)
- [CI/CD](#cicd)
- [Project Structure](#project-structure)

## Tech Stack

| Component         | Version / Description           |
| ----------------- | ------------------------------- |
| Java              | 25 (Eclipse Temurin)            |
| Spring Boot       | 4.1.0                           |
| PostgreSQL        | 17                              |
| Flyway            | Schema migrations               |
| springdoc-openapi | 3.0.3 — Swagger UI              |
| Lombok            | Boilerplate reduction           |
| Maven Wrapper     | `./mvnw` — pinned Maven version |

Tests run against an H2 in-memory database; production and local development use PostgreSQL.

## Requirements

For local development, you only need:

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose
- (Optional) Local Java 25 — only if you plan to run `./mvnw` with `MVNW_USE_DOCKER=0`

The project can be built and run with `./mvnw` and `docker compose` even when Java 25 is not installed on the host machine.

## Quick Start

### 1. Create the environment file

```bash
cp .env.example .env
```

Replace `change-me` values in `.env` with secure passwords.

### 2. Start the application

```bash
docker compose up --build
```

Once the service is ready:

- Health check: [http://localhost:8080/health](http://localhost:8080/health)
- API documentation: [http://localhost:8080/docs](http://localhost:8080/docs)
- OpenAPI JSON: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### 3. Stop the stack

```bash
docker compose down
```

To remove the database volume as well:

```bash
docker compose down -v
```

## Environment Variables

All configuration is managed through the `.env` file. See `.env.example` for the template.

| Variable            | Description                                                                     |
| ------------------- | ------------------------------------------------------------------------------- |
| `DATABASE_NAME`     | PostgreSQL database name                                                        |
| `DATABASE_USERNAME` | PostgreSQL username                                                             |
| `DATABASE_PASSWORD` | PostgreSQL password                                                             |
| `DATABASE_PORT`     | Host port for PostgreSQL (default: `5432`; maps to `5432` inside the container) |
| `SERVER_PORT`       | HTTP port (default: `8080`)                                                     |

When running with Docker Compose, the app container connects to Postgres via the internal service name `teknokent-postgres` on port `5432` (set automatically in `compose.yml`). `DATABASE_PORT` controls the port exposed on your machine. The default `DATABASE_HOST` is `localhost` when running the app directly on your host.

## Development

### Maven commands

The project ships with the Maven Wrapper (`./mvnw`). By default, Maven runs inside the `eclipse-temurin:25-jdk-jammy` image:

```bash
./mvnw test          # Run unit tests
./mvnw clean package # Build (skip tests)
make test            # Makefile shortcut
make build
make clean
```

To use a local Java 25 installation:

```bash
MVNW_USE_DOCKER=0 ./mvnw test
```

### IDE

Shared Java formatter settings live under `.vscode/`. If Java 25 is not installed locally, the IDE may show build warnings; this does not affect building or running the project via Docker or `./mvnw`.

## API

### Health

```
GET /health
```

Example response:

```json
{
  "status": "UP",
  "timestamp": "2026-07-25T11:00:00Z"
}
```

### Documentation

| Endpoint       | Description                    |
| -------------- | ------------------------------ |
| `/docs`        | Swagger UI                     |
| `/v3/api-docs` | OpenAPI 3 specification (JSON) |

## Database and Migrations

Schema changes are managed with [Flyway](https://flywaydb.org/). Migration files live in:

```
src/main/resources/db/migration/
```

Add new migrations using the `V2__description.sql` naming convention. Flyway applies pending migrations automatically on startup.

Hibernate `ddl-auto` defaults to `validate` — the schema is managed only through Flyway; Hibernate does not create tables.

## Docker

### Compose services

| Service              | Container            | Port   |
| -------------------- | -------------------- | ------ |
| `teknokent`          | `teknokent`          | `8080` |
| `teknokent-postgres` | `teknokent-postgres` | `5432` |

### Production image

The `Dockerfile` uses a multi-stage build:

1. **build** — Maven package with JDK 25
2. **runtime** — minimal runtime image with JRE 25

Build the image for deployment (e.g. EC2):

```bash
docker build -t teknokent:latest .
```

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) runs a two-stage pipeline:

1. **test** — `./mvnw clean test` with Java 25
2. **smoke-test** — `docker compose up --build`, then verifies `/health`, `/docs`, and `/v3/api-docs`

The pipeline runs on pushes and pull requests to `main`.

## Project Structure

```
.
├── src/main/java/com/dps/teknokent/
│   ├── TeknokentApplication.java    # Entry point
│   ├── config/                      # Configuration (Swagger, etc.)
│   ├── controller/                  # REST controllers
│   └── dto/                         # Request/response models
├── src/main/resources/
│   ├── application.yml              # Application configuration
│   └── db/migration/                # Flyway SQL files
├── src/test/                        # Test resources (H2)
├── compose.yml                      # Local development stack
├── Dockerfile                       # Production image
├── Makefile                         # Build shortcuts
├── mvnw                             # Maven Wrapper (Docker-enabled)
└── .env.example                     # Environment variable template
```

---
