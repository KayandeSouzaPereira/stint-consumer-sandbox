# SDD S.9 — Domínio de Engenharia: refinamento, SDDs ou dúvidas

| Campo | Valor |
|---|---|
| ID | S.9 |
| Status | **Implementado** (spec aprovada por Kayan em 21/10/2026, com um "aprovado" sem escolha: valem as propostas 8a a 8g) |
| Depende de | S.5, S.6, S.7 e S.8 (Implementados). Stint `500dfd1` |
| Bloqueia | S.10 (a Entrega consome o `sdd-batch-approved`), S.11 (gate da 3a) |
| Branch | `phase-3a-validation` (os PRs da fase são empilhados) |
| Commits analisados | sandbox `dd00a77` · Stint `500dfd1` |
| Data da análise | 21/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.9): 2 requisitos e 2 critérios, todos cobertos abaixo |

## 1. Análise

### O que o S.5 já entregou
A Engenharia tem dois workflows:
- `refinement`: o `design-published` do Negócio vira o `refinement-requested` (o convite à reunião técnica);
- `technical-meeting`: `meeting-recorded` (estágio técnico) → `transcribe` → `redact` (`onDoubt`) → tipo da reunião (`onUncertain` ao `tech-rep`) → prioridade → desfecho (`sdds` ou `questions`, `onUncertain` ao `tech-rep`) → dúvida bloqueante (`noul`) → ou **dúvidas** (escritas, registradas, `questions-sent`, que reinicia o ciclo no Negócio com `nextCycle`) ou **SDDs** (escritos, o lote e cada SDD registrados, aprovação item a item do `tech-rep`, `sdd-batch-approved`).

Tudo isso corre com modelos falsos que **leem o gabarito**: o `write-sdds` do CI não gera nada, copia `expected.sdds` do roteiro (título e "Must cover"). O gerador é o que o S.5 deixou para o S.9.

### O que o rascunho pede e ainda falta
| Pedido do rascunho | Estado hoje |
|---|---|
| SDDs no formato do processo (análise, requisitos, critérios de aceite), um documento por SDD no registro | o registro por SDD existe (S.7); o **formato não existe**: o SDD do CI é um título e uma lista "Must cover" |
| Aprovação parcial: o Representante Técnico pode reprovar SDDs individuais | **falta**: a revisão por itens existe, mas qualquer item reprovado leva o workflow a `rejected` e o lote inteiro morre |
| Dúvida bloqueante gera e-mail e reinicia o ciclo no Negócio, na mesma cadeia | existe por construção e é provado no esqueleto (`p2`); falta um teste do domínio por roteiro |
| Roteiro completo gera o número de SDDs do gabarito | só `p1` é testado; `p4` (3 SDDs), `p5` (1) e `p2` ciclo 2 só passam no esqueleto |
| (implícito) a aprovação esquecida | **falta**: a aprovação tem prazo e lembrete, mas, vencido o prazo, não está definido o que acontece (o mesmo problema que o S.8 fechou no Negócio) |

### O que o S.7 e o S.8 mudam
- A Engenharia **não pode ler o desenho** pelo registro: `read-record` só atende o domínio dono (Negócio). O gerador de SDDs usa a transcrição redigida da reunião técnica e cita o desenho por ponteiro (`designRecordId`), sem lê-lo. É um limite real, declarado em 8d.
- O registro versiona: um SDD reescrito vira a v2 do mesmo `documentId`, com `supersedes`. O lote também pode ter uma v2.
- A Entrega lê o **manifesto do lote** (`batchRef`) para saber quais SDDs correr. Numa aprovação parcial, o manifesto precisa listar só os aprovados.

### Conclusão
O S.9 dá formato e validador aos SDDs, deixa o `tech-rep` reprovar SDDs individuais (a reescrita volta ao mesmo SDD, até 3 versões; o que ficar de fora não vai à Entrega), fecha o prazo da aprovação como no S.8 (escala, avisa os leads e termina documentado), e prova cada roteiro técnico do corpus. Não muda o Stint.

## 2. Problema e objetivo
**Problema.** O gerador de SDDs é um espelho do gabarito e uma reprovação mata o lote inteiro; uma aprovação esquecida deixa o ciclo parado sem dono.

