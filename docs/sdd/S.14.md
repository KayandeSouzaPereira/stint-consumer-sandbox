# SDD S.14 — Ponte entre domínios do pipeline: ponteiros de grafo, resolução pela política da origem, ADR com checagem de conflito e o e-mail de dúvidas que reinicia o ciclo

| Campo | Valor |
|---|---|
| ID | S.14 |
| Status | **Em implementação** (spec aprovada por Kayan em 23/10/2026, com as recomendações 8a–8i) |
| Depende de | S.12 e S.13 (Implementados; as duas bases). Stint `10266ee` (`phase-3c/domain-bridge`, SDDs 3c.1–3c.6; contém a 3b em `05fb70e` e a 3a em `a52b20a`) |
| Bloqueia | S.15 (gate da 3c) |
| Branch | `phase-3c-validation`, criada de `phase-3b-validation` (o PR da 3b ainda não foi mergeado, então os PRs são empilhados) |
| Commits analisados | sandbox `bce60c1` · Stint `10266ee` |
| Data da análise | 23/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.14): um parágrafo de objetivo, coberto abaixo |

## 1. Análise

### 1.1 O que o Stint 3c entrega e o S.14 consome
| Peça | SDD | Uso aqui |
|---|---|---|
| `GraphPointer` (`stint-kg://<namespace>/<base>/<nó>?v=<versão>`), nó `DomainReference`, esquema `architecture@1`, tarefas `knowledge-announce` e `knowledge-reference`, fato `nodes-published.v1` | 3c.1 | Negócio anuncia os nós que um documento acrescentou; Engenharia guarda só ponteiros |
| `resolve-pointers` servido pela origem, concessão por definição, `RESOLVED`/`DENIED`/`REMOVED`, blob `resolved-view` de vida curta, auditoria `pointer-resolve` | 3c.2 | Engenharia traz o que o ponteiro aponta, sob a política de Negócio, sem cópia durável |
| Catálogo canônico (`catalog-lookup`, `catalog-link`, `catalog-mentions`) | 3c.3, 3c.3b | Opcional aqui (8d) |
| `architecture-adr`, decisão `conflict-check`, revisão humana (`conflicts` / `compatible` / `needs-change`), ADR com "motivada por" | 3c.4 | O ADR da Engenharia, rastreável às decisões de Negócio |
| `BridgeContract` (consumidores, tipos, visão, teto), `ChannelAttestation` (falha fechada), `AuditTrail`, `bridge-review.md` | 3c.5 | O contrato de exposição de Negócio e a atestação do canal |
| Cenário ponta a ponta medido | 3c.6 | Referência do gabarito (`kb.decisions`, `kb.requirements`, `architecture`) |

### 1.2 O que o sandbox já tem
- Negócio e Engenharia soberanos, cada um com a sua base em memória (`business-kb`, `engineering-kb`, esquema `meetings@1`), ingestão por `document-registered`, inspetor, exclusão, pergunta de fora (S.12, S.13).
- A Engenharia hoje lê o design de Negócio por **ponteiro de documento** do registro (`read-record`, lista `readers`, S.7/S.10): é um ponteiro de blob, não de grafo, e o registro guarda a cópia. A ponte troca isso, para o conhecimento, por ponteiros de grafo resolvidos pela origem.
- O ciclo reinicia hoje por `questions-sent` (S.9): a Engenharia manda as dúvidas por e-mail a Negócio e o próximo ciclo começa. O S.14 reaproveita esse caminho para o conflito do ADR.
- O corpus (v2) tem o gabarito de cada decisão do LitJev, mas **não** tem `kb.decisions`/`kb.requirements` nem reuniões de arquitetura (o formato v1.2 do Stint 3c.6 tem).

### 1.3 Achados que moldam o desenho
1. **A extração de teste só acha termos do glossário** (`ScriptedExtraction`, S.12). A ponte precisa de nós `Decision` e `Requirement` para anunciar. O gabarito do corpus ganha `kb` (aditivo, 8c) e a extração de teste o lê.
2. **Engenharia precisa de uma segunda base.** O `DomainReference` e o ADR vivem no esquema `architecture@1`, não no `meetings@1` da `engineering-kb`. Uma base nova `sandbox/engineering-arch` por domínio (8b), sem tocar a `engineering-kb`.
3. **`resolve-pointers` é servido pela origem a outro domínio.** O sandbox só tem tarefas compartilhadas (privacy, records, channels). Este é o primeiro serviço entre dois domínios soberanos: uma fila `business-tasks-engineering` e uma ligação a mais do worker de Negócio, que responde na fila de resultados da Engenharia (o padrão de `privacy-tasks-<domínio>`).
4. **A atestação do canal é uma declaração de configuração** (3c.5/3c.6): o floci não autentica o transporte (credenciais dummy). O sandbox declara a atestação, prova a falha fechada sem ela e registra que a declaração não prova autenticação (S14-1, antecipado).
5. **O S13-1 vale aqui:** o ponteiro resolvido não pode virar `derivedFrom` de outro domínio. A ponte nunca copia nem lista blob alheio.
6. **A medição com modelo real continua fraca** (S12-5/S13-4): a decisão `conflict-check` roda com o adaptador de teste no CI; com LitJev só na rodada manual.

