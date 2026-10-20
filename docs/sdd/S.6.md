# SDD S.6 — Privacidade: filtro de PII em camadas, sensibilidade, política e varredor

| Campo | Valor |
|---|---|
| ID | S.6 |
| Status | **Implementado** (spec aprovada por Kayan em 20/10/2026, sem escolha explícita: valem as recomendações, 8a como proposta e 8h opção 1, sem NER) |
| Depende de | S.5 (Implementado). Stint 3a.5 (compliance de entrada) e 3a.6 (marco), `500dfd1` |
| Bloqueia | S.7 a S.11 (todo domínio chama o filtro) |
| Branch | `phase-3a-validation` (a mesma do S.5; os PRs da fase são empilhados) |
| Commits analisados | sandbox `f12bf94`+ · Stint `500dfd1` |
| Data da análise | 19/10/2026 |
| Notion | A página do S.6 (rascunho de 12/10/2026) foi lida depois da aprovação: seus 6 requisitos e 4 critérios estão cobertos por esta spec. Dois pontos pedem o limite real: "criptografados" (o floci aceita SSE e não o aplica; o sandbox declara e confere a classe e a retenção) e "blob bruto expira na retenção" (provado com relógio controlável, não pelo lifecycle do S3, que só existe na AWS) |

## 1. Análise

### O problema, medido
O CA7 do S.5 rodou uma reunião em áudio real. O Whisper escreveu a PII falada de formas que o redator de referência do Stint não reconhece:
- o nome `SYNTH-PII-2011 Rivera` virou `synth PI 2011 Rivera` (a transcrição perde uma letra e a pontuação);
- o e-mail `SYNTH-PII-2012@example.com`, ditado, virou só `at.`;
- o telefone, ditado, vira dígitos por extenso ou agrupados ("plus 5511 98022 4022").

O `redact` devolveu `counts: {}` e o `doubt: false`: **nada foi redigido e ninguém desconfiou**. É o achado grave do 3a.6 (9.5), agora medido no pipeline. Hoje, com dado real, isso iria para o documento de desenho, para o Confluence e para o e-mail.

### O que o Stint já oferece (`500dfd1`)
- `LayeredRedactor` compõe `RedactionLayer`s (`name()` e `apply(text, scope)` → texto, contagens por tipo e `doubt`). A dúvida de qualquer camada é a dúvida da redação.
- `PatternLayer` (e-mail, cartão, documento, IP, telefone, endereço) e `TermListLayer` (lista de termos).
- `call: redact` com `onDoubt` e `lowersTo`; a dúvida baixa a sensibilidade só se não houver, e leva ao caminho humano.
- Política de sensibilidade (3a.5): níveis `restricted`/`confidential`/`internal`, rotas permitidas por nível, provedores só locais para dado confidencial, retenção por classe, auditoria (`DynamoDbAuditLog`).
- O LitJev, perfil local, responde `noul` (sim/não) e devolve confiança.

### O que o sandbox tem (S.5)
- `sandbox-privacy`: serviço de tarefa remota (`redactor`) por chamador, hoje só com o redator de referência. Os três domínios já o chamam e já têm `onDoubt`.
- `corpus/pii-forms.json`: as formas normalizadas de cada marcador, escritas e faladas, com a forma observada no CA7.
- `Corpus.leaksIn(texto)`: o varredor de um texto (normaliza e procura as formas).
- O esqueleto já prova que **nenhum marcador escrito** vaza no barramento, nos spans e nos logs; falta provar o mesmo para o texto que sai do **filtro** com a fala real, e em todos os depósitos (MailHog, Confluence falso, S3, DynamoDB).

### Conclusão
O S.6 troca o redator de referência por um filtro em camadas dentro do `sandbox-privacy` (sem mudar o Stint, só implementando a interface `RedactionLayer`), prova com áudio real que nada vaza, e transforma o varredor numa guarda de gate. O que o filtro não sabe fazer fica explícito (8g): nome real fora de qualquer lista só se pega por heurística, `noul` e revisão humana.