**Objetivo.** O domínio de Engenharia completo: cada reunião técnica do corpus chega ao fim certo, os SDDs seguem o formato do processo, a reprovação é por SDD e o que não for aprovado nunca chega à Entrega.

## 3. Requisitos
- **RF1 — Caminho por roteiro** (8a). Para cada um dos sete roteiros técnicos do corpus, o caminho (tipo, desfecho, dúvida bloqueante, número de SDDs ou de perguntas, fim) é o do gabarito, com os modelos falsos.
- **RF2 — Formato do SDD** (8b). Cada SDD escrito tem título com o id, **Análise**, **Requisitos** (`RF1…`) e **Critérios de aceite** (`CA1…`, um por item de `covers` do gabarito). Um validador (`SddFormat`) confere a forma; o CI o aplica a todo SDD escrito, e a rodada manual o aplica à saída do modelo real.
- **RF3 — Aprovação parcial** (8c). O `tech-rep` aprova ou reprova cada SDD. Os reprovados voltam ao `write-sdds` com a nota por ponteiro (`noteRef`) e a versão anterior, e são registrados como a v2 do mesmo SDD, até 3 versões. Terminadas as rodadas, o lote aprovado leva só os SDDs aprovados: o manifesto é reescrito (v2 do documento do lote, `supersedes: 1`) e o `sdd-batch-approved` aponta para ele. Se nenhum for aprovado, não há lote: `outcome: rejected`.
- **RF4 — Prazo da aprovação** (8d). Sem resposta em 48h, a aprovação do lote escala ao papel `tech-lead` por mais 24h, e todos os leads (`tech-lead` e `business-lead`) recebem o aviso `approval-overdue`. Se ainda assim ninguém responder, o fim é `approval-timeout`, documentado (registro `report`, aviso `approval-timeout`, auditoria). **Nunca** emite `sdd-batch-approved` por omissão.
- **RF5 — Dúvidas e reinício do ciclo.** O desfecho `questions` (ou a dúvida bloqueante) escreve e registra as perguntas, publica `questions-sent` com `nextCycle` e o e-mail ao Negócio sai pelo `sandbox-channels`; o ciclo seguinte do Negócio é uma instância nova na mesma cadeia (mecanismo do S.5, agora com teste por roteiro).
- **RF6 — Prioridade** (8f). A prioridade decidida vai para o `sdd-batch-approved` (campo opcional `priority`, aditivo) e para a entrada do `register-document` do lote.
- **RF7 — Privacidade herdada.** Nada de texto no evento; nenhum marcador em depósito algum (o varredor do S.6 continua passando); a dúvida do filtro ainda para a reunião.
- **RF8 — Modelos reais, medidos** (8e). Rodada manual: o LitJev decide tipo, desfecho e dúvida bloqueante sobre os transcritos técnicos reais; o llama.cpp local escreve os SDDs de três roteiros e o validador os confere. Só relatório.

## 4. Fora de escopo
- Negócio e Entrega (S.8 feito, S.10); o gate (S.11).
- Mudar o Stint, o filtro (S.6) ou o registro (S.7) além do que RF3 e RF6 pedem.
- Ler o desenho pelo registro (8d).
- Verificar que o SDD gerado pelo modelo real é *correto*: só a forma e a cobertura por palavras (8e).

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | Os sete roteiros técnicos terminam no caminho do gabarito; o número de SDDs bate (2, 3, 1, 2); o de perguntas bate; o fora de escopo não escreve nem registra nada; o ambíguo vai ao `tech-rep` | `EngineeringCorpusTest`, `S.9-CA1.txt` |
| CA2 | Todo SDD escrito pelo CI passa no `SddFormat` e tem um critério por item de `covers`; o validador recusa um SDD sem análise, sem requisitos, sem critérios, ou sem id | `SddFormatTest` |
| CA3 | Um SDD reprovado com nota volta como a v2 do mesmo `documentId` (`supersedes: 1`), os aprovados não são tocados, e o lote final leva todos; esgotadas as 3 versões, o lote leva só os aprovados com o manifesto v2, e o evento aponta para ele; sem nenhum aprovado, `rejected` e nenhum evento de lote | `EngineeringApprovalTest` |
| CA4 | A aprovação sem resposta é lembrada em 40h, escalada em 48h ao `tech-lead` com o aviso a todos os leads, e termina em `approval-timeout` sem `sdd-batch-approved`, com o registro `report`, o aviso e a auditoria; a resposta do `tech-lead` dentro das 24h aprova | `EngineeringApprovalTest`, `NoticesTest` (relógio controlável, sem sleep) |
| CA5 | O roteiro com dúvida bloqueante registra as perguntas, publica `questions-sent` com o ciclo seguinte e o e-mail sai; no esqueleto, o ciclo 2 do Negócio é outra instância na mesma cadeia | `EngineeringCorpusTest`, `PipelineSkeletonIT` |
| CA6 | O `sdd-batch-approved` com `priority` cumpre o contrato; sem ela também | `SddBatchApprovedContractTest` |
| CA7 | No esqueleto, o lote do `p2` ciclo 2 tem um SDD reprovado uma vez e reescrito (v2 com `supersedes: 1` no catálogo), a Entrega corre os SDDs do manifesto final e o varredor continua em zero | `PipelineSkeletonIT`, `S.9-CA7.txt` |
| CA8 | S.1–S.8 continuam verdes | `gate.sh --regression-only` |

