# SDD S.3 — Domínio Orders (pedidos)

| Campo | Valor |
|---|---|
| ID | S.3 |
| Status | **Em implementação** (spec aprovada por Kayan em 10/10/2026, com as propostas 8a–8h) |
| Depende de | S.1 (`4b84eb3`), S.2 (Implementado em `4c5300f`) |
| Bloqueia | S.4 (cenários cruzados e caos) |
| Branch | `phase-2-validation` |
| Commits analisados | sandbox `4c5300f` · Stint `d455dc0` (`phase-2/federated-orchestration`) |
| Data da análise | 10/10/2026 |
| Notion | **Inacessível nesta sessão** (o MCP do Notion não conecta). A página S.3 em "Stint · SDDs" será criada quando ele voltar |

## 1. Análise

### Sandbox (`4c5300f`)
- **`OrdersEngine`** (`sandbox-orders`, porta 8081) é o gêmeo do `BillingEngine` do S.1, ainda sem nenhum workflow nem handler:
	- `DynamoDbStateStore(orders-instances, -waits, -outbox, 4, -inbox)` e `SqsTaskTransport(orders-tasks, orders-results)`;
	- timers `DelayRoutingTimerService(SqsDelayTimerService(orders-timers), EventBridgeSchedulerTimerService(orders-timers group, 365 d))`;
	- `EventBridgeEventPublisher(sandbox-domain)` + `S3FactBlobStore(sandbox-facts)`, com o relay do outbox a cada 30 s;
	- `ScheduleReconciler` + `ScheduledStart` sobre `orders-schedules`;
	- `DomainEventRouter(StartReaction, ResumeReaction)` sobre `orders-domain-events`. O `ResumeReaction` usa a janela padrão de eventos antecipados (5 min);
	- worker em loop com `new TaskHandlerRegistry()` vazio;
	- `WorkflowRegistry` carregado por `loadClasspath("stint/workflows/*.yaml", STRICT)`; a pasta só tem `.gitkeep`.
- **Propriedades `orders.*`**: filas, tabelas, `sandbox-orders-blobs`, `sandbox-facts`, `sandbox-domain`, grupos `orders-schedules`/`orders-timers`, role fictícia, `sent-timestamp-fallback=true`, `engine.enabled`/`worker.enabled`. Clients `quarkus.<svc>.*` no floci com `test`/`test`. Sem `quarkus-mailer` nem testes.
- **Contrato depois do S.2:**
	- `invoice-requested.v1` (Orders → Billing): `orderId`, `customerId`, `amount`, `currency`, `items?`;
	- `invoice-issued.v1` (Billing → Orders): os mesmos campos mais `invoiceId`, `issuedAt` e `invoiceDocument?` (`s3://sandbox-facts/billing/invoices/<invoiceId>.html`);
	- `invoice-rejected.v1` (Billing → Orders): `orderId`, `reason` (`invalid-amount` | `missing-currency`; um valor desconhecido é rejeição genérica), `rejectedAt?`;
	- `order-confirmed.v1` (Orders, só observação): `orderId`, `invoiceId`, `confirmedAt`;
	- política do `sandbox-facts`: Orders lê só os objetos citados em `invoiceDocument`.
- **Bootstrap:** já existem as regras `invoice-issued-to-orders` e `invoice-rejected-to-orders` → `orders-domain-events`. Não há tabela de efeitos de Orders nem regra de entrada própria.
- **Achados do S.2 que restringem o S.3:**
	- **8h:** dentro de `try`, o `with.input` de uma `call` é ignorado; usar `input.from` no `try`.
	- **8i:** um timer SQS curto pode disparar antes de a espera ser salva e se perder; nenhum atraso ou timeout abaixo de 2 s.

