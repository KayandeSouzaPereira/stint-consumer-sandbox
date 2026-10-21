# SDD S.8 — Domínio de Negócio: da reunião ao desenho aprovado

| Campo | Valor |
|---|---|
| ID | S.8 |
| Status | **Implementado** (spec aprovada por Kayan em 21/10/2026; 8b decidido por ele: escalar ao business-lead e avisar todos os leads; as demais propostas valem) |
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
- **RF3 — Escalonamento e registro do travamento** (8b). Sem resposta em 48h, a aprovação é escalada ao `business-lead` por mais 24h, e **todos os leads** (`business-lead` e `tech-rep`) recebem o aviso `approval-overdue` por e-mail. Se ainda assim ninguém responder, o workflow termina com `outcome: approval-timeout` e o travamento fica **documentado**: um registro do tipo `report` no catálogo (S.7), um aviso `approval-timeout` aos mesmos leads e a entrada de auditoria da revisão. Nunca publica e nunca aprova por omissão.
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
| CA2 | A aprovação sem resposta é lembrada em 40h, escalada em 48h ao `business-lead` com o aviso a todos os leads, e termina em `approval-timeout` sem `design-published`, com o registro `report`, o aviso e a auditoria; a resposta do `business-lead` dentro das 24h a aprova | `BusinessEscalationTest` (relógio controlável, sem sleep) |
| CA3 | Uma rejeição com nota gera a v2 sob o mesmo `documentId` com `supersedes: 1`, reenvia pelo mesmo canal e, aprovada, publica a v2; três rejeições terminam em `rejected` sem publicar; a nota viaja só por ponteiro | `BusinessReworkTest` |
| CA4 | Cada canal produz o artefato certo e nenhum outro (MailHog, página, ou mensagem de nova reunião) | `BusinessChannelsTest` |
| CA5 | A segunda instância do Negócio do `p2` (ciclo 2) tem a mesma cadeia da primeira, outro ciclo, e as duas existem ao mesmo tempo no catálogo | `PipelineSkeletonIT` |
| CA6 | O `design-published` com `priority` cumpre o contrato; sem ela (versão antiga do emissor) também | `BusinessDesignWorkflowTest` |
| CA7 | No esqueleto, o catálogo mostra o `supersedes` quando o `p1` é rejeitado uma vez e aprovado na segunda; o varredor continua em zero | `PipelineSkeletonIT`, `S.8-CA7.txt` |
| CA8 | S.1–S.7 continuam verdes | `gate.sh --regression-only` |

## 6. Plano de tarefas (etapa 2, após aprovação)
1. `BusinessCorpusTest` para os sete roteiros (RF1). Primeiro, para ver o que falha.
2. Papel `business-lead` no portal (usuário de teste), o `escalate` da aprovação e os templates `approval-overdue` e `approval-timeout`.
3. A estrutura do laço de rejeição no `business-design.yaml`; o `DesignWriter` falso recebe `noteRef` e o desenho anterior.
4. `priority` no evento e na entrada do registro; id do `document-registered` por versão.
5. Testes de escalonamento, retrabalho e canais; o `p1` do esqueleto rejeitado uma vez.
6. Rodada manual com o LitJev (8g); regressão.

## 7. Questões em aberto: propostas para aprovação

### 8a. Quais roteiros e o que é o "caminho"
**Proposta:** os sete roteiros de negócio (`p1`, `p2` c1, `p2` c2, `p3`, `p6`, `o1`, `a1`). O caminho é o dos eventos e das chamadas despachadas, não o texto do desenho. Os dois ambíguos (`a1`) vão ao Representante e seguem a resposta do teste.

### 8b. Escalar para quem (decidido por você em 21/10/2026)
**Decisão:** escalar para o novo papel `business-lead` (o Stint escala a revisão a um papel) **e avisar todos os leads** por e-mail: `business-lead` e `tech-rep`. Se travar depois disso, o fim é `approval-timeout` e fica documentado (registro `report`, aviso e auditoria). O `technician` não é lead de desenho e não recebe o aviso; se você quiser incluí-lo, é uma linha. Dois templates novos no `sandbox-channels`: `approval-overdue` e `approval-timeout`. O `onTimeout: {value: rejected}` com `defaulted: true` é o que o workflow distingue de uma rejeição humana (esta reabre o retrabalho, aquela encerra).

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

