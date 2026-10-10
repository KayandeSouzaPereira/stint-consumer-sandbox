# SDD S.1 — Estrutura multi-módulo, infraestrutura e engine soberano por domínio

| Campo | Valor |
|---|---|
| ID | S.1 |
| Status | **Em especificação** (aguardando aprovação da seção 8) |
| Bloqueia | S.2 (Billing), S.3 (Orders), S.4 (cenários ponta a ponta) |
| Branch | `phase-2-validation` (a partir de `main` `0708a2d`) |
| Commits analisados | sandbox `0708a2d` · Stint `d455dc0` (`phase-2/federated-orchestration`, **local, ainda sem push**) |
| Data da análise | 10/10/2026 |
| Notion | **Inacessível nesta sessão** (o servidor MCP do Notion não conecta). A página S.1 na base "Stint · SDDs" e o Log serão criados quando ele voltar; a Política não pôde ser relida e vale a última versão lida nas sessões do Stint |

## 1. Análise

### Sandbox (`main` `0708a2d`)
- Projeto Maven único (`jar`), **sem herdar `stint-parent`** (prova de consumo externo). Quarkus 3.33.2, Java 25, `stint.version=0.1.0-SNAPSHOT`.
- Dependências do Stint: `stint-inmemory`, `stint-aws`, `stint-worker-sdk` e `stint-dsl`. Também usa `quarkus-mailer`, `quarkus-amazon-s3` e `url-connection-client`. Gera o manifesto de workflows com `exec-maven-plugin` + `WorkflowManifestGenerator`, e tem perfil `native`.
- `EngineProducer`: engine **em memória** (`InMemoryStateStore`, `InMemoryTaskTransport`, `InMemoryTimerService`) + `S3BlobStore` real (floci), com três handlers (`query-and-stage`, `format-file`, `send-email`). Dois YAMLs: `query-report-email` e `query-report-email-secondary`.
- `ReportResource`: `POST /reports` e `POST /reports/secondary`, porta 8080. O e-mail chega no MailHog.
- `docker-compose.yml`: floci (4566) e MailHog (1025/8025). O bucket `stint-sandbox-reports` é criado à mão pelo aws CLI (README).
- Sem testes em `src/test` (só a pasta). O README manda instalar o Stint a partir de `phase-1/engine-foundation`.

### Stint (`d455dc0`, referência de configuração)
- **Esquemas DynamoDB** (`stint-it/.../DynamoDbTestTables`, `docs/connectors.md`):
	- instâncias: PK `instanceId` (S);
	- esperas: PK `waitKey` (S) + GSI `byInstance-index` (PK `instanceId`);
	- outbox: PK `eventId` (S) + GSI `pending-by-age` (PK `shard` S, SK `createdAt` N, projeção ALL), 4 shards;
	- inbox: PK `waitKey` (S) + SK `eventId` (S), TTL opcional em `expiresAt`.
- **SQS** (`docs/connectors.md`, "SQS setup"):
	- fila de entrada com `RedrivePolicy` `maxReceiveCount` 5, `VisibilityTimeout` 30 s e retenção de 4 dias;
	- DLQ com retenção de 14 dias;
	- corpo de cada mensagem = CloudEvent estruturado.
- **EventBridge → SQS:** regra com alvo SQS e `InputPath: $.detail`. Um barramento de domínio dedicado tem que ser **diferente** do barramento de tasks; o `EventBridgeEventPublisher` falha na inicialização se os dois forem iguais. Regra "pega-tudo" provada no `FlociEmitIT`: `{"source":[{"prefix":""}]}`.
- **Scheduler** (SDD 2.4/2.7):
	- grupos por aplicação, um para as agendas e outro para os timers;
	- o alvo é a fila de agendas e a fila de timers;
	- no floci, `stint.aws.scheduler.sent-timestamp-fallback=true` é **obrigatório** (o floci não substitui `<aws.scheduler.scheduled-time>`; SDD 2.7, 8c).
