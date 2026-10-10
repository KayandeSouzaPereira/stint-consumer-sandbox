#!/usr/bin/env bash
# SDD S.1, RF5: creates every AWS resource the sandbox needs on floci. Idempotent: run it as often as you like,
# nothing is duplicated and nothing that exists is changed.
#
# Schemas and settings are copied from Stint (branch phase-2/federated-orchestration, SHA d455dc0):
#   - DynamoDB tables: stint-it/.../DynamoDbTestTables.java and docs/connectors.md (outbox, inbox)
#   - SQS: docs/connectors.md "SQS setup" (redrive maxReceiveCount 5, visibility 30 s, retention 4 d; DLQ 14 d)
#   - EventBridge -> SQS with InputPath $.detail: docs/connectors.md; catch-all pattern from FlociEmitIT
#   - Scheduler groups: SDD 2.4 / 2.7
# Event routing follows contracts/events.md.
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

aws_() { "$AWS_CLI" --endpoint-url "$ENDPOINT" --region "$AWS_REGION" "$@"; }
say() { printf '%-9s %s\n' "$1" "$2"; }

# --- S3 ---------------------------------------------------------------------------------------------------
ensure_bucket() {
  if aws_ s3api head-bucket --bucket "$1" >/dev/null 2>&1; then say exists "s3://$1"
  else aws_ s3api create-bucket --bucket "$1" >/dev/null; say created "s3://$1"; fi
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
ensure_bucket sandbox-facts           # facts too large for the bus (dataref), shared
for d in "${DOMAINS[@]}"; do
  ensure_bucket "sandbox-$d-blobs"
  domain_tables "$d"
  domain_queues "$d"
  ensure_schedule_group "$d-schedules"
  ensure_schedule_group "$d-timers"
done

ensure_queue sandbox-spy
ensure_bus
# Routing (contracts/events.md): each type to the inbound queue of the domain that consumes it.
ensure_rule invoice-requested-to-billing \
  '{"detail-type":["io.sandbox.billing.invoice-requested.v1"]}' billing-domain-events
ensure_rule invoice-issued-to-orders \
  '{"detail-type":["io.sandbox.billing.invoice-issued.v1"]}' orders-domain-events
# The spy sees everything that reaches the bus (catch-all pattern proven by Stint's FlociEmitIT).
ensure_rule spy-all '{"source":[{"prefix":""}]}' sandbox-spy

echo "Done."
