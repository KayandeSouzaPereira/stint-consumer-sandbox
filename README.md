# stint-consumer-sandbox

Validates [Stint](https://github.com/stintflow/stint) as a plain external library, outside Stint's own reactor
build. It deliberately does **not** inherit `stint-parent`: if it builds and runs here, Stint works as a published
library.

| Module | Port | What it validates |
|---|---|---|
| `sandbox-reports` | 8080 | Phase 1 regression: YAML contracts, remote task handlers, in-memory engine, real S3 and real SMTP |
| `sandbox-orders` | 8081 | Phase 2: the Orders domain (S.3): HTTP orders that request an invoice and wait for it, with correlation, timeouts and a 24 h late-invoice wait. See [its README](sandbox-orders/README.md) |
| `sandbox-billing` | 8082 | Phase 2: the Billing domain (S.2): invoices with retry, rejection and a scheduled summary. See [its README](sandbox-billing/README.md) |

Orders and Billing never depend on each other (the build fails if they do). They coordinate only through the
domain events in [`contracts/events.md`](contracts/events.md). Billing (S.2) and Orders (S.3) are in place; the cross-domain
and chaos scenarios are S.4. The specs live in [`docs/sdd`](docs/sdd).

## Stint version

The sandbox is pinned to **Stint `d455dc0`** (branch `phase-2/federated-orchestration`, Phase 2 with SDDs 2.1–2.8).

## Prerequisites

- JDK 25 and Maven 3.9+
- Docker (floci, MailHog, Jaeger)
- AWS CLI v2. Only ever used against floci, with floci's dummy credentials `test`/`test`.
- A bash shell for `infra/*.sh` (Git Bash on Windows)

## 1. Install Stint at the pinned SHA

Stint isn't published to a remote repository yet (`0.1.0-SNAPSHOT`), so install it locally:

```bash
git clone https://github.com/stintflow/stint.git
cd stint
git checkout d455dc0
mvn install -DskipTests
```

## 2. Start the infrastructure

```bash
docker compose up -d
```

| Service | Port | Purpose |
|---|---|---|
| floci | 4566 | AWS emulator: S3, DynamoDB, SQS, EventBridge, EventBridge Scheduler |
| MailHog | 1025 | SMTP |
| MailHog | 8025 | web UI for received e-mails |
| Jaeger | 16686 | tracing UI (OTLP on 4317/4318) |

## 3. Create the AWS resources

```bash
bash infra/bootstrap.sh            # idempotent: run it again whenever you like
bash infra/list-resources.sh       # what exists on floci
```

The bootstrap creates, with the schemas documented by Stint (`docs/connectors.md`, `stint-it`):

| Resource | Names |
|---|---|
| S3 buckets | `stint-sandbox-reports`, `sandbox-facts` (facts too large for the bus, invoice documents; declarative policy from `infra/policies/`), `sandbox-<domain>-blobs` |
| DynamoDB, per domain | `<domain>-instances`, `<domain>-waits` (GSI `byInstance-index`), `<domain>-outbox` (GSI `pending-by-age`), `<domain>-inbox` (TTL `expiresAt`) |
| DynamoDB, Billing | `billing-invoices`, `billing-effects` |
| DynamoDB, Orders | `orders-effects` |
| SQS, per domain (each with a `-dlq`) | `<domain>-tasks`, `<domain>-results`, `<domain>-timers`, `<domain>-schedules`, `<domain>-domain-events` |
| EventBridge | bus `sandbox-domain`; rules `invoice-requested-to-billing`, `invoice-issued-to-orders`, `invoice-rejected-to-orders`, `order-placed-to-orders`; `spy-all` → queue `sandbox-spy` |
| EventBridge Scheduler | groups `<domain>-schedules`, `<domain>-timers` |

If your AWS CLI isn't on the `PATH` as `aws`, point the scripts at it, e.g.
`AWS_CLI="/c/Program Files/Amazon/AWSCLIV2/aws.exe" bash infra/bootstrap.sh`.

## 4. Build and run the modules

```bash
mvn package -DskipTests

java -jar sandbox-reports/target/quarkus-app/quarkus-run.jar   # :8080
java -jar sandbox-orders/target/quarkus-app/quarkus-run.jar    # :8081
java -jar sandbox-billing/target/quarkus-app/quarkus-run.jar   # :8082
```

You can also run each one in dev mode: `mvn quarkus:dev -pl sandbox-orders`.

Health: `curl http://localhost:8081/q/health/ready` (and `:8082`). The logs are JSON, and the line `"Orders engine
started ..."` lists the AWS connectors the engine runs on.

`<domain>.engine.enabled` and `<domain>.worker.enabled` (both `true` by default) let one jar run the engine only, the
worker only, or both.

## 5. Try it

### Phase 1 regression (sandbox-reports)

```bash
curl -X POST http://localhost:8080/reports \
  -H 'Content-Type: application/json' \
  -d '{"reportQuery":"monthly sales","outputFormat":"csv","recipient":"someone@example.com","subject":"Monthly sales report"}'
```

POST the same body to `/reports/secondary` for the second definition, which prefixes the subject with `URGENT`
when `rows > 15`. Both e-mails, with `final.csv` attached, show up at http://localhost:8025.

### An order, end to end

With both domains running:

```bash
curl -X POST http://localhost:8081/orders -H 'Content-Type: application/json'   -d '{"orderId":"o-1","customerId":"c-1","recipient":"me@example.com","amount":10.0,"currency":"BRL"}'
curl http://localhost:8081/orders/o-1     # requested -> confirmed
```

The confirmation e-mail, with the invoice attached, shows up at http://localhost:8025. Use `"amount":0` to see a rejection;
stop Billing to see the delay notice after 2 minutes (and the confirmation once it is back).

### Domain routing

Publish a test event on the domain bus. The rule for its type sends it to the consuming domain's inbound queue, and
the spy queue gets a copy:

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
aws --endpoint-url http://localhost:4566 events put-events --entries '[{
  "EventBusName": "sandbox-domain",
  "Source": "https://sandbox.stintflow.io/orders",
  "DetailType": "io.sandbox.billing.invoice-requested.v1",
  "Detail": "{\"specversion\":\"1.0\",\"id\":\"test-1\",\"source\":\"https://sandbox.stintflow.io/orders\",\"type\":\"io.sandbox.billing.invoice-requested.v1\",\"datacontenttype\":\"application/json\",\"data\":{\"orderId\":\"o-1\",\"customerId\":\"c-1\",\"amount\":10.0,\"currency\":\"BRL\"}}"
}]'
aws --endpoint-url http://localhost:4566 sqs receive-message --queue-url http://localhost:4566/000000000000/sandbox-spy
```

If Billing is running, it consumes its queue right away and issues the invoice: `invoice-issued.v1` shows up in the
spy. See the [Billing README](sandbox-billing/README.md) for rejections, injected faults and the summary e-mail.

### Tracing

`curl -X POST http://localhost:8081/dev/span` (or `:8082`) records a test span through Stint's tracer. It shows up
at http://localhost:16686 under `sandbox-orders` / `sandbox-billing` a few seconds later (batched export).

## Configuration

Everything is in each module's `src/main/resources/application.properties`. All AWS clients point at floci with
the dummy credentials `test`/`test`; nothing here is a real secret. Dev services are off on purpose, so a
misconfigured client fails instead of reaching anything else. Mind the Scheduler extension's prefix:
`quarkus.eventbridge-scheduler.*`, not `quarkus.scheduler.*`.

## Native build

```bash
mvn package -Dnative -pl sandbox-reports
```

The workflow manifest and the YAML files are included through `quarkus.native.resources.includes`. The native build
of the domain modules is SDD S.4.
