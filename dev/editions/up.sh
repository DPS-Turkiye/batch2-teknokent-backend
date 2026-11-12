#!/usr/bin/env bash
# Both editions side by side from this checkout, each with its own Postgres and Redis (never your usual stack's):
#   TEKNOKENT  app :8080  postgres :5435  redis :6381   (compose project menton-teknokent, your .env)
#   ODTU       app :8090  postgres :5434  redis :6380   (compose project menton-odtu, .env.odtu)
# Stop with dev/editions/down.sh; add --volumes there to drop both databases.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ ! -f .env ]; then
  echo "No .env here: copy .env.example to .env and fill it in first." >&2
  exit 1
fi

# .env.odtu = .env minus the keys the ODTÜ stack overrides, plus those overrides.
overrides=dev/editions/odtu.env
keys=$(grep -E '^[A-Z_]+=' "$overrides" | cut -d= -f1 | paste -sd'|' -)
{ grep -vE "^(${keys})=" .env; echo; cat "$overrides"; } > .env.odtu

export APP_IMAGE=menton-editions-app:local
APP_PORT=8080 DATABASE_PORT=5435 REDIS_PORT=6381 APP_ENV_FILE=.env \
  docker compose -p menton-teknokent up -d --build app
APP_PORT=8090 DATABASE_PORT=5434 REDIS_PORT=6380 APP_ENV_FILE=.env.odtu \
  docker compose -p menton-odtu up -d app

for port in 8080 8090; do
  printf 'Waiting for :%s' "$port"
  for _ in $(seq 1 90); do
    if curl -fsS "http://localhost:${port}/api/health" >/dev/null 2>&1; then
      echo " up"
      continue 2
    fi
    printf .
    sleep 2
  done
  echo " not up after 3 minutes; see docker compose -p menton-$([ "$port" = 8080 ] && echo teknokent || echo odtu) logs app" >&2
  exit 1
done
