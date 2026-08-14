# Teknokent Backend: Original Template and Prototype Changes

## Purpose

This document explains:

1. What existed in the cloned GitHub repository before local development began.
2. What each original file was responsible for.
3. Which original files were modified locally.
4. Which files and API endpoints were added for the Teknokent prototype.

The comparison baseline is the original `main` branch at commit `c3af350`
(`v0.1.2`). The prototype work described here currently exists as local,
uncommitted changes on top of that commit.

## High-level summary

The cloned repository was a technical backend foundation. It already provided
Spring Boot, PostgreSQL, Docker, Flyway, security, Swagger, testing, CI, and an
AWS deployment workflow. Its only implemented application feature was a health
check backed by a single `health_probe` database table.

The prototype work preserved that foundation and added the first Teknokent
business slice:

- Users
- Organizations
- Mentorships
- Progress updates
- Bottlenecks
- Meetings
- Action items
- Dashboard totals
- A temporary API testing UI

The basic architecture remains:

```text
HTTP request
    -> Controller
    -> Service
    -> Repository
    -> JPA Entity
    -> PostgreSQL
```

## Part 1: Files in the original GitHub template

The original repository contained 34 tracked files.

### Root configuration and documentation

#### `.dockerignore`

Excludes build output, Git data, IDE files, logs, and temporary files from the
Docker build context. This keeps image builds smaller and prevents irrelevant
local files from being copied into the image.

#### `.env.example`

Provides the environment-variable template developers copy to `.env`.

The original values configured:

- The `dev` Spring profile
- Backend port `8080`
- PostgreSQL database name `db`
- PostgreSQL username `teknokent`
- PostgreSQL password `change-me`
- PostgreSQL host port `5432`

The real `.env` file is intentionally excluded from Git.

#### `.gitattributes`

Forces Unix line endings for `mvnw` and Windows line endings for `.cmd` files.
This prevents wrapper scripts from failing when used on different operating
systems.

#### `.gitignore`

Excludes private and generated files, including:

- `.env`
- Maven `target/` output
- Local CI caches
- IntelliJ, Eclipse, NetBeans, and VS Code local metadata
- The downloaded Maven wrapper JAR

It explicitly keeps `.env.example` and the shared VS Code configuration.

#### `README.md`

Originally contained the quick-start instructions, Docker commands, health and
Swagger URLs, test commands, technology stack, and a short Docker/CI overview.
It did not document any Teknokent business endpoints because those endpoints did
not exist yet.

### Maven and build files

#### `pom.xml`

Defines the Maven project as Spring Boot `4.1.0` using Java `25`.

Original production dependencies:

- Spring Web for REST APIs
- Spring Data JPA for persistence
- Spring Security for HTTP Basic authentication
- Spring Validation
- Flyway and the PostgreSQL Flyway module
- PostgreSQL JDBC driver
- Lombok
- Springdoc OpenAPI/Swagger

Original test dependencies:

- Spring Boot Test
- Spring MVC Test
- Spring Data JPA Test
- Spring Security Test
- H2

It also configures the Spring Boot Maven plugin, Java compiler release, method
parameter metadata, and Lombok annotation processing.

#### `mvnw`

The Maven wrapper for macOS, Linux, and Git Bash. The repository customized it
to run Maven inside a Java 25 Docker container unless it is already running
inside that container. This allows developers to run tests and builds without
installing matching Java and Maven versions locally.

#### `mvnw.cmd`

The Windows Command Prompt/PowerShell version of the Maven wrapper. It provides
the same Docker-based Maven behavior as `mvnw`.

#### `.mvn/wrapper/maven-wrapper.properties`

Pins Maven Wrapper `3.3.4` and Apache Maven `3.9.16`, and defines the Maven
distribution download URL.

### Docker files

#### `Dockerfile`

Defines a multi-stage production image:

1. A Java 25 JDK build stage copies the project and packages the Spring Boot
   application.
2. A smaller Java 25 JRE runtime stage copies the built JAR.
3. The runtime creates a non-root `spring` user.
4. The application starts on port `8080` with configured JVM memory settings.

Tests are skipped during the Docker image build because the CI workflow runs
them separately before deployment.

#### `compose.yml`

Originally defined:

