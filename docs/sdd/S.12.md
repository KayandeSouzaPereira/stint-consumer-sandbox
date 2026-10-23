# SDD S.12 — Bases de conhecimento de Negócio e Engenharia: ingestão, recuperação com origem, crescimento, exclusão e inspeção

| Campo | Valor |
|---|---|
| ID | S.12 |
| Status | **Em implementação** (spec aprovada por Kayan em 23/10/2026, com as recomendações 8a–8j) |
| Depende de | S.5 a S.11 (3a). Stint `05fb70e` (`phase-3b/knowledge-base`, SDDs 3b.1–3b.6; contém a 3a em `a52b20a`) |
| Bloqueia | S.13 (gate da 3b) |
| Branch | `phase-3b-validation`, criada de `phase-3a-validation` (o PR da 3a ainda não foi mergeado, então os PRs são empilhados) |
| Commits analisados | sandbox `ba21a08` · Stint `05fb70e` |
| Data da análise | 23/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.12): objetivo em um parágrafo, coberto abaixo |

## 1. Análise

### 1.1 O que o Stint 3b entrega e o S.12 consome
| Peça | Onde | Uso aqui |
|---|---|---|
| `KnowledgeStore` (retrieve, write, delete, changes, entities, `describe`, `reindex`, `verifyErased`) e `CompositeKnowledgeStore` em memória | `stint-knowledge` (3b.1, 3b.5) | A base de cada domínio |
| Esquema `meetings@1`, glossário, extração ancorada, `PassageSplitter`, `EntityResolver` | `stint-knowledge`, `stint-worker-langchain4j` (3b.2) | Extração do grafo a partir dos documentos |
| Rotas `knowledge-write`, `knowledge-retrieve`, `knowledge-erase`, `knowledge-verify`, `knowledge-reindex`, `resolve-person`, `forget-subject`, `area-key-destroy`; `ScopeAllowList`; `PiiGate` | `stint-worker-knowledge`, `stint-worker-redaction` (3b.3, 3b.5, 3b.5b) | Os workers de cada domínio |
| Recuperação híbrida (`mode` semantic/relational/auto, `hops` 0–3, orçamento, origem em tudo) e `KnowledgeContentRetriever` | `stint-knowledge`, `stint-worker-langchain4j` (3b.4) | O contexto dos agentes de Negócio e Engenharia |
| Inspetor somente leitura: linha do tempo, métricas, duplicatas, GraphML, REST e tela, `InspectorAccess` (padrão negar) | `stint-knowledge-inspector` (3b.6) | A visualização do crescimento |
| Crypto-shredding por área | 3b.5b | Fora do S.12 (8h) |

### 1.2 O que o sandbox já tem
- `document-registered` (S.7) carrega `recordId`, `version`, `sha256`, `kind`, `sensitivity`, `ownerRole` e `readers` (S.10, 8d). Não carrega o texto nem um ponteiro para ele: a cópia mora no catálogo do Records (`storedRef`) e a tarefa `read-record` a devolve por ponteiro. A ingestão por fato precisa dessa leitura (confirmar no RF0).
- Os tipos de documento (`kind`): design, questions, sdd-batch, sdd, report, transcript-redacted, email, page, patch, dlq-decision. O Negócio é dono de design e questions; a Engenharia, de sdd-batch e sdd; a Entrega, de report e patch.
- O corpus tem um projeto com dois ciclos (`p2-c1` e `p2-c2`), que serve para ver o grafo crescer de um ciclo para o seguinte.
- A guarda de PII do S.6 e o varredor continuam valendo; a base passa a ser mais um depósito a varrer.

