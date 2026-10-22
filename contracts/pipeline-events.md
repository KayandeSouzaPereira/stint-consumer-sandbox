# Contrato de eventos v1 do pipeline de reuniões (SDD S.5, 8b)

Este documento é o acordo entre `sandbox-channels`, `sandbox-business`, `sandbox-engineering`, `sandbox-delivery`,
`sandbox-records` e quem observa. Os JSON Schemas estão em [`v1/`](v1), e o índice [`v1/envelopes.json`](v1/envelopes.json)
diz, por tipo, o `source`, o schema do `data` e as extensões obrigatórias. O `sandbox-contracts` empacota os dois e confere
cada evento (`PipelineContracts`). As regras gerais de [`events.md`](events.md) (CloudEvents 1.0 pelo `emit`, bus
`sandbox-domain`, `spy-all`, linhagem do Stint, at-least-once, `dataref`) valem aqui também.

## Regras
- **Tipos:** `io.sandbox.<dono>.<evento>.v1`; o dono do contrato dá o namespace.
- **Correlação só por extensões e `subject`, nunca por `data`** (achado do Stint 3a.5: um `listen` não correlaciona por
  `.data` de fato restrito):

  | Extensão | Valor |
  |---|---|
  | `sandboxproject` | `[a-z0-9-]{1,40}` |
  | `sandboxcycle` | `"1"`, `"2"`, …: string, porque o `correlate` compara JSON canônico |
  | `sandboxsdd` | id do SDD, nos eventos da Entrega |
  | `sandboxstage` | `business` ou `technical`, no `meeting-recorded` |
  | `sandboxdomain` | domínio de uma revisão: posto nos `io.stintflow.review.requested/reminder` pelo publisher do domínio e devolvido pelo portal no `completed` |

  O `subject` é o id natural do objeto do evento.
- **`data` só com ids, ponteiros (`s3://…`, `record://…`), enums e contagens.** Cada schema tem
  `additionalProperties: false` e nenhum campo de texto livre. Conteúdo de reunião, documento ou pessoa nunca viaja em
  evento.
- **Linhagem:** o gravador põe `chainid = "chn-" + UUIDv3("project|" + projectId)` em todo `meeting-recorded` do
  projeto. É **uma cadeia só** da primeira reunião ao ciclo fechado, e nos ciclos seguintes.
- **Idempotência:** todo evento emitido por workflow declara `id` determinístico (`<projectId>:<cycle>:<evento>[:<sddId>]`),
  e o gravador usa `rec-<meetingId>`.
- **Mudanças só aditivas.**