- **Clients AWS** (SDD 2.7): vêm das extensões `quarkus-amazon-{sqs,dynamodb,s3,eventbridge,scheduler}`. Os prefixos de configuração são `quarkus.sqs|dynamodb|s3|eventbridge|eventbridge-scheduler.*`. **Atenção:** `quarkus.scheduler.*` é da extensão de agendamento do Quarkus e é ignorado; um client com a configuração ignorada cai no Dev Services ou na cadeia padrão de credenciais da máquina. O SDD 2.7 registrou um incidente com isso, sem impacto.
- **Montagem do engine em app Quarkus:** o `stint-bundle-aws` é só um marcador e não produz engine. A referência é o `examples/stint-example-aws-smoke/SmokeEngine` (SDD 2.7): construtores manuais dos conectores a partir dos clients injetados, `DelayRoutingTimerService(SqsDelayTimerService, EventBridgeSchedulerTimerService)`, reconciliação de agendas no startup e worker em loop no mesmo processo.
- **Peças de produção que o app precisa ligar** (README/`docs/connectors.md` do Stint):
	- `engine.outboxRelay().start(Duration.ofSeconds(30))`;
	- `new ScheduleReconciler(registry, schedules).reconcile()` + `schedules.onFire(new ScheduledStart(registry, engine))`;
	- `new DomainEventRouter(List.of(new StartReaction(bindings, engine), new ResumeReaction(engine))).subscribeTo(new SqsDomainEventSource(...))`, com `bindings.bindDeclaredSchedules()`.
- **Observabilidade (SDD 2.5):**
	- `stint-otel` com `OpenTelemetryExecutionTracer(OpenTelemetry | Tracer)`;
	- MDC (`stint.chainId` etc.) no log JSON (`quarkus-logging-json`, que o `stint-bundle-aws` já traz).

### Conclusão
O sandbox atual vira o módulo `sandbox-reports` sem mudar comportamento. Os módulos `sandbox-orders` e `sandbox-billing` são apps Quarkus com engine montado como o `SmokeEngine` do Stint, sobre recursos próprios, criados por um bootstrap idempotente que copia os esquemas do Stint.

**Bloqueio para o RF1:** o SHA alvo `d455dc0` ainda **não está no remoto**. O push da Fase 2 foi recusado: o token do GitHub não tem o escopo `workflow`. Pela spec, a instalação fica parada até o push.

## 2. Problema e objetivo
Hoje o sandbox valida só a Fase 1: um app, engine em memória e só o S3 real. O S.1 deixa o terreno pronto para dois domínios independentes que se coordenam por eventos sobre infraestrutura real no floci, cada um com seu engine sobre os conectores AWS. Os workflows de negócio ainda ficam fora.

## 3. Requisitos
RF1–RF8 conforme a spec inicial (fixar o SHA do Stint, multi-módulo, `sandbox-reports`, `sandbox-orders`/`sandbox-billing`, infraestrutura e bootstrap, `contracts/events.md`, isolamento com enforcer, README).

## 4. Especificação
### 4.1 Layout
```
pom.xml                    (parent, packaging pom)
sandbox-reports/           (app atual, git mv, porta 8080, Fase 1)
sandbox-orders/            (porta 8081)
sandbox-billing/           (porta 8082)
contracts/events.md
infra/bootstrap.sh
docker-compose.yml         (floci, MailHog, Jaeger)
docs/sdd/S.1.md
```
### 4.2 Engine por módulo (ver 8c)
`<Domain>Engine` (`@ApplicationScoped`, inicia no `StartupEvent`) monta:
- `WorkflowEngine(registry, SqsTaskTransport, DynamoDbStateStore(4 tabelas do domínio), DelayRoutingTimerService, S3BlobStore, ..., EventBridgeEventPublisher(sandbox-domain, "default"), S3FactBlobStore(sandbox-facts), OpenTelemetryExecutionTracer)`;
- `outboxRelay().start(30 s)`;
- reconciliação de agendas;
- roteador de domínio com `StartReaction` e `ResumeReaction` sobre `SqsDomainEventSource`;
- o loop do worker (SQS tasks → `WorkerRuntime` → SQS results).

No S.1 não há YAML de negócio: o registry fica vazio.

Expõe:
- `/q/health` (`quarkus-smallrye-health`, com um check "engine started");
- um `POST /dev/span`, só para o CA6 (gera um span de teste pelo `ExecutionTracer`).

## 5. Fora de escopo
Workflows de Orders e Billing (S.2, S.3), cenários ponta a ponta e caos (S.4), build nativo (S.4).

