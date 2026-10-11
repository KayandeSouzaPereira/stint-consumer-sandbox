# stint-consumer-sandbox

Validates [Stint](https://github.com/stintflow/stint) as a plain external library, outside Stint's own reactor
build. It deliberately does **not** inherit `stint-parent`: if it builds and runs here, Stint works as a published
library.

| Module | Port | What it validates |
|---|---|---|
| `sandbox-reports` | 8080 | Phase 1 regression: YAML contracts, remote task handlers, in-memory engine, real S3 and real SMTP |
| `sandbox-orders` | 8081 | Phase 2: the Orders domain (S.3): HTTP orders that request an invoice and wait for it, with correlation, timeouts and a 24 h late-invoice wait. See [its README](sandbox-orders/README.md) |
| `sandbox-billing` | 8082 | Phase 2: the Billing domain (S.2): invoices with retry, rejection and a scheduled summary. See [its README](sandbox-billing/README.md) |
| `sandbox-scenarios` | — | Phase 2 gate (S.4): tests only. They run the packaged apps as processes, kill them at chosen moments, load them and read Jaeger, all from outside |

Orders and Billing never depend on each other (the build fails if they do). They coordinate only through the
domain events in [`contracts/events.md`](contracts/events.md). Billing (S.2) and Orders (S.3) are in place, and S.4
runs them together under failures and load. The specs live in [`docs/sdd`](docs/sdd); the gate report is
[`docs/evidence/phase-2-gate.md`](docs/evidence/phase-2-gate.md).

## Stint version

The sandbox is pinned to **Stint `b1f4579`** (branch `phase-2/federated-orchestration`, Phase 2 with SDDs 2.1–2.9).

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
git checkout b1f4579
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

## 6. The Phase 2 gate in one command (S.4)

```bash
bash infra/gate.sh                               # regression once, then chaos, load and observability
bash infra/gate.sh --regression-only --repeat 3  # the regression three times in a row
bash infra/gate.sh --only LoadIT --orders 100    # one scenario class, N orders for the load
bash infra/gate.sh --native                      # Orders and Billing as native images, the happy path on them
```

- **Destructive for the emulator only.** Every scenario starts from `infra/reset.sh`, which empties floci's sandbox
  tables, queues and one-time schedules, and every MailHog message. It refuses any endpoint other than
  `http://localhost:4566`.
- **Before running:** no sandbox app may be running, so ports 8080-8085 must be free. Set `MVN=...` if `mvn` on
  your `PATH` isn't Maven 3.9 with JDK 25.
- **What it runs:**
  - the build and every unit test;
  - the floci tests of Billing, and of Orders with Billing started as a process;
  - `sandbox-scenarios`:
    - `RegressionIT`: S.1 CA2, S.2 CA6 and S.3 CA3;
    - `ChaosIT`: RF2 a-e;
    - `LoadIT`: RF3;
    - `ObservabilityIT`: RF5.
- **Where the output goes:** logs and evidence in `gate-out/<timestamp>/`, and each app's own log in
  `sandbox-scenarios/target/scenarios/`.
- **Chaos points** (S.4 8b). They are off by default, and a scenario sets one with `-D` only on the process it means
  to kill:
  - `billing.chaos.halt-before-publish=<event type>` halts the engine on that type's first publication, after its
    checkpoint;
  - `billing.chaos.halt-in-task=<route>` halts the worker after that task's effect, before its result.

## Configuration

Everything is in each module's `src/main/resources/application.properties`. All AWS clients point at floci with
the dummy credentials `test`/`test`; nothing here is a real secret. Dev services are off on purpose, so a
misconfigured client fails instead of reaching anything else. Mind the Scheduler extension's prefix:
`quarkus.eventbridge-scheduler.*`, not `quarkus.scheduler.*`.

## Native build

```bash
mvn package -Dnative -pl sandbox-reports
```

The workflow manifest and the YAML files are included through `quarkus.native.resources.includes`.

The domain modules are built and run natively by `bash infra/gate.sh --native` (S.4 RF4):
- the build runs in a container with `-Dquarkus.native.remote-container-build=true`, so Docker needs about 8 GB of
  memory (each image peaks at ~4.4 GB). The remote variant copies files in and out with `docker cp`, because Docker
  Desktop on Hyper-V never creates a container with a bind mount of `C:`;
- each runner is copied into an image `stint-s4/<module>:native` on `quay.io/quarkus/ubi9-quarkus-micro-image:2.0`
  and run on the compose network, with the `localhost` endpoints overridden to `floci`, `mailhog` and `jaeger`;
- Orders and Billing set `quarkus.native.auto-service-loader-registration=true`: a workaround for a Stint finding
  (S.4 8i), without which every jq expression calling a builtin function (`type`, `length`, ...) fails natively.
