# SDD S.10 — Domínio de Entrega: implementação por SDD, validadores, 5 tentativas e DLQ

| Campo | Valor |
|---|---|
| ID | S.10 |
| Status | **Implementado** (CA9, a regressão, adiada por decisão do Kayan para depois da última implementação; aguarda a validação do Kayan) |
| Depende de | S.5, S.6, S.7 e S.8 (Implementados); S.9 (spec escrita, ainda sem aprovação: esta spec supõe o lote aprovado só com os SDDs aprovados, como o S.9 propõe). Stint `500dfd1` |
| Bloqueia | S.11 (gate da 3a) |
| Branch | `phase-3a-validation` (os PRs da fase são empilhados) |
| Commits analisados | sandbox `5b64e52` · Stint `500dfd1` |
| Data da análise | 21/10/2026 |
| Notion | Rascunho de 12/10/2026 (página S.10): 4 requisitos e 3 critérios, todos cobertos abaixo |

## 1. Análise

### O que o S.5 já entregou
A Entrega tem dois workflows (decisão A do S.5: um coordenador e uma instância por SDD, porque o Stint não tem `fork`):
- `delivery-cycle` (coordenador): lê o lote aprovado (`load-batch`), emite um `sdd-ready` por SDD (id determinístico, uma reentrega não inicia nada duas vezes), espera os fins consultando o estado (`check-sdds`) e fecha com `cycle-closed` (`complete`, `partial` ou `stalled`).
- `sdd-delivery` (um por SDD): `implement` → `sdd-implemented` → `run-target-tests` (container sem rede, no `sandbox-target`) → `validate`; até 5 tentativas por laço explícito (o `try`/`retry` do Stint só aceita uma chamada ou uma espera como corpo: achado do S.5); esgotadas, `sdd-dead-lettered` e a escolha do Técnico (reenviar com tentativas novas, aceitar ou cancelar; cancela sozinho em 72h).

O que corre ali é **falso**: o `implement` do CI devolve sempre o mesmo changeset (um módulo `by_author` e o teste dele, com "falha até a tentativa k" vinda do gabarito) e o `validate` só repassa o resultado dos testes. O S.5 deixou os agentes e os validadores para o S.10.

### O que o rascunho pede e ainda falta
| Pedido do rascunho | Estado hoje |
|---|---|
| 5 tentativas por SDD, com o feedback do validador na memória da instância | as 5 tentativas existem; o **feedback não chega ao `implement`** da tentativa seguinte (a entrada leva só `sddId`, `sddRef`, `attempt` e os ids dos registros) |
| Validadores conferem contra o SDD, o documento de negócio e o técnico | **falta**: `validate` só repassa os testes; nada confere o SDD |
| Esgotou: DLQ com o histórico das tentativas | `sdd-dead-lettered` leva só `attempts` e o `lastReportRef`; **o histórico não existe** |
| O Técnico decide reenviar com nota, aceitar ou cancelar | a escolha existe; a **nota** do reenvio não chega ao agente |
| Resumo diário da DLQ por `schedule` cron | **falta** |
| Concorrência dos agentes pelo `WorkerPool` | as instâncias correm em paralelo, mas não há prova de que os SDDs de um lote estão **ao mesmo tempo** em andamento |
| Patches e relatórios registrados (S.7) | **falta**: o changeset e o relatório ficam só no bucket de documentos |
| Código gerado nunca roda com rede nem segredos | o `DockerTestRunner` roda com `--network none`, sem capabilities, só leitura e usuário sem privilégio; falta **prova** de rede e segredos, e uma guarda no changeset |

### O que o S.7 e o S.9 mudam
- O validador precisa ler o SDD, o desenho (do Negócio) e o lote técnico (da Engenharia). O S.7 só deixa **o dono** ler um registro (`read-record`), e o domínio de Entrega não é dono de nenhum dos três. Esta spec propõe uma lista de leitores no registro (8d).
- O S.9 propõe que o lote só leve os SDDs aprovados; o `load-batch` da Entrega já lê o manifesto que o evento aponta, então segue como está.