**Ambiente.** O do S.5 a S.7 (Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2, Stint `500dfd1` sob `0.1.0-3a-500dfd1`, floci). A pilha de modelos subiu só para a medição de 8g (LitJev, GPU) e foi parada em seguida.

### 8.1 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** Os sete roteiros de negócio terminam no caminho do gabarito: tipo, canal e artefato; o fora de escopo não escreve, registra nem envia nada; o ambíguo (`a1`) vai ao Representante, que responde como o gabarito; a prioridade do evento fica dentro de ±0,2 do gabarito | `BusinessCorpusTest` (7) |
| CA2 | **Verde.** Sem resposta: lembrete em 40h, escalonamento em 48h para o papel `business-lead` (o pedido traz `escalatedFrom: business-rep`), fim em `approval-timeout` em 72h, sem `design-published`; documentado por um registro `report` (`p1-c1-design-approval-timeout`), um `document-registered` do tipo `report`, o aviso `approval-timeout` ao `business-lead` e ao `tech-rep`, e as entradas de auditoria `requested`, `reminded` e `escalated`. O `business-lead` que responde dentro das 24h aprova e o desenho é publicado. Relógio controlável, sem sleep | `BusinessApprovalTest` (4), `NoticesTest` (1) |
| CA3 | **Verde.** Uma rejeição com `noteRef` reabre o `write-design` com a rodada 2, o ponteiro da nota e a versão anterior; a v2 é registrada sob o mesmo `documentId`, o evento da v2 tem outro id e traz `supersedes: 1`, a página é publicada de novo pela mesma chave e o `design-published` leva a v2. Três rejeições terminam em `rejected`, com três escritas e três versões, sem publicar nem gerar relatório de travamento. A nota viaja só por ponteiro | `BusinessApprovalTest` |
| CA4 | **Verde.** Cada canal produz o artefato certo e nenhum outro: Confluence → uma `publish-page` com a chave do documento e nenhum e-mail; e-mail → um `send-email` `design` e nenhuma página; nova reunião → um `send-email` `meeting`, nenhuma página e nenhum `design-published` | `BusinessCorpusTest` |
| CA5 | **Verde.** No esqueleto, o `p2` tem duas instâncias do Negócio (ciclos 1 e 2): dois `design-published` (`p2-c1-design` e `p2-c2-design`), todos os eventos do `p2` na mesma cadeia `chn-3cf066c1…`, e os dois desenhos no catálogo | `PipelineSkeletonIT`, `S.7-CA6.txt` |
| CA6 | **Verde.** `design-published` aceita `priority` entre 0 e 1 e continua aceitando a forma antiga; recusa 1,4, um texto e um campo de conteúdo | `DesignPublishedContractTest` |
| CA7 | **Verde, com o desvio de 8.2.** O `p2` é rejeitado uma vez no esqueleto: o catálogo tem `rec-p2-c1-design` v1 e v2, a v2 com `supersedes: 1`; o varredor continua em zero | `PipelineSkeletonIT`; `S.8-CA7.txt` |
| CA8 | **Verde.** `gate.sh --regression-only`: build completo, `FlociBillingIT` 5/5, `FlociOrdersIT` 6/6 e `RegressionIT` 3/3 (S.1–S.7), PASSED em 21/10/2026 | `gate.sh --regression-only` |
| 8g | **Medido.** LitJev (Qwen3.5-2B) sobre os sete transcritos reais já redigidos pelo filtro do S.6: **tipo 6 de 7** (errou o `o1`, o almoço da equipe, que chamou de desenho), **canal 7 de 7** nos de negócio, **prioridade dentro de ±0,2 em 3 de 7** (e 1 de 7 antes do ajuste de escala, S8-1). O ambíguo `a1` acertou o tipo | `S.8-litjev.txt` |

