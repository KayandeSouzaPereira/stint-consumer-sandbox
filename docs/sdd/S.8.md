# SDD S.8 — Domínio de Negócio: da reunião ao desenho aprovado

| Campo | Valor |
|---|---|
| ID | S.8 |
| Status | **Em especificação** (aguarda a aprovação do Kayan) |
| Depende de | S.5, S.6 e S.7 (Implementados). Stint `500dfd1` |
| Bloqueia | S.11 (gate da 3a); a Engenharia (S.9) consome o `design-published` |
| Branch | `phase-3a-validation` (os PRs da fase são empilhados) |
| Commits analisados | sandbox `ebf7fcc` · Stint `500dfd1` |
| Data da análise | 21/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.8): 3 requisitos e 3 critérios, todos cobertos abaixo |

## 1. Análise

### O que o S.5 já entregou no `business-design`
O workflow do Negócio já corre de ponta a ponta com modelos falsos e já usa o filtro (S.6) e o registro (S.7):
`meeting-recorded` → `transcribe` → `redact` (`onDoubt`) → `decide-meeting-type` (`onUncertain`: Representante de Negócio, 24h, padrão `out-of-scope`) → `decide-priority` → `write-design` → `register-document` + `document-registered` → `decide-channel` (`onUncertain`, padrão `email`) → publicar no Confluence falso, e-mail ou "pedir nova reunião" → `review` de aprovação (48h, lembrete em 40h) → `design-published`.

### O que o rascunho pede e ainda falta
| Pedido do rascunho | Estado hoje |
|---|---|
| Início por evento; `call: decision` com `onUncertain` | feito |
| Cada roteiro do corpus segue o gabarito | só três roteiros são testados (`p1`, `o1`, `a1`); os outros quatro (`p2` ciclos 1 e 2, `p3`, `p6`) só passam no esqueleto |
| Aprovação fora do prazo escala | **falta**: a aprovação tem prazo e lembrete, mas, vencido o prazo, o que acontece não está definido nem testado |
| Rejeição volta com a nota do aprovador para nova versão | **falta**: `onReject` vai para `rejected` e o workflow termina |
| Canal escolhido produz o artefato certo | só provado dentro do esqueleto, para dois dos três canais; nenhum teste de unidade por canal |
| Resposta ao e-mail de dúvidas reinicia o ciclo (nova instância na mesma cadeia) | existe por construção (o gravador falso grava a reunião do ciclo seguinte quando vê `questions-sent`), mas não há teste do Negócio que prove a segunda instância na mesma cadeia |
| A prioridade da reunião | é decidida e **descartada**: não chega a nada |

### O que o Stint oferece (`500dfd1`)
- `review` com `remind`, `escalate: {role, after}` e `onTimeout: {value}`. Numa aprovação, `onTimeout` entrega `{verdict: <valor>, defaulted: true}`.
- A resposta de uma revisão traz `verdict` e `noteRef` (ponteiro): o texto livre do aprovador nunca viaja, só o ponteiro.
- O papel que responde vem da credencial do portal; resposta de outro papel é recusada e auditada (`wrong-role`).

### Conclusão
O S.8 fecha as lacunas acima sem mudar o Stint: prova o caminho de cada roteiro, acrescenta o escalonamento e o fim por prazo, faz a rejeição produzir uma nova versão do mesmo desenho (que, com o S.7, passa a ter `supersedes`), liga a prioridade ao evento e prova o reinício do ciclo.

## 2. Problema e objetivo
**Problema.** O Negócio só sabe o caminho feliz: um desenho rejeitado ou uma aprovação esquecida deixam o ciclo parado sem dono, e a maior parte do corpus não tem teste do caminho.

**Objetivo.** O domínio de Negócio completo: cada reunião do corpus chega ao fim certo; o desenho é refeito quando rejeitado, escalado quando esquecido e nunca publicado sem aprovação.

