# sandbox-orders

The Orders domain (SDD S.3), on port 8081. Its engine runs over Stint's AWS connectors on floci; see the
[sandbox README](../README.md) for the infrastructure. It talks to the invoicing side only through the events in
[`contracts/events.md`](../contracts/events.md), and never knows who answers them.

## HTTP

| Request | Answer |
|---|---|
| `POST /orders` with `orderId`, `customerId`, `recipient`, `amount`, `currency`, `items?` | `202 {orderId, instanceId, status: "received"}`, or `400 {errors}` when a field is missing or has the wrong type |
| `GET /orders/{orderId}` | `200 {orderId, instanceId, status, invoiceId?, reason?}`; `404` for an order never received |

How the request is handled:
- **Idempotency key:** the client chooses `orderId` (`[A-Za-z0-9-]{1,64}`). POSTing the same `orderId` again is the same
  order: same `instanceId`, nothing created twice. The first body wins.
- **Validation:** only the order's shape is checked. Whether the amount can be invoiced is decided on the other side.
- **Start:** the POST publishes `io.sandbox.orders.order-placed.v1` (id = `orderId`) on `sandbox-domain`, and
  `place-order` starts from it (Stint SDD 2.1). That is why `instanceId` is known before the instance exists.

Order states:

| `status` | Meaning |
|---|---|
| `received` | accepted, the instance is about to start |
| `requested` | the invoice was requested and is awaited |
| `invoice-delayed` | no answer within the first timeout; the customer was told, and a late invoice is still awaited |
| `confirmed` | invoiced; the confirmation e-mail went out and `order-confirmed.v1` was published |
| `rejected` | the invoice was refused (`reason`); the customer was told |
| `invoice-missing` | no answer within the second timeout either |
| `invalid` / `failed` | a malformed entry event / the instance failed |

The status comes from the order's Stint snapshot (`$context.status`), not from a table of its own.

## The workflow: `orders:place-order:1.0.0`

```
order-placed -> validate -> emit invoice-requested
  -> listen any [invoice-issued, invoice-rejected] by data.orderId (orders.invoice.timeout, default PT2M)
       issued   -> send-confirmation (invoice attached) -> emit order-confirmed
       rejected -> send-rejection
       timeout  -> send-delay-notice
                -> listen again (orders.late-invoice.timeout, default PT24H, an EventBridge Scheduler one-time schedule)
                     issued -> confirmation; rejected -> rejection; timeout -> invoice-missing
```

Timeouts:
- The YAML holds the default timeouts. `OrdersEngine` replaces them with the two properties before Stint loads the
  definition.
- Any positive duration works; zero or negative stops the startup. (Before Stint `b1f4579` the floor was `PT2S`:
  S.2, 8i, fixed by Stint SDD 2.9.)

When the late invoice arrives, the second listen resumes and Stint deletes its 24 h schedule. A fire arriving after
that finds no wait and does nothing.

## E-mails

Every e-mail is from `orders@sandbox.local` (`orders.mail.from`) to the order's `recipient`, sent through MailHog:
http://localhost:8025.

| E-mail | Subject |
|---|---|
| confirmation | `Order <orderId> confirmed: invoice <invoiceId>`, with `invoice-<invoiceId>.html` attached |
| rejection | `Order <orderId> was not invoiced: <reason>` |
| delay | `Order <orderId>: invoicing is delayed` |

The attachment:
- It is read with a single `GetObject` of exactly the object cited in `invoiceDocument`. Orders never lists the
  bucket.
- Only `s3://sandbox-facts/billing/invoices/<name>.html`, the contract's pattern, is accepted; any other citation fails
  the task.

Each e-mail writes its marker `mail#<instanceId>#<taskId>` in `orders-effects` before sending. A redelivered task
sends nothing and returns the first send's instant. A crash between the marker and SMTP loses that e-mail instead of
sending it twice.

## Try it

With the infrastructure up, `infra/bootstrap.sh` run, and the invoicing side running (see the
[sandbox README](../README.md)):

```bash
java -jar sandbox-orders/target/quarkus-app/quarkus-run.jar

curl -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"o-1","customerId":"c-1","recipient":"me@example.com","amount":10.0,"currency":"BRL"}'
curl http://localhost:8081/orders/o-1
```

The JSON logs of the e-mail tasks carry `stint.chainId`, `stint.instanceId`, `stint.taskId` and `stint.attempt`. The
whole order (`order-placed`, `invoice-requested`, `invoice-issued`, `order-confirmed`) shares one `chainid`, which you
can see in the spy queue.

## Tests

| Command | What runs |
|---|---|
| `mvn verify -pl sandbox-orders` | unit tests of every branch on Stint's in-memory engine (test scope only), the isolation test and the enforcer |
| `mvn verify -Pfloci -pl sandbox-orders` | CA1, CA2 and CA4–CA7 against floci and MailHog, with the app started by `@QuarkusTest` (about 9 minutes) |

Before the floci tests:
- Start the invoicing side yourself. The test only checks that it answers at `-Dorders.it.counterpart-health`.
- Stop any other `sandbox-orders`: it would consume the same queues.

The timeout scenario (CA3) needs the invoicing side stopped and started again, so it is a manual run, recorded in
`docs/sdd/evidence/S.3-CA3.txt`.
