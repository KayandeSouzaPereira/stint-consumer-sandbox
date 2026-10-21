# SDD S.9 — Domínio de Engenharia: refinamento, SDDs ou dúvidas

| Campo | Valor |
|---|---|
| ID | S.9 |
| Status | **Em implementação** (spec aprovada por Kayan em 21/10/2026, com um "aprovado" sem escolha: valem as propostas 8a a 8g) |
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
(preencher na etapa 2)