### Conclusão
O S.10 liga o feedback à próxima tentativa, dá ao `validate` três verificações de verdade (testes, cobertura dos critérios do SDD e conformidade com os documentos), guarda o histórico das tentativas na DLQ, passa a nota do Técnico ao agente, registra patches e relatórios, prova a concorrência e o isolamento, e acrescenta o resumo diário. Não muda o Stint.

## 2. Problema e objetivo
**Problema.** Uma tentativa que falha refaz o mesmo trabalho às cegas, a DLQ não diz o que foi tentado, e nada garante que o que foi "validado" cobre o que o SDD pediu.

**Objetivo.** O domínio de Entrega completo: cada SDD é implementado em paralelo aos outros, validado contra o que pediu, refeito com o feedback do validador até 5 vezes, e, se falhar, entregue ao Técnico com todo o histórico; nada do que roda tem rede nem segredos.

## 3. Requisitos
- **RF1 — Feedback na memória da instância** (8a). O relatório da tentativa que falhou (o ponteiro e os critérios que não passaram) e a nota do Técnico, se houver, ficam no contexto da instância e vão na entrada do `implement` seguinte (`feedbackRef`, `failedCriteria`, `noteRef`). Ponteiros e ids; nunca o texto.
- **RF2 — Validadores** (8b). `validate` roda três conferências e o veredito é a conjunção: (1) **testes** do alvo passaram; (2) **cobertura**: cada critério `CA<n>` do SDD é citado por pelo menos um teste do changeset; (3) **conformidade**: o changeset cumpre o SDD, o desenho e o lote técnico (no CI, uma conferência determinística de rótulos; na rodada manual, um modelo local). O relatório lista o resultado de cada uma e os critérios que falharam.
- **RF3 — Tentativas e DLQ** (8c). Mantém as 5 tentativas por SDD. Ao esgotar, o `sdd-dead-lettered` passa a levar o **histórico**: um item por tentativa com o ponteiro do patch e do relatório (campo opcional e aditivo, no máximo 50 itens).
- **RF4 — Reenvio do Técnico** (8c). O reenvio com nota reabre **só aquele SDD**, com tentativas novas (mais 5) e a nota por ponteiro; os outros SDDs do lote não são tocados. Aceitar e cancelar seguem como estão.
- **RF5 — Resumo diário da DLQ** (8e). Um `schedule` cron diário dispara uma tarefa que lista os SDDs parados à espera do Técnico (ids e tentativas) e um e-mail `dlq-summary` ao `technician`. Sem SDDs parados, nenhum e-mail.
- **RF6 — Concorrência** (8f). Os SDDs de um lote correm ao mesmo tempo pelo `WorkerPool`; um teste com barreira (sem sleep) prova que as implementações de um lote de 3 estão em andamento juntas.
- **RF7 — Registro** (8g). Cada changeset é registrado como `patch` e cada relatório como `report` (`documentId` `<sddId>-a<n>-patch` e `-report`); cada registro publica o `document-registered`.
- **RF8 — Isolamento** (8h). O código gerado roda sem rede e sem segredos: uma guarda recusa o changeset antes de rodar (caminho fora da lista, tamanho, ou um segredo literal), e o contêiner é conferido por teste (`--network none`, nenhum `-e`, nenhum volume do hospedeiro, ambiente vazio).
- **RF9 — Modelos reais, medidos** (8i). Rodada manual: o llama.cpp local implementa SDDs de verdade; o alvo corre os testes no contêiner sem rede; relatório da taxa de acerto por tentativa. Só relatório.

## 4. Fora de escopo
- O gate (S.11); o Negócio (S.8) e a Engenharia (S.9).
- Mudar o Stint; o filtro (S.6) além de passar o texto do feedback por ele, se vier de uma pessoa.
- Código gerado *correto*: o CI prova a guarda, os testes e a cobertura, não a qualidade do modelo.
- Rede e segredos de verdade para o alvo (continua proibido).

