#!/usr/bin/env bash
# Runs one shell command on an EC2 instance through SSM Run Command and waits for it to finish.
# Prints the command's stdout (stderr goes to stderr) and exits non-zero unless the command succeeded.
#
# Usage: ssm-run.sh <instance-id> <timeout-seconds> <command>
set -euo pipefail

instance_id="$1"
timeout_seconds="$2"
command="$3"

parameters="$(jq -cn --arg c "$command" --arg t "$timeout_seconds" '{commands: [$c], executionTimeout: [$t]}')"

command_id="$(aws ssm send-command \
  --document-name AWS-RunShellScript \
  --instance-ids "$instance_id" \
  --parameters "$parameters" \
  --query Command.CommandId \
  --output text)"

# Poll ourselves instead of `aws ssm wait`, which gives up after ~100 seconds.
deadline=$((SECONDS + timeout_seconds + 60))
status=Pending
while :; do
  status="$(aws ssm get-command-invocation \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query Status \
    --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Pending | InProgress | Delayed | Cancelling) ;;
    *) break ;;
  esac
  if ((SECONDS >= deadline)); then
    echo "SSM command ${command_id} still ${status} after ${timeout_seconds}s." >&2
    exit 1
  fi
  sleep 3
done

aws ssm get-command-invocation \
  --command-id "$command_id" \
  --instance-id "$instance_id" \
  --query StandardOutputContent \
  --output text
aws ssm get-command-invocation \
  --command-id "$command_id" \
  --instance-id "$instance_id" \
  --query StandardErrorContent \
  --output text >&2

if [ "$status" != Success ]; then
  echo "SSM command ${command_id} ended with status ${status}." >&2
  exit 1
fi