### 1.3 Achados que moldam o desenho
1. **A base é só em memória.** O único adapter do `KnowledgeStore` na 3b é o `CompositeKnowledgeStore` em memória. Um processo morto perde a base. Para o sandbox isso muda o que "caos" significa na base (8e) e vira achado da fase (S12-1): a 3b não entrega um adapter persistente.
2. **O esquema `meetings@1` é de reuniões.** Os documentos do sandbox são designs, SDDs e relatórios. Se o esquema não representa decisões, requisitos e SDDs, o grafo sai pobre. Precisa de decisão (8a).
3. **A base é do domínio, não do pipeline.** Cada domínio é soberano (S.4): duas bases, nenhum domínio lê a do outro. A ponte entre domínios é da 3c (ponteiro e resolvedor), não daqui.
4. **A extração chama um modelo.** Nos testes o provedor é falso e determinístico; com modelo real, só na rodada manual (como no S.11).
5. **Quem lê o quê já está dito em `readers`.** A ingestão de cada base escolhe os documentos pelo `kind` e por `readers`, e o teto de sensibilidade vem da audiência (`Audience.ceiling`).

## 2. Problema e objetivo
Dar a Negócio e a Engenharia uma base de conhecimento cada, alimentada por `document-registered`, e provar, de fora e sob falha, que: agentes recuperam contexto com origem; o grafo cresce de ciclo em ciclo e isso aparece no inspetor; a exclusão de uma pessoa remove vetores, nós, entradas do log e blobs, sem tocar nas outras pessoas; e nenhum dado pessoal entra na base nem no inspetor.

## 3. Requisitos
**Funcionais**
- **RF0, subir o Stint** para `05fb70e` (instalação como no S.11: `infra/install-stint.sh`, versão `0.1.0-3b-05fb70e`). A regressão curta (unitários + `PipelineSkeletonIT`) roda antes de mudar qualquer coisa. Confirmar que `read-record` devolve o ponteiro do texto; se não devolver, parar e perguntar (a mudança seria no Records do sandbox, aditiva).
- **RF1, duas bases soberanas.** `business-kb` no `sandbox-business` e `engineering-kb` no `sandbox-engineering`, cada uma com o seu worker de conhecimento e a sua lista de escopos permitidos. Nenhum domínio atende o escopo do outro (403).
- **RF2, ingestão por fato.** Cada domínio tem um workflow `knowledge-ingest` iniciado por `document-registered` (`schedule.on`, nunca chamada direta), que filtra por `kind` e `readers` (8b), lê o documento por ponteiro, extrai, escreve e emite `document-indexed`. Tudo trafega por ponteiro.
- **RF3, idempotência.** A reentrega do fato, a retentativa do passo e o reprocessamento não duplicam trecho nem nó, e não reabrem o que foi apagado. Uma nova versão do documento (`supersedes`) substitui a anterior na base.
- **RF4, zero PII na base.** O lote passa pela guarda do passo de escrita (fecha ao achar padrão); o varredor passa a ler a base (vista do inspetor e dump), o lote em blob, o log de mudanças e os fatos novos.
- **RF5, recuperação com origem nos agentes.** O escritor do design (Negócio) e o do refinamento/SDD (Engenharia) usam o `KnowledgeContentRetriever` com o teto da audiência. O documento produzido termina com uma seção, escrita pelo handler, com os documentos que entraram no contexto. No segundo ciclo de `p2`, o contexto inclui documentos do primeiro.
- **RF6, o grafo cresce.** Depois de cada ciclo, o inspetor mostra mais nós e arestas; destacar a cadeia de um ciclo mostra exatamente o que ele criou; a contagem por tipo e a proporção de restritos fecham com a base.
- **RF7, exclusão de uma pessoa.** Um fato `erasure-requested` (pessoa sintética do corpus, por ponteiro a um blob restrito) percorre `resolve-person` → `knowledge-erase` → `knowledge-verify` → relatório. No fim: zero trecho, nó, aresta e blob da pessoa; a lápide gravada; as outras pessoas intactas; o blob do pedido removido; `erasure-completed` com contagens. Repetir o pedido não apaga mais nada.
- **RF8, inspetor.** Cada domínio expõe o inspetor, só leitura, com lista de operadores (sem lista, ninguém lê). Nenhum conteúdo restrito na tela, na API, no export GraphML nem nos logs.
- **RF9, reindexação.** Trocar o modelo de embedding da base de Negócio por `embedding-changed` termina em `reindexed` com `APPLIED`; antes e depois, a recuperação devolve os mesmos documentos para as mesmas perguntas do corpus; o modelo antigo passa a ser recusado.
- **RF10, falha na base.** Matar o worker de conhecimento no meio de `knowledge-write` e de `knowledge-erase` (pontos de caos como no S.11) não duplica nem deixa metade gravada; a reentrega conclui (a base em memória e o que isso significa estão em 8e).
- **RF11, medição com modelo real** (execução manual, só medida): fração das entidades e relações esperadas que a extração achou, trechos recuperados que contêm a resposta, latência e memória. Não é critério de aceite.