- `app`: the Spring Boot application on host port `8080`
- `postgres`: PostgreSQL 17 on host port `5432`
- `postgres_data`: a named volume that preserves database data

The backend connects to PostgreSQL through Docker's internal hostname
`postgres` and internal port `5432`.

### GitHub automation

#### `.github/workflows/ci.yml`

Runs for pushes and pull requests targeting `main`.

It:

1. Hardens the GitHub runner.
2. Checks out the repository.
3. Caches Maven dependencies.
4. Runs `./mvnw -B clean test`.

#### `.github/workflows/deploy.yml`

Runs when a Git tag matching `v*` is pushed.

It:

1. Runs tests.
2. Authenticates to AWS through GitHub OIDC.
3. Logs in to Amazon ECR.
4. Builds and pushes versioned and `latest` Docker images.
5. Finds the configured EC2 instance.
6. Waits for its bootstrap/deployment script.
7. deploys the image through AWS Systems Manager.
8. Creates a GitHub Release.

This workflow depends on AWS infrastructure and GitHub repository variables
that are not stored in the codebase.

### VS Code configuration

#### `.vscode/extensions.json`

Recommends the Red Hat Java extension.

#### `.vscode/settings.json`

Enables Java format-on-save, trims trailing whitespace, and enforces final
newlines.

#### `.vscode/java-formatter.xml`

Contains the shared Eclipse Java formatting profile. It standardizes spacing,
indentation, braces, wrapping, and comment formatting.

### Original Spring Boot application files

#### `src/main/java/com/dps/teknokent/TeknokentApplication.java`

The application entry point. `@SpringBootApplication` enables Spring Boot
auto-configuration and component scanning, and `main` launches the server.

#### `src/main/resources/application.yml`

Originally configured:

- Backend port
- PostgreSQL connection
- Hikari connection pool
- JPA/Hibernate schema validation
- UTC database timestamps
- HTTP Basic username and password
- Flyway migrations
- Logging levels and format
- Swagger JSON and UI paths

The template already used `teknokent` / `change-me` as fallback values for both
the PostgreSQL account and the prototype HTTP Basic account.

#### `src/main/java/com/dps/teknokent/config/SecurityConfig.java`

Originally:

- Disabled CSRF for the stateless REST API
- Disabled server-side login sessions
- Enabled HTTP Basic authentication
- Allowed `/api/health` without authentication
- Allowed Swagger resources without authentication
- Required authentication for all other routes
- Allowed localhost CORS origins only in `dev` and `local` profiles

#### `src/main/java/com/dps/teknokent/config/SwaggerConfig.java`

Originally defined the Swagger/OpenAPI title, version, description, contact,
and local server URL.

### Original health-check feature

#### `src/main/java/com/dps/teknokent/controller/HealthController.java`

Exposes `GET /api/health`, calls `HealthService`, and returns HTTP `200` for
`UP` or HTTP `503` for `DOWN`. It includes Swagger documentation annotations.

#### `src/main/java/com/dps/teknokent/service/HealthService.java`

Reads the seeded health-probe record from the database. It reports the database
as `DOWN` if the record is missing, blank, or cannot be read. It also includes
the current timestamp and application version in the response.

#### `src/main/java/com/dps/teknokent/repository/HealthProbeRepository.java`

Extends `JpaRepository<HealthProbe, Short>`, giving the health service standard
database operations such as `findById` and `save`.

#### `src/main/java/com/dps/teknokent/entity/HealthProbe.java`

Maps the Java object to the `health_probe` table. Its fields are `id`,
`message`, and `updatedAt`.

#### `src/main/java/com/dps/teknokent/domain/HealthStatus.java`

Defines the `UP` and `DOWN` states, their JSON representation, and an `isUp`
helper method.

#### `src/main/java/com/dps/teknokent/dto/responses/HealthComponentsResponse.java`

Immutable response record containing the database health status.

#### `src/main/java/com/dps/teknokent/dto/responses/HealthResponse.java`

Immutable response record containing the overall status, timestamp,
application version, and component statuses.

### Original database migration

#### `src/main/resources/db/migration/V1__init.sql`

Creates the `health_probe` table and inserts a single row with ID `1` and
message `ok`. Flyway executes this migration once and records it in
`flyway_schema_history`.

### Original tests

#### `src/test/resources/application.yml`

