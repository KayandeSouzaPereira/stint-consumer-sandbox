# SDD S.2 — Domínio Billing

| Campo | Valor |
|---|---|
| ID | S.2 |
| Status | **Em implementação** (spec aprovada por Kayan em 10/10/2026, com as propostas 8a–8g) |
| Depende de | S.1 (Implementado) |
| Bloqueia | S.3 (Orders), S.4 (cenários ponta a ponta) |
| Branch | `phase-2-validation` |
| Commits analisados | sandbox `4b84eb3` · Stint `d455dc0` (`phase-2/federated-orchestration`) |
| Data da análise | 10/10/2026 |
| Notion | **Inacessível nesta sessão** (o MCP do Notion não conecta). A página S.2 em "Stint · SDDs" será criada quando ele voltar |

## 1. Análise

### Sandbox (`4b84eb3`)
- **`BillingEngine`** (`sandbox-billing`, porta 8082) monta o engine à mão, como o `SmokeEngine` do Stint:
	- `DynamoDbStateStore(billing-instances, -waits, -outbox, 4 shards, -inbox)` e `SqsTaskTransport(billing-tasks, billing-results)`;
	- timers `DelayRoutingTimerService(SqsDelayTimerService(billing-timers), EventBridgeSchedulerTimerService(billing-timers group))`;
	- `EventBridgeEventPublisher(sandbox-domain)` + `S3FactBlobStore(sandbox-facts)`, com o relay do outbox a cada 30 s;
	- `EventBridgeSchedulerService(billing-schedules, sent-timestamp-fallback)` reconciliado no startup, com `onFire(ScheduledStart)`;
	- `DomainEventRouter(StartReaction com bindDeclaredSchedules, ResumeReaction)` sobre `billing-domain-events`;
	- worker em loop no mesmo processo: `billing-tasks` → `WorkerRuntime(new TaskHandlerRegistry(), ...)` → `billing-results`. **Hoje o registro está vazio** e `stint/workflows/` só tem `.gitkeep`.
- **Propriedades `billing.*`**: URLs/ARNs das filas, tabelas, `sandbox-billing-blobs`, `sandbox-facts`, `sandbox-domain`, grupos do Scheduler, role fictícia, `sent-timestamp-fallback=true`, `engine.enabled`/`worker.enabled`. Clients `quarkus.<svc>.*` no floci com `test`/`test`, dev services desligados, log JSON e OTLP em `localhost:4317`.
- **Contrato** (`contracts/events.md`): `invoice-requested.v1` (Orders→Billing: `orderId`, `customerId`, `amount`, `currency`, `items?`), `invoice-issued.v1` (Billing→Orders: `orderId`, `invoiceId`, `amount`, `currency`, `issuedAt`) e `order-confirmed.v1` (só spy). Pela seção "Mudanças", um campo opcional novo é compatível; o resto exige tipo novo.
- **Bootstrap** (`infra/bootstrap.sh`): buckets `sandbox-facts` (compartilhado, **sem política nem convenção de prefixos**) e `sandbox-billing-blobs`; tabelas e filas do Billing; regras `invoice-requested-to-billing`, `invoice-issued-to-orders` e `spy-all`. Não há tabela de negócio nem regra no sentido Billing→Orders além do `invoice-issued`.
- **E-mail**: só o `sandbox-reports` usa `quarkus-mailer` → MailHog (1025). O Billing ainda não envia e-mail.
- **Enforcer** do Billing: proíbe `sandbox-orders` e `stint-inmemory` em compile/runtime. Em **test** o `stint-inmemory` é permitido (CA8).

### Stint (`d455dc0`)
- **`schedule.on`** (SDD 2.1/2.4, `TriggerBindings.bindDeclaredSchedules` + `StartReaction`):
	- início idempotente por evento: o id da instância é função de (`source`, `id`) do gatilho, então um duplicado é no-op (CA2);
	- a entrada é **um array** com o evento (`.[0].data`), como no `archive-report`;
	- a instância **herda o `chainid` do gatilho**, ou cria um determinístico se ele não tiver; o `causationid` da ativação inicial é o `id` do gatilho (`WorkflowEngine.startFromEvent`).
