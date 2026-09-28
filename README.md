# stint-consumer-sandbox

A small Quarkus app that consumes [Stint](https://github.com/stintflow/stint) as a plain external
dependency, to validate the engine contract end to end outside Stint's own reactor build.

It deliberately does **not** inherit `stint-parent`: if it builds and runs here, Stint works as a
published library, with no reliance on reactor-only resolution.

## What it exercises

- **YAML workflow contracts** loaded through `stint-dsl` (`DslLoader.loadClasspath`, strict mode),
  with the workflow manifest generated at build time by `WorkflowManifestGenerator`.
- **Remote task handlers** registered through `stint-worker-sdk`:
  - `query-and-stage`: simulates a DB query and stages a 25-row CSV in S3.
  - `format-file`: reads the CSV by pointer, adds a title row and stages the final file.
  - `send-email`: fetches the final file from S3 and sends it as an email attachment.
- **Real infrastructure**: `stint-aws`'s `S3BlobStore` against [floci](https://hub.docker.com/r/floci/floci)
  (local S3) and SMTP delivery through [MailHog](https://github.com/mailhog/MailHog). The engine's
  state, transport and timers come from `stint-inmemory`.
- **Two distinct definitions running concurrently** on the same engine and StateStore, to show
  that instances don't cross-talk:
  - `sandbox:query-report-email:1.0.0`: query, format, email.
  - `sandbox:query-report-email-secondary:1.0.0`: same flow plus `switch` / `set` / `then`
    (prefixes the subject with `URGENT` when `rows > 15`).

## Prerequisites

- JDK 25
- Maven 3.9+
- Docker (for floci and MailHog)
- AWS CLI, or any S3 client, to create the bucket

## 1. Install Stint locally

Stint isn't published to a remote repository yet (`0.1.0-SNAPSHOT`), so install it into your local
Maven repository first:

```bash
git clone https://github.com/stintflow/stint.git
cd stint
git checkout phase-1/engine-foundation   # needs stint-aws's embedded Jandex index
mvn install -DskipTests
```

## 2. Start the infrastructure

```bash
docker compose up -d
```

| Service | Port | Purpose |
|---------|------|---------|
| floci   | 4566 | S3-compatible endpoint |
| MailHog | 1025 | SMTP |
| MailHog | 8025 | Web UI for received emails |

The app doesn't create the bucket, so create it once:

```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url http://localhost:4566 --region us-east-1 s3 mb s3://stint-sandbox-reports
```

## 3. Run the app

```bash
mvn quarkus:dev
```

## 4. Trigger a workflow

```bash
curl -X POST http://localhost:8080/reports \
  -H 'Content-Type: application/json' \
  -d '{
        "reportQuery": "monthly sales",
        "outputFormat": "csv",
        "recipient": "someone@example.com",
        "subject": "Monthly sales report"
      }'
```

For the secondary definition, POST the same body to `/reports/secondary`.

The response is the workflow's final context. It includes the S3 `pointer` and `file` URIs, the
`rows` count and the `messageId`. The email, with `final.csv` attached, shows up at
http://localhost:8025.

> `outputFormat` goes through the contract but is ignored when the file is generated: the output
> is always a real CSV.

## Configuration

Everything is in `src/main/resources/application.properties`. The S3 credentials there
(`test`/`test`) are floci's dummy values, not real secrets.

## Native build

```bash
mvn package -Dnative
```

The workflow manifest and the YAML files are included through `quarkus.native.resources.includes`.
