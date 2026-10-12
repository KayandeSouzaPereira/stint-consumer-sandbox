# SDD S.4 — Cenários cruzados, caos, carga e evidência do gate

| Campo | Valor |
|---|---|
| ID | S.4 |
| Status | **Implementado** em 11/10/2026 (spec aprovada por Kayan em 11/10/2026, com as propostas 8a–8h; contorno do achado 8i decidido por ele). Validado e Mergeado ficam com o Kayan |
| Depende de | S.1, S.2 e S.3 (Implementados). SDD 2.9 do Stint (Implementado em `b1f4579`) |
| Bloqueia | Revalidação do gate da Fase 2 no PR novo do Stint (2.4–2.9), conforme 2.9, seção 8f |
| Branch | `phase-2-validation` |
| Commits analisados | sandbox `372a052` · Stint `b1f4579` (`phase-2/federated-orchestration`; local na análise, no remoto desde o push do RF0) |
| Data da análise | 11/10/2026 |
| Notion | **Inacessível nesta sessão** (o MCP do Notion não conecta). A política "Regras gerais para agentes" e o plano não puderam ser relidos. Valem as regras do pedido e as decisões registradas nos SDDs do repositório. A página S.4 em "Stint · SDDs" será criada quando ele voltar |

## 1. Análise

