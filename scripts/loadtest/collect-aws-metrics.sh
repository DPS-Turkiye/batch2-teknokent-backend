#!/usr/bin/env bash
# Read-only: collects the AWS numbers for one load-test window into a text file.
# Every call here is a describe/get/list/query — nothing is created, changed or deleted.
#
#   AWS_PROFILE=menton bash scripts/loadtest/collect-aws-metrics.sh            # T1 window (18:50–19:10 Turkey time)
#   AWS_PROFILE=menton START=2026-10-20T15:50:00Z END=2026-10-20T16:10:00Z bash scripts/loadtest/collect-aws-metrics.sh
#
# Output: ${OUT_DIR:-.}/loadtest-aws-metrics-<START>.txt
set -uo pipefail

REGION=eu-central-1
START=${START:-2026-10-20T15:50:00Z}   # = 18:50 Turkey time (UTC+3)
END=${END:-2026-10-20T16:10:00Z}       # = 19:10
OUT="${OUT_DIR:-.}/loadtest-aws-metrics-${START//:/}.txt"
exec > >(tee "$OUT") 2>&1

echo "# AWS metrics ${START} → ${END} (UTC), region ${REGION}"
echo "## caller"; aws sts get-caller-identity --query '[Account,Arn]' --output text

echo; echo "## app EC2 instances (tag Team=teknokent)"
aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Team,Values=teknokent" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].[InstanceId,InstanceType,Tags[?Key==`Name`]|[0].Value]' --output text
INSTANCE_ID=$(aws ec2 describe-instances --region "$REGION" \
  --filters "Name=tag:Team,Values=teknokent" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].[InstanceId,Tags[?Key==`Name`]|[0].Value]' --output text \
  | grep -vi vexa | awk 'NR==1{print $1}')
echo "using INSTANCE_ID=${INSTANCE_ID}"

metric() { # namespace metric dimName dimValue stat period [region] [extra dimension]
  echo; echo "## $1 $2 ($5, ${6}s)  [$3=$4]"
  local extra=()
  [ -n "${8:-}" ] && extra=(--dimensions "Name=$3,Value=$4" "$8")
  if [ ${#extra[@]} -eq 0 ]; then extra=(--dimensions "Name=$3,Value=$4"); fi
  aws cloudwatch get-metric-statistics --region "${7:-$REGION}" --namespace "$1" --metric-name "$2" \
    "${extra[@]}" --start-time "$START" --end-time "$END" \
    --period "$6" --statistics "$5" --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,'"$5"']' --output text
}

metric AWS/EC2 CPUUtilization InstanceId "$INSTANCE_ID" Maximum 300
metric AWS/EC2 CPUUtilization InstanceId "$INSTANCE_ID" Average 300
metric AWS/EC2 CPUCreditBalance InstanceId "$INSTANCE_ID" Average 300
metric AWS/EC2 NetworkOut InstanceId "$INSTANCE_ID" Sum 300

metric AWS/RDS CPUUtilization DBInstanceIdentifier teknokent-postgres Maximum 60
metric AWS/RDS DatabaseConnections DBInstanceIdentifier teknokent-postgres Maximum 60
metric AWS/RDS CPUCreditBalance DBInstanceIdentifier teknokent-postgres Average 300
metric AWS/RDS ReadLatency DBInstanceIdentifier teknokent-postgres Average 60
metric AWS/RDS FreeableMemory DBInstanceIdentifier teknokent-postgres Minimum 300

metric AWS/ElastiCache EngineCPUUtilization CacheClusterId teknokent-redis-001 Maximum 60
metric AWS/ElastiCache CurrConnections CacheClusterId teknokent-redis-001 Maximum 60

echo; echo "## CloudFront distribution for d2ulpskrb4nc9m.cloudfront.net"
DIST_ID=$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?DomainName=='d2ulpskrb4nc9m.cloudfront.net'].Id" --output text)
echo "DIST_ID=${DIST_ID}"
for m in Requests:Sum 5xxErrorRate:Average 4xxErrorRate:Average OriginLatency:Average; do
  metric AWS/CloudFront "${m%%:*}" DistributionId "$DIST_ID" "${m##*:}" 60 us-east-1 "Name=Region,Value=Global"
done

echo; echo "## Amplify apps (the frontend) — which region hosts menton"
for r in eu-central-1 eu-west-1 eu-west-2 eu-north-1 us-east-1 us-east-2 us-west-2; do
  aws amplify list-apps --region "$r" --query 'apps[].[name,appId,defaultDomain,platform]' --output text 2>/dev/null \
    | sed "s/^/$r  /"
done

to_epoch() { date -j -u -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s; }
logs_query() { # query-string
  local qid status
  qid=$(aws logs start-query --region "$REGION" --log-group-name /batch2/teknokent/app \
    --start-time "$(to_epoch "$START")" --end-time "$(to_epoch "$END")" \
    --query-string "$1" --query queryId --output text)
  for _ in $(seq 1 30); do
    status=$(aws logs get-query-results --region "$REGION" --query-id "$qid" --query status --output text)
    [ "$status" = "Complete" ] && break; sleep 2
  done
  aws logs get-query-results --region "$REGION" --query-id "$qid" \
    --query 'results[].[ [?field==`@timestamp`].value|[0], [?field==`@message`].value|[0], [?field==`level`].value|[0], [?field==`count(*)`].value|[0] ]' \
    --output text | cut -c1-300
}

echo; echo "## App log: errors, warnings, pool timeouts"
logs_query 'fields @timestamp, @message | filter @message like /ERROR|WARN|Hikari|timeout|Timeout|Connection is not available/ | sort @timestamp desc | limit 100'
echo; echo "## App log: lines by level"
logs_query 'parse @message /(?<level>ERROR|WARN|INFO|DEBUG)/ | stats count(*) by level'

echo; echo "# done → $OUT"
