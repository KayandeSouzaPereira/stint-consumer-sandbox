# SDD S.13 — Cenários e gate da Fase 3b: resolução, reentrega, reindexação em uso, exclusão conferida por fora, carga, vazamento, nativo e modelos reais

| Campo | Valor |
|---|---|
| ID | S.13 |
| Status | **Em implementação** (spec aprovada por Kayan em 23/10/2026, com as recomendações 8a–8h) |
| Depende de | S.12 (Implementado). Stint `05fb70e` (`phase-3b/knowledge-base`, SDDs 3b.1–3b.6), o mesmo do S.12 |
| Bloqueia | O PR de fase da 3b (e o início da 3c, S.14 em diante) |
| Branch | `phase-3b-validation` (empilhada sobre `phase-3a-validation`; o PR da 3a ainda não foi mergeado) |
| Commits analisados | sandbox `4850d5a` · Stint `05fb70e` |
| Data da análise | 23/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.13): um parágrafo de objetivo, coberto abaixo |

## 1. Análise

### 1.1 O que o S.12 deixou e o S.13 consome
| Peça | Estado | Uso aqui |
|---|---|---|
| Duas bases (Negócio, Engenharia), ingestão por `document-registered`, inspetores em 8197 e 8198, exclusão, troca de modelo (Negócio), contexto com origem | Implementado, regressão verde | O que o gate exercita sob falha, carga e escrutínio |
| `PipelineScenario` (counts, `verify`, Sweeper), `Leftovers`, pontos de caos `halt-in-task` (cobrem as rotas de conhecimento), `PipelineLoadIT` (corpus gerado `l1..lM`), `PipelineNativeIT`, rodada de modelos do S.11 | Do S.11 | Reaproveitados, nada reescrito |
| Achados S12-1 a S12-5 | Abertos | Cada um vira cenário, medição ou decisão aqui (seção 8) |

### 1.2 O que o rascunho pedia e como fica
O rascunho tem cinco itens: (1) resolução de entidades com variações sintéticas, (2) ingestão idempotente sob reentrega, (3) reindexação com a base em uso, (4) exclusão conferida por fora no floci, (5) zero vazamento nas bases. Somo o padrão do gate da 3a (S.11): carga, nativo, modelos reais e o relatório de fase.

### 1.3 Achados que moldam o desenho
1. **A exclusão de documento remove os blobs de `derivedFrom`** (`CompositeKnowledgeStore`, linha 1010 do Stint), e a ingestão do S.12 grava como `derivedFrom` o `contentRef`, que é a cópia do **registro de documentos (domínio Records)**. Se for isso mesmo, apagar um documento na base de Negócio apaga a cópia de Records, um efeito entre domínios que o S.12 não conferiu. É a primeira coisa que a conferência por fora do floci vai dizer (S13-1, hipótese a confirmar).
2. **Não há como perguntar à base de fora.** `knowledge-retrieve` é uma tarefa de worker; nenhum fluxo do sandbox a expõe. Para provar recuperação sob reindexação, carga e caos, cada domínio precisa de um fluxo `knowledge-recall` (fato de pergunta → recuperação → fato com ids e contagens), como o exemplo do Stint (3b.4).
3. **O modelo de extração de teste é roteirizado e devolve menções exatas.** Resolução de variações (maiúsculas, plural, erro de digitação, sinônimo do glossário) só se prova com um modelo de teste que também devolve variações. Os 6 textos de negócio e os técnicos do corpus servem de base.
4. **A base é só em memória (S12-1)** e o **modelo local não gera lote válido (S12-5)**: o gate mede isso, não resolve.
5. **A pessoa não é resolvível pelo nome (S12-2).** Resolver exige pseudônimo estável por escopo no S.6, que altera outro SDD aprovado; fica como decisão (8d).

## 2. Problema e objetivo
Provar a base de conhecimento das duas áreas sob falha, concorrência, carga e escrutínio de privacidade, conferindo por fora (floci, inspetores, barramento) o que o Stint diz que fez, e medir com modelos reais; fechar com o relatório do gate da 3b.