- **`schedule.every`** (`CronExpression.every`): só minutos que dividem a hora, então 1 minuto vira `* * * * *`.
	- Cada ocorrência inicia uma instância **nova, com chain nova**, a partir do evento sintético `io.stintflow.schedule.fired.v1`, com `id = <schedule>@<instante>` e `time` = instante agendado (`ScheduledStart`).
	- Uma ocorrência duplicada é no-op. No floci, o instante vem do fallback por `SentTimestamp`, e uma entrega a meio período ou mais da ocorrência é rejeitada (SDD 2.7).
- **`try`/`catch`/`retry`** (SDD 1.3/2.8):
	- `catch.errors.with` vira igualdade campo a campo, então `type` + `status` funcionam; o `type` literal é normalizado para a URI DSL 1.0;
	- `retry`: `delay`, `backoff.exponential`, `limit.attempt.count`; os atrasos usam o timer service;
	- esgotado, a instância falha com "Retry limit exhausted after N attempt(s)".
- **Falha do worker**: `TaskFailure(type, status, detail, retryAfter)` mantém o `status` do worker (ex.: 503) com o tipo padrão. O `TaskContext` traz `taskId`, `instanceId`, `correlationId`, `attempt`, `chainId` e `lineage`, e o MDC tem `stint.attempt`.
- **`emit`** (SDD 2.2): vai para o outbox e é publicado pelo relay. `data` grande vai para `sandbox-facts` em `facts/<namespace>/<name>/<id>` (chave determinística) com a extensão `dataref`.
- **Linhagem** (SDD 2.5 / README "Lineage"):
	- `causationid` é "o id do evento que **diretamente** causou este";
	- numa ativação retomada por resultado de task, ele é o `correlationId` do resultado (`WorkflowEngine` l. 427–428);
	- um `emit` depois de uma task carrega, portanto, a causação da task, **não** a do pedido. Ver 8f.
- **Erros padrão** (SDD 2.8): `https://serverlessworkflow.io/spec/1.0.0/errors/<type>` com `status` e `instance` (JSON Pointer); `communication` tem padrão 500, mas o 503 do worker prevalece.
- **Teste em memória**: `InMemoryTimerService(InstantSource)` e `InMemoryScheduleService(InstantSource)` têm modo de teste sem thread, com avanço manual do relógio (CA8).

### Conclusão
O Stint tem tudo o que o S.2 precisa, sem mudança no Stint. Falta decidir o que é do sandbox: onde fica o documento da fatura (8a), a resposta para pedido inválido (8b), onde ficam os registros (8c), a janela do resumo (8d) e o e-mail (8e). Há também uma divergência entre o CA1 e a semântica documentada de `causationid` (8f), e um caso não especificado: o retry esgotado (8g).

## 2. Problema e objetivo
O Billing existe só como engine vazio. O S.2 entrega o domínio de faturamento:
- emite a fatura de um pedido solicitada por evento, com retry a falhas transitórias;
- rejeita pedidos inválidos de forma visível para Orders;
- envia um resumo periódico por e-mail.

Tudo idempotente sob entrega at-least-once, observável e sem acoplamento a Orders além do contrato.

## 3. Requisitos
- **RF1** `billing:issue-invoice:1.0.0`, iniciado por `schedule.on` em `invoice-requested.v1`:
	- valida o pedido (`switch`: `amount > 0`, `currency` presente);
	- chama `render-invoice` (remota) com `invoiceId` determinístico a partir de `orderId`, dentro de um `try` com retry exponencial para `communication`/503, até 3 tentativas;
	- registra a fatura (`record-invoice`);
	- emite `invoice-issued.v1`.
- **RF2** Pedido inválido: emite `invoice-rejected.v1` (8b) e termina sem fatura.
- **RF3** `billing:billing-summary:1.0.0`, com `schedule.every` de 1 minuto: conta as faturas da janela (8d) e envia e-mail a um endereço fixo de finanças (8e) pelo MailHog.
- **RF4** Injeção de falhas só no worker e documentada no README do Billing:
	- `orderId` com prefixo `fail503-`: as 2 primeiras tentativas falham com `communication`/503;
	- prefixo `slow-`: atraso configurável.
- **RF5** Efeitos do worker idempotentes:
	- chaves determinísticas (documento, registro);
	- marcador de e-mail, chaveado por instância + task e gravado antes do envio.
- **RF6** Log JSON com `stint.chainId`/`stint.instanceId`; spans ligados ao evento gatilho.
- **RF7** Nenhum arquivo do Billing menciona Orders além do contrato, verificado por teste (padrão `ArchiveReportIndependenceTest`).