### 8.2 Ressalvas e desvios
- **A rejeição do esqueleto é no `p2`, não no `p1`** como a spec dizia (CA7). Rejeitar o `p1` mudaria uma asserção do esqueleto que já existe (4 `document-registered` do `p1`), e a regra é parar nesse caso; o `p2` não tem essa contagem. O que o CA7 pede, a prova de `supersedes` num ciclo real, está igual.
- **O CA4 é provado no `BusinessCorpusTest`** (um caso por roteiro), não numa classe `BusinessChannelsTest` à parte: os sete roteiros já cobrem os três canais.
- **O aviso a todos os leads sai do `sandbox-channels`, não do workflow.** O Stint escala a revisão a um papel só; o `Notices` vê o pedido escalado (`escalatedFrom`) e manda `approval-overdue` ao `business-lead` e ao `tech-rep`. Como o evento de revisão não traz projeto nem ciclo, o aviso os lê do ponteiro do desenho (`design/<p>-c<n>-design.md`) e usa `unknown`/`0` se o material não for um desenho. O `NoticesTest` prova o envio (o pedido escalado avisa o `business-lead` e o `tech-rep`, um primeiro pedido não avisa ninguém e a reentrega não avisa duas vezes); o `BusinessApprovalTest` prova o pedido escalado. O e-mail ao vivo, no MailHog, não é exercitado pelo esqueleto, que não escala.
- **O redator de desenho, na rodada de retrabalho, não chama o modelo**: acrescenta ao texto anterior uma seção `Revision n`. Ele não lê a nota (é texto de uma pessoa, e só um redator depois do filtro devia lê-la). Para a rodada manual com o LLM real isso significa que a v2 não incorpora o que o aprovador pediu: achado S8-3.
- **O `id` do `document-registered` ganha `:v<n>` só da v2 em diante**; o da v1 não mudou.
- **O `register-document` guarda `priority`** (campo opcional da entrada, no catálogo); o `ListCatalog` do teste do S.7 não foi tocado.
- **Papel novo `business-lead`**: usuário de teste no portal (`test-business-lead`), endereço no `Mail` e dois templates (`approval-overdue`, `approval-timeout`).
- **Nenhuma asserção existente mudou.** O `onTimeout` e o `escalate` entraram no workflow sem alterar os testes do S.5.

### 8.3 Achados (nada alterado no Stint)
| # | Achado | Proposta |
|---|---|---|
| S8-1 | Uma decisão `score` do Jev responde na escala do índice dos níveis (0 a n-1; com três níveis, 0 a 2), não em 0 a 1. Sem ajuste, a prioridade real (1,54, 1,91…) violaria o contrato de `design-published` (máximo 1) | O sandbox normaliza no adaptador local (`ScoreOnUnitScale`). Proposta ao Stint: documentar a escala, ou devolver o score já em [0, 1] |
| S8-2 | O `noul`/a escolha do 2B confunde o almoço da equipe com uma reunião de desenho (`o1`) e a prioridade só acerta 3 de 7 | Como no S.6, o modelo pequeno não é a garantia: o que a protege é o `onUncertain` para o Representante. Medir com um modelo maior antes de qualquer uso real |
| S8-3 | Uma revisão rejeitada traz a nota só por ponteiro (`noteRef`), e o redator real não a lê; o desenho refeito não incorpora o pedido | Um redator que leia a nota depois de passá-la pelo filtro do S.6; é trabalho de um SDD próprio |
| S8-4 | O pedido de revisão escalado não traz projeto nem ciclo (só o ponteiro do material), então o aviso a todos os leads os infere | Proposta ao Stint: carregar as extensões do chamador (`sandboxproject`/`sandboxcycle`) nos eventos de revisão |

A dúvida de 8h se resolveu sem achado: o `noteRef` chega ao contexto sem o `onReject` (usei um `switch` sobre o veredito), e o domínio em memória aceita um relógio controlável (`InMemoryTimerService` de teste).

### 8.4 Pendências
- Validado e Mergeado são seus. O push, com o seu pedido.
- Notion: página do S.8 e Log.