## 6. Plano de tarefas (etapa 2, após aprovação)
1. `EngineeringCorpusTest` para os sete roteiros (RF1), primeiro, para ver o que falha. Um `EngineeringFixture` com stand-ins que lembram as chamadas, como o do S.8.
2. `SddFormat` e `SddFormatTest`; o escritor do CI passa a produzir o formato (RF2).
3. O laço de reprovação por SDD e o manifesto v2 no `technical-meeting.yaml`; o `write-sdds` aceita `only` + `previousRefs` + `noteRef` (RF3).
4. O papel `tech-lead`, o `escalate`, o relatório de travamento e os avisos (RF4).
5. `priority` no evento e no catálogo (RF6).
6. O esqueleto: o `p2` ciclo 2 reprovado uma vez (CA7).
7. A rodada manual (RF8); regressão.

## 7. Questões em aberto: propostas para aprovação

### 8a. Quais roteiros e o que é o "caminho"
**Proposta:** os sete roteiros técnicos (`p1`, `p2` c1 e c2, `p4`, `p5`, `a2`, `o2`). O caminho é o das chamadas e eventos, não o texto do SDD. O `a2` (ambíguo, sem decisão) vai ao `tech-rep` e segue a resposta do teste.

### 8b. O formato do SDD
**Proposta:** o do processo do repositório, em Markdown, com `# SDD <id> — <título>`, `## 1. Análise`, `## 3. Requisitos` (itens `RF<n>`) e `## 5. Critérios de aceite` (itens `CA<n>`, um por cobertura do gabarito). O `SddFormat` confere a presença e a ordem das seções, ao menos um `RF` e um `CA`, e o id no título. A numeração salta de propósito (1, 3, 5), como nos SDDs do projeto; os outros capítulos (2, 4, 6…) não são exigidos de um SDD gerado.

### 8c. Aprovação parcial
**Proposta:** até 3 versões por SDD. A reescrita do CI é determinística: acrescenta uma seção `Revisão n` ao SDD anterior e **não lê a nota** (a nota é texto de uma pessoa; só um redator depois do filtro devia lê-la, como no S8-3). O lote final: o documento do lote ganha a v2 só com os aprovados (o manifesto é reescrito pela tarefa `finalize-batch`, que escreve o novo manifesto e responde o ponteiro). Aprovados todos de uma vez, nada muda em relação ao S.5 (v1 do lote).

### 8d. Prazo, escalonamento e papel
**Proposta:** o espelho do S.8: novo papel `tech-lead` (usuário de teste do portal, endereço no `Mail`), avisos `approval-overdue` e `approval-timeout` (templates já existentes do S.8) ao `tech-lead` e ao `business-lead`. O Stint escala a um papel só; o aviso a todos sai do `Notices`, que já trata `escalatedFrom` (o ponteiro do material hoje é um desenho, e passa a ser também o manifesto do lote: o regex é estendido). Se preferir que a Engenharia escale ao mesmo `business-lead` ou a outro, é uma linha.

### 8e. Modelos reais
**Proposta:** relatório manual, sem critério. O LitJev decide tipo, desfecho e dúvida bloqueante sobre os sete transcritos técnicos reais (redigidos pelo filtro do S.6, como no 8g do S.8); o llama.cpp (Qwen3-4B) escreve os SDDs dos três roteiros completos e o `SddFormat` mede a forma e a cobertura por palavras-chave de `covers`.

