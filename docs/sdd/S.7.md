# SDD S.7 — Registro de documentos do pipeline

| Campo | Valor |
|---|---|
| ID | S.7 |
| Status | **Implementado** (spec aprovada por Kayan em 20/10/2026, sem escolha explícita: valem as propostas 8a a 8f) |
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

**Ambiente.** O do S.5 e S.6 (Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2, Stint `500dfd1` sob `0.1.0-3a-500dfd1`, floci). A pilha de modelos não foi necessária: o registro não lê fala.

### 8.1 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** A entrada guarda reunião de origem, `chainId`, `causationId`, `supersedes` e a classe de retenção; a v2 aponta para a v1 e a v1 e a cópia dela não mudam | `RegisterDocumentHandlerTest` (6) |
| CA2 | **Verde.** Repetir o último conteúdo não cria versão; A, B, A cria a v3 que substitui a v2; duas corridas do mesmo conteúdo (as duas leem "sem versão" antes de qualquer escrita, travadas por uma barreira, sem sleep) dão uma versão só | `RegisterDocumentHandlerTest`, `RecordsAccessTest` |
| CA3 | **Verde.** A aprovação cai na versão certa, uma vez por `reviewId`; a rejeição fica registrada; o catálogo fica idêntico; a mesma revisão com outra decisão é 409 e uma versão inexistente é 404; a resposta não leva a nota | `RecordsAccessTest` |
| CA4 | **Verde.** O dono lê (resposta é só o ponteiro); Engenharia e Entrega recebem 403 sobre um registro do Negócio; registro inexistente é 404 | `RecordsAccessTest` |
| CA5 | **Verde.** O evento satisfaz o contrato (com `supersedes` opcional e os tipos novos) e os campos `text`, `content`, `body`, `document`, `transcript` e `note` são recusados | `RecordsAccessTest` |
| CA6 | **Verde, com a ressalva de 8.2.** No esqueleto do S.5 (288 s) o catálogo tem 10 versões de 10 documentos, os mesmos que o barramento anunciou; todos na cadeia do projeto (`chn-<uuid do projeto>`), todo desenho e toda pergunta com a reunião de origem, e toda entrada com a causa | `PipelineSkeletonIT`; `S.7-CA6.txt` |
| CA7 | **Verde.** O registro recusa um `documentRef` do bucket restrito `sandbox-meetings-raw`, um nível e um papel desconhecidos, e um documento de reunião sem `sourceMeetingId`; aceita os cinco tipos novos | `RegisterDocumentHandlerTest` |
| CA8 | **Verde.** `gate.sh --regression-only`: build completo, `FlociBillingIT` 5/5, `FlociOrdersIT` 6/6 e `RegressionIT` 3/3 (S.1–S.6), PASSED em 20/10/2026 | `gate.sh --regression-only` |

### 8.2 Ressalvas e desvios
- **O relatório de validação ainda não é registrado.** O esqueleto produz 10 documentos (desenho, perguntas, lote e SDDs); o `report` do `sdd-delivery` e os tipos novos (`transcript-redacted`, `email`, `page`, `patch`, `dlq-decision`) são aceitos e testados no registro, mas quem os registra são os workflows de S.8–S.10, como a spec diz. O CA6 cobre o que o ciclo de hoje produz.
- **Nenhum documento do esqueleto tem `supersedes`.** Cada ciclo registra ids diferentes (`p2-c1-design`, `p2-c2-design`), então a substituição é provada no registro (CA1 e CA2), não no ciclo. Quando S.8 refizer um desenho rejeitado sob o mesmo id, a cadeia de versões aparece.
- **Os workflows passaram a enviar `sourceMeetingId`** (Negócio: desenho; Engenharia: perguntas). É uma mudança aditiva na entrada de `register-document`; nenhuma asserção existente mudou.
- **A leitura só pelo dono é de rota (8e).** O floci não tem controle de acesso por objeto, e o `documentRef` original continua sendo um objeto do bucket de quem o produziu.
- **Versionamento do bucket (8b):** não habilitei. A imutabilidade é a da chave por versão e da condição do catálogo, como proposto; o critério não depende dele.
- **Quatro novas rotas nos três domínios:** `approve-record` e `read-record` entraram na tabela de roteamento dos três (`SHARED`); nenhum workflow as chama ainda (S.8–S.10).
- **A nota da aprovação** é guardada como o chamador a mandou, que já deve ter passado pelo filtro do S.6; o registro não filtra de novo.

### 8.3 Achados (nada alterado no Stint)
Nenhum novo. A dúvida de 8g não se confirmou: a `TaskContext` traz o `causationId` (a coluna `cause` de `S.7-CA6.txt` está preenchida em todas as entradas).

### 8.4 Pendências
- Push e atualização do PR #2, com o seu pedido.
- Notion: página do S.7 e Log.
