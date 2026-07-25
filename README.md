# Teknokent Backend

Spring Boot REST API for the Teknokent platform. Runs with Docker; Maven wrapper commands run inside Docker (no local Java required).

Works on macOS, Linux, and Windows (Docker Desktop).

## Quick Start

```bash
cp .env.example .env   # Windows: copy .env.example .env
docker compose up --build
```

- Health: http://localhost:8080/health
- Docs: http://localhost:8080/docs

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

## Stack

Java 25, Spring Boot 4.1, PostgreSQL 17, Flyway, Spring Security, springdoc-openapi, Lombok.

Tests use H2; local/production use PostgreSQL. Migrations: `src/main/resources/db/migration/`.

## Docker

Compose services: `app` (8080), `postgres` (5432). Production image: multi-stage `Dockerfile`.

```bash
docker build -t backend:latest .
```

CI runs on `main` via GitHub Actions (tests + compose smoke test).
