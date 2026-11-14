# Repository rules

These rules apply to every change, by people and by coding agents. Most of them are checked by the build; the rest are conventions the code already follows.

## Definition of done

- `./mvnw verify` passes. It is the whole gate, the same one CI runs:
  - the code compiles with **zero warnings** (javac lint and Error Prone both fail the build),
  - every test passes,
  - every file is formatted (Spotless),
  - Checkstyle reports nothing,
  - coverage stays at or above the floors in `pom.xml`.
- New or changed logic has tests. The coverage floors are never lowered.
- Do not loosen `config/checkstyle.xml`, the compiler and Error Prone flags in `pom.xml`, `ArchitectureTest` or the coverage floors to make a change pass. If a rule is wrong for a case, say so in the PR.

## Commands

`./mvnw` runs Maven inside Docker, so no local Java is needed. With JDK 25 installed, `MVNW_IN_DOCKER=1 ./mvnw ...` runs the same build without Docker. The `Makefile` wraps these (`make verify`, `make test-one TEST=FooServiceTest`, `make format`; `make` lists them all, `LOCAL=1` skips Docker).

| Command                                  | What it does                                                          |
| ---------------------------------------- | --------------------------------------------------------------------- |
| `./mvnw verify`                          | The full gate above. Run it before opening a PR.                      |
| `./mvnw test`                            | Compile (zero warnings) and run the tests; prints a coverage summary. |
| `./mvnw test -Dtest=FooServiceTest`      | One test class.                                                       |
| `./mvnw spotless:apply`                  | Format everything.                                                    |
| `./mvnw spotless:check checkstyle:check` | Format and lint checks only, without the tests.                       |

The first build in a checkout turns on the git hooks in `.githooks/`: `pre-commit` formats the staged Java files and `pre-push` runs `./mvnw verify`. `git config core.hooksPath .git/hooks` turns them off for your checkout.

VS Code formats on save with the same profile as the build (`config/eclipse-formatter.xml`), so a saved file is already formatted.

## Where code goes

All under `src/main/java/com/dps/teknokent/`. `ArchitectureTest` enforces the dependencies between these packages.

| Layer                  | Package                         | Owns                                                                                                                           |
| ---------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Controllers            | `controller`                    | The HTTP mapping: read the caller, validate the body (`@Valid`), call a service, return its DTO. No repositories, no entities. |
| Services               | `service`                       | Rules, permissions, transactions, and calls to repositories and outside services.                                              |
| Repositories           | `repository`                    | Spring Data interfaces and queries. No rules, and no calls back into services.                                                 |
| Entities               | `entity`                        | JPA entities. They never leave through a controller.                                                                           |
| Domain                 | `domain`                        | Enums and value types. No Spring, and nothing from the layers above.                                                           |
| Requests and responses | `dto/requests`, `dto/responses` | Records for API payloads. `dto/provider` holds the payloads of outside APIs.                                                   |
| Security               | `security`                      | Sign-in, token checks, and who the caller is (`CurrentActor`).                                                                 |
| Configuration          | `config`, `filter`              | Spring configuration and servlet filters.                                                                                      |
| Helpers                | `util`                          | Small shared functions.                                                                                                        |

Outside Java:

- `src/main/resources/db/migration/` holds the Flyway migrations, `V<number>__what_it_does.sql`.
- `src/main/resources/i18n/{tr,en}/texts.properties` holds every text the backend writes for people.

## Rules

