# Gate da Fase 2: validação externa pelo stint-consumer-sandbox

| Campo | Valor |
|---|---|
| SDD | S.4 (RF6, CA7) |
| Stint | `b1f4579` (`phase-2/federated-orchestration`, no remoto), SDDs 2.1–2.9 |
| Sandbox | `phase-2-validation` @ `33d4942` (regressão no `434a4b3`, que só difere na configuração do build nativo) |
| Data | 11/10/2026 |
| Ambiente | Windows 11, JDK 25 (Temurin 25.0.3), Maven 3.9.15, Quarkus 3.33.2, Docker Desktop (Hyper-V, 10 GB) com floci, MailHog e Jaeger do `docker-compose.yml` |
| Comando | `bash infra/gate.sh --regression-only --repeat 3`, `bash infra/gate.sh --only 'ChaosIT,LoadIT,ObservabilityIT'` e `bash infra/gate.sh --native` |

## 1. Recomendação

**Recomendo aprovar o gate da Fase 2, com uma condição: o achado 4.1 (funções builtin do jq ausentes no nativo) corrigido no Stint, ou aceito explicitamente como pendência do 2.7, antes do merge do PR das SDDs 2.4–2.9.**

- Todos os critérios do S.4 estão verdes (seção 9 do `docs/sdd/S.4.md`):
  - a regressão completa do S.1–S.3 passou 3 vezes seguidas;
  - os 5 cenários de caos, a carga com 50 pedidos e o trace entre domínios passaram sem sobras;
  - o caminho feliz passou com os dois domínios em imagem nativa.
- **O que sustenta a recomendação:**
  - cada SDD 2.1–2.9 foi exercitado de ponta a ponta por dois domínios soberanos, ligados só pelo contrato de eventos, sobre a API AWS do floci (seção 2);
  - nenhum processo morto no pior momento deixou estado preso, fato perdido ou efeito duplicado visível ao cliente: um e-mail por pedido, uma fatura por pedido;
  - os dois achados do S.2 estão resolvidos no 2.9 e os contornos saíram.
- **A condição.** O 2.7 promete imagem nativa, e o smoke nativo do Stint não usa nenhuma função jq. Um consumidor real que valide a entrada com `type` ou `length` quebra no nativo sem o contorno. A correção é pequena (registrar os provedores do jackson-jq no Stint e cobrir isso no smoke), mas é do Stint, e o gate não deveria esconder isso.
- Nada nas observações da seção 5 bloqueia o gate. São insumos para o plano da Fase 3.

## 2. SDD do Stint → cenário do sandbox

Cada linha diz o que o SDD promete, onde o sandbox exercitou isso de ponta a ponta (dois domínios soberanos, ligados só pelo `contracts/events.md`) e com que resultado. As evidências estão em `docs/sdd/evidence/`.

