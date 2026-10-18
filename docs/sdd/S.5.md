# SDD S.5 — Fundação do pipeline de reuniões

| Campo | Valor |
|---|---|
| ID | S.5 |
| Status | **Em implementação** (spec aprovada por Kayan em 15/10/2026, sem escolha explícita: valem as recomendações, 8k opção 1 e 8a opção A) |
| Depende de | S.1–S.4 (Implementados). Stint Fase 3a: 3a.1–3a.7 (Implementados, PR #5 aberto) |
| Bloqueia | S.6 a S.11 |
| Branch | `phase-3a-validation`, criada a partir de `ba14068` (`phase-2-validation`), empilhada (8k, opção 1). Só local |
| Commits analisados | sandbox `ba14068` (`phase-2-validation`, só local) · Stint `500dfd1` (`phase-3a/ai-decision-workers`, **no remoto**) |
| Data da análise | 15/10/2026 |

## 1. Análise

### Pré-condições (RF0)
1. **Stint no remoto: OK.** `git ls-remote origin phase-3a/ai-decision-workers` devolve `500dfd1d2510849ff9efbe5e1d4db0a2add9631d` (15/10/2026), o mesmo HEAD local. Os 12 commits do 3a.6 e o checklist do gate estão publicados, e o PR #5 está aberto. O sandbox pode fixar `500dfd1` sem quebrar o clone limpo (lição do S.4).
2. **Branch do sandbox: decisão do Kayan (8k).** O remoto do sandbox só tem `main` (`0708a2d`). A `phase-2-validation` (`ba14068`, 50+ commits do S.1 ao S.4) existe só aqui. Não criei branch nenhuma.
3. **Leituras feitas:**
   - S.1–S.4;
   - o plano "Plano: Sandbox — validação da Fase 3" e a página do S.5 no Notion;
   - 3a.4 (revisão), 3a.5 (compliance), 3a.6 (marco, corpus, contratos, `LOCAL-STACK.md`), 3a.7 (binding SQS);
   - `phase-3a-gate.md`.

### Sandbox (`ba14068`)
- **Módulos:**
  - `sandbox-reports` (:8080, Fase 1);
  - `sandbox-orders` (:8081) e `sandbox-billing` (:8082), Fase 2;
  - `sandbox-scenarios`, só de testes, com `-Pscenarios`, roda os jars como processos.
- **Padrão a manter:**
  - não herda `stint-parent`;
  - domínios só por eventos (enforcer e `*IndependenceTest`);
  - contrato em `contracts/events.md`;
  - `infra/bootstrap.sh` idempotente, `reset.sh` só no floci, e `gate.sh` num comando;
  - espera sem sleep (long poll da `sandbox-spy`, HTTP, `Process.waitFor`);
  - nativo por `remote-container-build` e imagem, sem bind mount de `C:`.
- **Engines:**
  - `OrdersEngine` e `BillingEngine` montam os conectores à mão;
  - o worker é **um laço serial** numa thread (`orders-worker`, `billing-worker`) que confirma a task depois de enviar o resultado. É isso que o RF9 troca pelo `SqsWorkerBinding`.
- **Carga do S.4** (`LoadIT`, N=50, com 5 `fail503-` e 5 `slow-`): p50 28,3 s e p95 31,1 s, só como observação.
- **Infra:** compose com floci, MailHog e Jaeger. O bus `sandbox-domain` tem uma regra por tipo e a `spy-all`.

### Stint (`500dfd1`): o que serve ao S.5, conferido no código
| Necessidade | O que existe | Onde |
|---|---|---|
| Início por evento com filtro por extensão | `schedule.on` com `with` exato sobre atributos e **extensões** (sem `correlate`) | `EventFilter`, `DslLoader:434` |
| Correlação do `listen` fora do `.data` | `correlate.from` é jq **sobre o envelope inteiro** (`CeWire.toJson` → `correlationValues`): `.subject` e extensões como `.sandboxproject` funcionam | `WorkflowEngine:1643–1676` |
| Filtro exato por extensão no `listen` | `with: { <extensão>: valor }` | `matchesWith`, `WorkflowEngine:1570` |
| `emit` com `subject`, `id` e extensões | `FactFactory`: chave fora do padrão CE vira extensão; `id` declarado é respeitado (idempotência) | `FactFactory:34–130` |
| Laço | **não há `for`, `fork` nem `wait`** (`DslLoader:969`). Há `then: <tarefa>` para trás (goto), limitado a 1 000 nós locais por ativação | `TreeInterpreter:36` |
| Junção | `listen.to.all` com lista **fixa** de filtros; `until` não existe | `DslLoader:1065–1085` |
| Inbox de eventos adiantados | janela padrão de **5 min** (`ResumeReaction.DEFAULT_EARLY_EVENT_WINDOW`), configurável | `ResumeReaction:18` |
| Outbox | os fatos de uma ativação vão no **mesmo `TransactWriteItems`** do checkpoint (limite de 100 itens do DynamoDB) | `DynamoDbStateStore:129–145` |
| Revisão | `call: review` com papéis, prazo, lembrete, escalonamento, itens e `kind: choice`; `x-stint-policy.review.sources` obrigatório; papel, `source` e contrato checados antes de consumir, com auditoria | 3a.4 |
| Transporte de tasks | `SqsTaskTransport`: **uma** fila de invocação e uma de resultado por engine; o `TaskInvocation` traz `routingKey`, mas **nenhum endereço de resposta** | `SqsTaskTransport`, `TaskInvocation` |
| Worker | `SqsWorkerBinding` com `WorkerPool`, concorrência, extensão de visibilidade, `shipResult` customizável | 3a.7 |
| Transcrição e publicação | `stint-worker-transcription` (falso e OpenAI-compatível) e `stint-worker-publishing` (em memória, Confluence REST e Confluence falso) | 3a.6 |
| Contratos | `meeting-recorded.v1` (`additionalProperties: false`, subject = `meetingId`) e `transcript.v1` | `docs/contracts` |

### Achados do Stint que pesam aqui
1. **Sem `fork`** (3a.3c), sem `for` e sem `listen.until`. O número de SDDs só se conhece em tempo de execução: ver 8a.
2. **`listen` não correlaciona por `.data` de fato restrito** (3a.5): resolvido no desenho, porque a correlação vai por extensões e `subject` (8b).
3. **Resposta adiantada do papel de escalonamento** aceita dentro da janela do inbox (3a.4): o portal bloqueia antes, e o teste documenta o achado (8g).
4. **PII falada** escapa do redator de referência (3a.6): o corpus traz as formas faladas (8d), e o filtro é do S.6.
5. **GPU no limite** (7,8 de 8 GB): plano em 8e.
6. **Docker Desktop instável com o C: cheio.** Hoje o C: tem **18 GB livres (99 %)**, o E: 197 GB, e o Docker responde (28.0.1, 10 GB). Aviso no README (8j).
7. **Novo (8l): o transporte de tasks não tem endereço de resposta.** Uma tarefa remota compartilhada (privacidade, registro, canais) não sabe para qual fila de resultado responder.

### Conclusão
O Stint fixado cobre o S.5 sem mudança no Stint, com quatro desenhos do lado do sandbox:
- paralelismo por instância por SDD (8a);
- correlação só por extensões (8b);
- filas de task por chamador para os serviços compartilhados (8c, 8l);
- um transporte de roteamento que implementa a porta pública `TaskTransport`, como o `ChaosEventPublisher` do S.4.

Duas escolhas são do Kayan: **8a** e **8k**.

## 2. Problema e objetivo
**Problema.** O pipeline de reuniões tem sete domínios, três papéis humanos, modelos locais e execução isolada de código. Escrever a lógica de cada domínio (S.8–S.10) antes de fixar contratos, fronteiras e fiação esconderia os problemas de integração até o fim.

**Objetivo:**
- montar a base: Stint fixado, contratos, módulos com fronteira, portal com papéis, corpus, pilha de modelos, guarda de rede, infra e execução isolada;
- provar com tudo falso que um ciclo atravessa Negócio → Engenharia → Entrega numa cadeia só, lida de fora.

## 3. Requisitos
- **RF0** — Pré-condições:
  - Stint `500dfd1` no remoto (feito);
  - branch decidida pelo Kayan (8k).
- **RF1** — Stint fixado em `500dfd1`:
  - README, `pom.xml` (`stint.version` e comentário do SHA) e `bootstrap.sh`;
  - instalado com `mvn install -DskipTests`;
  - o sandbox continua sem herdar o `stint-parent`;
  - os 5 YAMLs das Fases 1 e 2 carregam no STRICT do `500dfd1`.
- **RF2** — Contrato de eventos v1 do pipeline em `contracts/` (8b):
  - um JSON Schema por evento, mais `contracts/pipeline-events.md`;
  - eventos só com ids, ponteiros, enums e contagens;
  - correlação só por extensões e `subject`.
- **RF3** — Módulos com fronteira (8c). Um teste falha se um domínio depender de outro.
- **RF4** — Portal de aprovação no `sandbox-channels` com papéis e credenciais só de teste (8g). Publica `io.stintflow.review.completed.v1` com o `source` declarado em `x-stint-policy.review.sources`.
- **RF5** — Corpus do pipeline (8d): formato v1 estendido, gabarito de cada decisão, PII com marcador e formas faladas, áudio por TTS local reproduzível pelo manifesto.
- **RF6** — Pilha de modelos (8e):
  - no CI, tudo falso;
  - na execução manual, Whisper, LitJev e gerador locais, só em loopback;
  - plano de GPU.
- **RF7** — Guarda de rede igual à do Stint, com dois testes:
  - nos testes, só `localhost` resolve;
  - um teste falha se houver chave real de provedor no ambiente.
- **RF8** — Projeto-alvo `sandbox-target` e execução isolada em container sem rede (8f).
- **RF9** — `OrdersEngine` e `BillingEngine` com `SqsWorkerBinding` + `WorkerPool` (8i). Repetir a carga do S.4.
- **RF10** — Infra nova no `bootstrap.sh`, idempotente. O compose sem serviços pesados no CI. Aviso do C: no README (8j).
- **RF11** — Esqueleto ponta a ponta, tudo falso (8h):
  - `meeting-recorded` → Negócio → `design-published` → Engenharia → `sdd-batch-approved` → Entrega (2 SDDs, modelo de 8a) → `cycle-closed`;
  - aprovações dos papéis pelo portal;
  - uma cadeia só no Jaeger, lida pelo `sandbox-scenarios`.
- **RF12** — Roteiro e execução manual da pilha local (CA7).

## 4. Especificação (resumo; o detalhe está nas propostas da seção 8)

```
                   EventBridge sandbox-domain  (+ spy-all → sandbox-spy)
 channels ── meeting-recorded(stage=business) ──▶ business ── design-published ──▶ engineering
    ▲  ▲                                            │  ▲                              │  │
    │  └── review.requested/completed (portal) ─────┘  │      refinement-requested ◀──┘  │
    │                                                   │      meeting-recorded(stage=technical)
    │   questions-sent ◀───────────────────────────────────── engineering ──────────────┤
    │   (e-mail; o próximo ciclo começa num meeting-recorded com cycle+1)               │
    │                                                                  sdd-batch-approved
    │                                                                                    ▼
    │                             delivery: coordenador por (projeto, ciclo) ── sdd-ready ×N ──▶ instância por SDD
    │                                       ◀── sdd-validated / sdd-cancelled ──────────────────┘
    └──────────── cycle-closed ◀── coordenador
 Tarefas remotas por fila por chamador: privacy-tasks-<d>, records-tasks-<d>, channels-tasks-<d>, target-tasks-delivery
```

## 5. Fora de escopo
- Filtro de PII de verdade (S.6): no S.5, o `redact` é o redator de referência do Stint ou um falso.
- Registro de documentos de verdade (S.7): no S.5, o registro só guarda o documento e devolve id, versão e hash.
- Lógica real dos domínios (S.8–S.10): prompts, critérios, validadores e prazos reais.
- Qualquer mudança no Stint: as lacunas viram achados com proposta de SDD (8l).
- Dado real de qualquer tipo.

## 6. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | Build completo verde (S.1–S.4 inclusive). `BoundaryTest` falha se um domínio depender de outro, provado com uma dependência plantada num teste negativo | `S.5-CA1.txt` |
| CA2 | Esqueleto ponta a ponta no floci: 1 ciclo com 2 SDDs até `cycle-closed`, e outro ciclo com e-mail de dúvidas e reinício. Uma cadeia só no Jaeger, lida pelo `PipelineSkeletonIT` | `S.5-CA2.txt`, `S.5-CA2-jaeger.json` |
| CA3 | Não destravam o passo: resposta com papel errado, `source` não confiável, fora do contrato, e a resposta adiantada do papel de escalonamento (achado 3a.4). Cada caso tem a auditoria `review` correspondente | `PortalRolesIT`, `S.5-CA3.txt` |
| CA4 | Corpus validado: gabarito completo, marcadores e formas faladas listados, hashes de roteiro e áudio contra o manifesto | `PipelineCorpusTest` |
| CA5 | Guarda de rede e teste sem chave real verdes | `NoRealProvidersTest` em cada módulo com cliente HTTP |
| CA6 | Carga do S.4 com o binding: p95 registrado contra 31,1 s | `S.5-CA6.txt` |
| CA7 | Execução manual: uma reunião do corpus em áudio transcrita pelo Whisper, uma decisão pelo LitJev e uma geração local, com memória de GPU e latência. O que o hardware não comportar é registrado e o resto segue | `S.5-CA7-manual.txt` |
| Extra | Um container do `sandbox-target` sem rede não alcança a internet nem o floci | `TargetIsolationIT` |
| Extra | Eventos do esqueleto sem conteúdo: schema estrito e varredura de marcadores e formas faladas na `sandbox-spy` | `PipelineSkeletonIT` |

## 7. Plano de tarefas (etapa 2, após aprovação)
1. **Stint fixado (RF1):** README, pom, bootstrap; YAMLs antigos em STRICT.
2. **Contratos (RF2):** schemas, `pipeline-events.md`, `sandbox-contracts`.
3. **Módulos e fronteira (RF3):** esqueletos dos 7 módulos, enforcer e `BoundaryTest`.
4. **Portal e papéis (RF4):** `sandbox-channels` e `PortalRolesIT`.
5. **Corpus e áudio (RF5):** `corpus/`, o script de áudio reaproveitado e `PipelineCorpusTest`.
6. **Falsos e guarda de rede (RF6, RF7).**
7. **Infra (RF10).**
8. **Esqueleto ponta a ponta (RF11):** `PipelineSkeletonIT` no `sandbox-scenarios`.
9. **Binding na Fase 2 e carga (RF9).**
10. **Execução isolada (RF8):** imagem, runner e `TargetIsolationIT`.
11. **Roteiro e execução manual (RF12, CA7).**
12. **Fechamento:** seção 9, README e gate.

## 8. Questões em aberto: propostas para aprovação

### 8a. OBRIGATÓRIO — Paralelismo dos SDDs sem `fork` (escolha do Kayan)
**Restrições conferidas no `500dfd1`:**
- não há `fork`, `for`, `wait` nem `listen.until`;
- `listen.to.all` só aceita uma lista **fixa** de filtros;
- `then` para trás existe, até 1 000 nós por ativação;
- os fatos de uma ativação vão num `TransactWriteItems` (até 100 itens);
- o inbox guarda evento adiantado por 5 min (configurável).

**(A) Uma instância por SDD + um coordenador que espera todas (recomendada).**
- **Coordenador** `delivery-cycle`, uma instância por (`projectId`, `cycle`):
  - é iniciado pelo `sdd-batch-approved`, cujo `data.sddIds` lista os ids;
  - num laço com `then` para trás, emite um `sdd-ready` por SDD, com `id` determinístico `<projectId>:<cycle>:<sddId>` (reentrega não duplica, SDD 2.1);
  - depois espera, também em laço, os resultados.
- **Instância por SDD** `sdd-delivery`, iniciada pelo `sdd-ready`:
  - roda o `try`/`retry` de 5 tentativas (implementar → validar), com memória por instância, o que dá ao 3a.3 o que o plano quer: a tentativa 3 vê o feedback das anteriores;
  - esgotadas as tentativas, emite `sdd-dead-lettered` e abre o `call: review` `kind: choice` do Técnico: reenviar com nota, aceitar ou cancelar;
  - termina **sempre** com um fato terminal: `sdd-validated` (`outcome: validated | accepted`) ou `sdd-cancelled`.
- **Como o coordenador sabe quantas esperar:** pelo `sddIds` do `sdd-batch-approved`, que são ids e não conteúdo. O schema limita a **20** por lote:
  - mantém o `emit` em laço longe dos 100 itens da transação do checkpoint;
  - mantém o laço longe dos 1 000 nós.
  - Um lote maior é dividido pela Engenharia (S.9).
- **Como espera:**
  - um `listen.to.any` sobre `sdd-validated` e `sdd-cancelled`, correlacionado por `sandboxproject` e `sandboxcycle` (extensões, 8b);
  - cada evento consumido acrescenta o `sandboxsdd` a `settled` (como conjunto: duplicata não conta 2×);
  - o laço volta ao `listen` até `settled ⊇ sddIds`.
  - Em arrival order, e não por id, porque esperar o SDD 1 enquanto o SDD 2 termina deixaria o evento do 2 no inbox, que expira em 5 min.
- **Uma que nunca termina:**
  1. **Prazo próprio.** Cada `sdd-delivery` tem prazo em cada tentativa (3a.3c) e no `review` do Técnico (`onTimeout` com `default: cancel`), então sempre chega a um fato terminal.
  2. **Rede de segurança no coordenador.** O `listen` tem `timeout` maior que a vida máxima de uma instância de SDD: 5 × prazo da tentativa + prazo da revisão + folga. Estourado, ele emite `cycle-closed` com `outcome: stalled` e a lista dos ids pendentes.
     - O Técnico é avisado pelo `sandbox-channels`.
     - As instâncias pendentes não são canceladas: o Stint não cancela instância por evento, um gap registrado em 8l.
- **Prós:**
  - usa só o que o Stint tem;
  - isolamento por SDD (retry, memória, DLQ e trace próprios);
  - N dinâmico;
  - reentrega idempotente pelo `id` declarado.
- **Contras:**
  - dois workflows na Entrega;
  - o laço com `then` para trás é menos legível que um `fork`;
  - o limite de 20 por lote.

**(B) SDD de `fork` no Stint, esperando por ele.**
- O `fork` da DSL 1.0 tem **ramos estáticos** (`fork.branches` é uma lista fixa no YAML). Não resolve N dinâmico sem `for` + `fork`, que a DSL 1.0 não combina num paralelismo dinâmico.
- Um SDD de `fork` resolveria ramos fixos, não este caso. O que resolveria é um "`for` paralelo" (`for` com `in` e `do` concorrentes), uma extensão fora da DSL 1.0.
- **Custo:**
  - bloqueia o S.10 até um SDD novo do Stint;
  - mesmo com o `fork`, o retry e a DLQ por SDD ficariam dentro de uma instância só, sem memória e trace por SDD.
- **Proposta registrada, se o Kayan quiser** — "SDD 3a.8 — `for` com execução concorrente e junção":
  - `for.each/in/do` com `x-stint-concurrency: N`;
  - cada iteração com estado e espera próprios;
  - junção ao fim com `all` ou `any`;
  - limite de iterações;
  - semântica de falha (`failFast` ou `collect`).

**(C) Coordenador sem instâncias filhas: um `listen` por SDD com id fixo.**
- O coordenador espera `sdd-validated` correlacionado por `sandboxsdd`, um id por vez.
- Exige subir a janela do inbox para a vida inteira do lote, porque os resultados chegam fora de ordem.
- Pior que (A): depende de um ajuste global do engine para um caso local.

**Recomendação: (A).** Os contratos de 8b assumem (A).

**Ajuste na implementação (decidido pelo Kayan em 18/10/2026).** A espera "em ordem de chegada, num laço sobre o mesmo `listen`" não funciona no Stint `500dfd1`: ao consumir um fim, o save remove a espera e grava de novo a mesma chave, e os dois stores recusam isso.
- No `InMemoryStateStore`, a espera nova é conferida antes da remoção.
- No DynamoDB, um `TransactWriteItems` não aceita duas operações no mesmo item.
- Com 2 SDDs, o coordenador falhava depois de 5 conflitos (achado 8l-5).

Escolha do Kayan:
- o coordenador espera **cada SDD pelo seu id** (`sandboxsdd`), um depois do outro;
- os SDDs continuam em paralelo;
- um fim que chega antes da vez fica no inbox;
- a janela de eventos adiantados vale **só no engine da Entrega**: `delivery.early-event-window=PT96H`, acima da vida máxima de um SDD (cerca de 78 h);
- a rede de segurança de cada espera é de `PT84H`. Se for (B), a Entrega fica para depois do SDD do Stint, e o esqueleto do S.5 usa ramos fixos com 2 SDDs.

### 8b. OBRIGATÓRIO — Contrato de eventos v1 do pipeline
**Regras gerais (somam-se às de `contracts/events.md`):**
- Tipos `io.sandbox.<domínio>.<evento>.v1`, em que o domínio é o **dono do contrato**, como na Fase 2. CloudEvents 1.0 pelo `emit`, no bus `sandbox-domain`.
- **Correlação só por extensões e `subject`, nunca por `.data`:**

  | Extensão | Tipo | Valor |
  |---|---|---|
  | `sandboxproject` | string | `[a-z0-9-]{1,40}` |
  | `sandboxcycle` | string | `"1"`, `"2"`, …; string porque o `correlate` compara JSON canônico e a extensão inteira viraria número |
  | `sandboxsdd` | string | id do SDD, só nos eventos da Entrega |
  | `sandboxstage` | string | `business` ou `technical`, só no `meeting-recorded` |

  O `subject` é o id natural do objeto do evento (`meetingId`, `documentId`, `sddId`, `recordId`).
- **`data` só com ids, ponteiros (`s3://…`, `record://…`), enums e contagens.** Cada schema tem `additionalProperties: false` e nenhum campo de texto livre. Um teste valida cada evento emitido contra o schema, e o varredor da `sandbox-spy` procura marcadores e formas faladas (8d).
- **Linhagem:** `chainid` do Stint. O gravador (`sandbox-channels`) põe `chainid = "chn-" + UUIDv3("project|" + projectId)` no primeiro `meeting-recorded` do projeto, e reaproveita o mesmo `chainid` nos reinícios, lendo-o do `questions-sent`. Assim fica **uma cadeia só da primeira reunião ao ciclo fechado**, e os ciclos seguintes também.
- **Idempotência:** todo evento emitido por workflow declara `id` determinístico (`<projectId>:<cycle>:<evento>[:<sddId>]`).

**Eventos:**

| Tipo | Publica | Consome | `subject` | Extensões | `data` |
|---|---|---|---|---|---|
| `io.sandbox.channels.meeting-recorded.v1` | `sandbox-channels` (gravador, `POST /meetings`) | Negócio inicia (`stage=business`); Engenharia retoma o `listen` (`stage=technical`) | `meetingId` | project, cycle, stage | **o mesmo schema do `io.stintflow.examples.meeting-recorded.v1`** (ver abaixo) |
| `io.sandbox.business.design-published.v1` | Negócio, depois da aprovação do Representante de Negócio | Engenharia (inicia `refinement`) | `documentId` | project, cycle | `{documentId, recordId, version, designRef, channel: email\|confluence\|meeting}` |
| `io.sandbox.engineering.refinement-requested.v1` | Engenharia (pede a reunião técnica) | `sandbox-channels` (convite por e-mail; no esqueleto, o gravador falso responde com o `meeting-recorded` técnico) | `documentId` | project, cycle | `{documentId, recordId}` |
| `io.sandbox.engineering.questions-sent.v1` | Engenharia (dúvida bloqueante) | `sandbox-channels` (e-mail ao Negócio). Reinicia o ciclo: o próximo `meeting-recorded` de negócio vem com `cycle+1` | `recordId` | project, cycle | `{recordId, questionsRef, questionCount, nextCycle}` |
| `io.sandbox.engineering.sdd-batch-approved.v1` | Engenharia, depois da aprovação do Representante Técnico | Entrega (inicia o coordenador) | `batchId` | project, cycle | `{batchId, batchRef, sddIds[1..20], recordIds[]}` |
| `io.sandbox.delivery.sdd-ready.v1` | Coordenador | Entrega (inicia `sdd-delivery`) | `sddId` | project, cycle, sdd | `{sddId, recordId, sddRef, designRecordId, techRecordId}` |
| `io.sandbox.delivery.sdd-implemented.v1` | `sdd-delivery`, a cada tentativa | observação (spy) | `sddId` | project, cycle, sdd | `{sddId, attempt, changesetRef}` |
| `io.sandbox.delivery.sdd-validated.v1` | `sdd-delivery` (terminal) | Coordenador | `sddId` | project, cycle, sdd | `{sddId, attempts, outcome: validated\|accepted, reportRef}` |
| `io.sandbox.delivery.sdd-cancelled.v1` (**novo**, além da lista mínima) | `sdd-delivery` (terminal) | Coordenador | `sddId` | project, cycle, sdd | `{sddId, attempts, reason: technician\|timeout}` |
| `io.sandbox.delivery.sdd-dead-lettered.v1` | `sdd-delivery`, ao esgotar 5 tentativas | `sandbox-channels` (aviso ao Técnico); a decisão é o `review` do Stint | `sddId` | project, cycle, sdd | `{sddId, attempts, lastReportRef}` |
| `io.sandbox.delivery.cycle-closed.v1` | Coordenador | `sandbox-channels` (aviso); observação | `projectId` | project, cycle | `{outcome: complete\|partial\|stalled, validated, accepted, cancelled, pending[]}` |
| `io.sandbox.records.document-registered.v1` | o workflow que registrou, logo depois da `call` ao registro, pelo outbox dele | observação; S.12 (bases de conhecimento) | `recordId` | project, cycle | `{recordId, version, sha256, kind, sensitivity, ownerRole}` |
| `io.stintflow.review.*.v1` | engine (requested/reminder) e portal (completed) | portal, e o domínio que espera | `reviewId` | `stintrole`, `stintsensitivity` + **`sandboxdomain`** no completed (roteamento, 8g) | o contrato do 3a.4 |

- **`sdd-cancelled`:** foi acrescentado porque o coordenador precisa de um terminal para "cancelar" e para o `default` por prazo do Técnico. "Aceitar" vira `sdd-validated` com `outcome: accepted`.
- **`document-registered` no namespace `records`, emitido pelo chamador:**
  - o registro é uma tarefa remota (8c), e um worker não deve publicar fora do outbox;
  - o schema é do `sandbox-records`, mas quem emite é o workflow do domínio, logo depois da `call`;
  - o `source` diz quem registrou.
- **Relação com `io.stintflow.examples.meeting-recorded.v1`:**
  - o `data` do evento do sandbox **é o mesmo schema**: `meetingId`, `audioRef`, `language`, `durationSeconds`, `recordedAt`, `label`, com `additionalProperties: false`. Não acrescento campo: projeto, ciclo e estágio vão em extensões, o que o schema v1 permite e o achado do 3a.5 pede;
  - o tipo muda porque o namespace é de quem é dono do contrato: o `io.stintflow.examples.*` é do exemplo do Stint;
  - o `contracts/meeting-recorded.v1.schema.json` do sandbox **referencia** o do Stint no commit fixado (`$ref` ao arquivo copiado com o SHA no `$id`), e um teste confere que a cópia é byte a byte igual à do `500dfd1`;
  - assim o `TranscriptionHandler` do Stint lê o `audioRef` sem adaptação.

### 8c. OBRIGATÓRIO — Módulos e fronteiras
| Módulo | Papel | Processo e porta | Workers que roda | Engine |
|---|---|---|---|---|
| `sandbox-contracts` | **biblioteca, não domínio**: schemas (recursos), constantes de tipos e extensões, validador de schema | — | — | — |
| `sandbox-business` | Negócio | jar :8090 | transcrição (porta do Stint), decisão (LitJev ou falso), documento de desenho (LangChain4j ou falso) | sim: `business-*` |
| `sandbox-engineering` | Engenharia | jar :8091 | transcrição, decisão, SDDs/dúvidas (LangChain4j ou falso) | sim: `engineering-*` |
| `sandbox-delivery` | Entrega | jar :8092 | implementador e validador (LangChain4j ou falso) | sim: `delivery-*` (coordenador + `sdd-delivery`) |
| `sandbox-privacy` | filtro de PII | jar :8093, **só worker** | `redact` (redator de referência do Stint no S.5; camadas no S.6) | não |
| `sandbox-records` | registro | jar :8094, **só worker** | `register-document` (S.5: guarda em `sandbox-records-docs` e devolve id, versão e hash; o resto é do S.7) | não |
| `sandbox-channels` | e-mail (MailHog), Confluence falso, portal, gravador | jar :8095 | `send-email`, `publish-page` (`stint-worker-publishing` com `FakeConfluenceServer` embutido), portal HTTP, gravador `POST /meetings`, consumidor de `channels-domain-events` | não (consome eventos direto, sem workflow) |
| `sandbox-target` | projeto-alvo + runner de execução isolada | runner jar :8096, **só worker** | `run-target-tests` (8f) | não |

- **Regra de fronteira:**
  - Nenhum módulo `sandbox-*` depende de outro `sandbox-*`, exceto de `sandbox-contracts`.
  - Duas guardas:
    1. o `maven-enforcer` `bannedDependencies` em cada pom de domínio, com `io.stintflow.sandbox:*` banido e `sandbox-contracts` liberado;
    2. o `BoundaryTest` no `sandbox-scenarios`, que lê os poms do reator, falha com o nome da aresta proibida e tem um caso negativo com um pom plantado em `src/test/resources` (CA1).
  - O `sandbox-contracts` não tem lógica de domínio: um teste ArchUnit nele exige só records, constantes e o validador.
- **Tarefas compartilhadas sem endereço de resposta** (achado 8l). Como o `SqsTaskTransport` tem uma fila de invocação por engine e o `TaskInvocation` não diz para onde responder:
  - **filas por chamador**, `<serviço>-tasks-<domínio>`, por exemplo `privacy-tasks-business`, `privacy-tasks-engineering`, `privacy-tasks-delivery`;
  - o serviço roda um `SqsWorkerBinding` por fila, cada um com o `resultQueueUrl` = `<domínio>-results`;
  - nos domínios, um **`RoutingTaskTransport` do sandbox** implementa a porta pública `TaskTransport`: escolhe a fila pelo `routingKey` (`redact` → `privacy-tasks-<d>`, `register-document` → `records-tasks-<d>`, `send-email`/`publish-page` → `channels-tasks-<d>`, `run-target-tests` → `target-tasks-delivery`; o resto → `<d>-tasks`);
  - a leitura dos resultados continua a do `SqsTaskTransport` (fila `<d>-results`).
  - Nada muda no Stint. Teste unitário do roteamento, e o esqueleto prova a fiação.
- **Onde roda cada worker:**
  - os de IA ficam no processo do domínio, porque o modelo é do domínio e a memória é por instância;
  - privacidade, registro, canais e alvo são processos próprios, porque lidam com conteúdo bruto, catálogo, mundo externo e Docker.
- **Do Stint (usado como biblioteca, sem cópia):**
  - engine, DSL, conectores AWS, `stint-worker-sdk` e `stint-aws-worker`;
  - `stint-worker-decision`, `stint-worker-langchain4j`, `stint-worker-transcription` e `stint-worker-publishing` (com `FakeConfluenceServer`);
  - o redator de referência;
  - o contrato de revisão (`ReviewContract`).
- **Do sandbox:** workflows YAML, handlers de domínio, `RoutingTaskTransport`, portal, gravador, runner, corpus, falsos de domínio e cenários.

### 8d. OBRIGATÓRIO — Corpus do pipeline
- **Formato v2 compatível com o v1** do 3a.6: os campos novos são opcionais, e um leitor v1 ignora o que não conhece. Fica em `corpus/` na raiz do sandbox:

```yaml
id: p1-c1-business          # meetingId
project: p1                 # novo: projectId
cycle: 1                    # novo
stage: business             # novo: business | technical
language: en
label: restricted
speakers: [ { id: s1, voice: af_heart }, { id: s2, voice: am_adam } ]
script:
  - { speaker: s1, text: "..." }
expected:                   # gabarito de cada decisão do Jev
  meetingType: business-design      # business-design | technical-refinement | out-of-scope
  channel: confluence               # email | confluence | meeting (só stage business)
  outcome: sdds                     # sdds | questions (só stage technical)
  blockingQuestion: false           # noul "há dúvida bloqueante?"
  priority: 0.7                     # score, com tolerância ±0.2
  sddCount: 2                       # SDDs esperados (outcome sdds)
  sdds: [ { id: sdd-1, covers: ["..."] } ]   # itens que cada SDD precisa cobrir (cobertura, não igualdade: achado 3a.6)
  questions: [ "..." ]              # outcome questions
  review: false                     # true: decisão incerta por desenho
pii:
  - marker: SYNTH-PII-0201
    kind: email                     # email | phone | name | id-number
    written: "SYNTH-PII-0201@example.com"
    spoken: [ "synth p i i zero two zero one at example dot com", "sinthe-p-i-i-0-2-0-1 at example. calm." ]
```

- **Conteúdo** (inglês, sintético):

  | Grupo | Roteiros |
  |---|---|
  | Desenho de negócio | 3, um por canal (e-mail, Confluence, nova reunião) |
  | Refinamento técnico | 3: 2 SDDs, 3 SDDs, 1 SDD |
  | Ambíguos | 2: negócio que parece técnico, e técnico sem decisão |
  | Fora do escopo | 2: social, e outro projeto |
  | Ciclo com dúvidas | projeto `p2`: ciclo 1 (negócio → técnico com dúvida bloqueante → `questions-sent`), ciclo 2 (negócio respondendo → técnico → 2 SDDs) |

  Total: 14 reuniões. O esqueleto (CA2) usa `p1` (2 SDDs) e `p2` (dúvidas e reinício).
- **PII:** cada reunião tem 1 a 3 marcadores (`SYNTH-PII-02nn`). Cada um tem:
  - a forma escrita, a que o redator de referência reconhece;
  - **formas faladas** em dois grupos:
    1. as geradas por regra: dígitos por extenso, "at" e "dot", soletrado;
    2. as observadas no Whisper no CA7, acrescentadas depois da execução manual.
  - **`corpus/pii-forms.json`**, gerado do corpus, é a lista normalizada que o varredor usa: minúsculas, sem pontuação, dígitos juntos (`synthpii0201`, `0201`, os dígitos do telefone). O S.6 a consome.
- **Áudio:**
  - o `scripts/generate-audio.py` do 3a.6 é reaproveitado, copiado com a origem e o SHA no cabeçalho, porque o sandbox não depende de código de exemplo do Stint;
  - ele lê o v2, gera WAV 16 kHz mono com Kokoro (CPU) e grava o hash no manifesto;
  - o áudio fica fora do Git (`corpus/audio/`, no `.gitignore`), e o manifesto fica no Git.
- **`PipelineCorpusTest` (CA4):**
  - hash de cada roteiro contra o manifesto, e do áudio quando presente;
  - gabarito completo por estágio;
  - todo marcador listado e plantado;
  - toda forma falada com o marcador de origem;
  - os três tipos de reunião, os três canais e os dois resultados presentes;
  - `sddCount` igual ao tamanho de `sdds`.

### 8e. OBRIGATÓRIO — Pilha de modelos
- **CI, tudo falso e determinístico, guiado pelo gabarito:**
  - transcritor: `FakeTranscriber` do Stint, que devolve o roteiro;
  - decisão: um adapter falso por decisão, que responde o `expected` e devolve `uncertain` quando `review: true`;
  - gerador: falsos por tarefa (documento, SDDs, implementação, validação) que montam a saída do gabarito. A implementação falsa devolve um changeset fixo que passa nos testes do alvo; o "falha até a tentativa k" é configurável para o teste de retry e DLQ.
- **Manual, só em loopback, conforme o `LOCAL-STACK.md` do 3a.6:**
  - Whisper: faster-whisper `distil-small.en` int8, CPU, :8001;
  - LitJev: Qwen3.5-2B fp16, GPU, :8000;
  - gerador: llama.cpp b11658 + Qwen3-4B-Instruct-2507 Q4_K_M, CPU, :8080.
  - Pelo `docs/connectors.md` do Stint, sem Docker se o Docker Desktop falhar.
- **Plano da GPU de 8 GB** (medido no 3a.6: desktop ~2,5 GB, LitJev ~4,9 GB, pico 7,85 GB):
  1. Ordem de carga: Whisper (CPU) → LitJev (GPU) → gerador (CPU).
  2. Só o LitJev na GPU; Whisper e gerador na CPU (6 threads cada, os dois nunca ativos ao mesmo tempo no roteiro sequencial).
  3. Pico de memória amostrado com `nvidia-smi` durante a execução (CA7).
- **Subagentes de código:**
  - **Risco:** o Qwen3-4B Q4 em CPU faz ~10,6 tokens/s e listou itens além do gabarito no 3a.6. Um changeset de ~600 tokens leva ~1 min por tentativa; 2 SDDs × até 5 tentativas × (implementar + validar) dão até ~20 min por ciclo. A qualidade de código de um 4B é incerta.
  - **Alternativa sem chave real, na ordem:**
    1. Qwen2.5-Coder-7B-Instruct Q4_K_M (Apache-2.0, ~4,7 GB) na GPU **no lugar do LitJev**, numa fase "Entrega" da execução manual: o LitJev é parado depois das decisões de Negócio e Engenharia, e o coder carrega. A troca é sequencial, sem disputa.
    2. O mesmo 7B em CPU (~5 tokens/s), mais lento e sem troca.
  - **Mitigação de formato:** o implementador devolve um **changeset** (lista de arquivos com conteúdo inteiro) em saída estruturada, não um diff unificado, que modelos pequenos erram com frequência (8f).
  - O CA7 do S.5 só exige uma geração. A medição dos subagentes de código fica para o S.10.

### 8f. OBRIGATÓRIO — Projeto-alvo e execução isolada
- **`sandbox-target`:**
  - **projeto-alvo:** um pacote Python pequeno, `target-project/`: uma biblioteca de "catálogo de livros" com ~5 funções e testes `unittest` só da biblioteca padrão, sem dependência externa. Os agentes o alteram para implementar SDDs do corpus ("adicionar busca por autor", "validar ISBN").
  - **runner:** worker Java da tarefa `run-target-tests`. Recebe `{sddId, attempt, changesetRef}` e devolve `{passed, failed, errors, reportRef, durationMs}`.
  - **Por que Python e não Java:**
    - a imagem `python:3.12-slim` fixada por digest tem ~50 MB e roda os testes **sem rede e sem baixar nada**;
    - um alvo Maven precisaria de `.m2` pré-carregado na imagem (centenas de MB) num disco C: com 18 GB livres;
    - o pipeline valida o fluxo, não a linguagem.
    - Alternativa, se o Kayan preferir: Java com `javac` e um runner de testes mínimo, sem Maven.
- **Como um patch é aplicado e testado:**
  1. O runner valida o changeset: caminhos relativos, só sob `catalog/` e `tests/`, sem `..`, sem links, até 50 arquivos e 200 KB.
  2. Monta o workspace numa pasta temporária no E: (cópia do `target-project` + changeset).
  3. `docker create --network none --read-only --tmpfs /tmp:size=64m --cap-drop ALL --security-opt no-new-privileges --pids-limit 128 --memory 512m --cpus 1 --user 65534:65534 python:3.12-slim@sha256:<digest> python -m unittest discover -s /w/tests -v`.
     - **Sem variáveis de ambiente do host**: nenhum `-e`, nenhum segredo.
  4. `docker cp workspace → container:/w`, já que bind mount de `C:` trava no Hyper-V (S.4).
  5. `docker start -a` com tempo limite de 120 s. Estourou: `docker kill`, com resultado `timeout`.
  6. Lê o relatório da saída, guarda em `sandbox-target-runs/<sddId>/<attempt>.txt` e faz `docker rm -f`.
  - O argv é fixo, montado no código, **sem shell** (`ProcessBuilder` com lista). O modelo só controla o conteúdo dos arquivos, nunca flags.
- **Quem lança o container, e o risco:**
  - Só o processo `sandbox-target` fala com o Docker: o pipe do Docker Desktop no Windows, `/var/run/docker.sock` no Linux.
  - Acesso ao socket do Docker equivale a administrador do host. Um bug no runner, ou um changeset que escapasse da validação, chegaria lá.
  - **Mitigações:**
    - processo separado e mínimo, com o argv fixo acima;
    - imagem allowlisted por digest;
    - nenhum outro worker tem acesso;
    - o container nunca recebe o socket;
    - no CI, o runner é **falso**: aplica a regra de validação, não chama o Docker e devolve o resultado do gabarito.
  - **Evolução** (registrada, fora do S.5): Docker rootless ou gVisor numa VM do WSL dedicada, ou um runner remoto.
- **`TargetIsolationIT`** (perfil `-Ptarget`, fora do CI comum), na mesma imagem e com as mesmas flags:
  - `python -c "urllib.request.urlopen('http://example.com', timeout=5)"` falha;
  - `http://host.docker.internal:4566` e `http://floci:4566` falham;
  - a única interface é `lo`;
  - uma escrita fora de `/tmp` falha.

### 8g. Portal e papéis
- **Papéis e credenciais só de teste:**
  - `business-rep`, `tech-rep` e `technician`, mais `scripted` para o revisor automático do esqueleto;
  - HTTP Basic no `sandbox-channels`, com usuários e senhas fixos `test-<papel>`/`test-only` no `application.properties`, marcados como credencial de teste;
  - o papel vem **da credencial**, nunca de um cabeçalho do cliente.
- **Fluxo:**
  1. O portal consome `io.stintflow.review.requested.v1` e `reminder` (regra `review-requests-to-channels`) e mantém as revisões abertas por papel.
  2. Quando há escalonamento, o `requested` do novo papel abre a revisão para ele.
  3. `GET /reviews` lista as do papel autenticado.
  4. `POST /reviews/{reviewId}` com `{value | verdict, items, noteRef}`:
     - recusa com 403 se a revisão não estiver aberta **para o papel autenticado**, o que bloqueia a resposta adiantada do papel de escalonamento (achado 3a.4);
     - recusa com 422 o que estiver fora do `ReviewContract`;
     - senão publica `io.stintflow.review.completed.v1` com `source` = `https://portal.sandbox.example` (o valor de `x-stint-policy.review.sources` em todos os workflows), `stintrole` = papel e `sandboxdomain` = domínio de quem pediu (lido do `source` do `requested`).
- **Roteamento do `completed`:** regras por domínio com padrão no `detail.sandboxdomain` (`review-completed-to-<d>`).
  - **Risco:** o floci pode não suportar padrão em campo de `detail`.
  - **Contorno se não suportar:** enviar o `completed` às três filas, deixar o engine descartar (sem espera, conteúdo livre, inbox por 5 min) e registrar o achado do floci.
- **`PortalRolesIT` (CA3).** Sobre uma revisão do `business-rep` aberta pelo esqueleto:
  1. O `technician` responde pelo portal: 403, nada publicado.
  2. Uma resposta com `stintrole: technician`, publicada **direto no bus** (forjada, contornando o portal), não retoma; a auditoria `review` registra `wrong-role`.
  3. Outra com `source` desconhecido: não retoma; `untrusted-source`.
  4. Outra fora do contrato (texto livre): não retoma; `out-of-contract`.
  5. **Achado 3a.4.** Numa revisão com escalonamento, a resposta do papel de escalonamento antes do escalonamento:
     - pelo portal: 403;
     - forjada no bus, dentro da janela: o teste **documenta** que o Stint aceita depois do escalonamento. Fica marcado como achado conhecido, sem asserção que esconda o bug, e o relatório diz isso.
  6. A resposta certa, pelo portal com o papel certo, retoma.

### 8h. Esqueleto ponta a ponta
- **Processos**, todos jars lançados pelo `sandbox-scenarios` como no S.4: business, engineering, delivery, privacy, records, channels e target (runner falso).
- **Modelos falsos** guiados pelo corpus. Um revisor roteirizado no `sandbox-scenarios` aprova cada revisão pelo portal, com o papel certo, consultando `GET /reviews`.
- **Ciclo `p1`:**
  1. `POST /meetings` (gravador) com `p1-c1-business`: o áudio falso vai ao bucket, e sai o `meeting-recorded` com `stage=business`.
  2. Negócio: transcrever → `redact` (privacy) → decisão de tipo → documento (falso) → registrar (records) → `document-registered` → decisão de canal → publicar (channels, Confluence falso) → `call: review` do `business-rep` → `design-published`.
  3. Engenharia: `refinement-requested` → o gravador falso publica `p1-c1-technical` → transcrever → `redact` → decisão SDDs/dúvidas → 2 SDDs (falso) → registrar → `call: review` do `tech-rep` (itens: os 2 SDDs) → `sdd-batch-approved`.
  4. Entrega: coordenador → 2 `sdd-ready` → 2 `sdd-delivery`:
     - `sdd-1` passa na 1ª tentativa;
     - `sdd-2` falha 1× e passa na 2ª (retry);
     - 2 `sdd-validated` → `cycle-closed` (`complete`).
- **Ciclo `p2`, dúvidas e reinício:** ciclo 1 termina em `questions-sent` e e-mail no MailHog; o gravador publica o ciclo 2 com o mesmo `chainid` e `cycle=2`; o ciclo 2 segue até `cycle-closed`.
- **Asserções:**
  - **Cadeia:**
    - todos os eventos de `p1` na spy com o mesmo `chainid`;
    - no Jaeger, os spans de business, engineering, delivery, privacy, records e channels com esse `stint.chainId`, ligados por `FOLLOWS_FROM` (como o RF5 do S.4);
    - `p2` com uma cadeia só nos dois ciclos.
  - **Sem conteúdo:** todo evento da spy é validado contra o schema, e nenhum marcador nem forma falada aparece na spy, nos spans nem nos logs.
  - **Contagens:**
    - um `design-published`, um `sdd-batch-approved`, 2 `sdd-ready`, 3 `sdd-implemented`, 2 `sdd-validated` e 1 `cycle-closed` em `p1`;
    - um e-mail de dúvidas em `p2`;
    - uma página no Confluence falso por documento.
  - **Sobras:** zero, com as consultas do 8e do S.4 estendidas aos domínios novos.

### 8i. WorkerPool e binding nos domínios da Fase 2
- **Engines:** `OrdersEngine.startWorker` e `BillingEngine.startWorker` trocam o laço serial por `SqsWorkerBinding.builder()`:
  - `invokeQueueUrl` = `<d>-tasks`, `resultQueueUrl` = `<d>-results`;
  - `runtime` = o `WorkerRuntime` atual, mais `pool(WorkerPool)`;
  - `concurrency` = `<d>.worker.concurrency`, padrão 8;
  - `visibilityTimeout` = 30 s, com extensão.
- **Flags:** `<d>.worker.enabled` continua.
- **Caos do S.4:** o `billing.chaos.halt-in-task` continua valendo, porque o halt é dentro do handler.
- **Carga:** `gate.sh --only LoadIT` (N=50, os mesmos 5 `fail503-` e 5 `slow-`), 3 vezes, com p50 e p95 contra 28,3 s e 31,1 s.
- **Expectativa registrada antes:**
  - o `slow-` (5 s) e o retry com backoff (1 s + 2 s) entram no p95;
  - o engine ainda consome em série (`SqsPollLoop`, achado 7 do gate da 3a);
  - por isso o ganho pode ser pequeno. O CA6 só registra; não há meta.
- **Regressão:** `RegressionIT`, `ChaosIT` e `LoadIT` verdes no binding.

### 8j. Infra
- **`bootstrap.sh`, mesmo estilo idempotente:**
  - `DOMAINS=(orders billing business engineering delivery)`: tabelas, filas e grupos do Scheduler por domínio;
  - filas por chamador `privacy|records|channels-tasks-<d>` e `target-tasks-delivery`, com DLQ;
  - `channels-domain-events`;
  - buckets (o nome diz a retenção; lifecycle declarado como no 3a.5, efeito real só na AWS):
    - `sandbox-meetings-raw` (áudio e transcrição bruta, restrito, 7 dias);
    - `sandbox-documents` (documentos redigidos);
    - `sandbox-records-docs`;
    - `sandbox-target-runs`;
  - tabelas `records-catalog`, `<d>-corrections` (`CorrectionLog` do 3a.4) e `<d>-audit` (`DynamoDbAuditLog` do 3a.5);
  - regras:
    - uma por tipo, de 8b, para a fila do consumidor;
    - `review-requests-to-channels` (por prefixo de tipo);
    - `review-completed-to-<d>`;
    - a `spy-all` continua.
- **`reset.sh`:** estende as listas e mantém a recusa a endpoint que não seja `localhost:4566`.
- **`docker-compose.yml`:** **sem serviços novos**. Floci, MailHog e Jaeger bastam. O Confluence falso roda dentro do `sandbox-channels`, e Whisper, LitJev e gerador nunca entram no compose: são da execução manual, no `LOCAL-STACK.md` do sandbox.
- **README:**
  - aviso do disco C: no topo da seção de pré-requisitos: com menos de ~20 GB livres, a VM do Docker Desktop trava no boot (15/10/2026). Liberar espaço ou mover o disco da VM para o E:;
  - caches de modelos (`HF_HOME`, `UV_CACHE_DIR`) sempre no E:.

### 8k. Branch do sandbox (escolha do Kayan)
A `phase-2-validation` (`ba14068`) não foi publicada, e o `main` do sandbox está em `0708a2d`.

| Opção | Como | Prós | Contras |
|---|---|---|---|
| **(1) Empilhar (recomendada)** | Criar `phase-3a-validation` a partir de `ba14068`. Mais tarde, com seu pedido: push da `phase-2-validation` + PR da Fase 2, e o PR da 3a com base nela, rebaseado para o `main` só depois do merge da Fase 2 | Começa já; o S.5 precisa da Fase 2 (Orders e Billing para o RF9, o `sandbox-scenarios`, o `gate.sh`) | PR da 3a empilhado; se a Fase 2 mudar na revisão, o empilhado acompanha |
| (2) Esperar o merge da Fase 2 | Você publica e mergeia a `phase-2-validation`; só então crio a `phase-3a-validation` a partir do `main` | Histórico linear, PR da 3a contra o `main` | O S.5 fica parado até o merge |

Nada é publicado sem seu pedido. Na opção 1, eu só crio a branch local.

### 8l. Achados do Stint (para SDD próprio; nada alterado daqui)
1. **Sem paralelismo dinâmico** (`for` concorrente com junção). Proposta: "SDD 3a.8 — `for` concorrente com junção" (8a, B). Contornado por (A).
2. **Sem endereço de resposta no transporte de tasks.**
   - O `SqsTaskTransport` tem uma fila de invocação por engine, e o `TaskInvocation` não leva para onde responder. Uma tarefa remota compartilhada entre engines precisa de filas por chamador e de um transporte de roteamento do lado do consumidor.
   - **Proposta de SDD (3a.9):**
     - extensão `stintreplyto` (fila de resultado) no CloudEvent da invocação, aditiva;
     - o `SqsWorkerBinding` responde a ela quando presente;
     - um `RoutingTaskTransport` no `stint-aws`, por `routingKey`.
3. **Instância sem cancelamento externo.** Um coordenador que desiste (`stalled`) não pode cancelar as instâncias filhas. Proposta: `WorkflowEngine.cancel(instanceId, reason)` e um evento padrão de cancelamento, a avaliar com o 3a.8.
4. **Re-espera na chave consumida no mesmo save** (achado na implementação, em 18/10/2026).
   - Um `listen` que, na mesma ativação, volta a esperar na chave do evento que acabou de consumir falha sempre com `CONFLICT`, e a instância falha depois de 5 tentativas.
   - Acontece no `InMemoryStateStore`, que confere as esperas novas antes de remover as consumidas, e no `DynamoDbStateStore`, onde o Delete e o Put do mesmo item caem na mesma transação.
   - **Reprodução:** `DeliveryWorkflowsTest` com o `delivery-cycle` de `96fe848^` (laço sobre o mesmo `listen`) e 2 SDDs.
   - **Proposta (SDD 3a.10):** o save trata "consumir e reabrir a mesma chave" como uma atualização da espera; por exemplo, uma geração no item da espera. Com isso, um coordenador espera N eventos em ordem de chegada num `listen` só.
5. **Os achados do gate da 3a continuam abertos:** listen por `.data` restrito (evitado aqui por extensões), PII falada, resposta adiantada do escalonamento (bloqueada no portal, documentada no CA3) e `SqsPollLoop` serial (observado no CA6).

## 9. Registro de validação

**Ambiente.** Windows 11, Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2; Docker Desktop (Hyper-V, 10 GB) com floci, MailHog e Jaeger do `docker-compose.yml`; Stint `500dfd1` instalado de um clone limpo sob a versão `0.1.0-3a-500dfd1` (`infra/install-stint.sh`).

### 9.1 Commits (`phase-3a-validation`, só local; ainda sem push)
| Commit | O que faz |
|---|---|
| `a17d6d9` | spec aprovada (8k opção 1, 8a opção A) |
| `56a21e1` | Stint fixado; contrato v1, `sandbox-contracts`, corpus v2 e o script de áudio |
| `21e699b` | `sandbox-privacy`, `sandbox-records` e `sandbox-target` (container sem rede) |
| `5b69fcd` | `sandbox-channels` (gravador, portal com papéis, Confluence falso, e-mail) e o Negócio |
| `96fe848`, `3eebf83`, `cd2059e` | Engenharia e Entrega: coordenador por ciclo e instância por SDD; o ajuste da espera (9.3) |
| `a061e82`, `30a0682` | infra no `bootstrap.sh` e `reset.sh`; reset e bootstrap em paralelo |
| `27a1719` | Orders e Billing no `SqsWorkerBinding` com `WorkerPool` (RF9) |
| `fddf7af`, `d979182` | esqueleto ponta a ponta e papéis do portal, com as evidências CA2 e CA3 |
| `0aeee1c` | Stint instalado sob versão própria; evidência CA6 |
| `68109c9`, `63469b3`, `b17ecca`, `4feeeb1` | áudio com hashes, pilha local, execução manual (CA7) |

### 9.2 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** Build completo com os testes do S.1 a S.4 (build e testes unitários verdes; a regressão do floci achou uma falha minha, em correção: ver 9.7). O `BoundaryTest` falha se um domínio depender de outro, e um pom plantado prova que ele vê a aresta; o enforcer de cada domínio proíbe os outros módulos | `BoundaryTest` (3), `ContractsArchitectureTest`; `gate-out/…/build.log` |
| CA2 | **Verde, no floci.** Sete processos. O projeto `p1` (2 SDDs, um refaz a 2ª tentativa) e o `p2` (dúvidas, `questions-sent`, e-mail, ciclo 2) chegam ao `cycle-closed`. Uma cadeia por projeto no barramento (`chainid` do projeto, também nos ciclos seguintes) e no Jaeger, com spans dos sete serviços e links `FOLLOWS_FROM` entre Engenharia → Negócio e Entrega → Engenharia. Todo evento respeita o schema v1, nenhum marcador aparece no barramento, nos spans nem nos logs, e nada sobra (esperas, outbox, DLQs, instâncias não terminais) | `PipelineSkeletonIT`; `S.5-CA2.txt` |
| CA3 | **Verde.** Pelo portal: papel errado 403, texto livre 422. Forjadas no barramento: papel errado, `source` desconhecido e texto livre não retomam, e a auditoria do Stint registra `wrong-role`, `untrusted-source` e `out-of-contract`. O papel certo, pelo portal, retoma. A resposta adiantada do papel de escalonamento é recusada pelo portal; no barramento é o achado do 3a.4 (9.4) | `PortalRolesIT`, `PortalTest`; `S.5-CA3.txt` |
| CA4 | **Verde.** Gabarito completo por etapa (14 reuniões: 3 de negócio, uma por canal; 3 técnicas com 2, 3 e 1 SDD; 2 ambíguas; 2 fora do escopo; o ciclo de dúvidas). Todo marcador está plantado e listado, com formas escritas e faladas; `corpus/pii-forms.json` em dia com o corpus. O áudio dos 14 roteiros é reproduzível: `generate-audio.py --verify` regenera e dá o mesmo hash | `PipelineCorpusTest` (8); `--verify`, 14 vezes "same hash" |
| CA5 | **Verde.** Os testes só resolvem `localhost` (`infra/test-hosts`, no surefire e no failsafe de todos os módulos) e nenhuma chave real está configurada. Nenhum teste chama provedor real | `-Djdk.net.hosts.file`; os falsos do CI |
| CA6 | **Registrado, sem meta.** Com o binding, a carga do S.4 (N=50, com 5 `fail503-` e 5 `slow-`) deu **p50 4,3 s e p95 7,6 s**, contra 28,3 s e 31,1 s com o laço serial. 50 faturas, 50 e-mails, 50 cadeias, nenhuma sobra | `S.5-CA6.txt` |
| CA7 | **Verde, na pilha local.** `p1-c1-business` em áudio do Kokoro: o Whisper (CPU) transcreveu, o LitJev (GPU) decidiu o tipo (0,997), a prioridade e o canal (0,90, Confluence, como o gabarito), o llama.cpp (CPU) escreveu o documento, o portal aprovou. **24 s** da gravação ao `design-published`. Memória da GPU: **7 844 MiB de 8 192** (o desktop e o LitJev; Whisper e gerador na CPU, como o plano 8e) | `LocalStackIT`; `S.5-CA7-manual.txt` |
| Extra | **Verde.** Um container do alvo, com as flags do runner, não alcança a internet, o DNS, o `floci`, o `host.docker.internal` nem o gateway da ponte; só tem a interface `lo`; roda com o uid 65534, sem variável do host; não escreve fora do `/tmp`; um laço infinito é morto no limite de tempo. Os changesets do implementador falso passam e falham de verdade no container, como no runner falso | `TargetIsolationIT` (`-Ptarget`) |
| Extra | **Verde.** Eventos do esqueleto só com ids e ponteiros (schema `additionalProperties: false`), conferidos na `sandbox-spy` | `PipelineSkeletonIT` |

### 9.3 Desvios e decisões da implementação (para sua revisão)
- **8a, a espera do coordenador mudou duas vezes.**
  1. A espera em ordem de chegada, num `listen` em laço, falha no Stint `500dfd1`: ele não reabre a espera na chave que o mesmo save consome (achado 8l-5).
  2. Sua escolha de esperar cada SDD pelo id dependia de uma janela de inbox de 96 h, mas o Stint limita a janela a **15 min** (`PT15M`).
  3. Você escolheu a **consulta de estado**: o coordenador chama a tarefa `check-sdds`, que lê o estado das instâncias dos SDDs na tabela do Delivery, e dorme num `listen` de `PT5M` que um fim de SDD acorda. A segurança contra um SDD que nunca termina: 1 008 esperas ociosas (84 h) fecham o ciclo como `stalled`.
  4. Um fim que chega com o coordenador consultando fica no inbox até expirar (15 min), e os testes aceitam isso.
- **Roteamento das tarefas compartilhadas.** `RoutingTaskTransport` em cada domínio e uma fila por chamador (`privacy|records|channels-tasks-<domínio>`, `target-tasks-delivery`); o serviço responde na `<domínio>-results`. Nada mudou no Stint. A classe foi **copiada** em cada domínio: nenhum domínio depende de outro.
- **Etiqueta de domínio nas revisões.** O `DomainTagPublisher` acrescenta `sandboxdomain` aos eventos `io.stintflow.review.*` do engine, para o portal devolver a resposta ao domínio que perguntou. O floci aceita padrão em `detail` (testado), então não precisou do contorno.
- **O portal aceita ids com barras.** O id de revisão do Stint tem a forma `evt-…:/do/13/approve:1`; o caminho do `POST /reviews/{id}` casa com `.+`.
- **`try`/`retry` por tentativa de SDD.** O `try` do Stint aceita uma chamada só como corpo, e uma tentativa tem três (implementar, testar, validar). Virou um laço explícito com contador, de 5 tentativas; o SDD sai sempre em `sdd-validated` ou `sdd-cancelled`.
- **Prioridade (`score`) no Jev.** O adapter exige níveis de pontuação configurados (`scoreLevels`); o Negócio local declara três. O valor do score vem como número, não como o nível.
- **Dependências do `sandbox-scenarios`.** O módulo de cenários passou a depender do `sandbox-contracts` (schemas e leitor do corpus), a única exceção à regra de que ele não depende de módulo do sandbox. O enforcer dele a permite só para esse módulo.
- **Stint instalado sob versão própria.** Durante o trabalho outro agente mexeu no Stint (SDD 3a.10, 3b) e um `mvn install` dele no `~/.m2` contaminou o jar do `500dfd1`: o Quarkus não lia o índice do `stint-aws`. O `infra/install-stint.sh` instala o `500dfd1` limpo como `0.1.0-3a-500dfd1`, e o `stint.version` aponta para ele, então outra fase não muda o que o sandbox roda.
- **`reset.sh` e `bootstrap.sh` em paralelo.** Cada chamada do AWS CLI custa cerca de 1 s no Windows; com o dobro de recursos, o reset passou do limite de 5 min do `Scenario.reset()`. Agora leva 2 min 40 s.
- **Os buckets não são esvaziados pelo reset** (decisão do S.4). A evidência do CA7 filtra o transcrito da execução pela hora de gravação.
- **Docker Desktop** estava parado ao retomar o trabalho; foi iniciado e subiu normalmente com o C: a 19 GB livres.

### 9.4 Achados do Stint (fora do escopo, nada alterado daqui)
| # | Achado | Proposta |
|---|---|---|
| 8l-1 | Sem paralelismo dinâmico (`for` concorrente com junção) | SDD 3a.8 (8a, B); contornado por (A) |
| 8l-2 | O transporte de tasks não leva endereço de resposta | SDD 3a.9: extensão `stintreplyto` na invocação; `RoutingTaskTransport` no `stint-aws` |
| 8l-3 | Instância sem cancelamento externo (o `stalled` não cancela as filhas) | `WorkflowEngine.cancel(instanceId, reason)` e evento padrão |
| 8l-5 | Re-esperar na chave consumida no mesmo save falha sempre com `CONFLICT` (in-memory e DynamoDB) | **SDD 3a.10 já existe no repositório do Stint** (`394da56`) |
| 8l-6 | A janela de eventos adiantados é limitada a 15 min (`PT15M`) | Permitir janela por definição ou por espera, para esperas longas por eventos fora de ordem |
| 8l-7 | A resposta de uma revisão fica no inbox para as fases seguintes (lembrete e escalonamento) até o TTL | Já era o achado do inbox no 3a.4/3a.5 |
| 3a.4 | A resposta adiantada do papel de escalonamento, publicada no barramento, é aceita dentro da janela do inbox | O portal a recusa (403); o teste não finge que o Stint a recusa |
| 3a.6 | **PII falada escapa do redator de referência.** Medido no CA7: o `redact` devolveu `counts: {}` e o e-mail ditado virou só "at." na transcrição; o nome virou `synth PI 2011 Rivera`, que o redator não reconhece | Camada de forma falada no S.6. A lista do scanner (`pii-forms.json`) já traz essa forma |
| CA7 | O `score` do Jev não declara níveis por padrão | Documentar no 3a.2b que `score` exige `scoreLevels` |
| CA7 | O LitJev devolve `inputTokens=0` e `outputTokens=0` | Já era o achado do 3a.2b |

### 9.5 O que ficou fora do que o SDD pedia
- **Subagente de código com modelo local (8e).** O CA7 exige uma geração local; ela foi a do documento de desenho. A medição do implementador com o Qwen2.5-Coder-7B é do S.10, e o risco continua registrado em 8e.
- **Build nativo** dos módulos novos: não pedido no S.5.
- **Execução do alvo no container pelo pipeline completo:** o esqueleto usa o runner falso (como o CI); o container real foi provado pelo `TargetIsolationIT`.
- **Push e PR:** a branch continua só local, à espera do seu pedido.

### 9.6 Pendências
- Push das branches `phase-2-validation` e `phase-3a-validation`, e os PRs (8k): com o seu pedido.
- Notion: a página do S.5 e o Log.

### 9.7 Pendente: regressão do S.2 (CA3)
`FlociBillingIT.ca3_a_transient_503_succeeds_on_the_third_attempt` falha desde `27a1719`: com o `SqsWorkerBinding`, o `shipResult` recebe só o resultado, e a linha de log da falha perdeu a rota e a tentativa ("Task null attempt null"). Correção prevista: logar a falha num decorador de handler (rota e `ctx.attempt()`), como o `ChaosTaskHandler`, no `BillingEngine` e no `OrdersEngine`; depois repetir `gate.sh --regression-only`. Status do SDD: **Em implementação** (não Implementado) até lá.
