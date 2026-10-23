# Gate da Fase 3b: validação externa pelo stint-consumer-sandbox

| Campo | Valor |
|---|---|
| SDD | S.12 (bases de conhecimento) e S.13 (cenários e gate) |
| Stint | `05fb70e` (`phase-3b/knowledge-base`, SDDs 3b.1–3b.6, sobre a 3a `a52b20a`) |
| Sandbox | `phase-3b-validation` (empilhada sobre `phase-3a-validation`) |
| Data | 23/10/2026 |
| Ambiente | Windows 11, JDK 25, Maven 3.9.15, Quarkus 3.33.2, Docker Desktop, floci/MailHog/Jaeger do compose; llama.cpp (Qwen3-4B) só na medição |
| Comando | `bash infra/gate.sh --regression-only` (CA12 do S.12 e CA10 do S.13); os cenários `Knowledge*IT` um a um |

## 1. Recomendação

**Recomendo aprovar o gate da Fase 3b para o que ele mede, com uma ressalva que importa e quatro que não bloqueiam.**

- A base de conhecimento de cada área ingere o que o registro de documentos anuncia, cita os documentos anteriores, cresce de ciclo a ciclo, é excluída e verificada por fora no floci, troca de modelo de embedding com a base em uso e sobrevive à reentrega, tudo sem vazamento e sem sobras.
- **Ressalva que importa (S12-5 e S13-4):** com o modelo local de verdade (Qwen3-4B, llama.cpp) a extração do esquema `meetings@1` não é confiável: 0 de 6 lotes válidos numa rodada e 4 de 6 em outra, com os mesmos textos; nos SDDs, 1 de 4. O fluxo está provado com o modelo de teste; com o modelo real a base ficaria quase vazia. Isso não é falha do sandbox: o Stint recusa lote fora do esquema, de propósito.
- **Ressalvas que não bloqueiam:** S12-1 (a base só existe em memória), S12-2 (excluir uma pessoa pelo nome não é resolvível), S13-1 a S13-3 (limites do worker, do inspetor e do lote recusado).
- **Corrigido durante o gate:** a exclusão de um documento na base de Negócio apagava a cópia do registro de Records (S13-1). Mudei a ingestão do sandbox para só listar o lote da própria base; o cenário `KnowledgeErasureOutsideIT` prova que a cópia do registro sobrevive.

## 2. SDD do Stint → cenário → resultado

| SDD 3b | O que o Stint promete | Cenário do sandbox | Resultado |
|---|---|---|---|
| 3b.1 porta e adapters em memória | gravação atômica, log de mudanças, apagamento | `KnowledgeIngestIT`, `KnowledgeErasureOutsideIT` | Verde |
| 3b.2 esquema e extração ancorada | entidades resolvidas ao glossário, lote por ponteiro | `KnowledgeResolutionIT` (nove maneiras de escrever cinco termos, cinco nós) | Verde; com o modelo real, ver a seção 1 |
| 3b.3 ingestão e recuperação no workflow | início por fato, idempotente, redação antes da escrita | `KnowledgeIngestIT`, `KnowledgeRedeliveryIT`, `KnowledgeLeakIT` | Verde |
| 3b.4 recuperação híbrida e contexto com origem | modo, orçamento, origem em tudo, ata cita o contexto | design do 2º ciclo de `p2` cita o do 1º; pergunta de fora em `KnowledgeResolutionIT` | Verde |
| 3b.5 reindexação e exclusão ponta a ponta | troca de modelo, lápide, verificação | `KnowledgeErasureIT` (S.12), `KnowledgeReindexIT`, `KnowledgeErasureOutsideIT`, `KnowledgeChaosIT` | Verde |
| 3b.5b chave por área | crypto-shredding | fora do escopo (decisão 8h do S.12) | — |
| 3b.6 visualização e métricas | inspetor somente leitura, padrão negar, sem conteúdo restrito | `KnowledgeIngestIT`, `KnowledgeLoadIT`, `KnowledgeLeakIT` | Verde |

## 3. Real e simulado