## 2. Problema e objetivo
**Problema.** A PII falada atravessa o filtro. Sem o filtro em camadas, S.8 a S.10 não podem rodar com áudio, e a validação externa da Fase 3a não prova privacidade nenhuma.

**Objetivo.** Um filtro em camadas que falha fechado, uma política de sensibilidade aplicada nos três workflows e um varredor que, sobre áudio real, prova **zero vazamento** com recall mínimo por tipo.

## 3. Requisitos
- **RF1 — Camadas** (8a), em `sandbox-privacy`, na ordem:
  1. padrões determinísticos: os do Stint (`PatternLayer`) mais os do sandbox;
  2. forma falada: e-mail com "at" e "dot", telefone e números ditos por extenso ou agrupados, letras soletradas;
  3. nomes: lista sintética, normalizada (caixa, pontuação, dígitos por extenso e espaços entre letras), e heurística de apresentação ("I am", "my name is", "this is");
  4. pergunta ao LitJev local: "ainda há dado pessoal?" (`noul`), nunca a única camada.
- **RF2 — Falha fechada** (8b). Dúvida de qualquer camada, ou `noul` acima do limiar, ou LitJev indisponível, resulta em `doubt: true`: o texto fica restrito, só provedor local e revisão humana (o `onDoubt` dos workflows).
- **RF3 — Política** (8c). Os três workflows declaram a sensibilidade e as rotas por nível; dado confidencial nunca vai a provedor remoto; um teste prova que um adapter remoto é recusado para texto confidencial.
- **RF4 — Dados em repouso** (8c). Áudio e transcrição bruta ficam em classes `restricted` com retenção de 7 dias e só o `transcribe` e o `redactor` os leem; teste de que nenhuma outra rota consegue lê-los.
- **RF5 — Varredor** (8d) em `sandbox-scenarios`, que procura os marcadores, em todas as formas de `pii-forms.json`, em: logs de todos os processos, spans do Jaeger, eventos da `sandbox-spy`, e-mails do MailHog, páginas do Confluence falso, buckets S3 e tabelas DynamoDB, exceto as classes restritas declaradas.
- **RF6 — Corpus real do Whisper** (8e). As 14 reuniões do corpus transcritas pelo Whisper local, com os transcritos guardados como fixtures reproduzíveis (hash, modelo e versão), e o filtro rodado sobre elas.
- **RF7 — Métricas** (8f): recall por tipo (e-mail, telefone, nome) sobre os marcadores plantados, e preservação do texto não pessoal (o filtro não pode esvaziar o documento).
- **RF8 — Auditoria.** Cada redação deixa na `audit` do domínio só ids, camadas, contagens e a decisão sobre a dúvida; nenhum valor.

## 4. Fora de escopo
- Registro de documentos (S.7), lógica dos domínios (S.8–S.10), gate da 3a (S.11).
- Mudar o Stint: o que faltar vira achado.
- Detecção de nome **real** arbitrário: sem modelo de NER nesta spec (8h).
- Qualquer dado real: o gate do DPO e do jurídico continua pendente (3a.5, seção 10).

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | Cada camada tem teste próprio, incluindo as formas faladas observadas no CA7 e as geradas por regra; o filtro compõe todas | `*LayerTest`, `LayeredFilterTest` |
| CA2 | Sobre os 14 transcritos do Whisper real: **zero** marcador (qualquer forma) no texto redigido, e recall de 100% por tipo nos marcadores plantados | `RealTranscriptsIT`, `S.6-CA2.txt` |
| CA3 | Falha fechada: cada camada em dúvida, `noul` alto e LitJev fora do ar dão `doubt: true`, e o workflow vai ao caminho humano; nunca "limpo" | `FailClosedTest`, `S.6-CA3.txt` |
| CA4 | Política: texto confidencial recusado por provedor remoto; áudio e transcrição bruta só legíveis pelas duas rotas; retenção de 7 dias declarada | `PolicyTest`, teste de rota |
| CA5 | Esqueleto do S.5 de novo, agora com o filtro em camadas e a **fala real** de uma reunião: o varredor acha zero marcador em todos os depósitos de RF5 | `PipelineSkeletonIT` + `PiiSweepIT`, `S.6-CA5.txt` |
| CA6 | O varredor **detecta** um vazamento plantado (um marcador escrito num log, num e-mail, numa página e num item do DynamoDB): sem isso, "zero" não prova nada | `PiiSweepIT.sweeper_sees_a_planted_leak` |
| CA7 | Preservação: pelo menos 90 % das palavras não pessoais do transcrito sobrevivem à redação; o documento de desenho continua legível | `RealTranscriptsIT` |
| CA8 | Os testes do S.1 a S.5 continuam verdes | `gate.sh --regression-only` |

