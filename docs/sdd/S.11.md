# SDD S.11 — Cenários e gate da Fase 3a: caos, carga, vazamento, nativo e modelos reais

| Campo | Valor |
|---|---|
| ID | S.11 |
| Status | **Implementado** (aguarda a validação do Kayan; spec aprovada por Kayan em 21/10/2026, com as propostas 8a–8j; decisão do SHA em 8k) |
| Depende de | S.5 a S.10 (Implementados em `500dfd1`). Stint `a52b20a` (`phase-3a/ai-decision-workers`, SDDs 3a.1–3a.11), fixado por 8k |
| Bloqueia | O PR de fase da 3a e o início da 3b (S.12) |
| Branch | `phase-3a-validation` (os PRs da fase são empilhados) |
| Commits analisados | sandbox `8483c4c` · Stint `500dfd1` |
| Data da análise | 21/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.11): 6 requisitos, todos cobertos abaixo |

## 1. Análise

### O que já existe no sandbox (`8483c4c`)
- **Domínios da 3a**, cada um um processo Quarkus: `sandbox-business` (8090), `sandbox-engineering` (8091), `sandbox-delivery` (8092), `sandbox-privacy` (8093), `sandbox-records` (8094), `sandbox-channels` (8095) e `sandbox-target` (8096). Mais os da regressão das Fases 1 e 2 (`reports`, `orders`, `billing`).
- **`sandbox-scenarios`** é um módulo só de testes que sobe os jars como processos (`App.start`, ou contêiner com `-Dscenarios.apps=native`) e observa o mundo de fora: floci, MailHog, Jaeger, o barramento (`Spy`, fila `sandbox-spy`), o portal e o HTTP dos apps. Os testes de cenário atuais:
  - **Fase 2 (S.4):** `RegressionIT`, `ChaosIT` (a–e), `LoadIT`, `ObservabilityIT`, `NativeIT`.
  - **Fase 3a:** `PipelineSkeletonIT` (S.5, estendido em S.7–S.10: dois projetos, os três domínios, uma cadeia por projeto, contagens, registro, varredor em zero, nada sobrando), `PortalRolesIT` (S.5), `PiiSweepIT` (S.6, o controle positivo do varredor) e `LocalStackIT` (S.5, manual com áudio real).
- **`infra/gate.sh`** (S.4) só conhece a Fase 2: regressão `FlociBillingIT`, `FlociOrdersIT` e `RegressionIT` (`--repeat`), depois `ChaosIT,LoadIT,ObservabilityIT`, e `--native` para Orders e Billing. Nenhum cenário da 3a está ligado a ele, fora o `PipelineSkeletonIT`, que hoje corro à mão por um script.
- **Pontos de morte (caos)** existem só no Billing (S.4 8b): `billing.chaos.halt-before-publish` e `billing.chaos.halt-in-task`, desligados por padrão, com `ChaosEventPublisher` e `ChaosTaskHandler`. Os domínios da 3a não têm nenhum. Business, Engineering e Delivery já decoram o `EventPublisher` (`DomainTagPublisher`), então o mesmo padrão cabe.
- **Modelos.** No CI são stand-ins (`BusinessModels.fakes`: transcritor com o roteiro do corpus, decisões do gabarito, redator determinístico). Na rodada manual são o Whisper (8001), o LitJev (8000) e o llama.cpp (8080), via `infra/local-stack.sh`. Só o `sandbox-business` carrega o LangChain4j no `pom`.
- **Varredor** (`Sweeper`, S.6): procura os dados plantados, em todas as formas, em logs de processo, spans, eventos do barramento, e-mails, páginas do Confluence falso, objetos de todos os buckets e itens de todas as tabelas, exceto no bucket restrito `sandbox-meetings-raw`. O `PiiSweepIT` prova que ele vê um vazamento em cada tipo de armazenamento.
- **Ambiente.** O Docker Desktop tem ~10,4 GB (Hyper-V); o build nativo em contêiner pede ~8 GB (S.4 8f). A GPU é de 8 GB: Whisper, LitJev e llama.cpp se carregam em sequência. Já houve uma execução do gate morta por falta de memória nesta máquina (S.10).