### 8f. Prioridade
**Proposta:** `priority` opcional (0 a 1) em `sdd-batch-approved` e na entrada do lote, igual ao S.8. Sem outro uso: quem prioriza é a Entrega (S.10).

### 8g. Expectativas de achados
- A Engenharia não lê o desenho (S.7, 8e): o SDD gerado não cita nada dele além do id. Se o produto precisar disso, é um SDD próprio (leitura entre domínios com autorização).
- A revisão por itens pode entregar `rejectedItems` ao contexto só no caminho de rejeição total; se a parcial não os trouxer, o laço usa o `items` da resposta, e se nem assim, é achado do Stint.

## 8. Registro de validação

**Ambiente.** O do S.5 a S.8 (Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2, Stint `500dfd1` sob `0.1.0-3a-500dfd1`, floci). A pilha de modelos (LitJev e llama.cpp) subiu só para as medições de 8e e foi parada em seguida.

**Aprovação.** A spec foi aprovada por Kayan em 21/10/2026 com um "aprovado" dado à pergunta "o que você aprova primeiro, o S.9 ou o S.10?". Li como aprovação das duas specs, valendo as propostas 8a a 8g. Kayan confirmou depois: "approvado para o S.9 e S.10".

### 8.1 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** Os sete roteiros técnicos terminam no caminho do gabarito: tipo, desfecho e dúvida bloqueante; o número de SDDs bate (2, 2, 3, 1) e os ids são os do gabarito; o de perguntas bate; o fora de escopo (`o2`) não escreve nem registra nada; o ambíguo (`a2`) vai ao `tech-rep` e segue a resposta | `EngineeringCorpusTest` (7) |
| CA2 | **Verde.** Todo SDD escrito passa no `SddFormat` e tem um critério por item de `covers` do gabarito; o validador recusa título de outro id, falta de análise, de requisitos, de critérios, de `RF`/`CA` e seções fora de ordem, e aceita uma seção de revisão depois | `EngineeringCorpusTest`, `SddFormatTest` |
| CA3 | **Verde.** Um SDD reprovado com nota volta como a v2 do mesmo `documentId` (`supersedes: 1`, o evento da v2 com id próprio) e o aprovado não é tocado (texto sem revisão); o lote final leva os dois. Reprovado nas três versões, o SDD é abandonado: o lote é reescrito só com o aprovado (a v2 do documento do lote, `finalize-batch`), `sddIds`/`recordIds` só com ele e `batchRef` para o manifesto novo. Sem nenhum aprovado, `rejected` e nenhum evento de lote | `EngineeringApprovalTest` (3 testes) |
| CA4 | **Verde.** Sem resposta: lembrete em 40h, escalonamento em 48h ao `tech-lead` (o pedido traz `escalatedFrom: tech-rep`), fim em `approval-timeout` em 72h, sem `sdd-batch-approved`; documentado por um registro `report` (`p1-c1-batch-approval-timeout`), o aviso `approval-timeout` ao `tech-lead` e ao `business-lead` e as entradas de auditoria `requested`, `reminded` e `escalated`. O `tech-lead` que responde dentro das 24h aprova. O aviso `approval-overdue` do escalonamento de um lote vai ao `tech-lead` e ao `business-lead`. Relógio controlável, sem sleep | `EngineeringApprovalTest` (2 testes), `NoticesTest` (2) |
| CA5 | **Verde.** O roteiro com dúvida bloqueante (`p2` ciclo 1) registra as perguntas (`questions`), publica `questions-sent` com `nextCycle` = ciclo + 1 e a contagem do gabarito, e não gera lote; no esqueleto, o ciclo 2 do Negócio é outra instância na mesma cadeia | `EngineeringCorpusTest`, `PipelineSkeletonIT` |
| CA6 | **Verde.** O `sdd-batch-approved` aceita `priority` entre 0 e 1 e a forma antiga; recusa 1,4 e um campo de conteúdo; a prioridade dos roteiros fica dentro de ±0,2 do gabarito | `SddFormatTest`, `EngineeringCorpusTest` |
| CA7 | **Verde.** No esqueleto, o lote do `p2` ciclo 2 tem o `p2-sdd-1` reprovado uma vez: o catálogo tem `rec-p2-sdd-1` v1 e v2 (`supersedes: 1`), o `p2-sdd-2` fica numa versão só, a Entrega corre os SDDs do manifesto final e fecha o ciclo, e o varredor continua em zero | `PipelineSkeletonIT`; `S.9-CA7.txt` |
| CA8 | **Verde.** `gate.sh --regression-only`: build completo, `FlociBillingIT` 5/5, `FlociOrdersIT` 6/6 e `RegressionIT` 3/3 (S.1–S.8), PASSED em 21/10/2026 | `gate.sh --regression-only` |
| 8e | **Medido.** O LitJev (Qwen3.5-2B) sobre os sete transcritos técnicos reais redigidos pelo filtro do S.6: **tipo 5 de 7** (chamou o `o2`, de outro projeto, e o `p2` ciclo 1 de reunião de negócio), **desfecho 3 de 7** (diz "questions" para quase todas), **dúvida bloqueante 5 de 7**. O llama.cpp (Qwen3-4B) escreveu os SDDs dos quatro roteiros de SDDs: **8 de 8 no formato do processo** e **12 de 13** itens de `covers` citados | `S.9-litjev.txt`, `S.9-llm.txt` |