Replaces PostgreSQL with an in-memory H2 database in PostgreSQL compatibility
mode. Hibernate creates and drops the test schema, and Flyway is disabled.

#### `src/test/java/com/dps/teknokent/TeknokentApplicationTests.java`

Verifies that the full Spring application context can start.

#### `src/test/java/com/dps/teknokent/controller/HealthControllerTest.java`

Tests the controller with a mocked service. It verifies the HTTP status and JSON
for both healthy and unhealthy responses.

#### `src/test/java/com/dps/teknokent/service/HealthServiceTest.java`

Tests health-service behavior when the probe exists, is missing, has a blank
message, or the repository throws an exception.

#### `src/test/java/com/dps/teknokent/repository/HealthProbeRepositoryTest.java`

Tests saving and retrieving a health probe through JPA.

#### `src/test/java/com/dps/teknokent/integration/HealthIntegrationTest.java`

Tests the complete health flow from an HTTP request through the controller,
service, repository, and test database.

## Part 2: Original template files modified locally

Only five tracked template files were modified. The original health feature,
Dockerfile, Maven configuration, application configuration, tests, CI, and
deployment workflow were otherwise preserved.

### `.env.example`

Changes:

- Added `APP_PORT` so the backend's host port can be changed without editing
  Compose.
- Added `SECURITY_USERNAME` and `SECURITY_PASSWORD` explicitly. Their default
  values already existed in `application.yml`; this change documents and
  exposes them through `.env`.
- Changed the PostgreSQL host port from `5432` to `5433` to avoid collision
  with the Mac's separately installed PostgreSQL server.

The backend still talks to PostgreSQL internally on port `5432`.

### `compose.yml`

Changed the backend port mapping from a fixed value:

```yaml
8080:8080
```

to a configurable host port:

```yaml
${APP_PORT:-8080}:8080
```

The PostgreSQL port was already configurable through `DATABASE_PORT`; the local
`.env` now sets that host-side value to `5433`.

### `src/main/java/com/dps/teknokent/config/SecurityConfig.java`

Changes:

- Allowed `/test-ui` and `/test-ui/**` without HTTP Basic authentication so the
  testing page can load its HTML, CSS, and JavaScript.
- Allowed `/api/swagger-ui/**` so Swagger's supporting resources load
  correctly.

Protected API operations still require HTTP Basic authentication.

### `src/main/java/com/dps/teknokent/config/SwaggerConfig.java`

Added an HTTP Basic OpenAPI security scheme named `basicAuth` and applied it as
the default security requirement. This enables Swagger's **Authorize** button
for protected endpoints.

### `README.md`

Added:

- Test UI URL
- Prototype authentication explanation
- New endpoint summary
- Seeded record IDs
- Example authenticated request
- Database-versus-HTTP-user clarification
- Updated PostgreSQL host-port documentation

### Local `.env`

`.env` is not a Git-tracked template file. It was created locally from
`.env.example`.

The local PostgreSQL host port was changed to `5433` so DataGrip reaches the
TeamTNT Docker database instead of the Mac's PostgreSQL server on `5432`.

## Part 3: New prototype files

### Database migration

#### `src/main/resources/db/migration/V2__core_prototype.sql`

Creates the prototype's business schema:

- `app_user`
- `organization`
- `mentorship`
- `progress_update`
- `bottleneck`
- `meeting`
- `action_item`

It defines foreign keys, status constraints, date constraints, indexes, and
timestamps. It also seeds an administrator, mentor, founder, demo organization,
and initial mentorship.

The original `V1__init.sql` was not modified. The new schema was correctly added
as the next Flyway migration.

### Domain enums

#### `domain/UserRole.java`

Defines prototype user roles such as administrator, mentor, and founder.

#### `domain/MentorshipStatus.java`

Defines the mentorship lifecycle: planned, active, paused, completed, or
cancelled.

#### `domain/ProgressStatus.java`

Defines progress states: on track, at risk, blocked, or completed.

#### `domain/BottleneckSeverity.java`

Defines low, medium, high, and critical severity.

#### `domain/BottleneckStatus.java`

Defines open, in-progress, resolved, and cancelled states.

#### `domain/MeetingStatus.java`

Defines scheduled, completed, and cancelled meeting states.

#### `domain/ActionItemStatus.java`