## 3. Requisitos
- **RF1 — Caminho por roteiro** (8a). Para cada um dos sete roteiros de negócio do corpus, o caminho do workflow (tipo, prioridade, canal, artefato e fim) é o do gabarito `expected`, com os modelos falsos.
- **RF2 — Prioridade** (8e). A prioridade decidida vai para o `design-published` (campo opcional `priority`, aditivo) e para o registro como parte da entrada do `register-document`; nada mais a usa.
- **RF3 — Escalonamento** (8b). Sem resposta em 48h, a aprovação é escalada ao papel `business-lead` por mais 24h. Sem resposta também dele, o workflow termina com `outcome: approval-timeout`: **nunca** publica e nunca aprova por omissão.
- **RF4 — Rejeição com nota** (8c). Uma rejeição reabre o `write-design` com o ponteiro da nota (`noteRef`) e o desenho anterior; o novo texto é registrado sob o mesmo `documentId` (a v2 substitui a v1, S.7), reenviado pelo mesmo canal e aprovado de novo. No máximo três versões; a terceira rejeição termina com `outcome: rejected`.
- **RF5 — Canal e artefato** (8d). Cada canal produz o artefato certo: Confluence → uma página com a chave do documento; e-mail → uma mensagem `design`; nova reunião → uma mensagem `meeting` e fim sem desenho.
- **RF6 — Reinício do ciclo** (8f). A resposta às dúvidas da Engenharia é a gravação da reunião do ciclo seguinte; ela inicia uma **nova instância** do Negócio na mesma cadeia do projeto.
- **RF7 — Privacidade herdada.** Nada de texto no evento, nenhum marcador em depósito algum (o varredor do S.6 segue passando), a dúvida do filtro ainda para a reunião.
- **RF8 — Modelos reais, medidos** (8g). Rodada manual (`-Dbusiness.litjev=true`) com o LitJev local sobre os sete transcritos reais já redigidos: acerto de tipo, canal e prioridade contra o gabarito. Só relatório; não é critério.

## 4. Fora de escopo
- Engenharia e Entrega (S.9, S.10); o gate (S.11).
- Mudar o Stint, o filtro (S.6) ou o registro (S.7) além do que o RF2 e o RF4 pedem.
- Ligar o desenho do ciclo 2 ao do ciclo 1 como versões do mesmo registro (8f).
- O texto da nota do aprovador: ela viaja por ponteiro e o fake do redator de desenho não a lê.

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | Os sete roteiros de negócio terminam no caminho do gabarito (tipo, prioridade dentro de ±0,2, canal, artefato, fim) | `BusinessCorpusTest`, `S.8-CA1.txt` |
| CA2 | A aprovação sem resposta é lembrada em 40h, escalada em 48h ao `business-lead`, e termina em `approval-timeout` sem `design-published`; a resposta do `business-lead` dentro das 24h a aprova | `BusinessEscalationTest` (relógio controlável, sem sleep) |
| CA3 | Uma rejeição com nota gera a v2 sob o mesmo `documentId` com `supersedes: 1`, reenvia pelo mesmo canal e, aprovada, publica a v2; três rejeições terminam em `rejected` sem publicar; a nota viaja só por ponteiro | `BusinessReworkTest` |
| CA4 | Cada canal produz o artefato certo e nenhum outro (MailHog, página, ou mensagem de nova reunião) | `BusinessChannelsTest` |
| CA5 | A segunda instância do Negócio do `p2` (ciclo 2) tem a mesma cadeia da primeira, outro ciclo, e as duas existem ao mesmo tempo no catálogo | `PipelineSkeletonIT` |
| CA6 | O `design-published` com `priority` cumpre o contrato; sem ela (versão antiga do emissor) também | `BusinessDesignWorkflowTest` |
| CA7 | No esqueleto, o catálogo mostra o `supersedes` quando o `p1` é rejeitado uma vez e aprovado na segunda; o varredor continua em zero | `PipelineSkeletonIT`, `S.8-CA7.txt` |
| CA8 | S.1–S.7 continuam verdes | `gate.sh --regression-only` |