**Não funcionais**
- **RNF1:** o contrato do barramento muda só de forma aditiva (campos novos opcionais); nenhuma asserção existente muda. Se mudar, parar e perguntar.
- **RNF2:** nenhum teste chama um provedor de LLM real; dados sintéticos; o embedder dos testes é o determinístico do Stint.
- **RNF3:** nada de `sleep` nos testes: relógio controlável, barreiras e long polls.
- **RNF4:** nenhuma credencial real; AWS apontando para o floci com `test`/`test`.

## 4. Especificação

### 4.1 Workers
- `sandbox-business` e `sandbox-engineering` ganham `stint-worker-knowledge` e `stint-knowledge`, e passam a registrar `knowledge-write`, `knowledge-retrieve`, `knowledge-erase`, `knowledge-verify`, `knowledge-reindex` (Negócio) e `resolve-person` (via `stint-worker-redaction`, que já está nos dois).
- A lista de escopos de cada worker tem uma entrada: `sandbox/business-kb`, `sandbox/engineering-kb`.
- O embedder dos testes é o determinístico; o real só na rodada manual.

### 4.2 Workflows de ingestão
`knowledge-ingest.yaml` em cada domínio:
1. Início: `document-registered.v1`, filtro por `kind` (8b).
2. `read`: `read-record` devolve o ponteiro do texto.
3. `extract-knowledge` (esquema e glossário conforme 8a), com `revision` igual à versão do registro.
4. `knowledge-write` com `derivedFrom` = ponteiros do lote e do texto.
5. `emit document-indexed` com ids e contagens.
6. Recusa da extração → `ingest-rejected` (sem texto), instância termina limpa.

### 4.3 Recuperação nos agentes
O escritor ganha o retriever com `hops` 2 no modo relacional e o orçamento do SDD 3b.4. A seção de origem é escrita pelo handler, nunca pelo modelo.

### 4.4 Inspetor
Cada domínio sobe o `InspectorServer` numa porta própria (Negócio 8097, Engenharia 8098), com a lista de operadores vinda de configuração. A porta só lê a base do próprio domínio.

### 4.5 Exclusão e reindexação
Usam as definições `knowledge-erase` e `knowledge-reindex` do Stint, adaptadas ao domínio. O pedido da pessoa chega por ponteiro a um blob restrito de retenção curta, nunca pelo nome no fato.

### 4.6 Cenários (`sandbox-scenarios`)
- `KnowledgeIngestIT`: p2 ciclo 1 e ciclo 2; contagens, origem, recuperação com contexto do ciclo 1, crescimento no inspetor, destaque por cadeia, varredura.
- `KnowledgeErasureIT`: exclusão de uma pessoa, verificação, idempotência, as outras pessoas intactas, inspetor sem o nome.
- `KnowledgeChaosIT`: morte do worker em `knowledge-write` e em `knowledge-erase`, reentrega.
- `KnowledgeReindexIT`: troca de modelo e mesma recuperação.
- `KnowledgeInspectorIT`: sem lista de operadores não lê; com lista lê; nenhum rótulo restrito.
- Rodada manual de modelos reais (RF11) com evidência em `docs/sdd/evidence/S.12-models.txt`.

