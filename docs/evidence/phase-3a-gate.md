# Gate da Fase 3a: validação externa pelo stint-consumer-sandbox

| Campo | Valor |
|---|---|
| SDD | S.11 (RF0–RF8, CA1–CA8) |
| Stint | `a52b20a` (`phase-3a/ai-decision-workers`, SDDs 3a.1–3a.11). S.5 a S.10 rodaram no `500dfd1` (3a.1–3a.7); a subida foi decisão do Kayan (opção 2, seção 8k do S.11) |
| Sandbox | `phase-3a-validation` |
| Data | 22/10/2026 |
| Ambiente | Windows 11, JDK 25, Maven 3.9.15, Quarkus 3.33.2, Docker Desktop (Hyper-V), floci/MailHog/Jaeger do compose; modelos locais (Whisper, LitJev, llama.cpp) só no CA7 |
| Comando | `bash infra/gate.sh --regression-only` (CA1), `PipelineChaosIT`, `PipelineLoadIT`, `PipelineNativeIT`, rodada de modelos reais |

## 1. Recomendação

**Recomendo aprovar o gate da Fase 3a, com duas ressalvas registradas (S11-1 e S11-3), nenhuma bloqueante.**

- A regressão completa passou: Billing 5/5, Orders 6/6, `RegressionIT` 3/3, `PortalRolesIT`, `PiiSweepIT` e o esqueleto do pipeline 3 vezes seguidas, cada um em floci novo, com o varredor em zero.
- Os 7 cenários de caos, a carga com 6 projetos simultâneos e os 3 domínios nativos que compilaram terminaram sem marcador, sem evento fora do contrato e sem sobras.
- A rodada com modelos reais mediu a qualidade e não encontrou falha de contrato; a qualidade é observação, não critério.
- **Ressalvas:** S11-1 (dúvida de redação termina sem revisão humana) e S11-3 (Business não compila em nativo).

## 2. SDD do Stint → cenário → resultado

| SDD 3a | Cenário | Resultado | Evidência |
|---|---|---|---|
| 3a.1–3a.7 (domínios, privacidade, ponte, decisões, SDD writer, implementador) | `PipelineSkeletonIT` ×3 no gate; caos a–g; carga | Verde | `S.11-CA1.txt`, `S.11-CA2-*.txt`, `S.11-CA4.txt` |
| 3a.8–3a.11 (correções vindas dos achados do S.5) | Regressão no `a52b20a` | Verde; os contornos do S.5 não foram removidos | `S.11-CA1.txt` |
| Primeira decisão após morte do worker | `PipelineChaosIT.g` | Verde: um `design-published`, prioridade igual à chave | `S.11-CA3.txt` |
| Varredor de PII (bus, logs, stores, spans) | dentro de todos os cenários + `PiiSweepIT` | Zero marcador; controle positivo verde | `S.11-CA2-*.txt` |
| Nativo | `PipelineNativeIT` | privacy, records e channels rodam o ciclo do p1; business não compila | `S.11-CA6*.txt` |
| Modelos reais | `S.11-models*.txt` | medido, sem falha de contrato | `S.11-models.txt` |

## 3. Real e simulado

- **Real:** floci (API AWS, credenciais dummy), MailHog, Jaeger, os sete domínios como processos, o portal, o varredor, os pontos de caos (`Runtime.halt(137)`), três imagens nativas, Whisper, LitJev e llama.cpp locais na rodada do CA7.
- **Simulado:** o revisor (script que responde pela chave do corpus), as reuniões (corpus sintético, sem dado real), o provedor de LLM nos testes de cenário (nunca um provedor real).

## 4. Resultados por critério

| CA | Resultado |
|---|---|
| CA1 regressão completa | Verde (22/10, 10:39–11:38) |
| CA2 caos a–f | Verde, 7 cenários com o g |
| CA3 primeira decisão | Verde via `PipelineChaosIT.g`; o teste unitário `DecisionReplayTest` da spec 8c não foi escrito |
| CA4 carga, 6 projetos | Verde: 6 cadeias, 6 ciclos; p50 por etapa ≈ 3,0 / 6,4 / 11,8 / 12,1 s; execução toda 28,3 s |
| CA5 varredura | Verde em caos, carga e esqueleto |
| CA6 nativo | privacy (0,054 s), records (0,197 s), channels: ciclo do p1 com zero marcador; business falha (S11-3); engineering, delivery, target não tentados |
| CA7 modelos reais | LitJev tipo de negócio 6/7, canal 7/7, prioridade (±0,2) 3/7; técnico: tipo 5/7, desfecho 3/7, bloqueio 5/7; SDD writer 8/8 no formato, 12/13 coberturas; implementador 2/2 na 1ª tentativa; uma reunião ponta a ponta em 35 s, GPU 7933 de 8192 MiB |
| CA8 este relatório | Entregue |

## 5. Achados consolidados

| Id | Achado | Estado | Proposta |
|---|---|---|---|
| S11-1 | Com dúvida de redação o ramo `onDoubt: doubt` encerra a instância com `redaction-doubt`, sem revisão humana (a spec previa ir ao humano) | Aberto; fail-closed comprovado (nada publicado, nada vazado) | Construir a revisão da dúvida em Business/Engineering |
| S11-2 | Nativo exige registro Jackson dos records do contrato | Corrigido em privacy e channels (`NativeReflection`) | Manter; avaliar anotação no contrato se ele puder depender do Quarkus |
| S11-3 | Business não compila em nativo (`Random` no heap da imagem, LangChain4j) | Aberto | Inicialização em runtime da classe ou remoção do `Random` estático; decisão da Fase 3b |
| S5 (`stintreplyto`, cancelamento externo, espera do reopen) | Corrigidos no Stint 3a.9–3a.11 | Corrigido no Stint, ainda não usado | Remover os contornos numa fase futura |
| S6-1…S6-5, S8-1…S8-4, S9-1/3/4, S10-1…S10-4 | Ver as seções 9 dos respectivos SDDs | Conforme registrado lá | — |
| Obs. | `reset` do floci leva 4–5 min; limite do `Scenario.reset` subiu de 5 para 10 min. Pressão de memória na máquina (3 execuções mortas) | Observação | `.wslconfig` com limite de memória |

## 6. Observações para a Fase 3b

- A janela de 15 minutos para eventos antecipados (S5) segue valendo.
- A transcrição do Whisper deforma marcadores plantados: a forma escrita do nome sai, mas o varredor ainda acha; a do e-mail sai e o varredor não acha. O varredor de formas ditas precisa de cobertura própria.
- O custo de memória da pilha de modelos (~8 GB) pede execução em etapas.