### Pré-requisito do RF0: o SHA do 2.9 **não está no remoto**
`git ls-remote origin` no Stint, em 11/10/2026:
- `refs/heads/main` aponta para `bb5776b` (o PR #2 mergeado);
- `refs/heads/phase-2/federated-orchestration` ainda aponta para `d455dc0`.

Os commits `1ee8741` → `b1f4579` do SDD 2.9 só existem no clone local `C:\dev\CDO`. Pelo RF0, a etapa 2 começa pedindo ao Kayan o push dessa branch, ou outro HEAD. Sem o SHA no remoto, o README do sandbox não pode fixá-lo: um clone limpo não o encontraria, e isso quebra o CA7 do S.1.

### Sandbox (`372a052`)
- **Módulos:**
	- `sandbox-reports` (:8080, regressão da Fase 1);
	- `sandbox-orders` (:8081, S.3);
	- `sandbox-billing` (:8082, S.2).
	- Orders e Billing não dependem um do outro: o enforcer e os testes `*IndependenceTest` garantem isso.
- **Engines.** `BillingEngine` e `OrdersEngine` montam os conectores à mão:
	- `DynamoDbStateStore` com instâncias, esperas, outbox (4 shards) e inbox;
	- `SqsTaskTransport`;
	- `DelayRoutingTimerService` (SQS até 15 min, Scheduler acima disso);
	- `EventBridgeEventPublisher(sandbox-domain)` com `S3FactBlobStore`, e o relay do outbox a cada 30 s;
	- `SqsDomainEventSource`, com `StartReaction` e `ResumeReaction`;
	- worker em loop numa thread `<domínio>-worker`, que confirma a task depois de enviar o resultado.
	- O `EventPublisher` é **instanciado pelo app** (`new EventBridgeEventPublisher(...)` no `startEngine`). Por isso ele pode ser decorado sem tocar no Stint (ver 8b).
- **Flags de papel (S.1, 8d):** `<domínio>.engine.enabled` e `<domínio>.worker.enabled`, ambas `true` por padrão. O mesmo jar roda só engine, só worker ou os dois. Com o engine desligado, o processo não consome `-domain-events`, `-results` nem `-timers`.
- **Contornos em vigor:**
	- **S.2 8h** (`dfb5002`): o `render` do `issue-invoice` monta a entrada do renderer com `input.from: '${ {request: $context} }'` no `try`, sem `with.input` na call.
	- **S.2 8i** (`988b1fc`): o retry do `render` começa em `seconds: 2`. O original (`048a838`) era `seconds: 1`.
	- **S.3, consequência do 8i:** `OrderDefinitions.MIN_TIMEOUT = 2 s` recusa timeouts de `listen` menores. O comentário do `place-order.yaml` cita a mesma regra.
- **Fault injection do Billing (S.2 RF4):**
	- `orderId` com prefixo `fail503-` falha nas tentativas 1 e 2 com 503;
	- prefixo `slow-` atrasa o render em `billing.fault.slow-delay` (5 s).
- **Idempotência do Billing:**
	- `render-invoice` gera `invoiceId` e chave do documento como funções do `orderId`, então repetir o render sobrescreve o mesmo objeto com o mesmo conteúdo;
	- `record-invoice` faz `recordIfAbsent`.
- **Idempotência do Orders:** os e-mails usam marcador por instância + task (`orders-effects`), e `POST /orders` é idempotente por `orderId` (S.3 8a/8b).
- **Testes no floci:**
	- `FlociBillingIT` (`-Pfloci -pl sandbox-billing`): CA1–CA5 do S.2;
	- `FlociOrdersIT` (`-Pfloci -pl sandbox-orders`): CA1, CA2 e CA4–CA7 do S.3. Exige o Billing rodando como processo externo, conferido pelo health (S.3 8g);
	- todos esperam por conclusão do engine, long poll da fila `sandbox-spy` ou barreira, nunca por sleep.
- **Roteiros manuais**, porque precisam parar e religar processos:
	- S.1 CA2: `curl` e MailHog;
	- S.2 CA6: Billing parado;
	- S.3 CA3: Billing parado além do timeout.
- **Resíduos no floci:**
	- a instância do 8i (`evt-e4f1a7a1-…`) continua `WAITING` em `billing-instances`;
	- uma mensagem malformada continua na `orders-timers-dlq` (S.3, Notas).
	- Uma medição de "nenhuma sobra" precisa partir de estado zerado (ver 8e).
- **Infra:** floci, MailHog e Jaeger estão de pé via `docker compose`.
	- O Docker tem **~2 GB de memória** (`docker info`: 2.058.821.632 bytes) e não há `native-image` local.
	- O build nativo em container costuma pedir 6–8 GB. É o mesmo bloqueio do CA2 do SDD 2.7 do Stint (ver 8f).

### Stint (`b1f4579`): o que o 2.9 muda para o sandbox
- **Timers (RF1–RF3 do 2.9):**
	- o atraso SQS é arredondado **para cima**, então um retry de 1 s nunca sai como `DelaySeconds=0`;
	- o JSON do timer leva `armedAt`;
	- um disparo que chega antes da espera é classificado `early` e **reentregue**: a mensagem volta visível depois de 2/4/8/16 s, dentro da janela de 30 s e abaixo do `maxReceiveCount` 5 documentado;
	- um disparo sem espera para uma instância terminal é `obsolete` (INFO). Depois da janela, ou sem `armedAt`, é `obsolete-after-window` (WARN). Os logs têm `decision`, `timerId`, `instanceId`, `armedAt`, `age` e `instanceStatus`;
	- a fila de timers já tem redrive 5 no `infra/bootstrap.sh`, como pede o `docs/connectors.md`.
	- **Consequência:** o contorno do 8i (2 s) e o `MIN_TIMEOUT` do S.3 perdem o motivo.
- **`with.input` (RF4 do 2.9):** vale **depois** do `input.from` da task. Dentro de `try`, a call recebe o `with.input` avaliado sobre a entrada do try. Cada retry reenvia a entrada da 1ª tentativa. **Consequência:** o contorno do 8h pode sair.
- **STRICT mais rígido (2.9, 8b):** qualquer chave declarada que o compilador não usaria falha o load, citando chave e caminho. Isso inclui:
	- `if`, `use`, schemas e `timeout` fora de lugar;
	- `then` no corpo do `try`;
	- duas construções na mesma task;
	- unidade de duração errada;
	- `catch.as` e `retry.jitter`.
	- Os 3 YAMLs do sandbox **já carregaram** no STRICT do 2.9 na validação cruzada (CA6 do 2.9): `issue-invoice`, `billing-summary` e `place-order`. A confirmação formal fica no CA1 deste SDD, já com o YAML final do 8a.
- **Outbox (SDD 2.2):**
	- `commitSuspend`/`save` grava o fato no outbox **na mesma transação** do checkpoint;
	- só depois `relay.publishNow(outbox)` chama `EventPublisher.publish`;
	- o relay varre a cada 30 s (`DEFAULT_GRACE` 30 s) os fatos pendentes; fatos publicados são apagados;
	- **consequência:** matar o processo dentro da 1ª chamada a `publish` cai exatamente "entre o checkpoint e a publicação".
- **Inbox (SDD 2.3):** o evento que chega sem espera vai para `<domínio>-inbox` e sai quando é consumido. Com tudo terminado, a tabela deve ficar vazia, salvo duplicatas, que o CA6 do S.3 mostrou ficarem lá; ver 8e.
- **Start programático:** continua sem id determinístico nem idempotência (S.3 8a). É uma observação para o plano, no RF6.

### Conclusão
O Stint em `b1f4579` cobre o S.4 sem mudança no Stint:
- os pontos de morte do caos saem de uma porta pública (`EventPublisher`) e do código do próprio sandbox (handlers);
- as réplicas usam só configuração;
- a medição de sobras usa as tabelas e filas documentadas.

Antes de qualquer código, a etapa 2 depende de duas ações do Kayan:
- o **push** da branch do Stint (RF0);
- **memória do Docker** suficiente para o build nativo (RF4, ver 8f).

## 2. Problema e objetivo
**Problema.** O S.2 e o S.3 provaram cada domínio e o caminho entre eles. Falta provar que a federação aguenta o que acontece de verdade: processos caindo nos piores momentos, réplicas concorrendo, volume e build nativo. Também falta juntar tudo numa evidência que o gate consiga conferir.

**Objetivo.** Rodar os dois domínios sobre o Stint corrigido, sem contornos, sob falhas e carga. Entregar um relatório que liga cada SDD 2.1–2.9 a um cenário que o prova.

## 3. Requisitos
- **RF0** — Atualizar para o Stint corrigido:
	- confirmar o SHA no remoto; se não estiver, PARAR e pedir o push;
	- atualizar o README e reinstalar;
	- remover os contornos (8a);
	- todos os YAMLs carregando no STRICT do 2.9.
- **RF1** — Suíte de regressão em um comando: S.1 CA2, S.2 CA1–CA6 e S.3 CA1–CA7, no novo SHA.
- **RF2** — Caos, com os papéis separados pelas flags do S.1:
	- a) worker do Billing morto no meio do `render-invoice`;
	- b) engine do Billing morto entre o checkpoint e a publicação do `invoice-issued`;
	- c) engine do Orders morto com o pedido esperando no `listen`;
	- d) duas réplicas do engine do Orders;
	- e) Billing fora do ar por mais que o timeout do Orders, com 10 pedidos.
- **RF3** — Carga: N pedidos simultâneos (padrão 50, configurável). Resultado:
	- N faturas, N e-mails com o anexo do próprio pedido e N cadeias distintas;
	- nenhuma sobra;
	- p50 e p95 ponta a ponta registrados só como observação.