## 6. Plano de tarefas (etapa 2, após aprovação)
1. `SpokenFormLayer`, `NameLayer` e `PresentationLayer` (RF1.2 e RF1.3), com testes das formas do corpus.
2. A camada `noul` com a porta de decisão (falso no CI, LitJev na execução manual) e a falha fechada.
3. O filtro em camadas no `sandbox-privacy`, substituindo o redator de referência, e `PrivacyHandlersTest` atualizado (a asserção que registrava "a forma falada escapa" passa a exigir que ela **não** escape, uma mudança de asserção que você já esperava e aprova aqui).
4. Política e retenção nos três workflows (RF3, RF4) e seus testes.
5. Fixtures do Whisper: transcrever o corpus (WSL, pilha local) e guardar com hash.
6. O varredor e o `PiiSweepIT` (RF5, CA6).
7. O esqueleto com fala real (CA5) e a regressão (CA8).
8. Seção 9, README e o Log.

## 7. Questões em aberto: propostas para aprovação

### 8a. As camadas e a ordem (obrigatória)
**Proposta.** A ordem do RF1, porque cada camada deixa pseudônimos que as seguintes não tocam, e a mais cara (`noul`) só vê o que sobrou:
1. `PatternLayer` do Stint, mais padrões do sandbox (CPF/documento e telefone em formatos do corpus).
2. **Forma falada** (`SpokenFormLayer`): reconhece
   - e-mail ditado: `<tokens> at <tokens> dot <tld>`, com a pontuação que o Whisper põe ("at.", "example. calm.");
   - sequência de pelo menos 7 dígitos, como dígitos, por extenso ("five five one one…") ou agrupados, com "plus" opcional;
   - letras soletradas ("P I I", "p-i-i");
   e troca pelo pseudônimo do tipo.
3. **Nomes** (`NameLayer`): lista sintética normalizada. A normalização é por caracteres alfanuméricos, sem caixa, com dígitos por extenso equivalentes, e casa por cima de espaços e pontuação (`synth PI 2011` = `SYNTH-PII-2011`, onde o Whisper perdeu um `I`: aceito até uma letra a menos). Mais a heurística de apresentação: o termo após "I am", "my name is", "this is", "call me" em maiúscula inicial.
4. **`noul`** (`LlmCheckLayer`): pergunta ao LitJev, sobre o texto já redigido, "ainda há dado pessoal?". Limiar de 0,5: acima disso, `doubt`.

**Por que não só o LitJev.** O Qwen3.5-2B é pequeno e foi confiante nos erros (3a.2b); como única camada seria pior que as regras. Como quarta, só pode acrescentar dúvida, nunca tirar.

### 8b. Falha fechada
- Dúvida de qualquer camada, `noul` ≥ 0,5, ou LitJev fora do ar ou sem resposta no prazo: `doubt: true`.
- O workflow tem `onDoubt: doubt` (S.5): o ramo termina em `redaction-doubt` e o texto **não** segue para a decisão nem para o gerador. A revisão humana da dúvida (um papel que vê o texto bruto) é do S.8/S.9; aqui o ramo existe e é provado vazio de conteúdo.
- O texto de uma redação em dúvida continua restrito: nunca baixa a sensibilidade (`lowersTo` só vale sem dúvida).

