#!/usr/bin/env bash
# SDD S.4, 8e: puts floci and MailHog back to a known empty state, so a scenario can prove "nothing left over".
# DESTRUCTIVE, but only for the emulator: it refuses any endpoint other than floci on localhost.
#   - deletes the domains' DynamoDB tables (state, waits, outbox, inbox, business tables), then bootstrap.sh
#     recreates them with the schemas documented by Stint;
#   - purges every sandbox SQS queue (work queues, their DLQs and the spy);
#   - deletes the one-time schedules of the <domain>-timers groups (the cron schedules are reconciled by the apps);
#   - deletes every MailHog message.
# Buckets and their objects are kept (documents are keyed by order, so a rerun overwrites them).
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
export AWS_PAGER=""
export MSYS_NO_PATHCONV=1

ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
MAILHOG="${MAILHOG_URL:-http://localhost:8025}"
AWS_CLI="${AWS_CLI:-aws}"
DOMAINS=(orders billing business engineering delivery)

case "$ENDPOINT" in
  http://localhost:4566|http://127.0.0.1:4566) ;;
  *) echo "reset.sh only resets floci on localhost:4566, not $ENDPOINT" >&2; exit 2 ;;
esac

aws_() { "$AWS_CLI" --endpoint-url "$ENDPOINT" --region "$AWS_REGION" "$@"; }
say() { printf '%-9s %s\n' "$1" "$2"; }

delete_table() {
  if aws_ dynamodb describe-table --table-name "$1" >/dev/null 2>&1; then
    aws_ dynamodb delete-table --table-name "$1" >/dev/null
    aws_ dynamodb wait table-not-exists --table-name "$1"
    say deleted "dynamodb:$1"
  fi
}

purge_queue() {
  local url
  url=$(aws_ sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>/dev/null || true)
  if [ -n "$url" ]; then aws_ sqs purge-queue --queue-url "$url" >/dev/null; say purged "sqs:$1"; fi
}

delete_schedules() { # group
  local names
  names=$(aws_ scheduler list-schedules --group-name "$1" --query 'Schedules[].Name' --output text 2>/dev/null || true)
  for n in $names; do
    [ "$n" = "None" ] && continue
    aws_ scheduler delete-schedule --group-name "$1" --name "$n" >/dev/null
    say deleted "scheduler:$1/$n"
  done
}

echo "Resetting the sandbox on $ENDPOINT and $MAILHOG"
for d in "${DOMAINS[@]}"; do
  for t in instances waits outbox inbox effects; do delete_table "$d-$t"; done
  for q in tasks results timers schedules domain-events; do purge_queue "$d-$q"; purge_queue "$d-$q-dlq"; done
  delete_schedules "$d-timers"
done
delete_table billing-invoices
# SDD S.5: the pipeline's tables and the shared services' queues.
delete_table records-catalog
for d in business engineering delivery; do
  delete_table "$d-audit"; delete_table "$d-corrections"
  for svc in privacy records channels; do purge_queue "$svc-tasks-$d"; purge_queue "$svc-tasks-$d-dlq"; done
done
for q in target-tasks-delivery channels-domain-events; do purge_queue "$q"; purge_queue "$q-dlq"; done
purge_queue sandbox-spy
curl -fsS -X DELETE "$MAILHOG/api/v1/messages" >/dev/null && say deleted "mailhog:messages"

bash "$(dirname "$0")/bootstrap.sh"
