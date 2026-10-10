# sandbox-billing

The Billing domain (SDD S.2), on port 8082. Its engine runs over Stint's AWS connectors on floci (see the
[sandbox README](../README.md) for the infrastructure). It knows other domains only through
[`contracts/events.md`](../contracts/events.md).

## Workflows

| Definition | Started by | What it does |
|---|---|---|
| `billing:issue-invoice:1.0.0` | `schedule.on` `io.sandbox.billing.invoice-requested.v1` | validates the request; renders the invoice (`render-invoice`, retried on `communication`/503, at most 3 attempts); records it (`record-invoice`); emits `invoice-issued.v1` with `invoiceDocument`. An invalid request emits `invoice-rejected.v1` instead |
| `billing:billing-summary:1.0.0` | `schedule.every` 1 minute (EventBridge Scheduler) | counts the invoices issued in `[T - 1 min, T)`, T being the occurrence's scheduled instant, and mails finance |

A request is invalid when `amount` is missing, not a number or `<= 0` (`reason: invalid-amount`), or when
`currency` is missing or empty (`reason: missing-currency`). When the renderer fails 3 times, the instance fails,
with no invoice and no event.

## Where things go

| What | Where |
|---|---|
| Invoice document (demo HTML) | `s3://sandbox-facts/billing/invoices/<invoiceId>.html`. Only Billing writes under `billing/` (bucket policy in the contract) |
| Invoice records | DynamoDB `billing-invoices` (PK `invoiceId`), written once: a repeat keeps the first `issuedAt` |
| Effect markers | DynamoDB `billing-effects` (PK `effectKey`), e.g. `mail#<instanceId>#<taskId>` |
| Summary e-mail | from `billing@sandbox.local` to `finance@sandbox.local` (`billing.mail.*`), via MailHog: http://localhost:8025 |

`invoiceId` is `inv-` followed by the first 16 hex digits of the SHA-256 of the `orderId`, so retries and redeliveries
always agree on it. All instants are UTC.

Idempotency under at-least-once delivery:
- A duplicated request has the same `source`/`id`, so it maps to the same instance and starts nothing.
- A duplicated schedule occurrence has the same scheduled instant, so it also maps to the same instance.
- Each worker effect has a deterministic key.
- The summary e-mail writes its marker **before** sending. A crash between the marker and SMTP loses that summary
  instead of sending it twice.

Known limits of the summary window:
- An occurrence lost while Billing is down for over half a minute is not summarized later.
- An invoice recorded with an `issuedAt` just before T, but stored after T's count ran, is left out.

## Fault injection

Faults are injected only in the worker's renderer, keyed on the request's `orderId`:

| `orderId` prefix | Effect |
|---|---|
| `fail503-` | attempts 1 and 2 fail with `https://serverlessworkflow.io/spec/1.0.0/errors/communication` and status 503; attempt 3 succeeds |
| `slow-` | the render takes `billing.fault.slow-delay` longer (default `5s`, below the tasks queue's 30 s visibility) |

Every failed result is logged as it travels back to the engine:

```
Task render-invoice attempt 1 of instance evt-… failed: type=https://serverlessworkflow.io/spec/1.0.0/errors/communication status=503 …
```

## Try it

With the infrastructure up and `infra/bootstrap.sh` run:

```bash
java -jar sandbox-billing/target/quarkus-app/quarkus-run.jar

export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
aws --endpoint-url http://localhost:4566 events put-events --entries '[{
  "EventBusName": "sandbox-domain",
  "Source": "https://sandbox.stintflow.io/orders",
  "DetailType": "io.sandbox.billing.invoice-requested.v1",
  "Detail": "{\"specversion\":\"1.0\",\"id\":\"req-1\",\"source\":\"https://sandbox.stintflow.io/orders\",\"type\":\"io.sandbox.billing.invoice-requested.v1\",\"datacontenttype\":\"application/json\",\"data\":{\"orderId\":\"fail503-o-1\",\"customerId\":\"c-1\",\"amount\":10.0,\"currency\":\"BRL\"}}"
}]'
aws --endpoint-url http://localhost:4566 sqs receive-message --queue-url http://localhost:4566/000000000000/sandbox-spy
```

The handlers log inside Stint's log context, so every JSON line carries `stint.chainId`, `stint.instanceId`,
`stint.causationId`, `stint.correlationId`, `stint.taskId` and `stint.attempt`.

## Tests

| Command | What runs |
|---|---|
| `mvn verify -pl sandbox-billing` | unit tests on Stint's in-memory engine (test scope only), the isolation test and the enforcer |
| `mvn verify -Pfloci -pl sandbox-billing` | CA1–CA5 against floci, MailHog and the real Scheduler, with the whole app started by `@QuarkusTest` (about 7 minutes). Stop any other `sandbox-billing` first: it would consume the same queues |

## Known Stint finding

At Stint `d455dc0`, a `call: remote` inside a `try` ignores its `with.input` (SDD S.2, 8h). `issue-invoice` shapes the
renderer's input with the `try` task's DSL 1.0 `input.from` instead.