### Stint (`d455dc0`)
- **Start programático** (`WorkflowEngine.start(ref, input)`): o id é `UUID.randomUUID()` e a chain é nova. **Não há** sobrecarga com id de instância determinístico, nem `start` idempotente.
- **Start por evento** (`startFromEvent` / `StartReaction`, SDD 2.1):
	- o id é `triggeredInstanceId = "evt-" + UUIDv3(definição, source, id)`, uma função pública e estática que o endpoint pode calcular antes;
	- um evento duplicado não cria nada;
	- a chain é a do evento, ou `chainIdOf(source, id)` se ele não tiver.
- **`emit`** (SDD 2.2): grava no outbox na mesma gravação do checkpoint e publica depois. O id do fato é estável.
- **`listen`** (SDD 2.3, README "Waiting for correlated events"):
	- `to.one|any|all`, cada filtro com `with.type` literal e `correlate { from, expect }`;
	- a chave de espera é `event:<type>:<shape>:<hash>`. Uma chave pertence a uma instância: uma 2ª instância esperando a mesma chave **falha com `correlation-conflict`** (`failIfCorrelationTaken`);
	- `read: data | envelope | raw`; a saída é um array dos eventos consumidos. Com `envelope`, `.[0].type` diz qual chegou;
	- `any`/`one` retomam no primeiro evento e encerram as outras esperas; a retomada **cancela o timer** do timeout (`timer.cancel`, best-effort, l. 1051);
	- `emit` seguido de `listen` grava a espera junto com o pedido, e o pedido só é publicado depois. Mesmo uma resposta instantânea acha a espera (CA4);
	- um evento que chega sem espera vai para o **inbox** (`orders-inbox`) pela janela do `ResumeReaction` (5 min, máx. 15) e é consumido quando a instância suspende.
- **Timeout de `listen`:** `timeout.after` (objeto DSL ou duração ISO-8601) levanta `https://serverlessworkflow.io/spec/1.0.0/errors/timeout` (408), capturável por `try/catch`, sem `retry` em volta de `listen`. O registro rejeita um timeout acima do `maxDelay` do timer (aqui 365 d).
- **Timers longos** (SDD 2.4, `DelayRoutingTimerService`):
	- até 15 min vão pelo SQS (`orders-timers`), acima disso por um agendamento one-time do Scheduler (`at(...)`, arredondado para o minuto, grupo `orders-timers`, `ActionAfterCompletion DELETE`), que entrega o mesmo JSON `{timerId, workflowInstanceId}` na mesma fila;
	- `cancel` apaga o agendamento;
	- um disparo tardio não acha a espera e não faz nada (SDD 1.3).
- **Erros padrão** (SDD 2.8): URI DSL 1.0 com `status`; o filtro `catch.errors.with.type` é normalizado.
- **Linhagem** (SDD 2.5): a retomada de um `listen` é um trace novo, com link para o evento recebido e para o span que suspendeu; o `causationid` é o id do evento.
- **`DslLoader.load(InputStream, name, STRICT)`** é público: o app pode carregar uma definição a partir de uma árvore ajustada em memória (ver 8f).

### Conclusão
O Stint cobre o S.3 sem mudança no Stint. A entrada idempotente (8a) sai do start por evento do SDD 2.1, então não é preciso registrar como achado a falta de start programático idempotente. Ela fica anotada como observação para o plano do Stint. O resto são decisões do sandbox: 8b–8g.

## 2. Problema e objetivo
O `sandbox-orders` sobe com engine soberano, mas não faz nada. O S.3 implementa o lado que pede e espera:
- recebe o pedido por HTTP;
- pede a fatura por evento e espera a resposta com correlação e timeout;
- reage à fatura, à recusa ou ao silêncio, incluindo a fatura tardia por um timer longo.

Tudo conversando com o Billing do S.2 só pelos eventos do contrato.

## 3. Requisitos
RF1–RF7 conforme a spec inicial: entrada HTTP idempotente; workflow `place-order`; leitura só do documento citado; e-mails idempotentes; observabilidade; isolamento; prefixos de falha do Billing como dado de teste.