## 5. Critérios de aceite
| CA | Critério | Evidência |
|---|---|---|
| CA1 | O feedback e a nota chegam ao `implement` seguinte: a tentativa 2 recebe o relatório e os critérios que falharam da 1; o reenvio recebe a nota; tudo por ponteiro | `DeliveryFeedbackTest` |
| CA2 | Os três validadores: um changeset que passa nos testes mas não cita um critério do SDD é reprovado pela cobertura e o relatório diz qual; um que não cumpre um rótulo do SDD é reprovado pela conformidade; um completo passa | `ValidatorsTest` |
| CA3 | Um SDD que falha 5 vezes cai na DLQ com o histórico de 5 itens (patch e relatório de cada tentativa), na ordem | `DeliveryWorkflowsTest` |
| CA4 | O reenvio com nota reabre só aquele SDD (mais 5 tentativas, a nota na entrada) e os outros terminam como estavam; aceitar e cancelar fecham o ciclo como `partial` ou `complete` | `DeliveryWorkflowsTest` |
| CA5 | O resumo diário lista os SDDs parados e manda um e-mail ao Técnico; sem parados, nada; dispara pelo cron do domínio | `DlqSummaryTest`, `NoticesTest` |
| CA6 | Os três SDDs de um lote têm a implementação em andamento ao mesmo tempo (barreira de 3) | `DeliveryConcurrencyTest` |
| CA7 | Cada changeset e cada relatório estão no catálogo como `patch` e `report`, na cadeia do projeto, com o `document-registered` publicado | `DeliveryWorkflowsTest`, `PipelineSkeletonIT`, `S.10-CA7.txt` |
| CA8 | A guarda recusa um caminho fora da lista, um arquivo grande e um segredo literal; o contêiner roda sem rede, sem variáveis de ambiente e sem volumes do hospedeiro (a linha de comando é conferida; com Docker, `-Dtarget.docker=true`, um teste que abre um socket falha e o ambiente está vazio) | `ChangesetGuardTest`, `DockerIsolationTest` |
| CA9 | S.1–S.9 continuam verdes e o varredor em zero | `gate.sh --regression-only` |

## 6. Mudanças de asserção a aprovar
O RF7 faz o `p1` e o `p2` registrarem patches e relatórios com o `document-registered`. Isso muda **duas asserções que já existem** no `PipelineSkeletonIT`, e a regra é parar e pedir antes:
1. `count(ofP1, DOCUMENT_REGISTERED) == 4` (o desenho, o lote e dois SDDs) passa a contar também os patches e relatórios de cada tentativa do `p1` (dois SDDs, um deles com 2 tentativas): 4 + 3 patches + 3 relatórios = **10**.
2. O `PipelineSkeletonIT` do S.7 (`o catálogo tem os documentos que o barramento anunciou`) continua verdadeiro, porque cada registro publica seu evento; só o número do item 1 muda.

Se você não quiser mudar essas asserções, a alternativa é registrar sem publicar o evento (o catálogo ganha os documentos e o barramento não); a asserção 2 do S.7 teria de ser relaxada do mesmo jeito. Recomendo publicar o evento (o catálogo e o barramento continuam contando a mesma história) e aprovar o 4 → 10.

## 7. Plano de tarefas (etapa 2, após aprovação)
1. Teste de feedback (CA1) e de reenvio (CA4): primeiro, para ver o que falha. Um `DeliveryFixture` com stand-ins que lembram as chamadas.
2. O contexto da instância guarda `feedback`; o `implement` e o `validate` o usam.
3. Os três validadores (CA2) e o relatório estruturado.
4. O histórico na DLQ (CA3); o campo aditivo no contrato.
5. O registro de patches e relatórios (CA7) e as duas asserções (item 6).
6. O cron e a tarefa de resumo (CA5); `dlq-summary` no `Mail`.
7. A guarda do changeset e a prova do contêiner (CA8); a barreira (CA6).
8. A rodada manual (RF9); regressão.

## 8. Questões em aberto: propostas para aprovação