### Achados das fases anteriores que o relatório deve consolidar
S.5 (8 observações do Stint 3a, entre elas o `stintreplyto`, o cancelamento externo e a janela de 15 min dos eventos adiantados), S6-1 a S6-5, S8-1 a S8-4, S9-1, S9-3, S9-4 e S10-1 a S10-4. Cada um entra no relatório com o estado atual e a proposta.

### O que o Stint garante e o gate precisa conferir
- **Decisão (`call: decision`).** O resultado da decisão entra no estado da instância no checkpoint; uma reexecução depois do checkpoint não chama o modelo de novo. Antes do checkpoint, o modelo é chamado outra vez. "A primeira decisão gravada vale" é isso: o teste deve distinguir os dois lados do checkpoint (ver 8c).
- **Fila de tarefas e outbox** (Fase 2, SDD 2.2): uma tarefa não confirmada volta a ficar visível e o fato do outbox é publicado depois do checkpoint, mesmo que o processo morra entre os dois. A 3a herda isso; o gate o confere nos domínios novos.
- **Revisão humana (3a.4)**: o `listen` por papel mantém a instância esperando; um engine morto retoma quando a resposta chega.

### Conclusão
O Stint em `500dfd1` cobre o S.11 sem mudança. O que falta é (1) ligar os cenários da 3a ao `gate.sh`; (2) pontos de morte nos domínios da 3a, do mesmo padrão do Billing; (3) cenários novos de caos e de carga sobre o pipeline; (4) a varredura de vazamento sobre o resultado desses cenários; (5) o build nativo dos domínios da 3a, que ainda não foi tentado; (6) a rodada manual que junta os modelos reais; (7) o relatório.

## 2. Problema e objetivo
**Problema.** Do S.5 ao S.10 cada domínio foi provado sozinho e no esqueleto com um caminho feliz e um de falha, sempre com stand-ins e sem nada morrendo. Falta provar o pipeline sob o que acontece de verdade: processos caindo no meio de uma chamada a modelo, projetos simultâneos, vazamento de dado pessoal pelos caminhos de falha, build nativo e o desempenho dos modelos locais. Também falta juntar tudo em uma evidência que o gate da fase consiga conferir.

**Objetivo.** Rodar o pipeline da 3a sob falha, carga e escrutínio de privacidade; medir os modelos reais; entregar um relatório que ligue cada SDD 3a.1–3a.7 do Stint a um cenário que o prova e que consolide os achados.

## 3. Requisitos
- **RF0 — Ponto de partida.** Conferir que `S.1–S.10` estão Implementados e subir o Stint para o `a52b20a` (8k): reinstalar sob `0.1.0-3a-a52b20a`, atualizar `pom.xml`, `infra/install-stint.sh` e README, e rodar a regressão antes de qualquer cenário novo. Se uma asserção existente tiver de mudar por causa do Stint novo, PARAR e pedir.
- **RF1 — Regressão da 3a num comando.** `gate.sh --regression-only` passa a cobrir S.1–S.10: `FlociBillingIT`, `FlociOrdersIT`, `RegressionIT`, `PipelineSkeletonIT`, `PortalRolesIT` e `PiiSweepIT`. Este critério fecha também o CA9 do S.10, adiado por decisão do Kayan para o fim da fase.
- **RF2 — Caos nos domínios da 3a**, com os papéis separados pelas flags de engine e worker:
  - a) o worker de privacidade morto no meio da redação de uma transcrição;
  - b) o engine de Negócio morto entre o checkpoint e a publicação do `design-published`;
  - c) o engine de Negócio morto com o desenho esperando a aprovação do Representante, retomado quando a resposta chega pelo portal;
  - d) o worker de Entrega morto no meio do `implement` (a chamada ao modelo está presa);
  - e) duas réplicas do engine de Entrega no mesmo lote;
  - f) o modelo fora do ar ou lento além do prazo: a instância não fica presa, vai ao humano do domínio.
  Em todos: nenhum registro, versão, evento ou e-mail duplicado visível; o estado final de tabelas, filas e MailHog anexado.
