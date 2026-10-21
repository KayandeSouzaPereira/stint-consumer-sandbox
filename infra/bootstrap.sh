#!/usr/bin/env bash
# SDD S.1, RF5: creates every AWS resource the sandbox needs on floci. Idempotent: run it as often as you like,
# nothing is duplicated and nothing that exists is changed.
#
# Schemas and settings are copied from Stint (SHA a52b20a, phase-3a/ai-decision-workers; unchanged since 500dfd1):
#   - DynamoDB tables: stint-it/.../DynamoDbTestTables.java and docs/connectors.md (outbox, inbox)
#   - SQS: docs/connectors.md "SQS setup" (redrive maxReceiveCount 5, visibility 30 s, retention 4 d; DLQ 14 d)
#   - EventBridge -> SQS with InputPath $.detail: docs/connectors.md; catch-all pattern from FlociEmitIT
#   - Scheduler groups: SDD 2.4 / 2.7
# Event routing and the sandbox-facts access policy follow contracts/events.md.
#
# Requires the AWS CLI v2. Credentials are floci's dummy values; never point this at a real account.
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
export AWS_PAGER=""
# Git Bash on Windows: don't rewrite "$.detail" or other arguments into Windows paths.
export MSYS_NO_PATHCONV=1

ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4566}"
AWS_CLI="${AWS_CLI:-aws}"
ACCOUNT=000000000000
DOMAINS=(orders billing)
# SDD S.5: the meeting pipeline's domains with an engine, and the shared services with a queue per caller (8c, 8l).
PIPELINE_DOMAINS=(business engineering delivery)

aws_() { "$AWS_CLI" --endpoint-url "$ENDPOINT" --region "$AWS_REGION" "$@"; }
say() { printf '%-9s %s\n' "$1" "$2"; }

# --- S3 ---------------------------------------------------------------------------------------------------
ensure_bucket() {
  if aws_ s3api head-bucket --bucket "$1" >/dev/null 2>&1; then say exists "s3://$1"
  else aws_ s3api create-bucket --bucket "$1" >/dev/null; say created "s3://$1"; fi
}

# Applies the policy only when it differs from the bucket's (floci stores it but doesn't enforce IAM:
# the policy is declarative, see contracts/events.md).
ensure_bucket_policy() { # bucket, policy file
  local wanted current
  wanted=$(tr -d '[:space:]' < "$2")
  current=$(aws_ s3api get-bucket-policy --bucket "$1" --query Policy --output text 2>/dev/null | tr -d '[:space:]' || true)
  if [ "$current" = "$wanted" ]; then say exists "policy:s3://$1"
  else aws_ s3api put-bucket-policy --bucket "$1" --policy "file://$2" >/dev/null; say ensured "policy:s3://$1"; fi
}

# --- DynamoDB ---------------------------------------------------------------------------------------------
table_exists() { aws_ dynamodb describe-table --table-name "$1" >/dev/null 2>&1; }

ensure_table() { # name, then the create-table arguments
  local name="$1"; shift
  if table_exists "$name"; then say exists "dynamodb:$name"; return; fi
  aws_ dynamodb create-table --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
  aws_ dynamodb wait table-exists --table-name "$name"
  say created "dynamodb:$name"
}

ensure_ttl() { # table, attribute
  local status
  status=$(aws_ dynamodb describe-time-to-live --table-name "$1" --query 'TimeToLiveDescription.TimeToLiveStatus' --output text)
  if [ "$status" = "ENABLED" ]; then say exists "ttl:$1.$2"
  else aws_ dynamodb update-time-to-live --table-name "$1" --time-to-live-specification "Enabled=true,AttributeName=$2" >/dev/null
       say created "ttl:$1.$2"; fi
}

domain_tables() { # domain
  local d="$1"
  ensure_table "$d-instances" \
    --attribute-definitions AttributeName=instanceId,AttributeType=S \
    --key-schema AttributeName=instanceId,KeyType=HASH
  ensure_table "$d-waits" \
    --attribute-definitions AttributeName=waitKey,AttributeType=S AttributeName=instanceId,AttributeType=S \
    --key-schema AttributeName=waitKey,KeyType=HASH \
    --global-secondary-indexes 'IndexName=byInstance-index,KeySchema=[{AttributeName=instanceId,KeyType=HASH}],Projection={ProjectionType=ALL}'
  ensure_table "$d-outbox" \
    --attribute-definitions AttributeName=eventId,AttributeType=S AttributeName=shard,AttributeType=S AttributeName=createdAt,AttributeType=N \
    --key-schema AttributeName=eventId,KeyType=HASH \
    --global-secondary-indexes 'IndexName=pending-by-age,KeySchema=[{AttributeName=shard,KeyType=HASH},{AttributeName=createdAt,KeyType=RANGE}],Projection={ProjectionType=ALL}'
  ensure_table "$d-inbox" \
    --attribute-definitions AttributeName=waitKey,AttributeType=S AttributeName=eventId,AttributeType=S \
    --key-schema AttributeName=waitKey,KeyType=HASH AttributeName=eventId,KeyType=RANGE
  ensure_ttl "$d-inbox" expiresAt
}

