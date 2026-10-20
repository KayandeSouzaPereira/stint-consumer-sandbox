# SDD S.7 — Registro de documentos do pipeline

| Campo | Valor |
|---|---|
| ID | S.7 |
| Status | **Em especificação** (aguarda a aprovação do Kayan) |
| Depende de | S.5 e S.6 (Implementados). Stint `500dfd1` |
| Bloqueia | S.8 a S.10 (os domínios registram o que produzem), S.11 (gate), 3b (ingestão) |
| Branch | `phase-3a-validation` (os PRs da fase são empilhados) |
| Commits analisados | sandbox `6eb7138` · Stint `500dfd1` |
| Data da análise | 20/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.7): 5 requisitos e 3 critérios, todos cobertos abaixo |

## 1. Análise

### O que o S.5 já entregou
`sandbox-records` hoje tem a tarefa `register-document`:
- lê o documento por ponteiro, calcula o SHA-256 e guarda uma cópia em `sandbox-records-docs` na chave `<recordId>/v<n>`;
- grava uma entrada em `records-catalog` (chave `recordId` + `version`) com `sha256`, `kind`, `ownerRole`, `sensitivity`, `storedRef`, `projectId` e `cycle`, por `putIfAbsent` condicional;
- o mesmo conteúdo de novo é a mesma versão; conteúdo novo é a versão seguinte; uma corrida perdida com conteúdo diferente vira 409 para nova tentativa;
- responde só ids, versão, hash e `recordRef`; o workflow chamador publica `document-registered` pelo outbox dele.

### O que falta para o rascunho do S.7
| Requisito do rascunho | Estado |
|---|---|
| id, tipo, versão, hash, sensibilidade, domínio dono | feito no S.5 (o dono é um papel, não um domínio) |
| reunião de origem, `chainid`, `causationid` | **falta** |
| aprovações | **falta** |
| ligação `supersedes` entre versões | **falta** (hoje a versão anterior é implícita pelo número) |
| versões imutáveis em S3 versionado | cópia por chave de versão existe; imutabilidade não é verificada nem forçada |
| evento `document-registered.v1` só com ponteiro | o schema está no catálogo de eventos do S.5, mas não há contrato JSON nem teste "sem conteúdo" |
| leitura só pelo domínio dono | **falta**: não existe rota de leitura; o `documentRef` original é lido por qualquer um |
| todos os tipos de documento do pipeline | só `design`, `questions`, `sdd-batch`, `sdd`, `report` |
| cada documento do ciclo no catálogo, com a cadeia certa | não provado |

### Conclusão
O S.7 completa o catálogo (campos, `supersedes`, aprovações, tipos), fecha o acesso (leitura só pelo dono), dá contrato e teste ao evento, e prova no esqueleto que cada documento do ciclo de referência aparece com a cadeia certa. Não muda o Stint.

## 2. Problema e objetivo
**Problema.** Sem uma cadeia no catálogo não há como ligar um documento à reunião que o originou, nem ao anterior que ele substitui. A ingestão da 3b e os ponteiros da 3c precisam dessa base, e hoje qualquer processo com o ponteiro lê o documento.

**Objetivo.** Um catálogo único, versionado e imutável de todo documento do pipeline, com cadeia e aprovações, lido só pelo dono, anunciado por um evento que carrega apenas ponteiro.

## 3. Requisitos
- **RF1 — Registro completo** (8a). `register-document` passa a gravar também `sourceMeetingId`, `chainId` e `causationId` (da `TaskContext`, não do input), `supersedes` (a versão anterior da mesma sequência) e a classe de retenção. Campos novos são aditivos: o S.5 continua passando.
- **RF2 — Versões imutáveis** (8b). Uma versão gravada nunca muda: a cópia no S3 é escrita uma vez, e o catálogo recusa sobrescrever (condição já existente). Conteúdo repetido da versão **mais recente** é a mesma versão; conteúdo igual a uma versão antiga, depois de uma intermediária (A, B, A), é uma **nova** versão (v3) que substitui a v2.
- **RF3 — Aprovações** (8c). A tarefa `approve-record` anexa a decisão de uma aprovação (`approver` papel, `decision` approved|rejected, `note`, `reviewId`) a uma versão existente, de forma idempotente por `reviewId`. As aprovações ficam em tabela própria; a versão do documento não é tocada.
- **RF4 — Tipos** (8d). `kind` aceita, além dos atuais: `transcript-redacted`, `email`, `page`, `patch`, `dlq-decision`. A ligação dos novos tipos aos workflows é de S.8–S.10.
- **RF5 — Leitura só pelo dono** (8e). A tarefa `read-record` devolve o conteúdo (pelo ponteiro, nunca embutido) somente ao domínio dono do papel registrado; outro domínio recebe 403. Nenhuma outra rota lê `sandbox-records-docs`.
- **RF6 — Evento** (8f). `document-registered.v1` ganha contrato JSON (`additionalProperties: false`) com `{recordId, version, sha256, kind, sensitivity, ownerRole, supersedes?}`; um teste prova que nenhum campo de conteúdo cabe nele e que o evento publicado pelos workflows o satisfaz.
- **RF7 — Idempotência** (8b). Reentrega da mesma tarefa não cria versão, não repete o `supersedes` e não duplica aprovação.
- **RF8 — Privacidade herdada do S.6.** O que o catálogo guarda não é texto bruto: o registro recusa `transcript-redacted` sem `sensitivity` e recusa um `documentRef` que aponte para um bucket de classe restrita (`sandbox-meetings-raw`). O varredor continua zero.