- **RF3 — A primeira decisão gravada vale.** Um modelo de decisão que responde diferente na segunda chamada: depois do checkpoint a decisão não muda; antes dele, vale a que for gravada primeiro e só uma.
- **RF4 — Carga.** M projetos simultâneos (padrão 6, configurável), cada um com um ciclo completo de dois SDDs. Resultado: M cadeias distintas, as contagens por projeto iguais às do esqueleto, nada sobrando, e p50/p95 por etapa (reunião gravada → desenho publicado → lote aprovado → SDD validado → ciclo fechado) só como observação.
- **RF5 — Zero vazamento sob falha e carga.** O varredor do S.6 roda, de fora, sobre o resultado do caos (RF2), da carga (RF4) e do esqueleto, incluindo os spans do Jaeger e os logs dos processos, e acha zero. O controle positivo (`PiiSweepIT`) continua verde na mesma execução.
- **RF6 — Nativo.** Construir e rodar como imagem nativa os domínios da 3a no caminho feliz do esqueleto (8e). O que não compilar ou não rodar vira achado com o log, não bloqueia o gate.
- **RF7 — Rodada manual com modelos reais.** Áudio sintético (Kokoro) → Whisper → LitJev → llama.cpp pelo pipeline, sobre as reuniões do corpus: acerto por decisão contra o gabarito, taxa de revisão humana, taxa de DLQ, latência por etapa e memória da GPU. Só relatório, sem limiar.
- **RF8 — Relatório do gate.** `docs/evidence/phase-3a-gate.md`: a tabela SDD do Stint → cenário → resultado → evidência; o que é real e o que é simulado; os achados do Stint e do sandbox (S.5–S.11) com estado e proposta; observações para a 3b; recomendação.

## 4. Fora de escopo
- A 3b e a 3c (bases de conhecimento, ponte entre domínios) e qualquer item deles.
- Mudar o Stint: uma falha vira achado e proposta.
- Metas de qualidade dos modelos e de latência: tudo é observação.
- Dado real, chave real ou provedor externo (continuam proibidos; a rodada manual usa só o que roda em loopback).

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | A regressão S.1–S.10 verde num comando (RF1); o esqueleto passa 3 vezes seguidas | `gate.sh --regression-only` → `S.11-CA1.txt` |
| CA2 | Os cenários de caos RF2 a–f verdes, cada um com o estado final anexado e sem duplicata visível | `PipelineChaosIT.a_…` a `f_…` → `S.11-CA2-{a..f}.txt` |
| CA3 | A decisão: a gravada primeiro vale, antes e depois do checkpoint | `PipelineChaosIT.first_decision_…`, `DecisionReplayTest` → `S.11-CA3.txt` |
| CA4 | A carga com M=6: M cadeias distintas, contagens por projeto, nada sobrando, p50/p95 por etapa | `PipelineLoadIT` → `S.11-CA4.txt` |
| CA5 | Varredor em zero sobre caos, carga e esqueleto, spans e logs inclusos; controle positivo verde | `PipelineSweepGateIT`, `PiiSweepIT` → `S.11-CA5.txt` |
| CA6 | Cada domínio da 3a tem seu desfecho de build nativo registrado (verde ou achado com log); os que compilam rodam o caminho feliz | `gate.sh --native` → `S.11-CA6.txt` |
| CA7 | A rodada manual produziu o relatório com as seis medidas de RF7 | `LocalStackIT`, `S.11-models.txt` |
| CA8 | O relatório do gate está completo, sem linha "a verificar", e os achados S.5–S.11 têm estado e proposta | `docs/evidence/phase-3a-gate.md` |

## 6. Mudanças de asserção a aprovar
Nenhuma prevista. Os cenários novos são classes novas (`PipelineChaosIT`, `PipelineLoadIT`, `PipelineSweepGateIT`); nenhuma asserção existente é tocada. Os pontos de morte entram desligados por padrão, com um teste unitário por domínio mostrando que a flag desligada não muda nada (como o `ChaosPointsTest` do Billing). Se alguma asserção existente tiver de mudar, paro e peço antes.

## 7. Plano de tarefas (etapa 2, após aprovação)
1. RF0: conferir o SHA, o README e os status; ligar o `PipelineSkeletonIT`, `PortalRolesIT` e `PiiSweepIT` ao `gate.sh` (RF1); copiar as evidências para `docs/sdd/evidence/` ao fim de cada etapa do gate (S10-4).
2. Pontos de morte nos domínios da 3a (8b) e seus testes unitários.
3. O modelo preso/lento e o de resposta variável como stand-ins de cenário (8c); o `PipelineChaosIT` a–f e o de decisão (RF2, RF3).
4. A carga (RF4) e a varredura sobre o resultado (RF5).
5. O nativo dos domínios da 3a (RF6, 8e).
6. A regressão completa e a rodada manual (RF7), em momentos separados, com a memória livre.
7. O relatório do gate (RF8), a seção 9, o README.