## 5. Critérios de aceite
| CA | Critério | Como se prova |
|---|---|---|
| CA1 | Duas bases soberanas: o escopo do outro domínio é recusado (403) | `KnowledgeIngestIT` |
| CA2 | Ingestão por fato, idempotente: reentrega e nova versão não duplicam | `KnowledgeIngestIT`, `KnowledgeChaosIT` |
| CA3 | Zero marcador de PII na base, no lote, no log de mudanças, nos fatos, no inspetor e no export | varredor estendido |
| CA4 | Recuperação com origem; no ciclo 2 de `p2` o contexto traz documentos do ciclo 1 | `KnowledgeIngestIT` |
| CA5 | O grafo cresce por ciclo; destacar a cadeia mostra só o que ela criou | `KnowledgeIngestIT` pelo inspetor |
| CA6 | Exclusão de pessoa de ponta a ponta, verificada, idempotente, sem tocar nas outras | `KnowledgeErasureIT` |
| CA7 | Inspetor somente leitura, padrão negar, sem conteúdo restrito | `KnowledgeInspectorIT` |
| CA8 | Reindexação com o mesmo resultado de recuperação; modelo antigo recusado | `KnowledgeReindexIT` |
| CA9 | Morte do worker em escrita e em exclusão não duplica nem deixa metade | `KnowledgeChaosIT` |
| CA10 | Nada sobra nos domínios (esperas, outbox, DLQ, instâncias não terminais) em todos os cenários | `Leftovers` |
| CA11 | Medição com modelo real registrada | `S.12-models.txt` |
| CA12 | Regressão S.1–S.11 verde | `gate.sh --regression-only`, depois da última implementação da fase |

## 6. Plano de implementação
1. Branch, RF0 e a regressão curta.
2. Workers e bases nos dois domínios, com testes unitários.
3. Workflows de ingestão; `KnowledgeIngestIT`.
4. Recuperação nos agentes e a seção de origem.
5. Inspetor; `KnowledgeInspectorIT`.
6. Exclusão e reindexação.
7. Caos da base e varredura estendida.
8. Rodada com modelos reais.
9. Regressão completa, registro e Notion.

## 7. Fora do escopo
- Ponte entre domínios (3c) e o gate da 3b com nativo e carga (S.13).
- Adapter persistente da base (a 3b não tem; achado S12-1).
- Crypto-shredding por área (3b.5b) e `forget-subject` (memória de conversa), a menos que o Kayan queira (8h).
- Mudança de esquema por reingestão em massa.

## 8. Decisões abertas (recomendação em negrito)
- **8a, esquema do grafo.** Usar o `meetings@1` como está, ou registrar um esquema próprio do sandbox (`pipeline@1`, com decisão, requisito, SDD, ADR e relações). **Recomendo `meetings@1` com glossário próprio do sandbox para a primeira entrega**, e medir; um esquema novo só se a medição mostrar grafo pobre. Se o registro de esquemas do Stint aceitar um esquema de fora, a troca é de configuração.
- **8b, o que cada base ingere.** **Negócio: design, questions e transcript-redacted. Engenharia: sdd-batch, sdd e o design (lido por `readers`).** Relatórios e patches da Entrega ficam fora (volume e valor baixos para a base).
- **8c, inspetor em portas próprias** (8097 e 8098) dentro do processo do domínio. **Recomendo.** A alternativa é um processo à parte, que não consegue ler a base em memória.
- **8d, a pessoa a excluir.** **Uma das pessoas sintéticas do corpus, com a forma escrita e a falada** do `pii-forms.json`.
- **8e, caos com a base em memória.** O processo morto perde a base; a reentrega reconstrói só o documento em curso. **Recomendo provar o que dá: nada duplicado, nada pela metade, nada vazado, e a perda registrada como achado S12-1**, em vez de inventar persistência no sandbox.
- **8f, reindexação.** **Entra, só na base de Negócio.**
- **8g, medição com modelos reais.** **Entra como RF11, só medida,** com o mesmo formato do S.11.
- **8h, crypto-shredding e `forget-subject`.** **Ficam fora** do S.12 (a exclusão por pessoa e por documento já cobre o pedido do rascunho); entram no S.13 se o Kayan quiser.
- **8i, SHA do Stint.** **`05fb70e`**, a ponta da 3b. A 3c contém a 3b, mas é outra fase.
- **8j, ordem de execução.** Regressão curta na subida do Stint; a regressão completa só depois da última implementação, como no S.11.