| SDD | O que promete | Cenário do sandbox | Resultado | Evidência |
|---|---|---|---|---|
| 2.1 | Início por evento (`DomainEventSource`, `schedule.on`) com id de instância determinístico: o mesmo evento não cria duas instâncias | S.3 CA5 (o mesmo `POST /orders` duas vezes ao mesmo tempo, por barreira); RF2d (duas réplicas do engine do Orders consumindo a mesma fila) | Verde: 1 instância e 1 e-mail no CA5; no RF2d, 20 pedidos repartidos entre as réplicas (cada instância processada por uma só) e 20 confirmações | `S.3-CA5.txt`, `S.4-CA3-d.txt` |
| 2.2 | `emit` com outbox transacional: o fato é gravado com o checkpoint e publicado depois; o relay varre o que ficou | RF2b (engine do Billing morto por `halt(137)` dentro do `publish` do `invoice-issued`, depois do checkpoint) | Verde: 1 fato pendente no `billing-outbox` com o engine caído, 0 `invoice-issued` no barramento; o engine religado publicou o fato pelo relay (~30 s) uma vez; 1 confirmação | `S.4-CA3-b.txt` |
| 2.3 | `listen` com correlação, timeout e inbox para evento adiantado ou repetido | S.3 CA6 (o mesmo `invoice-issued` 2×); RF2c (engine do Orders morto com o pedido no `listen`); RF2d (5 duplicatas com duas réplicas) | Verde: 1 e-mail no CA6; no RF2c o evento esperou na fila com o Orders caído e o engine religado retomou; no RF2d nenhuma confirmação dobrada (ver 5.4) | `S.3-CA6.txt`, `S.4-CA3-c.txt`, `S.4-CA3-d.txt` |
| 2.4 | `schedule.cron` reconciliado e timers longos pelo EventBridge Scheduler (one-time `at(...)`), apagados quando deixam de valer | S.2 CA5 (resumo por cron a cada minuto, ocorrência duplicada); S.3 CA3 (timeout curto, depois espera de 24 h no Scheduler, apagada ao confirmar; disparo tardio simulado ignorado); RF2e (10 pedidos) | Verde: 1 e-mail por ocorrência; no RF2e 10 avisos de atraso, 10 agendamentos one-time, 10 confirmações e 0 agendamentos no fim; o disparo tardio sai como `decision=obsolete` | `S.2-CA5.txt`, `S.4-CA2-s3-ca3-run{1..3}.txt`, `S.4-CA3-e.txt` |
| 2.5 | Linhagem: `chainid`/`causationid` em todo evento, contexto de trace entre domínios (links), MDC nos logs | S.2 CA1; S.3 CA1 (4 eventos com o mesmo `chainid`); RF3 (50 cadeias distintas); RF5 (Jaeger + logs) | Verde: trace do Billing com `FOLLOWS_FROM` para o span do Orders que publicou o pedido, e a retomada do Orders com `FOLLOWS_FROM` para o span do Billing; linhas de log dos dois processos com o mesmo `stint.chainId` | `S.2-CA1.txt`, `S.3-CA1.txt`, `S.4-CA4.txt`, `S.4-CA6.txt`, `S.4-CA6-jaeger.json` |
| 2.6 | O marco: workflows independentes ligados só por eventos (`emit` → `schedule.on`), cada um no seu domínio | A federação inteira: S.3 CA1 (Orders → Billing → Orders), independência de compilação (S.2 CA7, S.3 CA9, enforcer do `sandbox-scenarios`) | Verde: nenhum módulo depende do outro; o único artefato comum é o contrato | `S.3-CA1.txt`, `S.2-CA7.txt`, `S.3-CA9.txt` |
| 2.7 | Extensões Quarkus dos conectores AWS e imagem nativa | Todos os cenários rodam os jars Quarkus; RF4 roda os dois domínios como imagens nativas em containers na rede do compose | Verde **com contorno**: sem ele, o nativo do Orders falha em toda expressão jq com função builtin (achado 4.1); com `quarkus.native.auto-service-loader-registration=true`, o caminho feliz do S.3 CA1 passa nos dois binários | `S.4-CA5.txt` |
| 2.8 | Erros padrão da DSL 1.0 (`type` URI + `status`) no fio e nos `catch` | S.2 CA3 (render com 503 duas vezes, sucesso na 3ª); S.3 CA7; RF3 (5 pedidos `fail503-` sob carga) | Verde: o `catch` filtra `communication`/503; tentativas 1 e 2 falham, 3 passa; sob carga, os 5 confirmam | `S.4-CA2-*` (FlociBillingIT `ca3`, FlociOrdersIT `ca7`), `S.4-CA4.txt` |
| 2.9 | Correções da validação externa: delay SQS arredondado para cima, disparo adiantado reentregue, `with.input` dentro de `try`, STRICT completo | RF0: o `issue-invoice` voltou à DSL pura (`with.input` dentro do `try`, retry de 1 s), o piso de 2 s do Orders saiu, os 5 YAMLs carregam em STRICT; S.2 CA3 com 1 s nas 3 regressões; RF3 | Verde: 0 disparos `early` nos logs; nenhuma instância parada | `S.4-CA1.txt`, `S.4-CA2-*`, `S.4-CA4.txt` |

**Regressão (RF1, CA2).** `bash infra/gate.sh --regression-only --repeat 3` passou 3 vezes seguidas no `434a4b3`. Em cada rodada passaram o build com todos os testes unitários, `FlociBillingIT` 5/5 (S.2 CA1–CA5), `FlociOrdersIT` 6/6 (S.3 CA1, CA2, CA4–CA7, com o Billing como processo) e `RegressionIT` 3/3 (S.1 CA2, S.2 CA6, S.3 CA3, os antigos roteiros manuais). Nos logs, os timers registraram 123 decisões `obsolete` e 0 `early` (`S.4-CA2.txt`).

## 3. Real × simulado

**Real (de ponta a ponta, sobre a API do floci):**
- SQS: filas de task, resultado, timer e eventos de domínio, com visibilidade, `DelaySeconds` e DLQ (`maxReceiveCount`);
- DynamoDB: instâncias, esperas, outbox (GSI `pending-by-age`, 4 shards), inbox e efeitos, com escrita condicional por versão;
- S3: documentos das faturas e fatos grandes;
- EventBridge: barramento `sandbox-domain` com as regras do contrato;
- EventBridge Scheduler: cron do resumo e agendamentos one-time dos timers longos;
- SMTP no MailHog e OTLP no Jaeger;
- processos de verdade: cada domínio é um JVM (ou um binário nativo num container) separado, morto com `TerminateProcess`/`docker kill` ou `Runtime.halt(137)`.