### 8a. O feedback
**Proposta:** o contexto da instância guarda `lastReportRef`, `failedCriteria` (ids) e `noteRef`. O `implement` recebe os três como ponteiros e ids. O agente do CI é determinístico (o "falha até a tentativa k" do gabarito) e **registra** o que recebeu, para o teste provar; o agente real (manual) os lê.

### 8b. Os validadores
**Proposta:** três etapas dentro de `validate` (uma tarefa), cada uma com seu resultado no relatório: `tests` (do alvo), `coverage` (cada `CA<n>` do SDD é citado por um teste do changeset, por um comentário `# CA<n>` ou pelo nome do teste) e `conformance` (no CI, cada item de `covers` do SDD aparece no changeset; na rodada manual, o LitJev ou o llama.cpp local confere o changeset contra o SDD, o desenho e o lote). A reprovação por cobertura ou por conformidade vale tanto quanto a dos testes.

### 8c. A DLQ e o reenvio
**Proposta:** o histórico é uma lista de `{attempt, patchRef, reportRef}` no `sdd-dead-lettered` (pointer-only, no máximo 50). O reenvio mantém a regra atual (`limit = attempt + 5`), e a nota do Técnico vem pelo `noteRef` da resposta da revisão.

### 8d. Quem lê os documentos: lista de leitores no registro
**Proposta:** o `register-document` aceita `readers` (domínios, opcional); o `read-record` atende o dono **e** os leitores listados. O desenho é registrado com `readers: [engineering, delivery]`, e o lote e os SDDs da Engenharia com `readers: [delivery]` (campo de entrada novo nos dois workflows, aditivo). Sem a lista, vale o S.7 (só o dono). Isso também tira o limite do S.9, 8d (a Engenharia não lia o desenho). **Escolha sua:** a alternativa é o validador receber os ponteiros dos documentos no `sdd-ready` e lê-los direto (já é assim com o `sddRef`); abre mão do controle por rota.

### 8e. O resumo diário
**Proposta:** um workflow `dlq-summary` do domínio, com `schedule` cron (`0 9 * * *`, UTC), que chama a tarefa `summarize-dlq` (consulta as instâncias do `sdd-delivery` esperando o Técnico) e, se houver, `send-email` com o template novo `dlq-summary`. No domínio em memória, o teste dispara o gatilho à mão; no floci, a prova é que o agendamento é reconciliado e o gatilho manual roda o fluxo (o cron em si é do EventBridge Scheduler, que o floci aceita e que a AWS dispara).

### 8f. A concorrência
**Proposta:** a barreira vai dentro de um `implement` de teste que espera os três SDDs de um lote e só então responde. Se os três não ficassem em andamento juntos, o teste falharia por prazo.

### 8g. Patches e relatórios no registro
**Proposta:** o `implement` e o `validate` não registram sozinhos (um worker não publica fora do outbox, decisão do S.5): o workflow chama `register-document` e emite o evento, como o Negócio. `patch` e `report` já são tipos do S.7, com dono `technician`.

### 8h. O isolamento
**Proposta:** `ChangesetGuard` (no `sandbox-target`) recusa caminho fora de `catalog/` e `tests/`, mais de 64 KiB por arquivo e texto que pareça um segredo (chaves `AKIA…`, `-----BEGIN … KEY-----`, `password=`); isso é uma segunda linha, a primeira é o `--network none`. O `DockerIsolationTest` confere a linha de comando sempre, e roda o contêiner de verdade só com `-Dtarget.docker=true`.

### 8i. Modelos reais
**Proposta:** relatório manual, sem critério: o Qwen3-4B (llama.cpp) implementa dois SDDs do corpus a partir do texto do SDD; o alvo corre os testes no contêiner sem rede; o relatório tem a taxa de acerto por tentativa e o que o validador de cobertura reprovou.

### 8j. Expectativas de achados
- A cobertura por citação de `CA<n>` depende de o agente seguir a convenção; um modelo pequeno pode errá-la, e isso vira dado do relatório, não do critério.
- O resumo diário depende de a consulta de instâncias esperando o Técnico ser possível pelo estado do Stint; se não for, o resumo usa o estado próprio da Entrega (o mesmo que o `check-sdds` já lê), e é anotado.