## 9. Registro de validação (parcial, 23/10/2026; falta a medição com modelos reais, CA11,)

| CA | Estado | Evidência |
|---|---|---|
| CA1 | Verde: o escopo do outro domínio é recusado (teste unitário `DomainKnowledgeTest` em cada domínio) | testes unitários |
| CA2 | Verde: reentrega e nova versão não duplicam; o worker morto dentro de `knowledge-write` indexa uma vez | `S.12-CA1-5.txt`, `S.12-CA9-write.txt` |
| CA3 | Verde: zero marcador no barramento, logs, stores, spans e no que o inspetor mostra | `S.12-CA1-5.txt` |
| CA4 | Verde: o design do ciclo 2 de `p2` cita o do ciclo 1; o primeiro não cita nada | `S.12-CA1-5.txt` |
| CA5 | Verde: Negócio 9 nós/2 arestas/40 mudanças, Engenharia 13 nós/2 arestas/78 mudanças; a cadeia de `p2` fez todas as mudanças | `S.12-CA1-5.txt` |
| CA6 | Parcial: documento apagado e verificado nas duas bases, repetido sem apagar mais; pessoa: só o caminho de lápide e verificação (S12-2) | `S.12-CA6-8.txt` |
| CA7 | Verde: sem operador, 403 e lista vazia; com operador, a base e o export; nenhum marcador | `S.12-CA1-5.txt` |
| CA8 | Verde: troca aplicada uma vez, a segunda não muda, uma entrada `REINDEX` (cenário); as mesmas perguntas acham os mesmos documentos, na mesma ordem, antes e depois (teste unitário `a_move_to_another_model_finds_the_same_documents_for_the_same_questions`) | `S.12-CA6-8.txt`, `DomainKnowledgeTest` |
| CA9 | Verde: worker morto em `knowledge-write` e em `knowledge-erase`; o trabalho volta, conclui uma vez | `S.12-CA9-write.txt`, `S.12-CA9-erase.txt` |
| CA10 | Verde em todos os cenários (`Leftovers`) | as evidências acima |
| CA11 | Pendente | |
| CA12 | Verde (23/10/2026, 14:11–15:16): Billing 5/5, Orders 6/6, `RegressionIT` 3/3, `PortalRolesIT`, `PiiSweepIT`, esqueleto x3 no Stint `05fb70e` | `S.12-CA12.txt` |

**Desvios da spec.**
- **Módulo compartilhado:** o plano previa código comum; o guarda `sovereign-domain` do S.4 proíbe dependência entre módulos `sandbox-*`, então o código foi copiado por domínio (como os pontos de caos).
- **8b:** Negócio ingere só `design`. As `questions` são da Engenharia e não têm Negócio como leitor (`read-record` devolve 403). `transcript-redacted` não é registrado neste fluxo.
- **Portas do inspetor:** 8197 e 8198 em vez de 8097 e 8098, que colidem com os workers de cenários de caos do S.11.
- **RF5:** a seção de contexto está no design (Negócio) e nas perguntas (Engenharia), não nos SDDs: o formato de SDD é validado pela Entrega e uma seção extra o quebraria.
- **RF7:** o pseudônimo estável na ingestão, aprovado por Kayan, não é alcançável (S12-2).

**Achados (nada alterado no Stint).**
- **S12-1:** a 3b só tem a base em memória; um processo morto perde a base. O caos prova que nada duplica, nada fica pela metade e nada vaza, não que a base sobrevive.
- **S12-2:** o redator do S.6 troca o nome por um rótulo por instância (`PERSON_<hash>`) antes de o documento existir; a base só vê rótulos que não se resolvem pelo nome. Excluir uma pessoa pelo nome só funciona com pseudônimo estável por escopo, o que muda o S.6.
- **S12-3:** o grafo com `meetings@1` e glossário de 5 a 6 termos é pobre (2 arestas por base). Um esquema próprio do sandbox (8a) fica para quando a medição mostrar a necessidade.
- **S12-4:** um fato de instância restrita sai pelo barramento só com `dataref`; quem observa de fora precisa casar por `subject`, não por dados.