## 4. Especificação
### 4.1 Fluxo
```
POST /orders ──put-events──▶ order-placed.v1 ──(regra order-placed-to-orders)──▶ orders-domain-events
                                                  │
                     place-order (schedule.on) ◀──┘
                       validate ─ invalid ─▶ status invalid, fim
                       emit invoice-requested ─▶ Billing
                       try listen any [issued, rejected] (timeout T1 = 2 min)
                         ├ issued   ─▶ send-confirmation (anexo) ─▶ emit order-confirmed ─▶ confirmed
                         ├ rejected ─▶ send-rejection ─▶ rejected
                         └ timeout  ─▶ send-delay-notice ─▶ invoice-delayed
                                        try listen any [issued, rejected] (timeout T2 = 24 h, Scheduler)
                                          ├ issued   ─▶ send-confirmation ─▶ emit order-confirmed ─▶ confirmed
                                          ├ rejected ─▶ send-rejection ─▶ rejected
                                          └ timeout  ─▶ invoice-missing, fim (sem e-mail novo)
```

### 4.2 `place-order` (esboço; o YAML final sai na etapa 2)
```yaml
document: { dsl: '1.0.0', namespace: orders, name: place-order, version: '1.0.0' }
schedule:
  on: { one: { with: { type: io.sandbox.orders.order-placed.v1 } } }
do:
  - order:   { set: '${ $workflow.input[0].data + {status: "requested"} }', export: { as: '${ . }' } }
  - validate:
      switch:
        - invalid: { when: '<orderId/customerId/recipient/currency strings não vazias; amount número; items array ou ausente>', then: invalid }
        - valid:   { then: request }
  - invalid: { set: '${ $context + {status: "invalid"} }', export: { as: '${ . }' }, then: end }
  - request:
      emit: { event: { with: { source: https://sandbox.stintflow.io/orders, type: io.sandbox.billing.invoice-requested.v1,
              data: '${ $context | {orderId, customerId, amount, currency, items} }' } } }
  - await:
      try:
        - reply:
            listen:
              to:
                any:
                  - with: { type: io.sandbox.billing.invoice-issued.v1 }
                    correlate: { order: { from: '${ .data.orderId }', expect: '${ $context.orderId }' } }
                  - with: { type: io.sandbox.billing.invoice-rejected.v1 }
                    correlate: { order: { from: '${ .data.orderId }', expect: '${ $context.orderId }' } }
              read: envelope
            timeout: { after: PT2M }                      # orders.invoice.timeout (8f)
            export: { as: '${ $context + {reply: .[0]} }' }
      catch:
        errors: { with: { type: https://serverlessworkflow.io/spec/1.0.0/errors/timeout } }
        then: delayed
  - decide:  { switch: [ { issued: { when: '$context.reply.type == "io.sandbox.billing.invoice-issued.v1"', then: confirm } },
                         { rejected: { then: reject } } ] }
  - delayed:     { call: remote, with: { task: send-delay-notice, input: ... } , then: awaitLate }   # status invoice-delayed
  - awaitLate:   { try: [ listen igual, timeout PT24H (orders.late-invoice.timeout) ], catch: timeout → missing }
  - decideLate:  { switch → confirm / reject }
  - confirm:     { call: remote, with: { task: send-confirmation, input: ... } }   # devolve sentAt
  - confirmed:   { emit: order-confirmed { orderId, invoiceId, confirmedAt: sentAt }, status confirmed, then: end }
  - reject:      { call: remote, with: { task: send-rejection, input: ... }, status rejected, then: end }
  - missing:     { set: status invoice-missing, then: end }
```
Nenhuma `call` fica dentro de `try` (o 8h não se aplica). Os dois timeouts são ≥ 2 s (8i).