## 6. Critérios de aceite
CA1–CA7 conforme a spec inicial. Evidências previstas:
- CA1: saída de duas execuções do `bootstrap.sh` + listagem `aws ... list-*` anexada à seção 9.
- CA2: os dois `curl` do README + e-mails no MailHog (API `/api/v2/messages`, assunto e anexo).
- CA3: `quarkus:dev` e `java -jar` de cada módulo, `/q/health` UP e a linha JSON "engine started" com os conectores (classes `io.stintflow.aws.*`).
- CA4: `aws events put-events` no `sandbox-domain` + `receive-message` nas três filas.
- CA5: branch descartável `s1-enforcer-check` com a dependência proibida → `BUILD FAILURE` do enforcer; a branch é apagada depois.
- CA6: `POST /dev/span` em cada módulo + consulta à API do Jaeger (`/api/traces?service=...`).
- CA7: seguir o README do zero (volumes do floci limpos, `~/.m2` do Stint reinstalado no SHA) e registrar.

## 7. Plano de tarefas
SHA e instalação do Stint → parent pom + `git mv` para `sandbox-reports` (CA2 já aqui) → docker-compose e `bootstrap.sh` → `contracts/events.md` → esqueleto de orders e billing com engine AWS → enforcer → README → CAs.

## 8. Riscos e questões em aberto: propostas para aprovação

### (a) Nomes dos recursos AWS (esquemas copiados do Stint)
Prefixo = domínio (`orders`, `billing`). Os mesmos nomes valem para os dois:

| Recurso | Nome | Esquema (fonte) |
|---|---|---|
| DynamoDB instâncias | `<d>-instances` | PK `instanceId` S (`DynamoDbTestTables.create`) |
| DynamoDB esperas | `<d>-waits` | PK `waitKey` S + GSI `byInstance-index` (PK `instanceId`) |
| DynamoDB outbox | `<d>-outbox` | PK `eventId` S + GSI `pending-by-age` (`shard` S / `createdAt` N, ALL) |
| DynamoDB inbox | `<d>-inbox` | PK `waitKey` S + SK `eventId` S; TTL `expiresAt` |
| SQS tasks (invoke) | `<d>-tasks` + `<d>-tasks-dlq` | redrive 5, visibilidade 30 s, retenção 4 d; DLQ 14 d |
| SQS results | `<d>-results` + DLQ | idem |
| SQS timers | `<d>-timers` + DLQ | idem (o alvo dos timers longos do Scheduler) |
| SQS agendas | `<d>-schedules` + DLQ | idem (o alvo das agendas cron) |
| SQS entrada de domínio | `<d>-domain-events` + DLQ | idem (`SqsDomainEventSource`) |
| S3 blobs (claim-check) | `sandbox-<d>-blobs` | — |
| S3 fatos (`dataref`) | `sandbox-facts` (compartilhado, prefixo `facts/<ns>/<name>/`) | `docs/connectors.md` |
| S3 relatórios (Fase 1) | `stint-sandbox-reports` (mantido) | — |
| Scheduler | grupos `<d>-schedules` e `<d>-timers` | SDD 2.4/2.7; role dummy `arn:aws:iam::000000000000:role/sandbox-scheduler` |
| EventBridge barramento | `sandbox-domain` | diferente do `default` (tasks) |
| Regra → Billing | `invoice-requested-to-billing`: `detail-type` = `io.sandbox.billing.invoice-requested.v1` → `billing-domain-events` | `InputPath $.detail` |
| Regra → Orders | `invoice-issued-to-orders`: `detail-type` = `io.sandbox.billing.invoice-issued.v1` → `orders-domain-events` | idem |
| Regra espiã | `spy-all`: `{"source":[{"prefix":""}]}` → `sandbox-spy` (sem DLQ) | `FlociEmitIT` |

**Pergunta:** o `io.sandbox.orders.order-confirmed.v1` é emitido por Orders, mas a spec não diz quem consome. **Proposta:** no S.1 ele só vai para a espiã (nenhuma regra de domínio), e o `contracts/events.md` registra "consumidor: nenhum (observação)". Se o S.3/S.4 previr um consumidor, a regra entra lá.

### (b) Suporte do floci ao RF5
- **Provado pelas ITs do Stint:**
	- EventBridge com barramento próprio + regras por `detail-type` + alvo SQS com `InputPath` (`FlociDomainEventTriggerIT`, `FlociEmitIT`, `FlociListenIT`);
	- regra "pega-tudo" (`FlociEmitIT`);
	- DLQ por redrive (`FlociDomainEventTriggerIT`);
	- Scheduler: grupos, cron com fuso, `at()`, `ActionAfterCompletion=DELETE`, `ConflictException` (`FlociSchedulerIT`, `FlociTimerIT`, sondagem do 2.4);
	- tabelas, GSIs e transações DynamoDB (todos os ITs).
