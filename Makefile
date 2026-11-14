# Shortcuts for the commands in README.md and docs/development.md. `make` alone lists them.
#
# Maven runs inside Docker through ./mvnw, so only Docker is needed. With JDK 25 installed, add LOCAL=1 to run it
# on the host instead (faster, same result): `make test LOCAL=1`.

MVNW := $(if $(LOCAL),MVNW_IN_DOCKER=1 )./mvnw -B -ntp
COMPOSE := docker compose

.DEFAULT_GOAL := help
.PHONY: help env up down reset logs moodle editions-up editions-down \
	verify ci test test-one format lint build image clean

help: ## List the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{ printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }'

## Running locally

env: ## Create .env from .env.example (keeps an existing one)
	@test -f .env && echo ".env already exists" || (cp .env.example .env && echo "Created .env from .env.example")

up: env ## Build and start the app, PostgreSQL and Redis (http://localhost:8080/api/health)
	$(COMPOSE) up --build -d
	@echo "App: http://localhost:8080/api/health  API docs (SWAGGER_ENABLED=true): http://localhost:8080/api/docs"

down: ## Stop the stack, keep the database
	$(COMPOSE) down

reset: ## Stop the stack and delete the database volume
	$(COMPOSE) down -v

logs: ## Follow the app's logs
	$(COMPOSE) logs -f app

moodle: env ## Also start the local Moodle for office hours (see dev/moodle/README.md)
	$(COMPOSE) --profile moodle up -d

editions-up: ## Start TEKNOKENT (:8080) and ODTU (:8090) side by side (see dev/editions/README.md)
	dev/editions/up.sh

editions-down: ## Stop both editions
	dev/editions/down.sh

## Building and checking

verify: ## The full gate CI runs: zero warnings, NullAway, tests, format, lint, coverage
	$(MVNW) clean verify

ci: ## The CI workflow's steps, in its order
	$(MVNW) spotless:check
	$(MVNW) checkstyle:check
	$(MVNW) clean verify -Dspotless.check.skip=true -Dcheckstyle.skip=true

test: ## Compile and run the tests (prints a coverage summary)
	$(MVNW) test

test-one: ## Run one test class or method: make test-one TEST=FooServiceTest[#method]
	@test -n "$(TEST)" || (echo "Usage: make test-one TEST=FooServiceTest" && exit 1)
	$(MVNW) test -Dtest='$(TEST)' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true

format: ## Format every file the build checks
	$(MVNW) spotless:apply

lint: ## Format and lint checks only, without compiling or testing
	$(MVNW) spotless:check checkstyle:check

build: ## Build the jar without running the tests
	$(MVNW) clean package -DskipTests

image: ## Build the production image as the deploy workflow does
	docker build --build-arg APP_VERSION=local -t teknokent-backend:local .

clean: ## Delete build output
	$(MVNW) clean