### 8c. Política e dados em repouso
- Os três workflows já declaram `x-stint-policy`. O S.6 acrescenta as rotas novas ao nível `confidential` e confere por teste que `transcribe` e `redactor` são as únicas que leem `restricted`.
- **Provedor remoto:** um adapter de decisão que se declara remoto é recusado para texto confidencial (política do Stint); o teste usa um falso remoto.
- **Retenção:** `raw-audio` e `raw-transcript` em 7 dias (já declaradas). O efeito real do lifecycle do S3 só existe na AWS: o floci aceita a regra e não a aplica (gate já pendente do 3a.5).

### 8d. Varredor
- **Onde procura:** os logs de todos os processos, os spans do Jaeger, a `sandbox-spy`, o MailHog, o Confluence falso, os buckets (S3) e as tabelas (DynamoDB) do pipeline.
- **O que fica de fora, declarado:** os buckets `sandbox-meetings-raw` (classes `raw-audio` e `raw-transcript`), onde o conteúdo restrito deve estar.
- **Como procura:** o texto de cada item normalizado e comparado com todas as formas de `pii-forms.json` (≥ 10 caracteres, para não bater por acaso em ids e hashes).
- **Prova de que enxerga (CA6):** planta um marcador escrito em um item de cada depósito e exige que o varredor o ache.

### 8e. Transcritos do Whisper real como fixtures
- Transcrever os 14 áudios com o `distil-small.en` em CPU (cerca de 2 a 4 s cada) e guardar em `corpus/transcripts/<id>.json` com o hash do áudio, o modelo e a versão. Os dados são sintéticos. **A confirmar na implementação:** se o Whisper dá o mesmo texto para o mesmo áudio em duas execuções (decodificação por feixe em CPU deveria dar, mas o S.5 só viu uma execução real por reunião). A tarefa 5 transcreve cada áudio duas vezes e compara; se variar, as fixtures fixam uma execução e o teste do CA2 usa só elas, sem regenerar.
- O `RealTranscriptsIT` roda sem Docker e sem modelo: lê as fixtures.

### 8f. Métricas
- **Recall** por tipo = marcadores plantados do tipo que não aparecem, em nenhuma forma, no texto redigido ÷ marcadores plantados do tipo. Exigido: 100% nos três tipos (o conjunto é pequeno e todo marcador é plantado; um menos que 100% é um vazamento).
- **Preservação** = palavras não pessoais do transcrito que sobrevivem ÷ palavras não pessoais. Exigido: ≥ 90 %.
- O relatório também lista o que o `noul` marcou, com a confiança, para calibração futura.

### 8g. Limites que a spec declara em vez de esconder
1. O recall é medido **sobre marcadores plantados**: prova que o filtro pega o que foi previsto, não que pega tudo. Um nome real que não esteja na lista e não se apresente por "I am" só pode ser pego pelo `noul` ou pela revisão humana.
2. O `noul` de um modelo de 2 bilhões de parâmetros é fraco; ele reforça, não decide.
3. Telefone falado com poucos dígitos ou misturado a outros números pode ser confundido com quantidade; o limite de 7 dígitos tem falso positivo, e a preservação (CA7) o mede.
4. Nada disso substitui o gate do DPO e do jurídico (LGPD). Dado real continua proibido.

### 8h. NER local (escolha do Kayan)
| Opção | Prós | Contras |
|---|---|---|
| **(1) Sem NER agora (recomendada)** | Lista + heurística + `noul` + revisão humana já fecham o escopo do gate; sem mais um runtime | Nome real fora da lista depende do `noul` e do humano |
| (2) NER local num sidecar Python (spaCy `en_core_web_sm`, MIT, ou GLiNER, Apache-2.0) em loopback | Pega nomes fora da lista | Mais um processo e um modelo (CPU/GPU já no limite), a licença e a latência a medir, nova superfície de falha |
| (3) NER dentro da JVM (OpenNLP, Apache-2.0) | Sem sidecar | Qualidade inferior em fala transcrita; modelo a baixar e fixar |

**Recomendação: (1)**, com a opção (2) como SDD próprio se o recall em nomes reais, medido na revisão humana, ficar abaixo do aceitável. O S.6 deixa a interface `RedactionLayer` pronta para receber o NER.