## 3. Requisitos
**Funcionais**
- **RF0, regressão curta** no Stint `05fb70e` antes de mudar nada (unitários e `PipelineSkeletonIT`); a regressão completa só depois da última implementação.
- **RF1, recuperação de fora.** Um fluxo `knowledge-recall` por domínio (fato `io.sandbox.<d>.question-asked.v1` → `knowledge-retrieve` → `io.sandbox.<d>.question-answered.v1` com ids de documentos, modo, contagens por nível; nunca conteúdo). Aditivo.
- **RF2, resolução de entidades com variações.** O modelo de teste passa a devolver variações de um mesmo termo (caixa, plural, erro de uma letra, alias). Medir: quantos nós distintos viram a mesma entidade; os candidatos a duplicata do inspetor que sobram; nenhum nó novo para uma variação conhecida.
- **RF3, reentrega.** O mesmo `document-registered` duas vezes, e a mesma tarefa entregue duas vezes (worker morto depois de gravar e antes de responder): um índice, um documento, um `document-indexed`.
- **RF4, reindexação com a base em uso.** Perguntas continuas (RF1) durante a troca de modelo: cada resposta é da base antiga inteira ou da nova inteira; nunca mistura; depois da troca o modelo antigo é recusado (leitura e escrita).
- **RF5, exclusão conferida por fora.** Depois de excluir um documento: nenhum objeto S3 da base (lote, texto, cópias que `derivedFrom` lista) existe, a base não o devolve, a lápide barra a reingestão do mesmo documento, e **a cópia do registro de Records** é conferida (S13-1).
- **RF6, carga.** M projetos simultâneos (padrão 6) com as duas bases ingerindo: tempo de ingestão por documento (p50, p95), crescimento do grafo por projeto, nada duplicado, nada sobrando.
- **RF7, zero vazamento.** O varredor lê também o dump do inspetor, o export GraphML, os lotes em blob e o log de mudanças, em todos os cenários; controle positivo: um marcador plantado num lote é achado.
- **RF8, nativo (observação).** Tentar o build nativo de Negócio e de Engenharia com a base; registrar o que falha (S11-3 continua aberto). Não é critério.
- **RF9, modelos reais (medida).** Extração com o llama.cpp sobre os documentos reais do pipeline (design, SDD) e, se a decisão 8c der um esquema enxuto, com ele; mesma medição do S.12 mais a taxa de lotes válidos.
- **RF10, relatório** `docs/evidence/phase-3b-gate.md` (SDD 3b.1–3b.6 → cenário → resultado → evidência; real e simulado; achados consolidados S12 e S13; recomendação).

**Não funcionais**
- **RNF1:** o barramento muda só de forma aditiva; nenhuma asserção existente muda (se mudar, parar e perguntar).
- **RNF2:** nenhum teste chama um provedor real; dados sintéticos; AWS no floci com `test`/`test`.
- **RNF3:** nenhum `sleep` nos testes; relógio controlável, barreiras e long polls.
- **RNF4:** nada do Stint é alterado daqui; falha do Stint vira achado com proposta.

## 4. Especificação

### 4.1 Fluxo de pergunta (RF1)
`knowledge-recall.yaml` em cada domínio, iniciado por `question-asked.v1` `{questionId, question, hops?, mode?}`; uma tarefa `knowledge-retrieve` com o escopo do domínio; emite `question-answered.v1 {questionId, documents:[ids], mode, counts, trimmed}`. A lista de documentos vem da origem de cada item (`origin.documentId` e `evidence`); o texto da pergunta e dos itens não viaja.

### 4.2 Modelo de teste com variações (RF2)
`ScriptedExtraction` ganha um modo `variants` (propriedade de teste): para cada termo do glossário que o texto nomeia, devolve a menção escrita de uma de quatro formas, em rodízio determinístico. Sem o modo, o comportamento de hoje.

### 4.3 Conferência por fora (RF5)
Antes e depois da exclusão, o cenário lista os objetos dos buckets `sandbox-business-blobs` (lotes), `sandbox-documents` e `sandbox-records-docs` (cópia do registro) e confere a ausência dos listados em `derivedFrom`. A comparação é por chave, nunca por conteúdo.

### 4.4 Cenários (`sandbox-scenarios`)
`KnowledgeResolutionIT`, `KnowledgeRedeliveryIT`, `KnowledgeReindexIT`, `KnowledgeErasureOutsideIT`, `KnowledgeLoadIT`, `KnowledgeNativeIT` (observação) e a rodada de modelos (RF9).