# SDD S.2, 8c: Billing's business records (invoices) and its worker's effect markers (RF5).
billing_tables() {
  ensure_table billing-invoices \
    --attribute-definitions AttributeName=invoiceId,AttributeType=S \
    --key-schema AttributeName=invoiceId,KeyType=HASH
  ensure_table billing-effects \
    --attribute-definitions AttributeName=effectKey,AttributeType=S \
    --key-schema AttributeName=effectKey,KeyType=HASH
}

# SDD S.3, RF4: Orders' worker effect markers (same schema as billing-effects).
orders_tables() {
  ensure_table orders-effects \
    --attribute-definitions AttributeName=effectKey,AttributeType=S \
    --key-schema AttributeName=effectKey,KeyType=HASH
}

# SDD S.5, 8j: the register's catalog (one entry per record version), and Stint's review audit and correction log per
# pipeline domain (3a.4 / 3a.5, schemas from stint-aws DynamoDbAuditLog / DynamoDbCorrectionLog).
pipeline_tables() {
  ensure_table records-catalog     --attribute-definitions AttributeName=recordId,AttributeType=S AttributeName=version,AttributeType=N     --key-schema AttributeName=recordId,KeyType=HASH AttributeName=version,KeyType=RANGE
  # SDD S.7, 8c: the approvals of each record version, apart from the catalog so a version stays immutable
  ensure_table records-approvals   --attribute-definitions AttributeName=recordKey,AttributeType=S AttributeName=reviewId,AttributeType=S     --key-schema AttributeName=recordKey,KeyType=HASH AttributeName=reviewId,KeyType=RANGE
  # SDD S.10, 8e: the SDDs parked in the DLQ, waiting for the technician (the daily summary reads them)
  ensure_table delivery-parked     --attribute-definitions AttributeName=parkKey,AttributeType=S     --key-schema AttributeName=parkKey,KeyType=HASH
  for d in "${PIPELINE_DOMAINS[@]}"; do
    ensure_table "$d-audit"       --attribute-definitions AttributeName=instanceId,AttributeType=S AttributeName=entry,AttributeType=S       --key-schema AttributeName=instanceId,KeyType=HASH AttributeName=entry,KeyType=RANGE
    ensure_ttl "$d-audit" expiresAt
    ensure_table "$d-corrections"       --attribute-definitions AttributeName=definition,AttributeType=S AttributeName=entry,AttributeType=S       --key-schema AttributeName=definition,KeyType=HASH AttributeName=entry,KeyType=RANGE
    ensure_ttl "$d-corrections" expiresAt
  done
}

# --- SQS --------------------------------------------------------------------------------------------------
queue_url() { aws_ sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>/dev/null || true; }
queue_arn() { echo "arn:aws:sqs:$AWS_REGION:$ACCOUNT:$1"; }