### 8i. Achados esperados no Stint (a confirmar na implementação)
- O `RedactionHandler` não expõe o texto que cada camada viu; o diagnóstico por camada precisa de um relatório próprio no sandbox.
- O `LayeredRedactor.reference()` não traz a camada falada: a camada fica no sandbox. Se o Stint quiser o filtro falado, é proposta de SDD.

## 8. Registro de validação

**Ambiente.** O do S.5 (Windows 11, Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2, Docker Desktop, Stint `500dfd1` sob a versão `0.1.0-3a-500dfd1`), mais a pilha local do WSL2 para o Whisper (`distil-small.en`, CPU) e, na medição do `noul`, o LitJev (Qwen3.5-2B, GPU).

### 8.1 Commits (`phase-3a-validation`, só local; ainda sem push)
| Commit | O que faz |
|---|---|
| `c3674dc`, `a75b3c7` | spec e aprovação (8a como proposta, 8h opção 1, sem NER) |
| `e070089`, `8b0799e` | `SpokenFormLayer`, `NameLayer`, `PresentationLayer`, `LlmCheckLayer` com a porta `PersonalDataCheck` e o `LayeredFilter` no `sandbox-privacy` |
| `ab66850`, `67a71de` | ordem das camadas e ajuste sobre o texto real do Whisper, com o critério de resíduo |
| `c9632da` | `Sweeper`, `PiiSweepIT` e o texto real do Whisper nos transcritores falsos do CI |
| `d3561c7` | testes da dúvida nos dois workflows de reunião; cobertura da varredura no relatório |
| `18aa91e`, `86327c0` | medição do `noul` real; o limiar de observação; expiração do blob bruto |

### 8.2 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** Cada camada tem teste próprio (e-mail ditado em três grafias, número ditado, soletrado, nome com uma letra a menos, nome apresentado, dia que não é nome). O filtro composto, sobre as 14 reuniões em três formas (escrita e duas faladas), deixa zero marcador e não põe nenhuma em dúvida | `LayersTest` (7), `FilterOverCorpusTest` (4) |
| CA2 | **Verde, com a ressalva de 8.3.** Sobre os 14 transcritos do Whisper real: zero marcador pelo varredor e **zero resíduo** pelo critério estrito (`synth*`/`sinthe*`, o sobrenome sintético e o id de 4 dígitos do marcador). Recall de 100% por tipo: nome 14/14, e-mail 6/6, telefone 3/3. Pior preservação: 95%. Nenhuma redação em dúvida com a verificação do modelo | `RealTranscriptsTest`; `S.6-CA2.txt` |
| CA3 | **Verde.** Dúvida de qualquer camada, verificação ≥ limiar, no limiar, falha do modelo, NaN e resposta fora de [0, 1] resultam em `doubt: true`; abaixo do limiar, não. O modelo vê o texto como as camadas o deixaram e nunca o altera. Nos dois workflows, a dúvida para a reunião logo depois da transcrição: só o `transcribe` foi despachado, nada no barramento, nenhuma revisão pedida | `FilterOverCorpusTest`, `BusinessDesignWorkflowTest`, `EngineeringWorkflowsTest`; `S.6-CA3.txt` |
| CA4 | **Verde.** Política dos três workflows: restrito só chega a `transcribe` e `redactor`; confidencial não chega ao `transcribe`; a Entrega não toca dado bruto. Um provedor remoto é recusado para dado confidencial e restrito (e sem nível, que vale restrito), um local não. As classes `raw-audio`, `raw-transcript` e `restricted` são de 7 dias, e a do handler é a da política. Um blob bruto some depois de 7 dias (relógio controlável; o lifecycle do S3 só existe na AWS) | `PolicyTest` (5 no Negócio, 4 na Engenharia, 1 na Entrega) |
| CA5 | **Verde.** O esqueleto do S.5 de novo, com o **texto real do Whisper** nos transcritores do CI, o filtro em camadas e o varredor sobre todos os depósitos: zero achado. O que a varredura cobriu: 50 eventos do barramento, 1 página do Confluence, 153 itens do DynamoDB, 121 linhas de log, 10 e-mails, 66 objetos do S3 e 143 spans | `PipelineSkeletonIT`; `S.6-CA5.txt` |
| CA6 | **Verde.** O varredor acha um dado plantado em cada tipo de depósito (log, span, evento, e-mail, objeto S3, item do DynamoDB), em formas diferentes (escrita, falada, fragmento de marcador garbado), não aponta o bucket restrito `sandbox-meetings-raw` e dá zero num emulador limpo | `PiiSweepIT`; `S.6-CA6.txt` |
| CA7 | **Verde.** Pelo menos 90% das palavras que não são dado pessoal sobrevivem: o pior transcrito mantém 95%, e quase todos 100% | `RealTranscriptsTest` |
| CA8 | **Verde.** `gate.sh --regression-only`: build completo (todos os módulos), `FlociBillingIT` 5/5, `FlociOrdersIT` 6/6 e `RegressionIT` 3/3 (S.1–S.5), PASSED em 20/10/2026 | `gate.sh --regression-only` |
| 8f | **Medido.** O `noul` do LitJev (Qwen3.5-2B) sobre os 14 transcritos, antes e depois das camadas: 8 de 14 em ≥ 0,50 nos dois casos, e `o1` sobe de 0,47 para 0,71 depois de redigido. **Ele não distingue texto redigido de texto bruto.** Chamada mais lenta: 1,9 s | `LitJevCheckTest` (`-Dprivacy.litjev=true`); `S.6-noul.txt` |