## 4. Especificação
### 4.1 `issue-invoice` (esboço; o YAML final sai na etapa 2)
```yaml
document: { dsl: '1.0.0', namespace: billing, name: issue-invoice, version: '1.0.0' }
schedule:
  on:
    one:
      with: { type: io.sandbox.billing.invoice-requested.v1 }
do:
  - order:      { set: '${ .[0].data }' }
  - validate:
      switch:
        - invalid-amount:   { when: '${ (.amount // 0) <= 0 }',    then: reject-amount }
        - missing-currency: { when: '${ (.currency // "") == "" }', then: reject-currency }
        - valid:            { then: render }
  - reject-amount:   { emit: ...invoice-rejected.v1 { orderId, reason: invalid-amount },   then: end }
  - reject-currency: { emit: ...invoice-rejected.v1 { orderId, reason: missing-currency }, then: end }
  - render:
      try:
        - render-invoice: { call: remote, with: { task: render-invoice, input: '${ . }' } }
      catch:
        errors: { with: { type: https://serverlessworkflow.io/spec/1.0.0/errors/communication, status: 503 } }
        retry:  { delay: { seconds: 2 }, backoff: { exponential: {} }, limit: { attempt: { count: 3 } } }
  - record:  { call: remote, with: { task: record-invoice, input: '${ . }' } }
  - issued:  { emit: ...invoice-issued.v1 { orderId, invoiceId, amount, currency, issuedAt, invoiceDocument } }
```

### 4.2 Handlers (worker do Billing, `TaskHandlerRegistry`)
| Routing key | Efeito | Chave idempotente |
|---|---|---|
| `render-invoice` | `invoiceId = "inv-" + hex(sha256(orderId))[0..16]`; grava o documento HTML em `sandbox-facts` (8a); aplica `fail503-`/`slow-` | `billing/invoices/<invoiceId>.html` (sobrescrita idêntica) |
| `record-invoice` | `PutItem` condicional na tabela de faturas (8c); se o item já existe, devolve o registrado (inclusive `issuedAt`) | `invoiceId` |
| `count-invoices` | conta as faturas com `issuedAt` em `[início, fim)` (8d) | leitura |
| `send-summary` | marcador condicional `mail#<instanceId>#<taskId>` e depois SMTP; se o marcador já existe, não envia | `instanceId` + `taskId` |

O `issuedAt` é fixado no primeiro `record-invoice` e devolvido igual nas repetições, então o `invoice-issued` repetido carrega o mesmo valor.

### 4.3 `billing-summary`
`schedule.every: { minutes: 1 }` → `count-invoices` com `windowEnd = .[0].time` e `windowMinutes = 1` → `send-summary`.

### 4.4 Infraestrutura e configuração
- Bootstrap: regra `invoice-rejected-to-orders` → `orders-domain-events` (8b) e tabelas de 8c.
- `billing.mail.*` e `quarkus.mailer.*` → MailHog (8e). Dependência nova: `quarkus-mailer`, a mesma já usada pelo `sandbox-reports`, para o envio SMTP.
- `billing.fault.slow-delay` (padrão `5s`, abaixo do visibility timeout de 30 s da fila de tasks).

## 5. Fora de escopo
- O lado Orders (`listen` do `invoice-issued`/`invoice-rejected`, leitura do documento): S.3.
- Cenários ponta a ponta Orders↔Billing e build nativo: S.4.
- Formato fiscal real da fatura (o documento é um HTML de demonstração).

