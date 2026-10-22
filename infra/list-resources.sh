#!/usr/bin/env bash
# SDD S.1, CA1: lists what infra/bootstrap.sh created on floci (read-only).
set -euo pipefail
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1 AWS_DEFAULT_REGION=us-east-1 AWS_PAGER=""
export MSYS_NO_PATHCONV=1
ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
AWS_CLI="${AWS_CLI:-aws}"
aws_() { "$AWS_CLI" --endpoint-url "$ENDPOINT" --region "$AWS_REGION" "$@"; }

echo "== S3 buckets";            aws_ s3api list-buckets --query 'Buckets[].Name' --output text | tr '\t' '\n' | sort
echo "== DynamoDB tables";       aws_ dynamodb list-tables --query 'TableNames' --output text | tr '\t' '\n' | sort
echo "== SQS queues";            aws_ sqs list-queues --query 'QueueUrls' --output text | tr '\t' '\n' | sed 's#.*/##' | sort
echo "== EventBridge buses";     aws_ events list-event-buses --query 'EventBuses[].Name' --output text | tr '\t' '\n' | sort
echo "== Rules on sandbox-domain"
for r in $(aws_ events list-rules --event-bus-name sandbox-domain --query 'Rules[].Name' --output text); do
  target=$(aws_ events list-targets-by-rule --rule "$r" --event-bus-name sandbox-domain --query 'Targets[0].Arn' --output text)
  echo "$r -> ${target##*:}"
done
echo "== Scheduler groups";      aws_ scheduler list-schedule-groups --query 'ScheduleGroups[].Name' --output text | tr '\t' '\n' | sort