## 5. Critérios de aceite
| CA | Critério | Como se prova |
|---|---|---|
| CA1 | Pergunta de fora devolve ids de documentos com origem; nenhum conteúdo no barramento | `KnowledgeResolutionIT` |
| CA2 | Variações do mesmo termo viram o mesmo nó; zero candidato a duplicata para elas | `KnowledgeResolutionIT` |
| CA3 | Reentrega do fato e da tarefa: um índice, um `document-indexed` | `KnowledgeRedeliveryIT` |
| CA4 | Durante a troca de modelo, cada resposta é inteira da base antiga ou da nova; o modelo antigo é recusado depois | `KnowledgeReindexIT` |
| CA5 | Exclusão conferida no floci: nada do documento resta; lápide barra a reingestão; efeito em Records dito (S13-1) | `KnowledgeErasureOutsideIT` |
| CA6 | Carga com 6 projetos: nada duplicado, nada sobrando; p50 e p95 de ingestão registrados | `KnowledgeLoadIT` |
| CA7 | Zero marcador em tudo, incluindo inspetor, export, lotes e log; controle positivo | varredor estendido |
| CA8 | Nativo de Negócio e de Engenharia tentado, resultado registrado | `KnowledgeNativeIT` |
| CA9 | Medição com modelos reais registrada (inclui a taxa de lotes válidos) | `S.13-models.txt` |
| CA10 | Regressão S.1–S.12 verde | `gate.sh --regression-only`, depois da última implementação |
| CA11 | Relatório do gate da 3b entregue | `docs/evidence/phase-3b-gate.md` |

## 6. Plano de implementação
1. RF0 (regressão curta).
2. `knowledge-recall` nos dois domínios; `KnowledgeResolutionIT` (CA1, CA2).
3. `KnowledgeRedeliveryIT` (CA3).
4. `KnowledgeReindexIT` (CA4).
5. `KnowledgeErasureOutsideIT` (CA5).
6. Varredura estendida e controle positivo (CA7).
7. Carga (CA6), nativo (CA8).
8. Modelos reais (CA9).
9. Regressão completa (CA10), relatório (CA11), registro, Notion.

## 7. Fora do escopo
- Adapter persistente da base e qualquer mudança no Stint (achados).
- Ponte entre domínios (3c) e o gate da 3c.
- Crypto-shredding por área e `forget-subject` (decisão do S.12, 8h).

## 8. Decisões abertas (recomendação em negrito)
- **8a, `derivedFrom` e a cópia de Records.** Se a hipótese de S13-1 se confirmar: **registrar como achado e mudar a ingestão do sandbox para só listar os blobs da própria base (o lote), não a cópia do registro**, porque a cópia é de outro domínio. Essa mudança é no sandbox (uma linha do fluxo) e muda uma asserção do S.12 (o texto de origem deixa de ser removido pela exclusão); precisa da sua aprovação, e eu paro antes de aplicá-la.
- **8b, fluxo de pergunta.** **Um por domínio, aditivo** (4.1).
- **8c, esquema para o modelo local (S12-5).** **Medir primeiro com o `meetings@1`; só se a taxa de lotes válidos for baixa, propor um esquema enxuto do sandbox ao Stint como achado** (o sandbox não altera o Stint). A medição com o modelo local roda sem o `meetings@1` só se o Stint aceitar um esquema de fora; verifico no RF9.
- **8d, a exclusão de pessoa (S12-2).** **Fica como achado.** Mudar o S.6 para pseudônimo estável por escopo altera o redator de toda a 3a e pede aprovação à parte; entra só se você pedir.
- **8e, carga.** **6 projetos, como no S.11**, com as duas bases ingerindo.
- **8f, nativo.** **Observação, sem critério**, Negócio e Engenharia; Records, Channels e Privacy já foram para nativo no S.11.
- **8g, SHA do Stint.** **`05fb70e`** (o mesmo do S.12).
- **8h, ordem.** Regressão curta no início; regressão completa só depois da última implementação (como no S.11 e no S.12).

## 9. Registro de validação
(preencher na etapa 2)