## 9. Registro de validação

**Ambiente.** O do S.5 a S.9 (Temurin 25.0.3, Maven 3.9.15, Quarkus 3.33.2, Stint `500dfd1` sob `0.1.0-3a-500dfd1`, floci, Docker 28.0.1). O llama.cpp (Qwen3-4B) subiu só para a rodada manual e foi parado em seguida.

**Aprovação.** Kayan aprovou o S.9 e o S.10 em 21/10/2026 (confirmado depois da implementação: "approvado para o S.9 e S.10"). Valem as propostas 8a a 8j e a mudança de asserção do item 6 (`p1` `DOCUMENT_REGISTERED`: 4 → 10, com a lista `readers` no registro). Kayan também decidiu que a regressão (CA9) só roda depois da última implementação da fase, por ser muito demorada.

### 9.1 Critérios de aceite
| CA | Resultado | Evidência |
|---|---|---|
| CA1 | **Verde.** A tentativa 2 recebe o ponteiro do relatório da 1 e os critérios que falharam (`failedCriteria`), e o reenvio recebe a nota (`noteRef`); tudo por ponteiro, nenhum texto no contexto | `DeliveryFeedbackTest` (6) |
| CA2 | **Verde.** Testes passando sem citar um `CA<n>` reprovam pela cobertura e o relatório diz qual (CA1 não cobre CA10); um `RF<n>` ausente do changeset, um lote técnico sem o id do SDD ou um desenho em branco reprovam pela conformidade; um changeset completo passa | `ValidatorsTest` (7) |
| CA3 | **Verde.** Um SDD que falha 5 vezes cai na DLQ com o `history` de 5 itens (`attempt`, `patchRef`, `reportRef`), na ordem; o campo é aditivo no contrato (no máximo 50) | `DeliveryWorkflowsTest`, `DeliveryFeedbackTest` |
| CA4 | **Verde.** O reenvio com nota reabre só aquele SDD (6 tentativas no total, a nota na 6ª) e os outros ficam como estavam; aceitar fecha como `complete` e cancelar como `partial` | `DeliveryWorkflowsTest`, `DeliveryFeedbackTest` |
| CA5 | **Verde.** O resumo lista os SDDs parados ("SDDs waiting for the technician: N", um item por SDD com as tentativas) e o e-mail `dlq-summary` vai ao Técnico com a lista como corpo; sem parados, nenhum resumo nem e-mail; o workflow tem `schedule: cron: '0 9 * * *'` e o motor o reconcilia no EventBridge Scheduler | `DlqSummaryTest` (3), `NoticesTest`, `PolicyTest` |
| CA6 | **Verde.** Os três SDDs de um lote têm a implementação em andamento ao mesmo tempo (barreira de 3, sem sleep) | `DeliveryConcurrencyTest` |
| CA7 | **Verde.** No esqueleto, o `p1` tem os patches `rec-p1-sdd-1-a1-patch`, `rec-p1-sdd-2-a1-patch` e `rec-p1-sdd-2-a2-patch` e os relatórios correspondentes no catálogo, e o `document-registered` de cada um publicado; `DOCUMENT_REGISTERED` do `p1` = 10 | `PipelineSkeletonIT`; `S.10-CA7.txt` |
| CA8 | **Verde.** A guarda recusa caminho fora da lista, arquivo acima de 64 KiB e segredo literal (a razão nomeia arquivo e regra, não o texto), e a recusa vira desfecho `invalid`, não falha de tarefa; a linha de comando do contêiner não tem rede, `-e`, volume, privilégio, e a imagem é por digest. Com `-Dtarget.docker=true`, o contêiner não abre socket e o ambiente é o da imagem | `ChangesetGuardTest` (13), `DockerIsolationTest` (com Docker) |
| CA9 | **Pendente por decisão do Kayan.** A regressão (`gate.sh --regression-only`) roda depois da última implementação da fase. Uma execução foi interrompida por falta de memória e não deu resultado | — |
| 8i | **Medido.** O Qwen3-4B (llama.cpp) implementou dois SDDs sintéticos (`t-year` e `t-authors`) e o contêiner sem rede validou os dois **na primeira tentativa** (2 de 2), com a cobertura de `CA<n>` completa | `S.10-llm.txt` |