## 6. Critérios de aceite
| CA | Critério | Evidência nomeada |
|---|---|---|
| CA1 | `invoice-requested` manual (com `chainid` explícito) → 1 instância + `invoice-issued` no spy, com `chainid` = do pedido e `causationid` conforme 8f | `docs/sdd/evidence/S.2-CA1.txt` + `FlociIssueInvoiceIT.issues_an_invoice_with_the_order_lineage` |
| CA2 | Evento duplicado (mesmo `source`/`id`) → 1 instância e 1 `invoice-issued` | `S.2-CA2.txt` + `duplicate_request_issues_once` |
| CA3 | `fail503-` → sucesso na tentativa 3 (`stint.attempt=3` no log do worker); os erros intermediários com tipo `communication` (URI DSL) e `status` 503 | `S.2-CA3.txt` + `transient_503_succeeds_on_the_third_attempt` |
| CA4 | Pedido inválido → `invoice-rejected` (8b) no spy, sem fatura registrada | `S.2-CA4.txt` + `invalid_order_is_rejected` |
| CA5 | Resumo pelo Scheduler do floci com a contagem certa; ocorrência duplicada não envia 2º e-mail (MailHog) | `S.2-CA5.txt` + `FlociBillingSummaryIT` |
| CA6 | Billing parado: 3 pedidos esperam em `billing-domain-events`; ao voltar, as 3 faturas saem | `S.2-CA6.txt` (roteiro manual com `list-resources`/spy) |
| CA7 | Teste de isolamento + enforcer verdes | `BillingIndependenceTest` + `mvn verify` |
| CA8 | Testes unitários com engine em memória (test scope) e relógio controlável: válido, inválido, retry, retry esgotado | `IssueInvoiceWorkflowTest` (4 testes nomeados) |

## 7. Plano de tarefas (etapa 2, após aprovação)
1. Contrato + bootstrap (tipo/campo novos, regra, tabelas, política do bucket).
2. YAMLs `issue-invoice` e `billing-summary`.
3. Handlers e configuração do e-mail.
4. Testes unitários (CA8).
5. Testes no floci (CA1–CA5) e roteiro do CA6.
6. Isolamento (CA7).
7. README do Billing (injeção de falhas, e-mail, como rodar).

## 8. Questões em aberto: propostas para aprovação

### 8a. Como Orders recebe o documento da fatura (obrigatória)
**Proposta:** o `render-invoice` grava o documento em `sandbox-facts` sob `billing/invoices/<invoiceId>.html`, e o `invoice-issued.v1` ganha o campo **opcional** `invoiceDocument` (string, URI `s3://sandbox-facts/billing/invoices/<invoiceId>.html`).
- É compatível pela seção "Mudanças" do contrato: campo novo e opcional.
- A chave é determinística, então reexecutar sobrescreve o mesmo objeto.
- O contrato ganha a seção "Bucket `sandbox-facts`: política de acesso":

| Prefixo | Escreve | Lê |
|---|---|---|
| `facts/<namespace>/…` | o engine do domínio dono do namespace (dataref, SDD 2.2) | qualquer consumidor do fato |
| `billing/…` | só o Billing | Orders (só os objetos citados em `invoiceDocument`) |
| `orders/…` | só Orders (reservado) | Billing, se um contrato futuro citar |

- O bootstrap aplica uma bucket policy equivalente, se o floci aceitar `put-bucket-policy`. O floci não aplica IAM (todos usam `test`/`test`), então a política é **declarativa**. A garantia real no sandbox é a convenção, mais o teste de isolamento do CA7, que verifica que o Billing só escreve sob `billing/` e `facts/billing/`.
- Se o floci rejeitar a política, eu paro e pergunto.

Alternativas descartadas:
- documento inline no evento: tamanho e acoplamento;
- bucket próprio do Billing exposto a Orders: viola a soberania do bucket de blobs.

### 8b. Resposta para pedido inválido (obrigatória)
**Proposta:** um tipo novo, `io.sandbox.billing.invoice-rejected.v1`.
- `source`: `https://sandbox.stintflow.io/billing`; correlação: `data.orderId`.
- `data`:
	- `orderId` (obrigatório);
	- `reason` (obrigatório): `invalid-amount` ou `missing-currency`;
	- `rejectedAt` (opcional, ISO-8601 UTC).
- Regra nova `invoice-rejected-to-orders` → `orders-domain-events`. O spy recebe pela `spy-all`.
- O contrato lista os valores de `reason`. Um valor novo é uma mudança compatível só se os consumidores tratarem um valor desconhecido como rejeição genérica, o que fica dito no contrato.

Alternativa descartada: um `invoice-issued` com status, porque muda a semântica de um tipo existente.

### 8c. Onde ficam os registros de fatura
**Proposta:** uma tabela DynamoDB `billing-invoices`.
- PK `invoiceId` (S); atributos `orderId`, `amount`, `currency`, `issuedAt` (ISO UTC), `issuedAtEpochMs` (N).
- `PutItem` com `attribute_not_exists(invoiceId)`.
- No mesmo bootstrap, a tabela `billing-effects` (PK `effectKey`), para os marcadores de e-mail do RF5.