ensure_queue() { # name [attributes-json]
  if [ -n "$(queue_url "$1")" ]; then say exists "sqs:$1"; return; fi
  if [ $# -ge 2 ]; then aws_ sqs create-queue --queue-name "$1" --attributes "$2" >/dev/null
  else aws_ sqs create-queue --queue-name "$1" >/dev/null; fi
  say created "sqs:$1"
}

ensure_queue_with_dlq() { # name
  ensure_queue "$1-dlq" '{"MessageRetentionPeriod":"1209600"}'
  local redrive="{\\\"deadLetterTargetArn\\\":\\\"$(queue_arn "$1-dlq")\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}"
  ensure_queue "$1" "{\"VisibilityTimeout\":\"30\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"$redrive\"}"
}

domain_queues() { # domain
  for q in tasks results timers schedules domain-events; do ensure_queue_with_dlq "$1-$q"; done
}

# SDD S.5, 8c/8l: a task invocation carries no reply address, so each shared service has one queue per caller and
# answers on that caller's <domain>-results.
pipeline_queues() {
  for d in "${PIPELINE_DOMAINS[@]}"; do
    for svc in privacy records channels; do ensure_queue_with_dlq "$svc-tasks-$d"; done
  done
  ensure_queue_with_dlq target-tasks-delivery
  ensure_queue_with_dlq channels-domain-events
}

# --- EventBridge ------------------------------------------------------------------------------------------
BUS=sandbox-domain

ensure_bus() {
  if aws_ events describe-event-bus --name "$BUS" >/dev/null 2>&1; then say exists "events:$BUS"
  else aws_ events create-event-bus --name "$BUS" >/dev/null; say created "events:$BUS"; fi
}

ensure_rule() { # name, pattern, target queue  (put-rule / put-targets are idempotent)
  aws_ events put-rule --name "$1" --event-bus-name "$BUS" --event-pattern "$2" >/dev/null
  aws_ events put-targets --rule "$1" --event-bus-name "$BUS" \
    --targets "Id=$3,Arn=$(queue_arn "$3"),InputPath=\$.detail" >/dev/null
  say ensured "rule:$1 -> sqs:$3"
}

# --- EventBridge Scheduler --------------------------------------------------------------------------------
ensure_schedule_group() {
  if aws_ scheduler get-schedule-group --name "$1" >/dev/null 2>&1; then say exists "scheduler:$1"
  else aws_ scheduler create-schedule-group --name "$1" >/dev/null; say created "scheduler:$1"; fi
}

# --- main -------------------------------------------------------------------------------------------------
echo "Bootstrapping sandbox resources on $ENDPOINT"

ensure_bucket stint-sandbox-reports   # sandbox-reports (Phase 1 regression)
ensure_bucket sandbox-facts           # facts too large for the bus (dataref) and invoice documents, shared
ensure_bucket_policy sandbox-facts "$(dirname "$0")/policies/sandbox-facts.json"
# SDD S.5: each AWS CLI call costs about a second on Windows, so the domains are set up side by side (they share no
# resource, and every ensure_* is idempotent); the output of one domain may interleave with another's.
domain_all() {
  ensure_bucket "sandbox-$1-blobs"
  domain_tables "$1"
  domain_queues "$1"
  ensure_schedule_group "$1-schedules"
  ensure_schedule_group "$1-timers"
}
for d in "${DOMAINS[@]}"; do domain_all "$d" & done
wait

billing_tables
orders_tables

# SDD S.5: the meeting pipeline. Buckets by role; the raw bucket holds restricted audio and transcripts (classes
# raw-audio and raw-transcript, 7 days: Stint 3a.5 lifecycle, applied for real only on AWS).
for b in sandbox-meetings-raw sandbox-documents sandbox-records-docs sandbox-target-runs; do ensure_bucket "$b" & done
wait
for d in "${PIPELINE_DOMAINS[@]}"; do domain_all "$d" & done
pipeline_tables &
pipeline_queues &
wait

ensure_queue sandbox-spy
ensure_bus
# Routing (contracts/events.md): each type to the inbound queue of the domain that consumes it.
ensure_rule invoice-requested-to-billing \
  '{"detail-type":["io.sandbox.billing.invoice-requested.v1"]}' billing-domain-events
ensure_rule invoice-issued-to-orders \
  '{"detail-type":["io.sandbox.billing.invoice-issued.v1"]}' orders-domain-events
ensure_rule invoice-rejected-to-orders \
  '{"detail-type":["io.sandbox.billing.invoice-rejected.v1"]}' orders-domain-events
ensure_rule order-placed-to-orders \
  '{"detail-type":["io.sandbox.orders.order-placed.v1"]}' orders-domain-events
# SDD S.5, 8b (contracts/pipeline-events.md): the pipeline's routing. A business meeting starts Business, a technical one
# Engineering (by the sandboxstage extension inside the detail, the CloudEvent).
ensure_rule meeting-recorded-business   '{"detail-type":["io.sandbox.channels.meeting-recorded.v1"],"detail":{"sandboxstage":["business"]}}' business-domain-events
ensure_rule meeting-recorded-technical   '{"detail-type":["io.sandbox.channels.meeting-recorded.v1"],"detail":{"sandboxstage":["technical"]}}' engineering-domain-events
ensure_rule design-published-to-engineering   '{"detail-type":["io.sandbox.business.design-published.v1"]}' engineering-domain-events
ensure_rule sdd-batch-approved-to-delivery   '{"detail-type":["io.sandbox.engineering.sdd-batch-approved.v1"]}' delivery-domain-events
# Delivery's own events back to Delivery: sdd-ready starts an SDD's instance, the ends resume the coordinator.
ensure_rule delivery-to-delivery   '{"detail-type":["io.sandbox.delivery.sdd-ready.v1","io.sandbox.delivery.sdd-validated.v1","io.sandbox.delivery.sdd-cancelled.v1"]}'   delivery-domain-events
ensure_rule pipeline-to-channels   '{"detail-type":["io.sandbox.engineering.refinement-requested.v1","io.sandbox.engineering.questions-sent.v1","io.sandbox.delivery.sdd-dead-lettered.v1","io.sandbox.delivery.cycle-closed.v1"]}'   channels-domain-events
# Stint's review events (3a.4): requests and reminders to the portal, answers back to the domain that asked.
ensure_rule review-requests-to-channels   '{"detail-type":["io.stintflow.review.requested.v1","io.stintflow.review.reminder.v1"]}' channels-domain-events
for d in "${PIPELINE_DOMAINS[@]}"; do
  ensure_rule "review-completed-to-$d"     "{\"detail-type\":[\"io.stintflow.review.completed.v1\"],\"detail\":{\"sandboxdomain\":[\"$d\"]}}" "$d-domain-events"
done
# The spy sees everything that reaches the bus (catch-all pattern proven by Stint's FlociEmitIT).
ensure_rule spy-all '{"source":[{"prefix":""}]}' sandbox-spy

echo "Done."