- **Spike de 10/10/2026** (floci descartável, porta 4598, removido):
	- `UpdateTimeToLive`/`DescribeTimeToLive` em `expiresAt` → `ENABLED`;
	- recriar um grupo do Scheduler → `ConflictException`, então o bootstrap tem que conferir antes de criar.
	- O Stint não depende do TTL apagar itens (filtra `expiresAt` na leitura), então a exclusão preguiçosa do floci não importa.
- **Limitações conhecidas que valem aqui:**
	- o floci não substitui o placeholder do Scheduler (fallback ligado por configuração);
	- o floci não valida a expressão cron (a validação é feita pelo Stint na carga).
- **Idempotência do bootstrap:**
	- `create-queue` com os mesmos atributos é idempotente;
	- `create-table`, `create-event-bus`, `create-bucket` e `create-schedule-group` exigem "consultar e criar se não existir";
	- `put-rule`, `put-targets` e `update-time-to-live` podem ser repetidos.
- **Sem spike pendente.**

### (c) Onde o engine é montado e quais propriedades lê
**Proposta: um producer CDI por módulo, `<Domain>Engine`, no padrão do `SmokeEngine` do Stint.** Não existe bundle AWS que monte o engine: o `stint-bundle-aws` só agrega dependências.
- **Clients:**
	- `quarkus.sqs|dynamodb|s3|eventbridge|eventbridge-scheduler.endpoint-override=http://localhost:4566`;
	- `aws.region=us-east-1`;
	- `aws.credentials.type=static` com `test`/`test`;
	- `sync-client.type=url`;
	- `devservices.enabled=false`;
	- `quarkus.s3.path-style-access=true`.
- **Engine:** `stint.aws.scheduler.sent-timestamp-fallback=true` (floci), mais as propriedades do domínio, `<d>.*`: URLs das filas, nomes das tabelas, barramento, grupos do Scheduler, role, buckets e `<d>.worker.enabled` (ver 8d).
- Os conectores são construídos **manualmente** (construtores de teste/manuais do Stint). Os beans CDI do `stint-aws`, que leem `stint.aws.*`, não são injetados, então dois domínios nunca dividem configuração por acidente.
- **Fail-safe** (aprendido no SDD 2.7): o `application.properties` de cada módulo também fixa `quarkus.devservices.enabled=false`. Os scripts e o README exportam `AWS_ACCESS_KEY_ID=test`/`AWS_SECRET_ACCESS_KEY=test`/`AWS_REGION=us-east-1`, para nenhum client cair na conta real da máquina.
- **Dependências por módulo:**
	- `stint-bundle-aws`, que traz `stint-core`, `stint-aws` e `quarkus-logging-json`;
	- `stint-dsl`, `stint-worker-sdk` e `stint-otel`;
	- `quarkus-opentelemetry` (exportador OTLP → Jaeger em 4317), `quarkus-smallrye-health` e `quarkus-rest`.
	- São dependências novas só no sandbox. O Quarkus BOM gerencia todas; todas têm suporte nativo pelas extensões.

### (d) Engine e worker no mesmo processo, comunicando por SQS
**Proposta: sim, é suficiente para o S.4**, porque a fronteira já é assíncrona e durável (SQS):
- O worker pode ficar indisponível sem perder trabalho; as mensagens esperam na fila.
- O engine pode cair entre o checkpoint e a publicação; o outbox recupera (SDD 2.2 e marco do 2.6).
- Mais de uma instância do mesmo módulo pode rodar sobre as mesmas tabelas e filas.

Para o S.4 poder separar papéis **sem código novo**, cada módulo lê `<d>.worker.enabled` e `<d>.engine.enabled` (padrão `true`). O mesmo jar sobe como "só engine", "só worker" ou os dois. Assim o S.4 consegue, por exemplo, derrubar só o worker de Billing.

### Outros riscos
- **RF1 bloqueado** até o push do Stint (`d455dc0`).
- **CA7 (máquina limpa):** vou simular com volumes do floci zerados e o Stint reinstalado no `~/.m2`; não é uma VM nova. Registro isso como limitação.
- **Bootstrap em `bash`** no Windows: roda no Git Bash, que já é pré-requisito no README, e usa o aws CLI do host (`--endpoint-url`).

## 9. Registro de validação
(preencher na implementação)