- **RF4** — Imagens nativas de `sandbox-orders` e `sandbox-billing` no caminho feliz do S.3 CA1.
- **RF5** — Para um pedido, o trace no Jaeger com os dois domínios ligados por links, e os logs JSON dos dois apps com o mesmo `stint.chainId`.
- **RF6** — `docs/evidence/phase-2-gate.md`:
	- a tabela SDD → cenário → resultado → evidência;
	- o que é real e o que é simulado;
	- os achados do Stint e o estado de cada um;
	- observações para o plano.

## 4. Especificação
A forma dos cenários sai das propostas da seção 8. Resumo:

```
infra/gate.sh [--regression-only] [--repeat 3] [--orders N] [--native]
  1. infra/reset.sh                         floci zerado e recriado (8e)
  2. mvn install                            jars + testes unitários de todos os módulos
  3. mvn verify -Pfloci -pl sandbox-billing (S.2 CA1-CA5)
  4. Billing jar em 2º plano → mvn verify -Pfloci -pl sandbox-orders (S.3 CA1, CA2, CA4-CA7) → Billing parado
  5. mvn verify -Pscenarios -pl sandbox-scenarios
       Regression:  S.1 CA2, S.2 CA6, S.3 CA3 (os antigos roteiros manuais, agora automáticos)
       Chaos:       RF2 a-e
       Load:        RF3 (N = -Dscenarios.orders, padrão 50)
       Observability: RF5 (trace no Jaeger + logs JSON dos processos)
  6. --native: mvn package -Dnative nos dois domínios → Scenarios.happyPath com os binários (RF4)
```

- **`sandbox-scenarios`** é um módulo **só de testes** (8c). Ele não depende de `sandbox-orders`, `sandbox-billing` nem `stint-inmemory`. Ele roda os artefatos empacotados como processos (`ProcessBuilder`) e observa o mundo pelo floci, pelo MailHog, pelo Jaeger e pelo HTTP dos apps.
- **Pontos de morte** (8b): `Runtime.halt(137)` dentro do próprio processo, ligado por uma flag `<domínio>.chaos.*` que é `false` por padrão.
- **Mortes sem ponto exato:** quando basta matar o processo, por exemplo o Billing fora do ar no cenário e, usa-se `Process.destroyForcibly()`, que no Windows vira `TerminateProcess`, o equivalente a `kill -9`.

## 5. Fora de escopo
- Novos domínios, mudanças de contrato e qualquer item da Fase 3.
- Mudanças no Stint: uma falha vira achado (seção 8 deste SDD e do SDD do Stint correspondente).
- Metas de latência: p50/p95 são só observação.

## 6. Critérios de aceite
| CA | Critério | Evidência nomeada |
|---|---|---|
| CA1 | RF0: SHA novo no README, contornos removidos (8a), todos os YAMLs carregando em STRICT | `S.4-CA1.txt` (diff + `git ls-remote` + saída do load) + `IssueInvoiceWorkflowTest` + `FlociBillingIT.ca3_…` |
| CA2 | Regressão (RF1) verde **3 vezes seguidas** | `infra/gate.sh --regression-only --repeat 3` → `S.4-CA2.txt` |
| CA3 | Cenários RF2 a–e verdes, cada um com o estado final de tabelas, filas e MailHog anexado | `ChaosIT.a_…` a `e_…` → `S.4-CA3-{a..e}.txt` |
| CA4 | Carga RF3 com N=50: contagens + verificação de sobras (8e) | `LoadIT.n_simultaneous_orders_…` → `S.4-CA4.txt` |
| CA5 | Nativo RF4 verde nos dois domínios | `infra/gate.sh --native` → `S.4-CA5.txt` |
| CA6 | Trace e logs RF5 capturados | `ObservabilityIT.…` → `S.4-CA6.txt` (JSON do Jaeger + linhas de log dos dois processos) |
| CA7 | Relatório do gate (RF6) completo, sem nenhuma linha "a verificar" | `docs/evidence/phase-2-gate.md` |

## 7. Plano de tarefas (etapa 2, após aprovação)
1. **RF0:**
	- pedir o push ao Kayan;
	- SHA no README, reinstalar o Stint;
	- YAML do 8a;
	- remover o `MIN_TIMEOUT` (8a);
	- `IssueInvoiceWorkflowTest` e `FlociBillingIT`; `S.4-CA1.txt`.
2. **RF1:**
	- `infra/reset.sh` (8e) e `infra/gate.sh`;
	- `sandbox-scenarios` com o lançador de processos e o `Regression` (S.1 CA2, S.2 CA6, S.3 CA3);
	- 3 execuções seguidas.
3. **Infra de caos (8b):** decoradores e flags nos dois apps, com teste unitário mostrando que a flag desligada não muda nada.
4. **RF2 a–e:** um teste por cenário, com o snapshot de sobras anexado a cada um.
5. **RF3:** carga com N configurável.
6. **RF4:** nativo, que depende da memória do Docker (8f).
7. **RF5:** trace e logs.
8. **RF6:** relatório do gate; seção 9; README.

## 8. Questões em aberto: propostas para aprovação

### 8a. Remoção dos contornos do S.2 (obrigatória)
**Proposta:** o `render` do `issue-invoice` volta à forma de `048a838`, que é DSL 1.0 pura.
- A call recebe o `with.input`, avaliado sobre a entrada do `try` (DSL 1.0: o `with` de uma call; Stint 2.9 RF4).
- O retry volta a 1 s.

```yaml
  # RF1: a 503 from the renderer is transient: exponential retry, at most 3 attempts in all. Exhausted, the
  # instance fails (S.2 8g); anything else is not caught and fails it too.
  - render:
      try:
        - render-invoice:
            call: remote
            with:
              task: render-invoice    # routing key -> CloudEvent subject -> selects the worker
              input:
                request: '${ $context }'
            export:
              as: '${ $context + {invoiceId, invoiceDocument, renderAttempt} }'
      catch:
        errors:
          with:
            type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
            status: 503
        retry:
          delay:
            seconds: 1
          backoff:
            exponential: {}
          limit:
            attempt:
              count: 3
```

