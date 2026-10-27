#!/bin/bash
# Read-only CloudWatch poll of the prod RDS every 2 min; SIGINTs k6 when free memory < 40 MB.
# usage: AWS_PROFILE=menton scripts/loadtest/watch-rds.sh [once]
LIMIT=$((40*1024*1024))
while true; do
  S=$(date -u -v-5M +%Y-%m-%dT%H:%M:%SZ); E=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  q() { aws cloudwatch get-metric-statistics --region eu-central-1 --namespace AWS/RDS --metric-name $1 \
        --dimensions Name=DBInstanceIdentifier,Value=teknokent-postgres --start-time $S --end-time $E \
        --period 60 --statistics $2 --query "sort_by(Datapoints,&Timestamp)[-1].$2" --output text; }
  MEM=$(q FreeableMemory Minimum); CONN=$(q DatabaseConnections Maximum); CPU=$(q CPUUtilization Maximum)
  MB=$(python3 -c "print(int(float('$MEM')/1048576))" 2>/dev/null || echo "?")
  echo "$(date -u +%H:%M)Z rds free=${MB}MB conns=${CONN} cpu=${CPU}%"
  if [ "$MB" != "?" ] && [ "$(python3 -c "print(float('$MEM')<$LIMIT)")" = "True" ]; then
    echo "RDS free memory below 40 MB -> stopping k6"; pkill -INT -x k6; exit 0
  fi
  [ "$1" = "once" ] && exit 0
  pgrep -x k6 >/dev/null || { echo "k6 not running, watcher exits"; exit 0; }
  sleep 120
done