## 8. Questões em aberto: propostas para aprovação

### 8a. Quantas vezes a regressão
**Proposta:** a regressão inteira (Billing, Orders, `RegressionIT`, `PortalRolesIT`, `PiiSweepIT`) uma vez, e só o `PipelineSkeletonIT` três vezes seguidas, por ser o cenário mais sensível a corrida e o mais lento (~5 min cada). A Fase 2 repetiu tudo três vezes (S.4, CA2); aqui a regressão é lenta e a repetição de tudo custaria horas. **Escolha sua:** repetir tudo 3 vezes, como na Fase 2.

### 8b. Onde ficam os pontos de morte
**Proposta:** copiar o padrão do Billing para Business, Engineering e Delivery: `<domínio>.chaos.halt-before-publish=<tipo de evento>` (decorador do `EventPublisher` já existente) e `<domínio>.chaos.halt-in-task=<rota>` (decorador do handler), desligados por padrão. Para a morte no meio de uma chamada a modelo (RF2 a e d), nenhum código novo: o stand-in do modelo segura a resposta (uma trava no servidor de teste) e o cenário mata o processo de fora (`App.kill()`, `docker kill`/`TerminateProcess`). Só os pontos "entre o checkpoint e a publicação" exigem o ponto de morte dentro do processo.

### 8c. A primeira decisão gravada vale
**Proposta:** dois testes. (1) Teste de unidade/workflow (`DecisionReplayTest`, com o engine em memória): uma decisão que responde A na primeira chamada e B nas outras; o engine é reiniciado **depois** do checkpoint da decisão e o valor seguido é A, com uma só chamada ao modelo; reiniciado **antes**, o modelo é chamado de novo e o valor gravado é o da chamada que completou o checkpoint, uma só vez. (2) No `PipelineChaosIT`, o worker de Negócio morto com a decisão presa: depois de reiniciado, o desenho tem uma só versão. Se o Stint não se comportar assim, é achado, não ajuste.

### 8d. O tamanho da carga
**Proposta:** M=6 projetos por padrão (`-Dscenarios.projects`), cada um de sete JVMs compartilhadas (um processo por domínio, não por projeto), então a carga é por instância. Com 10 GB de Docker e sete JVMs de apps mais floci, MailHog e Jaeger, M maior que 6 tende a trocar o teste de carga por um teste de memória. **Escolha sua:** outro M.

### 8e. O escopo do nativo
**Proposta:** tentar o nativo de `sandbox-privacy`, `sandbox-business` (o único com o LangChain4j), `sandbox-records` e `sandbox-channels`, um a um (o build pede ~8 GB cada), e rodar o caminho feliz de um projeto com esses quatro em imagem nativa e os outros três na JVM. O que falhar vira achado com o log; só quem compila entra no CA6. `engineering`, `delivery` e `target` ficam na JVM por não carregarem nada novo da 3a (o `target` chama o Docker, que o contêiner nativo não tem).

### 8f. A rodada manual com modelos reais
**Proposta:** estender o `LocalStackIT` para todas as reuniões do corpus (as de negócio e as técnicas), em sequência para caber na GPU de 8 GB (Whisper, depois LitJev, depois llama.cpp), e a Entrega com o llama.cpp implementando dois SDDs sintéticos do alvo (como o S.10, 8i). Medir: acerto por decisão contra o gabarito, taxa de revisão humana (`uncertain`), taxa de DLQ, latência por etapa e a memória da GPU (`nvidia-smi`). O áudio é gerado com o Kokoro (dados sintéticos).

### 8g. A varredura sobre o resultado dos cenários
**Proposta:** o `PipelineSweepGateIT` é uma fase final que roda o `Sweeper` do S.6 depois do caos e da carga, sobre o que sobrou no floci, no MailHog, no Confluence falso e nos logs, e sobre os spans do Jaeger dos traces da execução (pela API de consulta, por `stint.chainId`). É a mesma classe, sem reduzir a cobertura.

