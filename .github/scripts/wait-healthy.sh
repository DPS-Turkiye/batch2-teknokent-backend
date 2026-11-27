#!/usr/bin/env bash
# Waits until the public health endpoint reports status UP for the expected release.
# Checking the version, not only UP, proves the new container is the one answering.
#
# Usage: wait-healthy.sh <health-url> <expected-version> <timeout-seconds>
set -euo pipefail

url="$1"
expected_version="$2"
timeout_seconds="$3"

deadline=$((SECONDS + timeout_seconds))
body=""
while :; do
  if body="$(curl -fsS --max-time 5 "$url" 2>/dev/null)" &&
    jq -e --arg v "$expected_version" '.status == "UP" and .version == $v' <<<"$body" >/dev/null 2>&1; then
    echo "Healthy: ${body}"
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    echo "Not healthy as ${expected_version} after ${timeout_seconds}s. Last response: ${body:-none}" >&2
    exit 1
  fi
  sleep 5
done