- **Real:** floci, MailHog, Jaeger, os sete domínios como processos, as bases e os inspetores, o portal, o varredor, os pontos de caos, uma imagem nativa (Engenharia), o llama.cpp na medição.
- **Simulado:** o revisor (script), as reuniões (corpus sintético), o modelo de extração dos testes (roteirizado, devolve as menções como o texto as escreveu), o modelo de embedding (determinístico, e o que "troca de modelo" é o mesmo sob outro nome), o registro de documentos nos cenários que plantam textos (`KnowledgePlant`).

## 4. Resultados por critério do S.13

| CA | Resultado |
|---|---|
| CA1 pergunta de fora | Verde: só ids de documentos, modo, contagens; nada de pergunta ou conteúdo no barramento |
| CA2 variações do termo | Verde: cinco nós, zero candidato a duplicata |
| CA3 reentrega | Verde: nós, arestas e log iguais; segunda resposta `UNCHANGED` |
| CA4 troca de modelo em uso | Verde: 30 perguntas simultâneas, todas respondidas com os mesmos documentos; `APPLIED` uma vez |
| CA5 exclusão conferida por fora | Verde depois da correção 8a; lápide recusa a reingestão |
| CA6 carga, 6 projetos | Verde: cada documento indexado uma vez em cada base; da publicação do fato à resposta, p50 1,5 s, p95 1,6 s (Negócio) e 2,0 s (Engenharia) |
| CA7 zero vazamento | Verde: o texto com marcador não entra; o varredor o acha onde foi plantado |
| CA8 nativo | Observação: Engenharia compila e corre o ciclo; Negócio não compila (`Random` no heap da imagem, S11-3) |
| CA9 modelos reais | Medido; a taxa de lotes válidos varia entre rodadas (seção 1) |
| CA10 regressão S.1–S.12 | Verde (23/10/2026, 18:51–19:58): Billing 5/5, Orders 6/6, `RegressionIT` 3/3, `PortalRolesIT`, `PiiSweepIT`, esqueleto x3 |
| CA11 este relatório | Entregue |

## 5. Achados consolidados

| Id | Achado | Estado | Proposta |
|---|---|---|---|
| S12-1 | A base só existe em memória; processo morto perde a base | Aberto | Adapter persistente na 3b ou 3c; o caos prova que nada duplica nem vaza |
| S12-2 | A pessoa não é resolvível pelo nome: o redator do S.6 usa rótulo por instância | Aberto | Pseudônimo estável por escopo no S.6 (mexe na 3a; só se o Kayan pedir) |
| S12-3 | Grafo pobre com `meetings@1` (2 arestas por base) | Aberto | Esquema próprio do sandbox, quando a extração real for confiável |
| S12-4 | Fato de instância restrita sai só com `dataref` | Observação | Quem observa casa por `subject` |
| S12-5, S13-4 | O modelo local não gera lote válido de forma estável (0/6 e 4/6 nos mesmos textos; 1/4 nos SDDs) | Aberto | Modelo maior na extração, esquema enxuto, ou chamada de reparo no Stint |
| S13-1 | A exclusão remove os blobs de `derivedFrom`, mesmo de outro domínio | Corrigido no sandbox (8a); aberto no Stint | O worker de conhecimento aceitar `derivedFrom` só do seu próprio blob store |
| S13-2 | A heurística de duplicatas sinaliza documentos de um mesmo projeto | Aberto | O inspetor ignorar nós `Meeting` ou comparar por conteúdo |
| S13-3 | O lote de extração (com o texto) fica 7 dias antes de a escrita recusar dado pessoal | Aberto | O worker apagar o lote recusado |
| S11-2 / S11-3 | Registro de reflexão no nativo (corrigido na Engenharia agora); Negócio não compila | S11-2 corrigido; S11-3 aberto | `Random` estático no LangChain4j |

## 6. Observações para a Fase 3c

- A ponte entre domínios (3c) vai reaproveitar o retriever; o `derivedFrom` do S13-1 mostra que um domínio que apaga não pode tocar o armazenamento de outro: a 3c precisa dessa fronteira desenhada.
- O modelo de extração real é o ponto fraco do caminho; antes de medir a 3c com modelos, decidir o esquema e o modelo.
- A memória da máquina continua sendo o limite das execuções longas (nativo, modelos e regressão competem por ela).