### 8.3 Ressalvas que valem tanto quanto os números
1. **As camadas foram ajustadas nos mesmos 14 transcritos em que são testadas.** O CA2 mostra que o filtro cobre o que o Whisper escreveu **nesses** áudios; não é uma medida fora da amostra. Um áudio novo, com outra voz, outro modelo de fala ou outro nome, pode escrever o dado de uma forma que nenhuma camada conhece. O que dá alguma garantia fora da amostra é a falha fechada (o `noul`, se um dia for confiável, e a revisão humana), não o recall deste corpus.
2. **O Whisper não preserva o dado, nem o marcador.** Ele cortou o final de várias falas (`My address is Sinthe-I-2032 at example.`, `Reach.....`, `at.`: o e-mail ou o telefone muitas vezes nem chega à transcrição) e escreveu o marcador sintético de formas variadas (`synth PI 2018-1`, `Sinthe-I-2-1-131`, `2016` por `2061`, `since P.I. 2041`). Por isso o critério "o varredor não vê o marcador" sozinho **enganava**: um nome meio redigido passava. O critério passou a incluir o resíduo (8.2, CA2), e a comparação do varredor com as formas listadas ficou como segunda linha.
3. **Os marcadores são sintéticos e garbados por uma máquina; nomes reais seriam outra coisa.** O `NameLayer` compara com uma lista; um nome real que não esteja nela e que ninguém apresente por "I am…" só seria pego pelo `noul` ou pelo humano (8g da spec). Nada aqui mede esse caso, e sem NER (8h, opção 1) ele continua aberto.
4. **O recall por tipo conta só o dado plantado que chegou ao filtro de alguma forma.** Onde o Whisper cortou o final e o e-mail ou o telefone sumiu da transcrição (`scanner sees it raw: no` no relatório), não há o que redigir; o relatório mostra isso por dado.