- **Equivalência com o contorno.** Em `$context` o `request` é o mesmo objeto, e o handler do render lê `input.request`, igual nos dois. O `renderAttempt`, a fatura e o documento não mudam.
- **CA3 do S.2 (retry com 503), com o delay de 1 s:**
	- **delay real:** com o arredondamento do 2.9, o 1º retry sai com `DelaySeconds=1` e o 2º com `DelaySeconds=2` (backoff exponencial);
	- **prova do caso de corrida:** se o timer chegar antes de a espera ser salva, ele é reentregue (`early`);
	- **validação cruzada:** o CA6 do 2.9 já rodou exatamente esse YAML 20 vezes seguidas sem instância parada e sem nenhum disparo `early`;
	- **neste SDD:** a prova vem do `FlociBillingIT.ca3_transient_503_succeeds_on_the_third_attempt` dentro do CA2 (3 execuções) e da carga do RF3, com 10 % dos pedidos `fail503-` (8e).
- **O contorno do S.3 também sai:** `OrderDefinitions.MIN_TIMEOUT` (2 s) e a frase "None may be under 2 s (S.2 8i)" do `place-order.yaml`.
	- A guarda passa a recusar só timeout zero ou negativo.
	- Os valores padrão não mudam: `PT2M` e `PT24H`.
	- Isso deixa o cenário e usar um timeout curto (8c, cenário e) sem contorno.
- **Comentários do YAML:** as linhas que citam 8h/8i saem. O histórico fica no S.2.

### 8b. Como matar um processo no ponto exato (obrigatória)
**Proposta:** pontos de morte **dentro do sandbox**, ligados por flag e desligados por padrão.
- **Mecanismo:** `Runtime.getRuntime().halt(137)`. Ele não roda shutdown hooks nem o `ShutdownEvent`: é o mais próximo de um `kill -9` vindo de dentro do processo. Antes do halt, uma linha de log `CHAOS halt at <ponto> instance=<id>` dá ao cenário o momento exato.
- **Flags**, todas `false`/vazias no `application.properties`. O cenário só as liga por `-D` na linha de comando do processo que vai morrer.

| Cenário | Flag | Onde | Por quê |
|---|---|---|---|
| a | `billing.chaos.halt-in-task=render-invoice` | `BillingHandlers.renderInvoice`, **depois** do `documents.put` e antes de devolver o resultado | É o pior momento do worker: o efeito externo (o PDF no S3) já aconteceu e o resultado não saiu. A task volta à fila pela visibilidade (30 s) e outro worker a pega. O render é idempotente por `orderId` |
| b | `billing.chaos.halt-before-publish=io.sandbox.billing.invoice-issued.v1` | `ChaosEventPublisher`, decorador do `EventBridgeEventPublisher` montado em `BillingEngine.startEngine` | A 1ª chamada a `publish` com esse tipo vem do `relay.publishNow`, **depois** de o checkpoint gravar o outbox (SDD 2.2). Morrer ali deixa o fato só no outbox |
| c | nenhuma | — | Ver abaixo |

- **Por que o decorador do b é legítimo.** `EventPublisher` é uma porta pública do SPI, e o app já a instancia à mão. O decorador só delega, e com a flag desligada nem é montado: `startEngine` usa o publisher original. Nada no Stint muda.
- **Cenário c não precisa de ponto de morte.** O estado "esperando no `listen`" é estável e observável de fora:
	- a espera aparece em `orders-waits`, e `GET /orders/{id}` responde `requested`;
	- o cenário segura a resposta do Billing com um pedido `slow-` (5 s) ou com o Billing parado;
	- quando a espera aparece, o Orders é morto com `destroyForcibly()`;
	- o `invoice-issued` chega à `orders-domain-events` com o Orders fora do ar;
	- ao religar, o engine consome o evento e retoma.
	- Um ponto de morte interno não traria mais precisão, e o `kill` externo é o caso real.
- **Teste da infra:**
	- `ChaosEventPublisherTest` (unitário, no Billing) cobre os dois lados: com a flag desligada, o app monta o publisher original; com ela ligada, o decorador chama um "halter" injetável no tipo certo e só na 1ª vez, sem matar o JVM do teste;
	- a mesma estrutura vale para o ponto do worker.

### 8c. Onde os cenários rodam (obrigatória)
**Proposta:** um **módulo de testes `sandbox-scenarios`** em JUnit 5, com profile `-Pscenarios` e failsafe, que controla os processos. Na frente dele fica um **script fino `infra/gate.sh`** que só sequencia as execuções do Maven. Para o Kayan, é **um comando**: `bash infra/gate.sh`.

| | Script (Git Bash + aws CLI + MailHog) | Módulo de testes (proposta) |
|---|---|---|
| Subir, derrubar e religar processos | `java -jar … &` e `kill`. No Git Bash, `kill` de um `java` nativo do Windows é instável: o PID do MSYS não é o do Windows | `ProcessBuilder` e `Process.destroyForcibly()`, iguais em Windows e Linux; o stdout de cada processo vai para um arquivo da evidência |
| Asserções e espera sem sleep | `jq`/`grep` e laços com `sleep` | AssertJ; espera por conclusão (HTTP `GET /orders`), long poll da `sandbox-spy` e da API do MailHog, como os ITs atuais |
| Reuso | — | Os mesmos padrões de `FlociProbe`/`awaitCompleted` do S.2/S.3 |
| Evidência | saída do script | relatório do failsafe + arquivos `target/scenarios/*.txt` copiados para `docs/sdd/evidence` |