1. **Controllers have no business logic.** A controller method resolves the caller, calls one service and returns what it gives. A rule, a permission check or a query belongs in a service.
2. **Who is acting comes from the session.** Use `CurrentActor.resolve(...)`. A verified bearer token always wins; an id sent in the request is trusted only on the frontend's service credentials. Never treat an id from the request as the caller.
3. **Dependencies come through the constructor.** No `@Autowired` fields. A class with a second constructor for tests (for a `Clock`, for example) marks the one Spring uses with `@Autowired`.
4. **Errors are the shared exceptions.** Throw `ResourceNotFoundException` (404), `InvalidRequestException` (400) or `ConflictException` (409); for any other status (403, 502, 503) throw `ResponseStatusException` with a translated reason. `GlobalExceptionHandler` turns them all into the JSON the frontend expects. Do not build error responses or catch `Exception` in a controller.
5. **Time has a zone.** A moment is an `Instant`. "Today" is `LocalDate.now(MeetingSlotTimes.APP_ZONE)`, never `LocalDate.now()`: the servers run in UTC and the users are in Turkey. A class whose rules depend on the time takes a `Clock`.
6. **Text has a locale and a charset.** `toLowerCase(Locale.ROOT)` and `getBytes(StandardCharsets.UTF_8)`, always. On a machine set to Turkish, `"I".toLowerCase()` is `"ı"`, which breaks enum and e-mail comparisons.
7. **No hardcoded text for people.** Notifications, e-mails and error texts that a user reads are keys in `i18n/tr/texts.properties` (and `en`), read through `Texts`. Turkish is the source and the fallback. Code, comments and identifiers are in English.
8. **A migration that has shipped never changes.** Flyway stores a checksum of each applied file; editing one stops the application from starting. Add a new `V<next>__...sql` instead. Formatting tools leave this folder alone for the same reason.
9. **Suppressions need a reason, on the same line.** `@SuppressWarnings("unchecked") // why`. For one Checkstyle finding: `@SuppressWarnings("checkstyle:CheckName") // why`. A suppression without a reason fails lint. `@Disabled` tests need a reason too.
10. **Log through SLF4J.** No `System.out`, no `printStackTrace()`. The logger field is named `log`.
11. **Tests mirror main.** `service/FooService` is tested in `src/test/java/.../service/FooServiceTest`. A test name is a sentence about behaviour (`rejectsAnyoneWhoIsNotAnAdmin`). Assert what the code does for its caller, not how it does it.
12. **Comments say why, not what.** Write a short comment for a decision or a constraint that is not obvious. Do not narrate the code.
13. **JSON is Jackson 3.** Spring MVC reads and writes bodies with `tools.jackson`; use `tools.jackson.databind.JsonNode` and the injected `JsonMapper`/`JsonUtils`, never `com.fasterxml.jackson.databind` (a Jackson 2 node in a DTO arrives as a 500 and leaves as garbage). The annotations in `com.fasterxml.jackson.annotation` are shared and fine. `ArchitectureTest` enforces it.
14. **Reuse the shared helpers** before writing a private one: `TextUtils` (trimming, blank to null), `EmailDomains.normalizeEmail`, `RandomTokens`, `PdfBytes`, `Downloads.attachment` (every file download), `JsonSchema` (AI structured-output schemas) and `ModelOutput` (reading what a model returned).
15. **Null is part of the type.** Every main package except `entity` and `dto/requests` is `@NullMarked` (its `package-info.java`): a type is non-null unless it is written `@Nullable` (`org.jspecify.annotations.Nullable`), and NullAway fails the build on a possible null dereference. Mark a value `@Nullable` only when it can really be absent (an optional record component, a "not found" return); otherwise check for null where it arrives. A new package gets a `package-info.java` with `@NullMarked`.

## What the tools check

| Tool                     | Configuration                                            | Checks                                                                                                                                                     |
| ------------------------ | -------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Spotless                 | `pom.xml`, `config/eclipse-formatter.xml`                | Layout, import order, unused imports; also `pom.xml` and trailing whitespace in configuration files.                                                       |
| javac lint + Error Prone | `pom.xml` (`maven-compiler-plugin`, `error-prone.flags`) | Likely bugs that need types: ignored return values, default time zone, locale or charset, comparing objects with `==`, misplaced Javadoc, deprecated APIs. |
| NullAway                 | `pom.xml` (`error-prone.flags`), `package-info.java`     | Possible null dereferences, and nulls passed or returned where the type says non-null, in every `@NullMarked` package.                                       |
| Checkstyle               | `config/checkstyle.xml`                                  | Braces, one statement per line, names, wildcard imports, JUnit 4 imports, `System.out`, suppressions without a reason.                                     |
| ArchitectureTest         | `src/test/java/.../ArchitectureTest.java`                | Which package may use which, and where controllers, services, repositories and entities live.                                                              |
| JaCoCo                   | `pom.xml` (`coverage.minimum.*`)                         | The coverage floors.                                                                                                                                       |

## Known traps

- **Tests do not run the migrations.** The test profile builds the schema from the entities on H2 with Flyway off, while production validates the real schema against the entities at start-up. A migration that disagrees with its entity passes every test and then stops the application from starting. Check a new migration against a real PostgreSQL (`docker compose up --build`).
- **Two editions share this code.** `APP_EDITION` is `TEKNOKENT` or `ODTU`, and each answers 404 for the other's endpoints. The paths that belong to one edition are listed in `domain/Edition.java`; an endpoint that is not listed there is served by both.
- **`.env` holds real credentials** and is ignored by git. `.env.example` is the template; a new setting goes there with a safe default.
- **iCloud copies** (`V45__x 2.sql`, `Foo 3.java`) are ignored by git and Docker on purpose: two migrations with one version stop Flyway. Delete them when you see them.