### 8.4 Desvios e decisões da implementação (para sua revisão)
- **Ordem das camadas: forma falada primeiro, depois os padrões.** Foi achado: a camada de padrões do Stint fica em dúvida diante de uma sequência de dígitos que não classifica ("5511 98022 4022"), e essa dúvida permanece mesmo que uma camada seguinte a redija. Com a forma falada antes, o número já é um pseudônimo quando os padrões olham. O custo é um IP ou um cartão escrito em dígitos ser rotulado como número, não como ele mesmo; a redação é a mesma.
- **Camadas a mais que a spec:** a lista dos sobrenomes dos nomes listados (`TermListLayer`), antes da apresentação, e a **absorção** dos fragmentos entre "I am" e o nome já redigido, porque o Whisper garbla o marcador em pedaços.
- **Duas edições de tolerância** para nomes de 15 ou mais caracteres, com a janela de menor distância e, no empate, a mais longa. O primeiro ajuste (qualquer janela dentro da tolerância) engolia a palavra seguinte ("PERSON read the design"): achado na leitura do texto redigido, não pelos testes.
- **O `noul` fica em observação com o LitJev (limiar 1,01).** A spec propunha 0,5 (8a). A medição de 8f mostra que com 0,5 ele mandaria 8 de 14 reuniões limpas à revisão humana. A camada continua ligada e continua falhando fechada (uma chamada que falha é dúvida), mas só observa; o relatório traz a probabilidade. No CI, o `CorpusKeyCheck` (um falso que conhece os marcadores) faz o papel de um modelo bom, e os testes provam as camadas determinísticas, não o modelo.
- **Asserção trocada, como você aprovou:** o `PrivacyHandlersTest` registrava que a forma falada escapava do redator de referência; agora exige que ela não escape.
- **`reset.sh` esvazia os buckets do pipeline** (`sandbox-meetings-raw`, `-documents`, `-records-docs`, `-target-runs` e os três `-blobs`). Antes mantinha os objetos (decisão do S.4), e as sobras de rodadas antigas contaminavam o varredor. O `sandbox-facts` do S.4 continua como estava.
- **A API de pseudônimo do Stint `500dfd1`** é `scope.pseudonyms().label(instanceId, tipo, valor)`; o `Scope.label` do Stint mais novo (3b.2) não existe nessa versão. O sandbox usa o que o commit fixado oferece.
- **O endpoint `/dev/confluence/pages` aceita `?bodies=true`** para o varredor ler o corpo das páginas (dados sintéticos, endpoint de desenvolvimento).
- **Estratégia de fixtures:** o Whisper deu o mesmo texto nas duas transcrições de cada um dos 14 áudios (`repeatable: true`), então as fixtures são estáveis; o teste do CA2 só lê as fixtures, sem Docker e sem modelo.

### 8.5 Achados (nada foi alterado no Stint)
| # | Achado | Proposta |
|---|---|---|
| S6-1 | O `distil-small.en` corta o final de várias falas e garbla até um marcador soletrado | Medir com um Whisper maior (`small.en`/`medium.en`) e com áudio mais longo antes de qualquer dado real; guardar a duração e o corte no relatório |
| S6-2 | O `noul` do LitJev Qwen3.5-2B não distingue texto redigido de bruto (8 de 14 acima de 0,5 nos dois) | Modelo maior ou calibrado antes de usá-lo como porta; até lá, observação |
| S6-3 | Uma camada em dúvida (os padrões, diante de dígitos que não classifica) mantém a redação em dúvida mesmo que uma camada seguinte a resolva | Proposta ao Stint: a dúvida de uma camada poderia ser "resolvida" por camada posterior que a redige; hoje o sandbox contorna pela ordem |
| S6-4 | O Stint não traz uma camada de forma falada; o filtro falado é do sandbox | Se o Stint quiser, é proposta de SDD (e-mail e número ditos, soletrado) |
| S6-5 | O `LayeredRedactor` não relata o que cada camada fez ao texto, só contagens | O diagnóstico por camada precisou de um relatório próprio no teste |

### 8.6 Fora do que o SDD pedia
- NER local (8h, opção 1): fora, como aprovado.
- Detecção de nome real arbitrário: fora; limite declarado em 8.3.
- Efeito real do lifecycle do S3 e o SSE-KMS: só na AWS (gate pendente do 3a.5). Em 8c, o sandbox declara a classe, a retenção e as rotas, e prova a expiração com relógio controlável.
- Dado real: continua proibido até o gate do DPO e do jurídico.

### 8.7 Pendências
- Push da branch e atualização do PR #2, com o seu pedido.
- Notion: a página do S.6 e o Log.