- **Isolamento:** o `sandbox-scenarios` depende só do AWS SDK (`sqs`, `dynamodb`, `s3`), Jackson, JUnit e AssertJ, todos já no BOM.
	- Ele acha os jars por caminho (`../sandbox-<domínio>/target/quarkus-app/quarkus-run.jar`, ou os binários nativos com `--native`), não por dependência Maven.
	- Nenhum domínio passa a depender de outro, e os enforcers não mudam.
	- O contrato continua sendo só o `contracts/events.md`.
	- O módulo fica fora do build padrão (`-Pscenarios`), e o `mvn verify` comum não sobe processos.
- **O script** existe porque o `FlociOrdersIT` exige o Billing rodando antes (S.3 8g), e um teste de Orders não pode subir o jar do Billing.
	- O `gate.sh` faz isso: sobe o Billing, roda o `-Pfloci` do Orders e para o Billing.
	- Ele não tem lógica de asserção: falhou um passo, ele para com o código de saída do Maven.
	- `AWS_ACCESS_KEY_ID=test`, `AWS_SECRET_ACCESS_KEY=test` e `AWS_REGION=us-east-1` ficam exportados no topo, como nos outros scripts.
- **Os roteiros manuais viram testes:** S.1 CA2, S.2 CA6 e S.3 CA3 passam a rodar no `Regression` do `sandbox-scenarios`. Assim o RF1 fica inteiro em um comando. O S.3 CA3 roda com `-Dorders.invoice.timeout=PT20S` em vez de 2 min, o que o 8a permite.
- **Espera sem sleep.** Toda espera é uma de quatro:
	- conclusão observada (HTTP ou DynamoDB) com prazo;
	- long poll de SQS (`sandbox-spy`, 5 s);
	- leitura do MailHog intercalada com esse long poll, o mesmo padrão do `awaitKeptInInbox` do S.3;
	- `Process.waitFor(timeout)`.
	- Nenhum `Thread.sleep`.

### 8d. Cenário d: duas réplicas do engine do Orders
- **Mesmo jar, duas JVMs:**
	- réplica A com `-Dquarkus.http.port=8081`;
	- réplica B com `-Dquarkus.http.port=8083`;
	- **nenhuma** outra propriedade muda: as duas leem o mesmo `application.properties`, com as mesmas filas `orders-*`, as mesmas tabelas `orders-*` e o mesmo barramento.
	- Isso é o que garante as mesmas filas: não há como configurar filas diferentes sem passar uma propriedade `orders.sqs.*`, e o cenário não passa nenhuma.
- **As duas rodam engine e worker** (flags padrão), então os consumidores competem em `orders-domain-events`, `orders-results`, `orders-timers` e `orders-tasks`. O Billing roda normal (:8082).
- **Prova de que as duas trabalharam:**
	- os 20 `POST /orders` se alternam entre 8081 e 8083;
	- o log JSON de cada réplica (`Orders engine …`, linhas de ativação com `stint.instanceId`) mostra que **as duas** processaram ativações. Se uma ficar ociosa, o cenário falha, porque não provaria a concorrência;
	- para forçar disputa pelo mesmo pedido, 5 dos 20 pedidos recebem o `invoice-issued` em duplicata, republicado pelo cenário no barramento com o mesmo `source`/`id` (como no CA6 do S.3), de modo que as duas réplicas podem consumir o mesmo fato.
- **Resultado esperado:**
	- 20 instâncias `COMPLETED` com `status: confirmed` e versão final igual à de uma execução simples;
	- 20 `order-confirmed` no spy, 1 por `orderId`;
	- 20 e-mails, 1 por `orderId`;
	- nenhuma sobra (8e).
	- O controle de versão condicional do `DynamoDbStateStore` e o inbox são o que o cenário exercita.
- O mesmo arranjo, com a flag `orders.worker.enabled=false` numa réplica, não acrescenta nada ao RF2d e fica fora.

### 8e. Como medir "nenhuma sobra"
- **Estado inicial zerado** com `infra/reset.sh`, que roda no começo de cada classe de cenário. É **destrutivo, mas só no floci**:
	- o script recusa rodar se o endpoint não for `http://localhost:4566`;
	- ele apaga e recria as tabelas e as filas com o próprio `bootstrap.sh` (`delete-table`, `purge-queue` e depois `bootstrap.sh`), e limpa o MailHog (`DELETE /api/v1/messages`);
	- os resíduos atuais (a instância do 8i e a mensagem malformada da DLQ) somem;
	- **eles ficam registrados no S.2/S.3 antes do reset**, e o S.4-CA1 guarda a saída de `list-resources.sh` de antes do reset.
- **Consultas exatas** ao final de cada cenário, para `<d>` em `orders` e `billing`, depois que as filas internas ficam vazias:

| O quê | Consulta | Esperado |
|---|---|---|
| Esperas | `aws dynamodb scan --table-name <d>-waits --select COUNT` | `Count: 0` |
| Outbox | `aws dynamodb query --table-name <d>-outbox --index-name pending-by-age --key-condition-expression "shard = :s" --expression-attribute-values '{":s":{"S":"<0..3>"}}' --select COUNT`, somado nos 4 shards (o Stint apaga o fato publicado, então tabela = pendentes) | `0` |
| Inbox | `aws dynamodb scan --table-name <d>-inbox --select COUNT` | `0` nos cenários sem duplicata. No d (duplicatas de propósito), exatamente o número de duplicatas: elas ficam até o TTL, como no CA6 do S.3 |
| Instâncias não terminais | `aws dynamodb scan --table-name <d>-instances --filter-expression "#s <> :c" --expression-attribute-names '{"#s":"status"}' --expression-attribute-values '{":c":{"S":"COMPLETED"}}' --select COUNT` | `0`. A exceção esperada é a instância do Billing com retry esgotado, que nenhum cenário do S.4 produz |
| DLQs | `aws sqs get-queue-attributes --queue-url <q>-dlq --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible`, para as 5 DLQs de cada domínio | `0` e `0` |
| Filas de trabalho | o mesmo nas filas `<d>-tasks`, `-results`, `-timers` e `-domain-events` | `0` e `0` no fim |
| Agendamentos | `aws scheduler list-schedules --group-name <d>-timers` | só registro. No cenário e, a confirmação apaga o `at(+24 h)` (S.3 CA3), então o esperado é `0`. Nos outros cenários os timers são SQS |

- **Implementação:** as consultas rodam no teste pelo AWS SDK e são impressas na evidência também como os comandos `aws` equivalentes, para o Kayan repetir.
- **"Filas internas vazias" sem sleep:** a condição de parada é "todas as instâncias terminais e filas de trabalho com 0/0", verificada entre long polls da `sandbox-spy`, com prazo.
- **Carga (RF3):** N=50, dos quais 5 `fail503-`, para o retry de 1 s do 8a ficar sob carga, e 5 `slow-`. "Cadeias distintas" = 50 `chainid` diferentes no spy entre os `order-confirmed`. "Anexo do próprio pedido" = o anexo de cada e-mail é byte a byte igual a `s3://sandbox-facts/billing/invoices/<Billing.invoiceId(orderId)>.html`, e o HTML cita o próprio `orderId`. Essa verificação é a do CA1 do S.3, repetida N vezes.

### 8f. Build nativo (RF4): depende de memória do Docker
- **Situação:** não há GraalVM local, então o build nativo é em container (`-Dquarkus.native.container-build=true`), que gera binários Linux.
	- O Docker tem ~2 GB, e o `native-image` de um app Quarkus com AWS SDK costuma pedir 6–8 GB.
	- O mesmo limite deixou pendente o CA2 do SDD 2.7 do Stint.
- **Proposta:**
	- o Kayan aumenta a memória do Docker Desktop (WSL2: `.wslconfig` `memory=10GB`) antes da etapa RF4;
	- os binários rodam em container `ubi-minimal`, na rede do `docker compose`. O floci e o MailHog passam a ser acessados pelo nome do serviço, por variáveis de ambiente (`QUARKUS_SQS_ENDPOINT_OVERRIDE=http://floci:4566`, etc.), **sem mudar o `application.properties`**;
	- o `Scenarios.happyPath` (S.3 CA1) roda contra eles.
	- Se a memória não puder subir, o S.4 fecha **Bloqueado só no CA5**, com o motivo, e o relatório do gate diz isso explicitamente: o CA7 proíbe "a verificar", não proíbe "bloqueado, motivo X".

### 8g. Observabilidade (RF5)
- Um pedido feliz, com o Orders e o Billing como jars e `quarkus.otel.exporter.otlp.traces.endpoint` no Jaeger do compose.
- **Trace:** o cenário busca no Jaeger (`GET :16686/api/traces?service=sandbox-orders&tags={"stint.chainId":"<chain>"}`, e o mesmo para `sandbox-billing`) e verifica:
	- spans dos dois serviços;
	- links `FOLLOWS_FROM` da ativação do Billing para o span que publicou o `invoice-requested`, e da retomada do `listen` no Orders para o trace do `invoice-issued` (SDD 2.5).
	- O JSON fica anexado.
- **Logs:** o stdout JSON dos dois processos, capturado pelo lançador. O cenário filtra as linhas com `mdc."stint.chainId"` igual ao `chainid` do pedido e exige ao menos uma linha de cada app.
- Se o Jaeger não indexar a tag `stint.chainId`, a busca vai por `service` + janela de tempo e o filtro é feito no cliente. A evidência diz qual caminho foi usado.

### 8h. Relatório do gate (RF6): forma
`docs/evidence/phase-2-gate.md` terá:
- **Tabela SDD → cenário.** Uma linha por SDD 2.1–2.9 do Stint, com colunas SDD | o que o SDD promete | cenário do sandbox | resultado | evidência. Exemplos:
	- 2.1 (start por evento idempotente): S.3 CA5 e RF2d;
	- 2.2 (outbox): RF2b;
	- 2.3 (`listen` e inbox): S.3 CA6 e RF2c;
	- 2.4 (timers longos e Scheduler): S.3 CA3 e RF2e;
	- 2.5 (linhagem): RF5;
	- 2.6 (marco: dois workflows independentes ligados só por eventos, `schedule.cron` + `emit` + `schedule.on`): a federação inteira (S.3 CA1, independência S.2 CA7/S.3 CA9) e o resumo por cron (S.2 CA5);
	- 2.7 (Quarkus/nativo): RF4;
	- 2.8 (erros padrão): S.2 CA3;
	- 2.9: 8a, S.2 CA3 com 1 s e RF3.
- **Real × simulado:**
	- **real no floci:** SQS, DynamoDB, S3, EventBridge, Scheduler, SMTP no MailHog e OTLP no Jaeger;
	- **simulado:** o IAM e a bucket policy (aceitos mas não aplicados), o `sent-timestamp-fallback` do Scheduler, os 503/lentidão por prefixo, as mortes por `halt`/`destroyForcibly`, e as duplicatas republicadas à mão.
- **Achados do Stint, com estado:**
	- S.2 8h e 8i: resolvidos no 2.9;
	- 2.7: o SNS dev services, corrigido no 2.9 8g-3;
	- os novos do S.4, se houver.