### 4.3 Handlers (worker de Orders)
| Routing key | Efeito | Idempotência |
|---|---|---|
| `send-confirmation` | `GetObject` **só** do objeto de `invoiceDocument` (8 e RF3) e e-mail com o anexo | marcador `mail#<instanceId>#<taskId>` em `orders-effects`, gravado antes do envio |
| `send-rejection` | e-mail com o motivo | idem |
| `send-delay-notice` | e-mail de atraso | idem |

Se o marcador já existe, o handler não reenvia e devolve `mailed: false`.

### 4.4 Infraestrutura
- Bootstrap: tabela `orders-effects` (PK `effectKey`, copiando a `billing-effects`) e regra `order-placed-to-orders` → `orders-domain-events` (8a).
- `quarkus-mailer` → MailHog, como no S.2.
- Propriedades `orders.invoice.timeout` (padrão `PT2M`) e `orders.late-invoice.timeout` (padrão `PT24H`) (8f).

## 5. Fora de escopo
Caos com processos derrubados, carga paralela e build nativo (S.4). Cancelamento de pedido e reembolso.

## 6. Critérios de aceite
| CA | Critério | Evidência nomeada |
|---|---|---|
| CA1 | `POST /orders` → `order-placed`, `invoice-requested`, `invoice-issued` e `order-confirmed` no spy com o mesmo `chainid` (8b) e e-mail de confirmação no MailHog, com anexo byte a byte igual ao objeto do `invoiceDocument` | `FlociOrdersIT.ca1_…` + `S.3-CA1.txt` |
| CA2 | `amount` 0 → `invoice-rejected` → e-mail com o motivo, nenhum `order-confirmed` | `FlociOrdersIT.ca2_…` + `S.3-CA2.txt` |
| CA3 | Billing desligado → e-mail de atraso depois de T1. Agendamento one-time de T2 criado no grupo `orders-timers`. Billing religado → confirmação, agendamento apagado. Um disparo tardio simulado é ignorado (8c) | roteiro `S.3-CA3.txt` (8g) + `PlaceOrderWorkflowTest.late_invoice_…` |
| CA4 | 20 pedidos seguidos, Billing sem atraso: 20 confirmados, nenhum perdido | `FlociOrdersIT.ca4_…` + `S.3-CA4.txt` |
| CA5 | POST repetido com o mesmo `orderId` (a chave, 8a/8b) → 1 instância, 1 e-mail | `FlociOrdersIT.ca5_…` + `PlaceOrderWorkflowTest` |
| CA6 | O mesmo `invoice-issued` entregue 2× → 1 e-mail de confirmação | `FlociOrdersIT.ca6_…` + `PlaceOrderWorkflowTest` |
| CA7 | `orderId` `fail503-…` confirma depois do retry do Billing, sem tratamento especial em Orders | `FlociOrdersIT.ca7_…` + `S.3-CA7.txt` |
| CA8 | Testes unitários (engine em memória, relógio controlável): emitido, recusado, timeout, resposta tardia (e timeout longo) | `PlaceOrderWorkflowTest` |
| CA9 | `OrdersIndependenceTest` + enforcer verdes | `mvn verify -pl sandbox-orders` + `S.3-CA9.txt` |

## 7. Plano de tarefas (etapa 2, após aprovação)
1. Bootstrap e contrato.
2. YAML `place-order` e o ajuste dos timeouts por propriedade.
3. Endpoints HTTP.
4. Handlers com marcador.
5. Testes unitários (CA8).
6. Testes no floci com o Billing do S.2 rodando (CA1, CA2, CA4–CA7) e o roteiro do CA3.
7. Isolamento (CA9).
8. README de Orders.

## 8. Questões em aberto: propostas para aprovação