Defines open, in-progress, completed, and cancelled action-item states.

### JPA entities

#### `entity/AppUser.java`

Maps prototype users used as mentors, founders, owners, and authors.

#### `entity/Organization.java`

Maps a Teknokent company/startup, including its name, description, and audit
timestamps.

#### `entity/Mentorship.java`

Connects a mentor to an organization and stores status, dates, and goals.

#### `entity/ProgressUpdate.java`

Stores an organization's dated progress status, summary, metrics, and author.

#### `entity/Bottleneck.java`

Stores an organization's blocker, severity, status, owner, target-resolution
date, and resolution notes.

#### `entity/Meeting.java`

Stores organization meetings, optional mentors, schedule, duration, agenda,
notes, outcomes, meeting link, and status.

#### `entity/ActionItem.java`

Stores follow-up work linked to an organization and optionally a meeting. It
includes an owner, due date, status, and completion notes.

### Repositories

#### `repository/AppUserRepository.java`

Provides user persistence and sorted user lookup.

#### `repository/OrganizationRepository.java`

Provides organization persistence and queries used by the API.

#### `repository/MentorshipRepository.java`

Provides mentorship listing and status-count queries.

#### `repository/ProgressUpdateRepository.java`

Returns organization progress updates in reverse chronological order.

#### `repository/BottleneckRepository.java`

Returns organization bottlenecks and provides status/severity counts for the
dashboard.

#### `repository/MeetingRepository.java`

Lists meetings and counts upcoming scheduled meetings.

#### `repository/ActionItemRepository.java`

Lists action items and counts overdue unfinished items.

### Request DTOs

Request DTOs define and validate the JSON accepted by write endpoints.

#### `dto/requests/CreateOrganizationRequest.java`

Accepts a required organization name and optional description.

#### `dto/requests/CreateMentorshipRequest.java`

Accepts mentor and organization IDs, status, dates, and goals.

#### `dto/requests/CreateProgressUpdateRequest.java`

Accepts the progress author, category, status, summary, supporting evidence,
and recording date.

#### `dto/requests/CreateBottleneckRequest.java`

Accepts owner, title, description, severity, and target-resolution date.

#### `dto/requests/UpdateBottleneckRequest.java`

Accepts partial bottleneck changes, including owner, status, severity, dates,
and resolution notes.

#### `dto/requests/CreateMeetingRequest.java`

Accepts organization, optional mentor, schedule, duration, agenda, notes,
outcomes, link, and status.

#### `dto/requests/CreateActionItemRequest.java`

Accepts organization, optional meeting, owner, title, description, and due
date.

#### `dto/requests/UpdateActionItemRequest.java`

Accepts partial action-item changes, including owner, title, status, due date,
and completion notes.

### Response DTOs

Response DTOs keep API output separate from JPA entities and expose only the
fields intended for clients.

#### `dto/responses/UserResponse.java`

Returns user ID, email, name, and role.

#### `dto/responses/OrganizationResponse.java`

Returns organization details and audit timestamps.

#### `dto/responses/MentorshipResponse.java`

Returns mentor and organization identities, status, dates, and goals.

#### `dto/responses/ProgressUpdateResponse.java`

Returns organization, author, category, progress status, summary, supporting
evidence, and date.

#### `dto/responses/BottleneckResponse.java`

Returns organization, owner, blocker details, status, severity, dates, notes,
and audit timestamps.

#### `dto/responses/MeetingResponse.java`

Returns organization, optional mentor, schedule, content, link, and status.

#### `dto/responses/ActionItemResponse.java`

Returns organization, optional meeting, owner, task details, status, due date,
completion notes, and audit timestamps.

#### `dto/responses/DashboardSummaryResponse.java`

Returns organization totals, active mentorships, open and high-severity
bottlenecks, overdue actions, and upcoming meetings.

#### `dto/responses/ApiErrorResponse.java`

Defines a consistent JSON error shape containing the HTTP status, message,
request path, timestamp, and optional field-validation errors.

### Services

#### `service/DomainLookupService.java`

Centralizes required entity lookups and throws clear `404` errors when users,
organizations, or meetings do not exist.

#### `service/AppUserService.java`

Lists the seeded prototype users as API response DTOs.

#### `service/OrganizationService.java`

Lists, retrieves, and creates organizations.