**Simulado:**
- IAM e bucket policy: o floci aceita, mas não aplica;
- o Scheduler do floci não manda o horário agendado; o Stint usa o fallback `sent-timestamp` (o log avisa `delivered without its scheduled time`);
- falhas do renderer: o prefixo `fail503-` produz 503 nas tentativas 1 e 2, e `slow-` atrasa 5 s;
- as mortes no ponto exato são `Runtime.halt(137)` atrás de flags `billing.chaos.*`, desligadas por padrão (S.4 8b);
- as duplicatas são republicadas à mão no barramento, com o mesmo `source`/`id`;
- o disparo tardio de um timer apagado é uma mensagem injetada na fila de timers (S.3 CA3).

## 4. Achados do Stint e estado

| # | Achado | Origem | Estado |
|---|---|---|---|
| 4.1 | **Nativo sem as funções builtin do jq.** O build nativo do Quarkus desliga o `ServiceLoaderFeature`; o jackson-jq carrega `type`, `length` e as demais por `ServiceLoader`, então toda expressão com função falha no binário. O smoke nativo do 2.7 só usa caminhos e não pegou (o `docs/connectors.md` diz "Native: proven by stint-example-aws-smoke") | S.4 8i | **Aberto no Stint.** Contorno no sandbox decidido por Kayan: `quarkus.native.auto-service-loader-registration=true`. Correção sugerida: registrar as funções no próprio Stint e pôr uma função builtin no smoke nativo |
| 4.2 | `with.input` de uma `call: remote` dentro de `try` era ignorado | S.2 8h | Resolvido no 2.9 (RF4). Contorno removido no RF0 |
| 4.3 | Timer de retry de 1 s podia disparar antes de a espera existir e se perder | S.2 8i (e S.3 8h) | Resolvido no 2.9 (arredondamento e reentrega `early`). Contornos removidos no RF0; 0 `early` nos logs |
| 4.4 | Dev service do SNS subia o LocalStack no teste do smoke AWS | 2.7 | Resolvido no 2.9 (8g-3) |

Os achados 4.1–4.3 ainda precisam ser copiados para a seção 8 dos SDDs 2.7 e 1.3 do Stint no Notion, inacessível nesta sessão.

## 5. Observações para o plano

1. **Sem `start` programático idempotente.** `WorkflowEngine.start(ref, input)` sempre gera um id novo. O Orders resolveu com um evento de entrada (S.3 8a), o que deixa o `POST` assíncrono (202). Um `start` com chave de idempotência evitaria isso.
2. **Ruído de timers SQS não canceláveis.** Os timers de timeout de task (5 min por padrão) ficam atrasados na fila `<d>-timers` depois de a instância terminar, e disparam como `obsolete` (ou `obsolete-after-window`). Não são sobras, mas aparecem em toda contagem de fila e em log (2.9, 8g-4).
3. **Latência sob carga vem do worker de uma thread.** Na carga com N=50, o p50 ponta a ponta ficou em 28,3 s e o p95 em 31,1 s, inclusive para os pedidos sem `slow-`. O worker do Billing consome um lote de até 10 tasks e as executa em série: cada lote com um `slow-` (5 s) segura os outros 9, e as tasks `record-invoice` entram na mesma fila atrás dos renders. É limite do worker do sandbox (`BillingEngine`, uma thread `billing-worker`), não do engine. O Stint poderia oferecer um worker com concorrência configurável no `stint-worker-sdk`.
4. **Duplicata que corre com o original.** No RF2d, 4 das 5 duplicatas ficaram no `orders-inbox` e 1 foi descartada como já consumida: ela chegou enquanto a espera existia, perdeu a gravação condicional para o evento original, e o engine a reconheceu (`WorkflowEngine.java:468`). As duas rotas são corretas e nenhuma confirmação saiu dobrada. A spec do S.4 (8e) previa "exatamente o número de duplicatas" no inbox; o cenário passou a exigir "no máximo".
5. **Falha de expressão sem causa.** Quando uma expressão jq falha na ativação, a instância fica `FAILED` e o log mostra só `Failed to evaluate jq expression: <fonte>`, sem a `JsonQueryException` de baixo. Foi o que deixou o achado 4.1 difícil de diagnosticar.
6. **Docker Desktop no Hyper-V e bind mounts.** Um container com volume de `C:` nem é criado. O build nativo usa `quarkus.native.remote-container-build=true`, e os binários rodam numa imagem montada com `docker build` (S.4, seção 9).
7. **Notion inacessível.** Nem a política "Regras gerais para agentes" nem as páginas dos SDDs puderam ser lidas ou atualizadas nesta sessão. As páginas S.1–S.4, o Log da Biblioteca e a cópia dos achados 4.1–4.3 para os SDDs do Stint ficam pendentes.

## 6. Como repetir

```
docker compose up -d
bash infra/gate.sh --regression-only --repeat 3      # CA2
bash infra/gate.sh --only 'ChaosIT,LoadIT,ObservabilityIT'   # CA3, CA4, CA6
bash infra/gate.sh --native                          # CA5 (Docker com ~8 GB)
```

Cada execução grava a saída e os logs dos processos em `gate-out/<data-hora>/` (fora do git).