## Eventos
| Tipo | Publica | Consome (regra) | `subject` | Extensões | `data` |
|---|---|---|---|---|---|
| `io.sandbox.channels.meeting-recorded.v1` | `sandbox-channels` (gravador) | Negócio inicia `business-design` (`meeting-recorded-business`, `stage=business`); Engenharia retoma o `listen` (`meeting-recorded-technical`, `stage=technical`) | `meetingId` | project, cycle, stage | o schema do `io.stintflow.examples.meeting-recorded.v1` do Stint, sem mudança |
| `io.sandbox.business.design-published.v1` | Negócio, depois da aprovação do `business-rep` | Engenharia inicia `refinement` | `documentId` | project, cycle | `{documentId, recordId, version, designRef, channel}` |
| `io.sandbox.engineering.refinement-requested.v1` | Engenharia | `sandbox-channels` (convite; o gravador falso responde com a reunião técnica) | `documentId` | project, cycle | `{documentId, recordId}` |
| `io.sandbox.engineering.questions-sent.v1` | Engenharia (dúvida bloqueante) | `sandbox-channels` (e-mail ao Negócio; o gravador falso grava o ciclo `nextCycle`) | `recordId` | project, cycle | `{recordId, questionsRef, questionCount, nextCycle}` |
| `io.sandbox.engineering.sdd-batch-approved.v1` | Engenharia, depois da aprovação do `tech-rep` | Entrega inicia o coordenador `delivery-cycle` | `batchId` | project, cycle | `{batchId, batchRef, sddIds[1..20], recordIds[]}` |
| `io.sandbox.delivery.sdd-ready.v1` | coordenador | Entrega inicia `sdd-delivery` | `sddId` | project, cycle, sdd | `{sddId, recordId, sddRef, designRecordId, techRecordId}` |
| `io.sandbox.delivery.sdd-implemented.v1` | `sdd-delivery`, a cada tentativa | observação | `sddId` | project, cycle, sdd | `{sddId, attempt, changesetRef}` |
| `io.sandbox.delivery.sdd-validated.v1` | `sdd-delivery` (fim) | coordenador | `sddId` | project, cycle, sdd | `{sddId, attempts, outcome: validated\|accepted, reportRef}` |
| `io.sandbox.delivery.sdd-cancelled.v1` | `sdd-delivery` (fim) | coordenador | `sddId` | project, cycle, sdd | `{sddId, attempts, reason: technician\|timeout}` |
| `io.sandbox.delivery.sdd-dead-lettered.v1` | `sdd-delivery`, ao esgotar as tentativas | `sandbox-channels` (aviso ao Técnico; a decisão é o `review` do Stint) | `sddId` | project, cycle, sdd | `{sddId, attempts, lastReportRef}` |
| `io.sandbox.delivery.cycle-closed.v1` | coordenador | `sandbox-channels` (aviso) | `projectId` | project, cycle | `{outcome: complete\|partial\|stalled, validated, accepted, cancelled, pending[]}` |
| `io.sandbox.records.document-registered.v1` | o workflow que registrou, logo depois da `call` ao registro | observação; S.12 | `recordId` | project, cycle | `{recordId, version, sha256, kind, sensitivity, ownerRole}` |

**Revisão:** os eventos são os do Stint (SDD 3a.4).
- `io.stintflow.review.requested.v1` e `reminder.v1` saem do engine com `source: stint://engine/review`. O publisher do
  domínio acrescenta `sandboxdomain`, e a regra `review-requests-to-channels` leva os dois ao portal.
- O portal publica `io.stintflow.review.completed.v1` com `source: https://portal.sandbox.example`, que é o único em
  `x-stint-policy.review.sources` de todo workflow do pipeline, mais `stintrole` e `sandboxdomain`. As regras
  `review-completed-to-<domínio>` (padrão em `detail.sandboxdomain`) o levam ao domínio.

## Relação com `io.stintflow.examples.meeting-recorded.v1`
- O `data` é **o mesmo schema**: `v1/meeting-recorded.v1.schema.json` referencia a cópia
  `v1/stint-meeting-recorded.v1.schema.json` do Stint `500dfd1`, sem mudança, e um teste confere o hash.
- Projeto, ciclo e estágio vão em extensões, o que o schema permite e o achado do 3a.5 pede.
- Muda só o tipo: o namespace `io.stintflow.examples` é do exemplo do Stint.
- Assim o `TranscriptionHandler` do Stint lê o `audioRef` sem adaptação.

## Tarefas remotas compartilhadas
Uma invocação de task não tem endereço de resposta (achado 8l do S.5). Por isso cada serviço compartilhado tem uma fila
por chamador, e responde na fila de resultados de quem chamou:

| Rota | Serviço | Fila | Resposta |
|---|---|---|---|
| `redactor` | `sandbox-privacy` | `privacy-tasks-<domínio>` | `<domínio>-results` |
| `register-document` | `sandbox-records` | `records-tasks-<domínio>` | `<domínio>-results` |
| `send-email`, `publish-page` | `sandbox-channels` | `channels-tasks-<domínio>` | `<domínio>-results` |
| `run-target-tests` | `sandbox-target` | `target-tasks-delivery` | `delivery-results` |

Nos domínios, o `RoutingTaskTransport` escolhe a fila pela `routingKey`. O resto vai para `<domínio>-tasks`, servido
pelo próprio processo.