## 2. Problema e objetivo
Fazer a Engenharia conhecer o que Negócio decidiu **sem copiar**, por ponteiros de grafo resolvidos sob a política de Negócio; produzir um ADR por decisão técnica rastreável a decisões e requisitos de Negócio; e, quando a decisão técnica **conflita** com um requisito de Negócio, o conflito vai a um humano e, se se mantém, vira o e-mail de dúvidas que reinicia o ciclo, tudo com zero vazamento e trilha de auditoria.

## 3. Requisitos
**Funcionais**
- **RF0, subir o Stint** para `10266ee` (instalação como no S.12: `infra/install-stint.sh`, versão `0.1.0-3c-10266ee`). A regressão curta (unitários e `PipelineSkeletonIT`) roda antes de mudar qualquer coisa.
- **RF1, corpus aditivo.** Os encontros de Negócio ganham `kb.decisions` (chave, rótulo, resumo, status) e `kb.requirements`; entram duas reuniões de arquitetura sintéticas para o `p2` (uma compatível, uma conflitante com um requisito) com `expected.drafts` (id, título, resumo, `motivatedBy`, `verdict`). Aditivo: o leitor v2 as ignora; `PipelineCorpusTest` continua verde.
- **RF2, extração com decisões.** A extração de teste de Negócio devolve `Decision` e `Requirement` do gabarito `kb`, além dos termos do glossário.
- **RF3, anúncio.** Negócio, ao indexar um documento, emite `io.sandbox.business.nodes-published.v1` com ponteiros (`{pointer, type, level}`), nunca nome, resumo nem propriedade; restrito não sai.
- **RF4, vínculos.** Engenharia, ao receber o anúncio, cria os nós `DomainReference` na `engineering-arch` e as relações das âncoras para eles. Ponteiro inválido ou domínio não permitido é recusado.
- **RF5, resolução pela origem.** Engenharia pede `resolve-pointers` a Negócio. Negócio concede só ao consumidor listado no `BridgeContract` (tipos, visão, teto `confidential`); sem concessão, `DENIED`. O conteúdo resolvido vai para um blob `resolved-view` que expira; nada dele no contexto da instância, no vetor ou no grafo da Engenharia.
- **RF6, o contrato e o canal.** `BridgeContract` de Negócio carregado de um recurso versionado (consumidor `engineering:architecture-adr`); `ChannelAttestation` declarada em configuração; sem a atestação o resolvedor recusa tudo (403); `restricted` nunca consta.
- **RF7, ADR e conflito.** `architecture-adr` na Engenharia: rascunhos por ponteiro → resolução → decisão `conflict-check` (adaptador de teste) → revisão humana do conflito e do incerto (papel `tech-rep`, pelo portal) → ADR por decisão com "motivada por" e o resultado, publicado no canal (Confluence).
- **RF8, o e-mail de dúvidas e o reinício.** Um conflito que o humano mantém emite o e-mail de dúvidas a Negócio (o caminho de `questions-sent` do S.9, com a razão `adr-conflict`) e o ciclo seguinte começa. Um conflito que o humano resolve como `compatible` ou `needs-change` não reinicia.
- **RF9, origem fora do ar.** Negócio parado: a Engenharia segue com o marcador `unavailable` (decisões sem contexto vão à revisão, nunca a `compatible`) ou suspende e tenta de novo, conforme a definição.
- **RF10, trilha e zero vazamento.** `AuditTrail` da cadeia mostra os passos da ponte, sem texto; o varredor lê o anúncio, o contexto das instâncias, o `resolved-view`, a base da Engenharia, a página do ADR e o e-mail.

**Não funcionais**
- **RNF1:** o barramento muda só de forma aditiva; nenhuma asserção existente muda (se mudar, parar e perguntar).
- **RNF2:** nenhum teste chama um provedor real; dados sintéticos; AWS no floci com `test`/`test`.
- **RNF3:** nenhum `sleep` nos testes; relógio controlável, barreiras e long polls.
- **RNF4:** nada do Stint é alterado daqui; falha do Stint vira achado com proposta.

## 4. Especificação

### 4.1 Corpus (RF1)
`corpus/` formato v2.1: em cada reunião de Negócio, `kb: {decisions: [{key, label, summary, status}], requirements: [{key, label, summary}]}`; lista nova `architecture` no manifesto: `a-001` (rascunho compatível com os requisitos de `p2`) e `a-002` (rascunho que conflita com o limite por membro), só texto, em inglês, sintéticos. `scriptSha256` e `pii-forms.json` regenerados; `PipelineCorpusTest` ganha as asserções do novo formato.

