# Deployment

## CI

`.github/workflows/ci.yml` runs on every pull request (stacked PRs included) and on pushes to `main`:

1. `./mvnw spotless:check`: formatting
2. `./mvnw checkstyle:check`: lint
3. `./mvnw clean verify`: zero-warning build with Error Prone and NullAway, every test, `ArchitectureTest` and the
   coverage floor

`make ci` runs the same steps locally. Maven runs inside the `eclipse-temurin:25-jdk-jammy` container through
`./mvnw`, as on a developer machine; the Maven repository is cached in `.ci/m2`.

## Releasing

Pushing a tag `v*` runs `.github/workflows/deploy.yml`:

1. The same gate as CI (`./mvnw clean verify`).
2. `docker build --build-arg APP_VERSION=<tag>`: a multi-stage build (JDK 25 to build, JRE 25 with Tesseract to run,
   as a non-root user), pushed to ECR as `<tag>` and `latest`.
3. Over SSM, `/opt/batch2/deploy.sh <image>` on the EC2 instance tagged `teknokent-app` pulls and restarts it. The
   script lives on the server, not in this repository.
4. A GitHub release with generated notes.

## In production

- A single EC2 instance runs the container behind CloudFront. The scheduled jobs assume one instance; running more
  would send reminders twice.
- The container's port must accept traffic only from CloudFront: the per-IP limits read `X-Forwarded-For`.
- PostgreSQL with pgvector, Redis (ElastiCache) with a password, S3 and SES in `eu-central-1`.
- Settings come from the server's environment (see [Configuration](configuration.md)). A released image refuses to
  start with a placeholder `SECURITY_PASSWORD`, `DATABASE_PASSWORD` or `REALTIME_TOKEN_SECRET`.
- Flyway migrates the database at start-up and Hibernate validates the entities against it; a migration that does
  not match its entity stops the start (see [Development](development.md#database-migrations)).
- The JVM heap is half the machine's memory (`JAVA_OPTS` in the `Dockerfile`), so a larger instance needs no image
  change.
- `/api/health` is the health check.