**Contagens.** Records, channels e delivery verdes (delivery 21/21); target 30 testes, 1 pulado sem Docker (o `DockerIsolationTest` do contêiner e o `ImplementerLlmTest` rodaram à parte, com os sinalizadores); esqueleto 271,7 s, `SKELETON-DONE 0`.

### 9.2 Ressalvas e desvios
- **A mudança de asserção foi feita e está aprovada:** `count(ofP1, DOCUMENT_REGISTERED)` de 4 para 10. Nenhuma outra asserção existente mudou.
- **O resumo lê a lista de parados da própria Entrega** (`ParkedSdds`, tabela `delivery-parked`), não o estado do Stint: o `StateStore` do Stint não lista instâncias. É o caso previsto em 8j; a tabela entrou em `infra/bootstrap.sh` e `infra/reset.sh`.
- **A conformidade do CI é por rótulos:** cada `RF<n>` do SDD citado no changeset (no docstring do módulo), o id do SDD no lote técnico e o desenho não vazio. Não mede se o código faz o que o RF diz. A conferência por modelo local, citada em 8b, não foi feita (só a medição de 8i).
- **A cobertura depende de o agente citar `# CA<n>`**, como a spec previa; o agente do CI o faz, o Qwen3-4B o fez nos dois casos.
- **A lista `readers`** (8d) também resolve o limite do S.9 (a Engenharia não lia o desenho): o desenho é lido por `engineering` e `delivery`, e o lote e os SDDs por `delivery`. Os domínios aceitos são `business`, `engineering` e `delivery`.
- **O corpo do e-mail do resumo** vem do `documentRef` (o `Mail.send` lê o corpo de lá); o `projectId` do envio é `delivery`, que cabe no padrão exigido.
- **O cron é do EventBridge Scheduler** (que o floci aceita): a prova no CI é que o workflow declara o agendamento, o motor o reconcilia e o gatilho manual roda o fluxo. O disparo por horário não foi observado.
- **A rodada manual não lê o feedback pelo workflow:** o harness passa o feedback como texto ao modelo (no sistema ele vai por ponteiro). Como o modelo acertou na primeira tentativa, o ciclo de feedback com o modelo real não foi exercitado.

### 9.3 Achados (nada alterado no Stint)
| # | Achado | Proposta |
|---|---|---|
| S10-1 | O `StateStore` do Stint não lista instâncias; o resumo da DLQ exigiu uma tabela própria da Entrega, que precisa ficar coerente com o estado da instância (entra no `deadLetter`, sai no `unpark`) | Uma consulta de instâncias por estado no Stint evitaria a duplicação; até lá, a lista própria |
| S10-2 | A conformidade determinística é de rótulos e a cobertura é de citação: ambas são convenções que o agente pode cumprir sem acertar o comportamento (o modelo pode citar `CA1` num teste vazio) | O relatório só garante a convenção e os testes; a verificação de conteúdo é do modelo local (8b) ou do Técnico, e fica para um SDD próprio |
| S10-3 | O Qwen3-4B acertou 2 de 2 na primeira tentativa em dois SDDs pequenos e sintéticos; a amostra não mede nada sobre SDDs reais | Medir com SDDs reais da Engenharia na 3b antes de qualquer conclusão |
| S10-4 | A regressão completa é lenta e o `mvn clean install` do gate apaga o `target` (a evidência do esqueleto se perdeu uma vez e foi regerada) | Rodar o gate uma só vez, no fim da fase, e copiar as evidências para `docs/sdd/evidence/` antes dele |

### 9.4 Pendências
- CA9: a regressão, depois da última implementação da fase (decisão do Kayan).
- Validado e Mergeado são seus. O push, com o seu pedido.
- Notion: a página do S.10 e o Log.