#### `service/MentorshipService.java`

Lists and creates mentorships. It verifies that the selected user has the
mentor role.

#### `service/ProgressUpdateService.java`

Lists and creates progress updates for a specific organization.

#### `service/BottleneckService.java`

Lists, creates, and partially updates bottlenecks.

#### `service/MeetingService.java`

Lists and creates meetings and validates an optional mentor's role.

#### `service/ActionItemService.java`

Lists, creates, and partially updates action items. It checks that an attached
meeting belongs to the same organization.

#### `service/DashboardService.java`

Aggregates repository counts into the dashboard summary.

### Controllers and endpoints

#### `controller/AppUserController.java`

Adds:

- `GET /api/users`

#### `controller/OrganizationController.java`

Adds:

- `GET /api/organizations`
- `GET /api/organizations/{id}`
- `POST /api/organizations`

#### `controller/MentorshipController.java`

Adds:

- `GET /api/mentorships`
- `POST /api/mentorships`

#### `controller/ProgressUpdateController.java`

Adds:

- `GET /api/organizations/{organizationId}/progress`
- `POST /api/organizations/{organizationId}/progress`

#### `controller/BottleneckController.java`

Adds:

- `GET /api/organizations/{organizationId}/bottlenecks`
- `POST /api/organizations/{organizationId}/bottlenecks`
- `PATCH /api/bottlenecks/{id}`

#### `controller/MeetingController.java`

Adds:

- `GET /api/meetings`
- `POST /api/meetings`

#### `controller/ActionItemController.java`

Adds:

- `GET /api/actions`
- `POST /api/actions`
- `PATCH /api/actions/{id}`

#### `controller/DashboardController.java`

Adds:

- `GET /api/dashboard/summary`

#### `controller/TestUiController.java`

Redirects `/test-ui` to `/test-ui/index.html`.

### Error handling

#### `exception/ResourceNotFoundException.java`

Represents missing users, organizations, meetings, and other requested
resources.

#### `exception/InvalidRequestException.java`

Represents domain validation failures that are not covered by simple field
annotations.

#### `exception/GlobalExceptionHandler.java`

Converts exceptions into consistent API error responses. It handles missing
resources, invalid business requests, malformed/invalid request bodies, and
unexpected errors.

### Temporary test UI

#### `src/main/resources/static/test-ui/index.html`

Defines the internal test console's structure: authentication controls,
navigation, resource forms, dashboard cards, tables, and API inspector.

#### `src/main/resources/static/test-ui/styles.css`

Defines the desktop and responsive mobile presentation.

#### `src/main/resources/static/test-ui/app.js`

Calls the real backend endpoints with HTTP Basic authentication, renders
database results, submits resource forms, displays request timing/status, and
supports arbitrary GET, POST, and PATCH requests.

The test UI is intentionally a prototype/testing tool, not the final product
frontend.

## Part 4: What was preserved unchanged

The following important template pieces were preserved:

- Spring Boot application entry point
- Health endpoint and health database probe
- `application.yml` runtime architecture
- Original `V1__init.sql` migration
- Maven dependencies and build plugins
- Dockerfile
- Docker-based Maven wrappers
- Original health tests
- H2 test configuration
- GitHub CI workflow
- AWS deployment workflow
- VS Code formatting setup

## Part 5: Testing status and current limitations

The original test suite contains five test files and nine test methods. It still
passes after the prototype additions.

Current limitations:

- There are not yet dedicated automated tests for every newly added business
  endpoint and service.
- HTTP Basic authentication is a temporary prototype gate, not final user
  authentication.
- The test UI is for internal API verification only.
- The AWS deployment path has not been exercised locally because it requires
  the team's AWS infrastructure and GitHub variables.
- The local prototype changes have not yet been committed or pushed.

## Recommended reading order

For someone learning the template and the extension, use this order:

1. `README.md`
2. `.env.example`
3. `compose.yml`
4. `src/main/resources/application.yml`
5. `V1__init.sql`
6. Original health entity, repository, service, controller, and DTOs
7. Original health tests
8. `pom.xml`
9. `Dockerfile`
10. GitHub workflows
11. `V2__core_prototype.sql`
12. New enums and entities
13. New repositories
14. Request and response DTOs
15. Services
16. Controllers
17. Global error handling
18. Temporary test UI