### 8a. Entrada HTTP idempotente (obrigatória)
- **(i) API do Stint com id determinístico: não existe.** `start(ref, input)` sempre gera um UUID novo. Fica como observação para o plano do Stint, não como achado, porque a (ii) resolve sem contorno.
- **(ii) Proposta.** O `POST /orders` valida a forma (400 se faltar campo) e publica pelo `put-events` no `sandbox-domain` um evento de entrada, `io.sandbox.orders.order-placed.v1`:
	- `source` `https://sandbox.stintflow.io/orders`, `id` = `orderId`;
	- `chainid` = `"chn-" + UUIDv3("order|" + orderId)`;
	- `data` com o pedido (`orderId`, `customerId`, `recipient`, `amount`, `currency`, `items?`);
	- a regra nova `order-placed-to-orders` o leva a `orders-domain-events`, e `place-order` começa por `schedule.on`.
	- O endpoint responde **202** com `orderId` e `instanceId = WorkflowEngine.triggeredInstanceId(place-order, evento)`, calculado na hora, mesmo antes de a instância existir.
	- Um POST repetido gera o mesmo evento (mesmo `source`/`id`) e, pelo SDD 2.1, a mesma instância: nada é criado duas vezes (CA5).
	- Contrato: `order-placed.v1` entra como tipo **novo**, publicado e consumido só por Orders, e visível para os outros só pela `spy-all`. Ele é o 1º dos "quatro eventos com o mesmo `chainid`" do CA1: `order-placed`, `invoice-requested`, `invoice-issued`, `order-confirmed`.
	- Alternativa: mandar o evento direto para a fila SQS de Orders. Evita mudar o contrato, mas o spy não vê a entrada e a linhagem do CA1 fica com 3 eventos.
- **(iii) descartada.** Uma chave gravada antes do `start` deixa uma janela em que o processo morre entre gravar e iniciar. Isso exige recuperação própria, e é o que o 2.1 já resolve.

### 8b. De onde vem o `orderId` (obrigatória)
**Proposta:** o **cliente envia** o `orderId`, obrigatório, `[A-Za-z0-9-]{1,64}`, e ele **é a chave de idempotência**.
- Ele vira o `id` do evento de entrada, então o mesmo `orderId` dá sempre a mesma instância. Duas instâncias nunca esperam a mesma chave de correlação, e o `correlation-conflict` só aconteceria entre versões diferentes da definição (fora do escopo, documentado).
- O cliente precisa escolher o id porque o CA7 exige `orderId` `fail503-…`. Orders não interpreta o prefixo (RF7).
- O 2º POST com o mesmo `orderId`:
	- devolve 202 com o mesmo `instanceId`;
	- **o primeiro corpo vence**: o 2º evento é duplicado e nada muda;
	- um corpo diferente não é detectado (seria preciso guardar o corpo fora do Stint). Fica documentado.

### 8c. O que acontece depois do timeout (obrigatória)
**Proposta: opção 2.**
1. Depois do e-mail de atraso (`status: invoice-delayed`), um 2º `listen any` (issued/rejected) espera com timeout T2 = 24 h. Esse timer vai por um agendamento one-time do Scheduler (SDD 2.4).
2. Uma fatura tardia confirma o pedido (e-mail + `order-confirmed`); uma recusa tardia manda o e-mail de recusa.
3. Se T2 vencer, o pedido termina `invoice-missing`, sem outro e-mail.

Uma resposta que chegue no intervalo entre o 1º timeout e a gravação do 2º `listen` fica no inbox (5 min) e é consumida quando ele suspende.

**Como testar T2 sem esperar 24 h** (roteiro do CA3, no floci):
1. depois do e-mail de atraso, `scheduler list-schedules --group-name orders-timers` mostra o agendamento `at(...)` para ~24 h;
2. religado o Billing, a fatura retoma o `listen`: confirmação, `order-confirmed`, e o agendamento **some** (`cancel`);
3. o disparo tardio é simulado pondo na fila `orders-timers` o JSON `{timerId, workflowInstanceId}` que o Scheduler entregaria: a instância continua `COMPLETED` e nenhum e-mail novo sai.

O CA8 cobre o mesmo em memória: timeout de T1, fatura tardia, e T2 vencendo.