### 8h. O CA9 do S.10
**Proposta:** o CA1 deste SDD é a regressão que o S.10 (CA9) adiou. Quando ele ficar verde, atualizo o registro do S.10 (CA9 e a seção de pendências) na mesma passada.

### 8i. Tempo e memória do gate
**Proposta:** o gate inteiro leva horas e já foi morto por falta de memória nesta máquina. Rodo cada etapa por vez (regressão, caos, carga, nativo, modelos), nunca duas ao mesmo tempo, copio as evidências ao fim de cada uma antes do próximo `mvn clean` (S10-4) e só relanço uma etapa morta quando você pedir. A compactação do VHDX do Docker no C: continua pendente e é sua.

### 8j. Expectativas de achados
- O nativo do `sandbox-business` com o LangChain4j pode não compilar; seria achado do Stint (`stint-worker-langchain4j`) ou do sandbox.
- O 2B do LitJev erra a maior parte das decisões (S6-2, S8-2, S9-1): a taxa de revisão humana na rodada manual será alta, e isso é o dado.
- O Whisper `distil-small.en` corta o final das falas (S6-1): o recall do filtro sobre áudio real será menor que sobre o texto.
- A carga pode mostrar um gargalo no portal ou no registro (um processo cada); é observação.

### 8k. O SHA do Stint (decidido pelo Kayan em 21/10/2026: opção 2)
Na análise o sandbox estava em `500dfd1`, e a branch `phase-3a/ai-decision-workers` do Stint já estava em `a52b20a` (SDDs 3a.8 a 3a.11: `for` concorrente com junção e cascata de cancelamento; endereço de resposta e transporte de roteamento; reabrir a espera consumida no mesmo save; cancelamento de fora). As branches `phase-3b/knowledge-base` e `phase-3c/domain-bridge` contêm esses commits, então os S.12 a S.15 não podem ficar no `500dfd1`.
**Decisão:** subir agora, antes do gate, para que ele valide o que vai a PR e os S.12–S.15 herdem um sandbox já validado. O gate da 3a passa a valer para o `a52b20a`.
**Consequências:** a regressão S.1–S.10 roda primeiro no SHA novo; os artefatos que o sandbox copia do Stint (esquemas das tabelas e filas, o esquema do `meeting-recorded`, o servidor do Whisper) não mudaram entre os dois SHAs (conferido por `git diff`). Os workarounds dos achados do S.5 que o Stint corrigiu (`stintreplyto`, cancelamento externo, reabertura da espera) **não** são removidos aqui: entram como achados com estado "corrigido no Stint, ainda não usado" no relatório, e a remoção é um SDD próprio.

## 9. Registro de validação

**Resultado (22/10/2026).** CA1 a CA8 fechados; evidências em `docs/sdd/evidence/S.11-*.txt` e o relatório em `docs/evidence/phase-3a-gate.md`.

| CA | Resultado |
|---|---|
| CA1 | Verde: Billing 5/5, Orders 6/6, `RegressionIT` 3/3, `PortalRolesIT`, `PiiSweepIT`, esqueleto x3 (`S.11-CA1.txt`) |
| CA2 | Verde, caos a–g |
| CA3 | Verde via `PipelineChaosIT.g` |
| CA4 | Verde, 6 projetos, 28,3 s |
| CA5 | Verde |
| CA6 | privacy, records e channels nativos verdes; business não compila |
| CA7 | Medido (`S.11-models.txt`) |
| CA8 | Relatório entregue |

**Desvios.**
- RF2f: a spec dizia que a dúvida de redação vai ao humano do domínio; o comportamento real (S.6) encerra com `redaction-doubt`. O cenário prova fail-closed (achado S11-1).
- `DecisionReplayTest` (8c) não foi escrito; o CA3 foi coberto pelo cenário g.
- Nativo: engineering, delivery e target não foram tentados.
- `Scenario.reset` passou de 5 para 10 min (o reset leva 4–5 min).

**Achados.** S11-1 (dúvida sem revisão humana), S11-2 (registro Jackson no nativo, corrigido em privacy e channels), S11-3 (business não compila em nativo).

**Pendências.** Aprovação (Validado) é do Kayan; commits locais após `8483c4c` não foram enviados.