### 4.2 Duas peças novas por domínio
- **Negócio (origem):** `knowledge-announce` (workflow e tarefa), `BridgeContract` (recurso `bridge/business-kb.v1.json`), `ChannelAttestation` por configuração, uma ligação de worker na fila `business-tasks-engineering` servindo `resolve-pointers` (e só ele).
- **Engenharia (consumidor):** base `sandbox/engineering-arch` (esquema `architecture@1`), `architecture-links`, `architecture-resolve`, `architecture-adr`, `architecture-conflict-check`; tarefas `knowledge-reference`, `write-adrs`, `pair-decisions`, `publish-adr`.
- **Fiação (bootstrap):** regras `nodes-published` → `engineering-domain-events`; fila `business-tasks-engineering` e seu DLQ; a Engenharia roteia `resolve-pointers` para ela.

### 4.3 Cenários (`sandbox-scenarios`)
`BridgePointersIT` (anúncio, vínculo, resolução, nada copiado), `BridgePolicyIT` (concessão, `DENIED`, `REMOVED`, falha fechada sem atestação, restrito), `BridgeAdrIT` (ADR compatível publicado; ADR conflitante: revisão, e-mail de dúvidas, ciclo reiniciado), `BridgeOutageIT` (Negócio parado, `follow` e `suspend`), `BridgeLeakIT` (varredura e trilha).

## 5. Critérios de aceite
| CA | Critério | Como se prova |
|---|---|---|
| CA1 | O anúncio de Negócio leva só ponteiros (id, tipo, nível); a Engenharia cria as Referências sem nome nem conteúdo da origem | `BridgePointersIT` |
| CA2 | A resolução devolve o que a política de Negócio permite, em blob que expira; nada no contexto, no vetor ou no grafo da Engenharia | `BridgePointersIT`, `BridgeLeakIT` |
| CA3 | Sem concessão: `DENIED`; sem atestação: 403; restrito nunca; nó removido: `REMOVED` | `BridgePolicyIT` |
| CA4 | ADR compatível publicado com "motivada por" e o resultado; ADR por decisão | `BridgeAdrIT` |
| CA5 | Conflito mantido: e-mail de dúvidas a Negócio e o ciclo seguinte começa; conflito resolvido como compatível/needs-change não reinicia | `BridgeAdrIT` |
| CA6 | Negócio parado: `follow` marca `unavailable` e as decisões vão à revisão; `suspend` tenta de novo e conclui quando volta | `BridgeOutageIT` |
| CA7 | `AuditTrail` da cadeia mostra os passos da ponte sem texto; zero marcador em tudo, incluindo `resolved-view` e a página do ADR | `BridgeLeakIT` |
| CA8 | Nenhum blob de outro domínio é apagado nem listado em `derivedFrom` pela ponte (S13-1) | `BridgePolicyIT` |
| CA9 | Nada sobrando (esperas, outbox, DLQ, instâncias não terminais) em todos os cenários | `Leftovers` |
| CA10 | Regressão S.1–S.13 verde | `gate.sh --regression-only`, depois da última implementação |

## 6. Plano de implementação
1. Branch, RF0 e a regressão curta.
2. Corpus aditivo e extração com `Decision`/`Requirement` (RF1, RF2).
3. Anúncio e vínculos (RF3, RF4); `BridgePointersIT` até a Referência.
4. Resolvedor, contrato, atestação e a ligação do worker (RF5, RF6); `BridgePolicyIT`.
5. ADR, conflito, revisão e o e-mail de dúvidas (RF7, RF8); `BridgeAdrIT`.
6. Origem fora do ar (RF9); `BridgeOutageIT`.
7. Trilha e varredura (RF10); `BridgeLeakIT`.
8. Regressão completa, registro e Notion.

## 7. Fora do escopo
- O gate da 3c com carga, nativo e modelos reais (S.15) e o observatório (S.16).
- Mudar o Stint; autenticar o transporte; assinar o convite (do deployment).
- O parecer do DPO/jurídico (do Kayan).

## 8. Decisões abertas (recomendação em negrito)
- **8a, SHA do Stint.** **`10266ee`**, a ponta da 3c.
- **8b, a segunda base da Engenharia.** **`sandbox/engineering-arch` com `architecture@1`**, separada da `engineering-kb`.
- **8c, corpus.** **Aditivo (v2.1)**: `kb` nas reuniões de Negócio e duas reuniões de arquitetura; o hash do manifesto muda para os arquivos tocados. Precisa da sua aprovação por mexer em arquivos que as asserções de corpus conferem.
- **8d, o catálogo canônico (3c.3).** **Fora do S.14**; entra no S.15 se o Kayan quiser.
- **8e, a Entrega continua no registro de documentos.** **Sim.** A Entrega valida SDDs pelo ponteiro do registro; a ponte cobre Negócio → Engenharia. A Entrega por ponteiro de grafo é uma mudança posterior.
- **8f, o reinício do ciclo.** **Reaproveitar o `questions-sent` do S.9** com a razão `adr-conflict` (campo aditivo no fato), sem fluxo novo.
- **8g, a atestação do canal.** **Declarada em configuração e registrada como declaração**; a falha fechada sem ela é provada.
- **8h, a decisão `conflict-check`.** **Adaptador de teste no CI, com o gabarito do corpus;** LitJev só na rodada manual do S.15.
- **8i, ordem de execução.** Regressão curta na subida do Stint; regressão completa só depois da última implementação.

## 9. Registro de validação
(preencher na etapa 2)