### 8d. Estados do pedido no `GET /orders/{orderId}`
**Proposta:** os estados vêm do **snapshot do Stint**, sem registro próprio.
- O workflow mantém `$context.status`; o GET calcula `instanceId` a partir do `orderId` (como em 8a) e lê o snapshot pelo `StateStore` do engine (SPI pública).

| `status` | Origem |
|---|---|
| `received` | POST aceito, instância ainda não existe (o evento está a caminho) |
| `requested` | esperando a fatura (1º `listen`) |
| `invoice-delayed` | e-mail de atraso enviado, esperando a fatura tardia (2º `listen`) |
| `confirmed` / `rejected` | terminal |
| `invoice-missing` | T2 venceu |
| `invalid` | forma inválida (o endpoint já barra; defesa para eventos de outra origem) |
| `failed` | instância `FAILED` no Stint |

- A resposta traz `orderId`, `instanceId`, `status`, `invoiceId?` e `reason?`. Um `orderId` nunca recebido dá 404.
- Por que não um registro próprio: seriam mais tasks e uma 2ª fonte de verdade que pode divergir do Stint.

### 8e. Os três e-mails (ASCII)
- Remetente: `orders@sandbox.local` (`orders.mail.from`). Destinatário: o `recipient` do pedido.
- **Confirmação:** assunto `Order <orderId> confirmed: invoice <invoiceId>`; corpo com valor, moeda e `issuedAt`; anexo `invoice-<invoiceId>.html` (`text/html`) com os bytes lidos de `invoiceDocument`. Sem `invoiceDocument` (o campo é opcional), vai sem anexo e o corpo diz isso.
- **Recusa:** assunto `Order <orderId> was not invoiced: <reason>`; o corpo explica `invalid-amount`/`missing-currency`, e um motivo desconhecido aparece como "rejected by billing".
- **Atraso:** assunto `Order <orderId>: invoicing is delayed`; o corpo diz que o pedido será confirmado por e-mail quando a fatura chegar.

**RF3:** o handler aceita só `s3://<orders.s3.facts-bucket>/billing/invoices/<nome>.html` (o padrão do contrato) e faz um único `GetObject` da chave citada, nunca `List`. Qualquer outra URI falha a task.

### 8f. Timeout configurável sem reescrever o YAML
O `timeout.after` do DSL é literal. **Proposta:**
- o YAML traz os padrões (`PT2M` e `PT24H`);
- o `OrdersEngine` carrega `place-order.yaml` como árvore, troca os dois `timeout.after` pelos valores de `orders.invoice.timeout` / `orders.late-invoice.timeout` e entrega a árvore ao `DslLoader.load(..., STRICT)`. É API pública, e a definição continua validada pelo Stint;
- valores abaixo de `PT2S` fazem a subida falhar (8i);
- os testes no floci podem usar T1 menor (ex.: `PT20S`) por propriedade de teste.

Alternativa: deixar fixo no YAML e chamar de "configurável por edição". É mais simples, mas o CA3 precisa esperar 2 min reais.

### 8g. Como os testes no floci tratam o Billing
O Billing do S.2 roda como **processo externo** (jar). O README de Orders manda subi-lo antes, e o teste confere a precondição pelo health em `orders.it.counterpart-health`, sem nomear o módulo.
- CA1, CA2 e CA4–CA7 são automáticos (`@QuarkusTest`, tag `floci`, como no S.2).
- O CA3 precisa **desligar e religar** o Billing, então é um **roteiro manual com evidência**, como o CA6 do S.2, mais o teste unitário do CA8. Iniciar e matar o jar do Billing de dentro de um teste de Orders acoplaria Orders ao artefato do Billing (contra o RF6).

### 8h. Ocorrências do 8i (S.2) durante o S.3
Se uma instância de Orders ficar `WAITING` sem timer pendente, ela é registrada aqui como nova ocorrência, com o estado de `orders-instances`, `orders-waits`, `orders-timers` e DLQ, em vez de contornar.