## 4. Fora de escopo
- Lógica de domínio que decide *quando* registrar cada tipo (S.8–S.10) e a ingestão (3b).
- Busca por conteúdo no catálogo.
- Remoção ou arquivamento de versões: só a retenção da classe, como no S.6.
- Mudar o Stint.

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | Uma entrada tem origem, cadeia, `supersedes` e retenção; a v2 aponta para a v1 e a v1 não muda | `RegisterDocumentHandlerTest` |
| CA2 | Reentrega e repetição do último conteúdo não criam versão; A, B, A cria a v3 que substitui a v2; duas corridas do mesmo conteúdo dão uma versão só | idem; `CatalogRaceTest` |
| CA3 | Aprovação anexada à versão certa, idempotente por `reviewId`, e a rejeição fica registrada sem tocar o documento | `ApproveRecordTest` |
| CA4 | O dono lê; outro domínio recebe 403; `sandbox-records-docs` não é lido por nenhuma outra rota | `ReadRecordTest`, varredura de rotas |
| CA5 | O evento satisfaz o contrato e não tem campo de conteúdo; um evento com conteúdo é recusado | `DocumentRegisteredContractTest` |
| CA6 | No esqueleto do S.5, cada documento do ciclo (desenho, perguntas, lote de SDDs, SDD, relatório) está no catálogo com o `chainId` e a reunião de origem certos | `PipelineSkeletonIT`, `S.7-CA6.txt` |
| CA7 | Registro recusa um `documentRef` de bucket restrito e um tipo ou nível desconhecidos | `RegisterDocumentHandlerTest` |
| CA8 | S.1–S.6 continuam verdes e o varredor continua zero | `gate.sh --regression-only` |

## 6. Plano de tarefas (etapa 2, após aprovação)
1. Entrada do catálogo com os campos novos; migração aditiva da tabela (`DynamoDbCatalog`).
2. `supersedes`, regra A-B-A e a corrida.
3. `records-approvals` (bootstrap e reset) e a tarefa `approve-record`.
4. `read-record` com a checagem do dono; rotas no `RoutingTaskTransport` dos três domínios.
5. Contrato JSON do evento e o teste de conteúdo.
6. Tipos novos e a recusa de bucket restrito.
7. Esqueleto: a verificação do catálogo (CA6); regressão (CA8).

## 7. Questões em aberto: propostas para aprovação

### 8a. Onde vêm `chainId`, `causationId` e a reunião de origem
**Proposta:** `chainId` e `causationId` vêm da `TaskContext` (a mesma cadeia do workflow que chama, o que o rastreio do S.5 já usa). A reunião de origem vem do input (`sourceMeetingId`, obrigatória para os tipos derivados de reunião). Assim um chamador não escolhe a própria cadeia.

### 8b. S3 versionado ou chave por versão
**Proposta:** manter a chave por versão (`<recordId>/v<n>`) como a imutabilidade do sandbox e escrevê-la uma vez; o `putIfAbsent` do catálogo é a guarda. Habilitar o versionamento do bucket só se o floci o suportar de fato (a conferir na implementação; se aceitar e não aplicar, como o SSE, vira ressalva como no S.6). Não depender dele para o critério.

### 8c. Aprovações: tabela própria
**Proposta:** `records-approvals` (chave `recordId#version` + `reviewId`), em vez de mutar o item do catálogo, para que a versão fique realmente imutável.

### 8d. Papel dono e domínio
**Proposta:** `business-rep` → Negócio, `tech-rep` → Engenharia, `technician` → Entrega. O domínio do chamador vem do `WorkflowRef` da `TaskContext`.

### 8e. Leitura: o que "só pelo dono" cobre
**Proposta:** vale para a tarefa `read-record`. O ponteiro original (`documentRef`) continua sendo um objeto do bucket do domínio que o produziu; o sandbox não tem controle de acesso por objeto no floci, então o limite é de rota e fica declarado (como o SSE no S.6).

### 8f. Quem emite `document-registered`
**Proposta:** continua o workflow do domínio, pelo outbox (decisão do S.5, 8b); o S.7 só dá o contrato e o teste.

### 8g. Achados esperados no Stint (a confirmar)
- Se a `TaskContext` não expuser o `causationId` do evento que iniciou a cadeia, vira achado, e o catálogo grava o `eventId` que houver.

## 8. Registro de validação
(preencher na etapa 2)