## 6. Plano de tarefas (etapa 2, após aprovação)
1. `BusinessCorpusTest` para os sete roteiros (RF1). Primeiro, para ver o que falha.
2. Papel `business-lead` no portal (usuário de teste) e o `escalate` da aprovação.
3. A estrutura do laço de rejeição no `business-design.yaml`; o `DesignWriter` falso recebe `noteRef` e o desenho anterior.
4. `priority` no evento e na entrada do registro; id do `document-registered` por versão.
5. Testes de escalonamento, retrabalho e canais; o `p1` do esqueleto rejeitado uma vez.
6. Rodada manual com o LitJev (8g); regressão.

## 7. Questões em aberto: propostas para aprovação

### 8a. Quais roteiros e o que é o "caminho"
**Proposta:** os sete roteiros de negócio (`p1`, `p2` c1, `p2` c2, `p3`, `p6`, `o1`, `a1`). O caminho é o dos eventos e das chamadas despachadas, não o texto do desenho. Os dois ambíguos (`a1`) vão ao Representante e seguem a resposta do teste.

### 8b. Escalar para quem
**Proposta:** um novo papel `business-lead` (usuário de teste do portal; um papel a mais, só na revisão). Os papéis existentes não servem: `tech-rep` e `technician` não têm autoridade sobre o desenho. Alternativa: escalar para o `tech-rep`. **Escolha sua.** Termina em `approval-timeout` por `onTimeout: {value: rejected}` com `defaulted: true`, que o workflow distingue de uma rejeição humana (esta reabre o retrabalho, aquela encerra).

### 8c. Retrabalho
**Proposta:** até 3 versões (duas reescritas). O `write-design` recebe `documentId` (o mesmo), `previousRef` (a versão anterior) e `noteRef`. O redator falso é determinístico: acrescenta uma seção de revisão numerada ao texto anterior e **não lê a nota**; o redator real a leria. A republicação usa a mesma chave de página (atualiza) ou reenvia o e-mail. O `id` do `document-registered` ganha o sufixo `:v<n>` só a partir da v2, para a v1 manter o id que já existe.

### 8d. Canais
**Proposta:** teste de unidade por canal sobre as chamadas despachadas e suas entradas; a prova de ponta a ponta continua no esqueleto (`p1` Confluence, `p2` e-mail) e o canal "nova reunião" (`p3`) só em unidade, pois o esqueleto não roda o `p3`.

### 8e. Prioridade
**Proposta:** campo opcional `priority` (número de 0 a 1) no `design-published` e na entrada do `register-document` (campo de entrada opcional, guardado no catálogo). Sem uso além disso: a priorização é de quem consome (S.9).

### 8f. Reinício do ciclo e continuidade entre ciclos
**Proposta:** manter o mecanismo do S.5 (o gravador falso grava o ciclo seguinte ao ver `questions-sent`, como a pessoa que responde). O `documentId` continua por ciclo (`p2-c1-design`, `p2-c2-design`): o desenho do ciclo 2 **não** é a v2 do do ciclo 1, porque isso mudaria ids que o S.5, o S.7 e a Engenharia já usam. Fica registrado como decisão para um SDD futuro.

### 8g. Modelos reais
**Proposta:** relatório manual, como no 8f do S.6: acerto de tipo, canal e prioridade do LitJev (Qwen3.5-2B) sobre os transcritos reais redigidos, contra o gabarito. Não bloqueia nada.

### 8h. Achados esperados no Stint (a confirmar na implementação)
- Se o `onReject` pular o `export` da revisão e a `noteRef` não chegar ao contexto, o laço usa um `switch` sobre `.verdict` no lugar do `onReject`; se nem assim a `noteRef` chegar, é achado.
- O escalonamento em teste exige um temporizador controlável no domínio em memória; se o `InMemoryDomain` não o tiver, o teste usa o do Stint (`ListenTimeoutTest` mostra o caminho) ou vira achado.

## 8. Registro de validação
(preencher na etapa 2)