**Por que não um prefixo no bucket:**
- contar por janela exigiria listar e ler cada objeto;
- o `LastModified` muda a cada sobrescrita idempotente, então não serve como `issuedAt`.

A contagem usa `Scan` com filtro por `issuedAtEpochMs`, o que basta na escala do sandbox. Um GSI fica fora do escopo.

### 8d. Como o resumo sabe "desde o último resumo"
**Proposta:** a janela é definida pelo **instante agendado da ocorrência**: `[T − 1 min, T)`, com `T = .[0].time` do evento sintético. Não há estado guardado.
- É determinística: uma ocorrência duplicada ou repetida conta a mesma coisa (CA5).
- Não tem corrida entre instâncias.
- Independe de atraso na entrega, porque usa o instante agendado, não o de chegada.

**Custos aceitos:**
- uma ocorrência perdida (Billing parado mais de meio período, rejeitada pelo SDD 2.7) não é resumida depois;
- uma fatura com `issuedAt` logo antes de `T`, mas gravada depois da contagem, fica fora.

Os dois ficam documentados no README.

Alternativa descartada: guardar "último resumo em". Exige escrita condicional e lógica de recuperação, e ainda falha com duplicatas concorrentes.

### 8e. Endereços e fuso do resumo
**Proposta:**
- remetente `billing@sandbox.local`, destinatário fixo `finance@sandbox.local`, em `billing.mail.from`/`billing.mail.to`;
- assunto `Billing summary <início> to <fim> UTC: N invoice(s)` (ASCII, sem codificação MIME);
- **todos os instantes em UTC** (o cron `* * * * *` não depende de fuso; a janela e o corpo mostram ISO-8601 UTC);
- `quarkus.mailer.host=localhost`, `port=1025`, `mock=false`.

### 8f. `causationid` no CA1 (divergência entre a spec e a semântica documentada do Stint)
Pelo README do Stint, o `causationid` é a causa **direta**. O `invoice-issued` é emitido depois de `record-invoice`, então sai com `causationid` = `correlationId` do resultado dessa task, não com o `id` do pedido. Isso é o Stint funcionando como documentado, não uma falha.

**Proposta para o CA1:**
- verificar `chainid` do `invoice-issued` = `chainid` do pedido;
- verificar que o `causationid` da **ativação inicial** da instância (log `stint.causationId` e span) é o `id` do pedido;
- verificar que o `causationid` do `invoice-issued` é o `correlationId` de `record-invoice`, presente no log do worker.

A cadeia completa fica provada pelos logs. No CA4 (rejeição sem task), o `invoice-rejected` já sai com `causationid` = `id` do pedido.

### 8g. Retry esgotado
A spec não diz o que acontece depois da 3ª falha.

**Proposta:** a instância falha (status de falha do engine, erro "Retry limit exhausted"), sem fatura e sem evento. Orders trata pelo timeout do seu `listen` (S.3). O `invoice-rejected` fica reservado a pedido inválido.

O CA8 "retry esgotado" verifica isso com 3 falhas forçadas pelo handler de teste.

### 8h. ACHADO no Stint: `with.input` de uma `call: remote` dentro de `try` é ignorado (registrado em 10/10/2026)
**Onde:** Stint `d455dc0`. A regra de `try`/`catch`/`retry` é do SDD 1.3; a página do Stint está no Notion, que está inacessível. O achado será copiado para a seção 8 do SDD 1.3 quando o Notion voltar.

**Comportamento:**
- Numa `call: remote` **dentro de um `try`**, o worker recebe a entrada efetiva do próprio `try`, não o `with.input` declarado na call.
- O `export` da call é aplicado normalmente.
- O `DslLoader` com `LoadOptions.STRICT` carrega o YAML sem erro nem aviso.
- Fora do `try`, o mesmo `with.input` funciona (ex.: `record-invoice`).
- No código: `TreeInterpreter` devolve `InterpretResult.suspendInTry(tryNode, effectiveInput, …)`, e o redispatch do retry usa `tryNode.dataFlow().inputFrom()`. Nenhum dos dois avalia o `inputFrom` do corpo, que é onde o `DslLoader.compileCallRemote` guarda o `with.input`.

