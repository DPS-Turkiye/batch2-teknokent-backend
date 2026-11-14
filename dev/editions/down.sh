#!/usr/bin/env bash
# Stops both edition stacks from up.sh. Pass --volumes to also drop their databases.
set -euo pipefail
cd "$(dirname "$0")/../.."
for project in menton-teknokent menton-odtu; do
  APP_IMAGE=menton-editions-app:local docker compose -p "$project" down "$@"
done