### 8.2 Ressalvas e desvios
- **O lote só é registrado de novo quando muda.** A spec dizia que cada rodada reescrevia o manifesto; mas o manifesto lista os mesmos ponteiros (o SDD reescrito fica na mesma chave), então um novo registro daria a mesma versão e um evento repetido. O lote é registrado duas vezes no máximo: como foi escrito e, se algum SDD foi abandonado, aparado (v2).
- **O esqueleto rejeita um SDD do `p2` ciclo 2**, não do `p1` (como a spec já dizia): rejeitar o `p1` mudaria a contagem de `document-registered` do `p1`.
- **A reescrita do CI não lê a nota**, só acrescenta uma seção `Revisão n` ao SDD anterior (a nota é texto de uma pessoa). A rodada manual escreve cada SDD uma vez; não há reescrita com o modelo real: S9-3.
- **A medição do llama.cpp pede um SDD por id e título** (os do gabarito) e mede formato e cobertura; não mede se o modelo sabe dividir uma reunião em SDDs.
- **O texto da rodada manual do llama.cpp passou pelas camadas de referência** (padrões e lista de termos), não pelo filtro do S.6 (que mora no módulo de privacidade); o LitJev passou pelo filtro do S.6.
- **O título "Design approval is overdue"** do e-mail `approval-overdue` serve também ao aviso de um lote de SDDs (o template é o mesmo do S.8).
- **Papel novo `tech-lead`**: usuário de teste do portal (`test-tech-lead`) e endereço no `Mail`.
- **A leitura do desenho pela Engenharia continua negada** (S.7, 8e): o gerador usa a transcrição e cita o desenho por id. A proposta do S.10 (lista de leitores no registro) trata disso.
- **Nenhuma asserção existente mudou.**

### 8.3 Achados (nada alterado no Stint)
| # | Achado | Proposta |
|---|---|---|
| S9-1 | O LitJev 2B decide mal os roteiros técnicos: tipo 5/7, desfecho 3/7, dúvida bloqueante 5/7 (diz "questions" quase sempre) | Como no S.6 e no S.8: o modelo pequeno não é a garantia, o `onUncertain` ao `tech-rep` é; medir com um modelo maior antes de qualquer uso real |
| S9-2 | O Qwen3-4B escreve SDDs no formato do processo (8/8) e cobre o que o gabarito pede (12/13), pedindo um SDD por vez | A divisão de uma reunião em SDDs é o que falta medir |
| S9-3 | A reescrita de um SDD rejeitado não incorpora o pedido do aprovador: a nota chega por ponteiro (`noteRef`) e nenhum redator a lê | Um redator que leia a nota depois de passá-la pelo filtro do S.6; é trabalho de um SDD próprio (o mesmo achado do S8-3) |
| S9-4 | Uma revisão por itens com rejeição parcial entrega `rejectedItems` e `noteRef` ao contexto sem precisar do `onReject` (a dúvida de 8g se resolveu): o `switch` sobre `.verdict`, `.defaulted` e `.rejectedItems` basta | Nada a propor; fica o registro |

### 8.4 Pendências
- Validado e Mergeado são seus. O push, com o seu pedido.
- Notion: a página do S.9 e o Log.