**Reprodução** (`IssueInvoiceWorkflowTest.valid_request_issues_the_invoice_on_the_request_chain`, YAML de `048a838`):
```yaml
- render:
    try:
      - render-invoice:
          call: remote
          with:
            task: render-invoice
            input:
              request: '${ $context }'
```
- Esperado no invoke: `{"request":{"orderId":"o-valid",…}}`.
- Observado: `{"orderId":"o-valid",…}` (o contexto, sem o envelope `request`).
- Resultado: `orderId` vazio no handler, `invoiceId` = `inv-e3b0c44298fc1c14` (sha256 de ""), e o retry do `fail503-` nunca acontece.

**Contorno aplicado** (decisão do Kayan em 10/10/2026): `input.from: '${ {request: $context} }'` na própria task `try`. É DSL 1.0 padrão (transformação de entrada da task). A call ficou sem `with.input`, para não sugerir um efeito que não existe. A correção no Stint fica para um SDD do Stint: aplicar o `with.input` do corpo do `try`, ou rejeitá-lo no `STRICT`.

### 8i. ACHADO no Stint: um timer de retry de 1 s pode disparar antes de a espera existir e se perder (registrado em 10/10/2026)
**Onde:** Stint `d455dc0`. Envolve `WorkflowEngine.armThenSave`, `SqsDelayTimerService.schedule` e o caminho de retry do SDD 1.3. A página do Stint está no Notion, inacessível; o achado vai para a seção 8 do SDD 1.3 do Stint quando ele voltar.

**Comportamento:**
1. Um `retry` com `delay: {seconds: 1}` arma o timer `retry:<id>` para `fireAt = now + 1 s`.
2. `armThenSave` **arma o timer antes** de salvar a espera (`timer.schedule(...)` e depois `state.save(...)`).
3. `SqsDelayTimerService.schedule` calcula `Duration.between(Instant.now(), fireAt).getSeconds()`. O `getSeconds()` trunca: com alguns milissegundos já decorridos, sai `0`, e a mensagem vai com `DelaySeconds=0`, visível na hora.
4. O poller (`stint-sqs-timer-poller`) pode recebê-la antes do `save`. Então `resumeWithRetry` não encontra a espera (`findWait` vazio → `done()`, "unknown or stale"), e a mensagem é **apagada**.
5. A espera `retry:<id>` é salva logo depois, mas nenhum timer vai dispará-la. A instância fica `WAITING` para sempre, sem log de erro.

Isso viola o at-least-once: o timer foi entregue e confirmado sem que o efeito existisse. A truncagem também encurta todo atraso SQS em até 1 s (N s vira N − 1 s).

**Reprodução** (`FlociBillingIT.ca3_a_transient_503_succeeds_on_the_third_attempt`, `mvn verify -Pfloci -pl sandbox-billing`):
- Na 1ª execução passou. Na 2ª, a instância `evt-e4f1a7a1-6e92-366f-a9a3-2002c27d2f9c` parou depois da tentativa 1 (falha 503 às 23:25:12.290Z), com timeout do teste em 90 s.
- Estado no floci depois do teste:
	- `billing-instances`: `status=WAITING`, `waitingKey=retry:98dc9c24-…`, `retryState.attempt=1`, `updatedAt` 23:25:12.351Z;
	- `billing-waits`: a espera `retry:98dc9c24-…` existe (`createdAt` 23:25:12.357Z);
	- `billing-timers` e `billing-timers-dlq`: **nenhuma** mensagem desse timer, só timeouts de task de outras instâncias;
	- log: nenhum aviso ou erro do poller nem do engine.
- É intermitente, porque depende de o poller receber a mensagem antes de o `PutItem` da espera terminar.

**Contorno aplicado** (decisão do Kayan em 10/10/2026): opção 1, `delay: {seconds: 2}` em `issue-invoice`. A corrida continua possível, só menos provável. A correção (opção 2) fica para um SDD do Stint.

**Contornos avaliados:**
1. `delay` do retry ≥ 2 s no YAML do sandbox: `DelaySeconds` ≥ 1 deixa uma folga de ~1 s para o `save`. Reduz a probabilidade, mas a corrida continua. Não é configuração documentada pelo Stint.
2. Corrigir no Stint, num SDD do Stint: salvar a espera antes de armar, ou arredondar para cima o atraso SQS, ou fazer o timer sem espera ser reentregue em vez de apagado.