- **Observações para o plano:**
	- falta de `start` programático idempotente (S.3 8a);
	- timers SQS não canceláveis geram ruído `obsolete-after-window` (2.9 8g-4);
	- Notion inacessível;
	- o que mais aparecer.
- **Recomendação** de aprovar, ou não, o gate.

### 8i. ACHADO no Stint: no nativo, as funções builtin do jq ficam fora do binário (registrado em 11/10/2026)
**Onde:** Stint `b1f4579`, `JqExpressionEvaluator` (`stint-core`), com o caminho nativo do SDD 2.7. A página do 2.7 está no Notion, inacessível; o achado vai para a seção 8 do SDD 2.7 do Stint quando ele voltar.

**Comportamento:**
- O `JqExpressionEvaluator` carrega as funções builtin do jackson-jq 1.6.0 com `BuiltinFunctionLoader.loadFunctions(classLoader, JQ_1_6, scope)`. Elas são declaradas em `META-INF/services/net.thisptr.jackson.jq.Function` (81 provedores) e chegam por `ServiceLoader`.
- O build nativo do Quarkus 3.33.2 passa `-H:-UseServiceLoaderFeature` ao `native-image`, e o GraalVM deixa de registrar os provedores de `ServiceLoader`. O binário sobe e carrega os YAMLs, mas sem `type`, `length`, `keys` e as outras builtin.
- Qualquer expressão que chame uma delas falha. No `place-order`, a validação da entrada (`$context | (.orderId | type) != "string" or …`) falha já na ativação, e a instância fica `FAILED` com só `Failed to evaluate jq expression: …` no log, sem a causa.
- **Por que o 2.7 não pegou:** o `stint-example-aws-smoke`, que é a prova nativa citada no `docs/connectors.md` ("Native: proven by `stint-example-aws-smoke`"), só usa caminhos (`${ .name }`, `${ . }`, `${ $context }`), nenhuma função. É divergência entre o Stint e a sua documentação.

**Reprodução:**
1. `bash infra/gate.sh --native` no `434a4b3`: `NativeIT` falha com `Not reached within PT3M: native-… confirmed`, e o log `native-orders.log` mostra a instância `FAILED`.
2. O mesmo com `-Dquarkus.native.auto-service-loader-registration=true` (opção documentada do Quarkus, que registra os provedores de `ServiceLoader` no build): `NativeIT` verde (1 teste, 0 falhas, 167 s).

**Contorno decidido por Kayan (11/10/2026):** `quarkus.native.auto-service-loader-registration=true` no `application.properties` do Orders e do Billing, com comentário apontando este item. Só vale no build nativo; o JVM não muda.

**Correção sugerida ao Stint (não feita daqui):** registrar as funções do jackson-jq para o nativo no próprio Stint (por exemplo, na extensão/bundle Quarkus ou num `native-image.properties` do `stint-core`), e pôr no smoke nativo uma expressão com função builtin, para o CA do 2.7 cobrir isso.

**Resolvido no 2.10** (12/10/2026). O SDD 2.10 do Stint pôs no `stint-core` os metadados nativos dos 81 provedores do jackson-jq (`META-INF/native-image/io.stintflow/stint-core`) e uma função builtin (`length`) no smoke nativo. Entrou no `main` pelo PR #3 (merge `9be0898`).
- O contorno saiu do Orders e do Billing, e o sandbox foi fixado no `9be0898` (README, `pom.xml` e `bootstrap.sh`), conforme a proposta 8b do 2.10.
- `gate.sh --native` sem o contorno: `NativeIT` 1/1. O build continua com `-H:-UseServiceLoaderFeature` e o Quarkus informa `auto-service-loader-registration is false`, então quem carrega as funções são os metadados do `stint-core` (`S.4-CA5-2.10.txt`).

## 9. Registro de validação

**Ambiente.**
- Windows 11, Temurin 25.0.3, Maven 3.9.15 e Quarkus 3.33.2;
- Docker Desktop no Hyper-V, com 10 GB a partir do RF4 (8f), rodando floci, MailHog e Jaeger do `docker-compose.yml`;
- Stint `b1f4579`, instalado no `~/.m2` com `mvn install -DskipTests`.

**Commits** (`phase-2-validation`, sem push):

| Commit | O que faz |
|---|---|
| `21b6e04` | spec |
| `af819e8` | aprovação |
| `4fbbc60` | RF0: SHA fixado e contornos removidos |
| `9e96a13` | evidência do CA1 |
| `aec3f2c` | pontos de caos (8b) |
| `71f36d1` | `sandbox-scenarios`, `reset.sh`, `gate.sh` e `RegressionIT` |
| `b29cccc` | caos, carga e observabilidade |
| `3357e39` | nativo em container e `NativeIT` |
| `434a4b3` | build nativo remoto e imagens, sem bind mount |
| `33d4942` | contorno do achado 8i |
| `1f24ceb` | evidências, relatório do gate e README |
| commit da remoção | 8i resolvido no Stint 2.10: contorno removido, Stint fixado no `9be0898` e CA5 refeito (12/10/2026) |

| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.**<br>- O SHA `b1f4579` está no remoto (push autorizado pelo Kayan: `d455dc0..b1f4579`, sem force) e fixado no README, no `pom.xml` e no `bootstrap.sh`.<br>- Os contornos do 8a saíram: `with.input` dentro do `try`, retry de 1 s e fim do `MIN_TIMEOUT`.<br>- Os 5 YAMLs do sandbox carregam em STRICT.<br>- `FlociBillingIT` 5/5: as tentativas 1 e 2 falham com 503 e a 3 renderiza; 0 disparos `early` | `S.4-CA1.txt` |
| CA2 | **Verde 3 vezes seguidas** no `434a4b3`, com `gate.sh --regression-only --repeat 3` (14:29–15:48). Cada rodada passou em:<br>- build e unitários;<br>- `FlociBillingIT` 5/5;<br>- `FlociOrdersIT` 6/6, com o Billing como processo;<br>- `RegressionIT` 3/3.<br>Nos logs, os timers registraram 123 decisões `obsolete` e 0 `early` | `S.4-CA2.txt` |
| CA3 | **Verde** no `33d4942`, com `gate.sh --only 'ChaosIT,LoadIT,ObservabilityIT'` (`ChaosIT` 5/5).<br>- a: o worker caiu com 137 depois do render; a task foi reentregue em ~30 s ao 2º worker; a mesma fatura foi renderizada 2× (idempotente); 1 confirmação.<br>- b: o engine caiu com 137 no `publish`; ficou 1 fato no `billing-outbox` e 0 no barramento; o engine religado publicou pelo relay em ~30 s.<br>- c: o Orders foi morto no `listen`; o evento esperou na fila; o Orders religado retomou.<br>- d: duas réplicas, com 9 e 11 instâncias, nenhuma nas duas; 20 confirmações e 20 e-mails.<br>- e: 10 avisos, 10 agendamentos one-time, 10 confirmações e 0 agendamentos no fim.<br>Todas as sobras ficaram em 0, exceto os timers atrasados já obsoletos e as duplicatas do d no inbox | `S.4-CA3-{a..e}.txt` |
| CA4 | **Verde** (`LoadIT`, N=50, 5 `fail503-` e 5 `slow-`):<br>- 50 faturas com ids distintos, 50 `invoice-issued`, 50 `order-confirmed` e 50 e-mails, cada anexo byte a byte igual ao documento do próprio pedido;<br>- 50 cadeias distintas e nenhuma sobra.<br>Só como observação: p50 28,3 s e p95 31,1 s (relatório do gate, 5.3) | `S.4-CA4.txt` |
| CA5 | **Verde com contorno** (`gate.sh --native` no `33d4942`, `NativeIT` 1/1).<br>- As duas imagens nativas subiram em ~0,07 s; o pedido gerou 4 eventos na mesma cadeia, a confirmação com o anexo certo e nenhuma sobra.<br>- Sem o contorno do 8i, o Orders nativo falha na 1ª expressão com `type`.<br>- **Refeito em 12/10/2026 sem o contorno**, com o Stint `9be0898` (8i resolvido no 2.10): `NativeIT` 1/1, mesmos 4 eventos numa cadeia e nenhuma sobra | `S.4-CA5.txt`, `S.4-CA5-2.10.txt` |
| CA6 | **Verde** (`ObservabilityIT`):<br>- 9 spans com o `stint.chainId` (4 do Orders, 5 do Billing);<br>- `FOLLOWS_FROM` do Billing para o Orders e do Orders para o Billing;<br>- linhas JSON dos dois processos com o mesmo `stint.chainId` (1 no Orders, 2 no Billing) | `S.4-CA6.txt`, `S.4-CA6-jaeger.json` |
| CA7 | **Verde.** `docs/evidence/phase-2-gate.md` está completo: tabela 2.1–2.9, real × simulado, achados com estado, observações e recomendação, sem nenhuma linha "a verificar" | `docs/evidence/phase-2-gate.md` |

**Desvios da spec e correções da sua previsão:**
- **8e, inbox no cenário d.** A spec previa "exatamente o número de duplicatas" no `orders-inbox`. Ficaram 4 de 5 nas duas execuções.
	- A duplicata que chega enquanto a espera ainda existe perde a gravação condicional para o evento original e é descartada como já consumida (`WorkflowEngine.java:468`, SDD 2.3). Só as que chegam depois da retomada ficam no inbox.
	- As duas rotas são corretas, e nenhuma confirmação saiu dobrada.
	- O cenário passou a exigir inbox entre 0 e o número de duplicatas, e a evidência diz quantas ficaram.
- **8a, teste do Orders.** O `listen_timeouts_under_two_seconds_are_refused_at_load` virou `only_positive_listen_timeouts_are_accepted_at_load`, consequência direta da remoção do `MIN_TIMEOUT`.
- **8f, build nativo.** A spec previa `-Dquarkus.native.container-build=true` e o runner montado no container.
	- No Docker Desktop com Hyper-V, nenhum container com bind mount de `C:` chega a ser criado: o `docker run` fica pendurado.
	- O build passou a `-Dquarkus.native.remote-container-build=true` (o mesmo builder, com arquivos por `docker cp`).
	- O runner é copiado numa imagem `stint-s4/<módulo>:native`.
	- As URLs vão por `-D` com os nomes do compose, em vez das variáveis de ambiente do 8f; o efeito é o mesmo, e o `application.properties` não muda.
- **8f, memória.** Cada imagem nativa pede ~4,4 GB de pico. Com 2 GB não roda; com os 10 GB que o Kayan configurou, roda.
- **8c, saída do gate.** A saída vai para `gate-out/<data-hora>/` (no `.gitignore`), não para `target/`, que o `mvn clean` do próprio gate apagava.
- **Achado novo (8i)**, com o contorno decidido pelo Kayan.

**Notion.** O MCP do Notion não conectou nesta sessão. Ficam pendentes:
- a página S.4 em "Stint · SDDs";
- o Log da Biblioteca;
- a cópia do achado 8i (resolvido no 2.10) para a seção 8 do SDD 2.7 do Stint;
- a dos achados S.2 8h/8i (já resolvidos no 2.9) para o SDD 1.3.
