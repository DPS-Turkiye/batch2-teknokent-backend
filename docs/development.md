# Development

## What you need

- Docker (Docker Desktop on macOS and Windows). `./mvnw` runs Maven inside an `eclipse-temurin:25-jdk` container,
  so no local Java is required.
- Optional: JDK 25. With it, `MVNW_IN_DOCKER=1 ./mvnw ...` (or `make ... LOCAL=1`) runs the same build on the host,
  which is faster.
- `make` (preinstalled on macOS and Linux). On Windows use the `./mvnw` / `mvnw.cmd` and `docker compose` commands
  the Makefile prints; they are all listed below.

## First run

```bash
make up          # creates .env from .env.example if missing, then docker compose up --build -d
```

- Health: http://localhost:8080/api/health
- API docs: http://localhost:8080/api/docs, only when `SWAGGER_ENABLED=true` in `.env`

The stack is the app, PostgreSQL 17 with pgvector and Redis. Flyway creates the schema and the demo seed data on
the first start. `make down` stops it; `make reset` also deletes the database.

Most endpoints expect the frontend in front of them (see [Security](security.md)). To call one by hand, use the
service credentials from `.env` (`SECURITY_USERNAME` / `SECURITY_PASSWORD`, `teknokent` / `change-me` locally) and
name the acting user where the endpoint takes an `actorId`:

```bash
curl -u teknokent:change-me "http://localhost:8080/api/dashboard/summary?organizationId=10000000-0000-0000-0000-000000000001"
```

Flyway seeds these records:

| Record | ID |
|---|---|
| Demo administrator | `00000000-0000-0000-0000-000000000001` |
| Demo mentor | `00000000-0000-0000-0000-000000000002` |
| Demo founder | `00000000-0000-0000-0000-000000000003` |
| Demo Startup | `10000000-0000-0000-0000-000000000001` |

Other local setups:

- `make editions-up`: TEKNOKENT on :8080 and ODTU on :8090 side by side ([dev/editions](../dev/editions/README.md)).
- `make moodle`: a local Moodle 4.5 standing in for ODTÜClass, for office hours over LTI
  ([dev/moodle](../dev/moodle/README.md)).

## Everyday commands

| Make | Same as | What it does |
|---|---|---|
| `make verify` | `./mvnw clean verify` | The whole gate CI runs. Run it before opening a PR. |
| `make test` | `./mvnw test` | Compile (zero warnings) and run the tests; prints a coverage summary. |
| `make test-one TEST=FooServiceTest` | `./mvnw test -Dtest=FooServiceTest` | One test class (or `Class#method`). |
| `make format` | `./mvnw spotless:apply` | Format everything the build checks. |
| `make lint` | `./mvnw spotless:check checkstyle:check` | Format and lint checks only. |
| `make ci` | the three CI steps | Exactly what `.github/workflows/ci.yml` runs. |
| `make build` | `./mvnw clean package -DskipTests` | The jar, without tests. |
| `make image` | `docker build ...` | The production image, as the deploy workflow builds it. |

## The gate

`make verify` fails unless all of these hold:

| Check | Tool | Configuration |
|---|---|---|
| Formatting | Spotless (Eclipse formatter) | `config/eclipse-formatter.xml` |
| Zero compiler warnings, likely bugs | javac lint + Error Prone | `pom.xml` |
| Null safety in `@NullMarked` packages | NullAway | `pom.xml`, `package-info.java` |
| Lint rules | Checkstyle | `config/checkstyle.xml` |
| Layering and package rules | ArchUnit | `src/test/java/.../ArchitectureTest.java` |
| Tests | JUnit 5, Mockito, AssertJ | `src/test` |
| Coverage floor | JaCoCo | `coverage.minimum.*` in `pom.xml` |

The first build in a checkout turns on the git hooks in `.githooks/`: `pre-commit` formats the staged Java files and
`pre-push` runs `./mvnw verify`. `git config core.hooksPath .git/hooks` turns them off for your checkout. VS Code
formats Java on save with the same profile as the build; install the recommended extensions when it asks.

## Tests

- Services: plain unit tests with Mockito (`@ExtendWith(MockitoExtension.class)`); strict stubs, so an unused
  `when(...)` fails the test.
- Database behaviour: `@DataJpaTest` or `@SpringBootTest` + `@Transactional` on H2.
- Controllers: `@WebMvcTest(XController.class)` + `@Import(SecurityConfig.class)` with `@MockitoBean` services; check
  the status, the JSON, that the caller comes from the session, and that an anonymous caller is refused.
- Names are sentences about behaviour (`rejectsAnyoneWhoIsNotAnAdmin`). Tests mirror main:
  `service/FooService` → `service/FooServiceTest`.

**Tests do not run the migrations.** The test profile builds the schema from the entities on H2 with Flyway off,
while production validates the real schema against the entities at start-up. A migration that disagrees with its
entity passes every test and then stops the application. Check a new migration with `make up` against PostgreSQL.

## Database migrations

`src/main/resources/db/migration/V<number>__what_it_does.sql`. A migration that has shipped never changes: Flyway
stores its checksum and refuses to start if the file differs. Add a new version instead. The formatter leaves this
folder alone for the same reason. iCloud copies (`V45__x 2.sql`) are ignored by git and Docker; delete them.
