# Log: SDD S.5 — Fundação do pipeline de reuniões (sandbox)

Pronto para a Base de Conhecimento (Tipo = Log, Status = Ativo, Tags = quarkus, geral). Não foi criado no Notion porque o MCP do Notion não estava conectado em 15–19/10/2026. A página do S.5 em "Stint · SDDs" também espera esta atualização: **Status = Implementado**, Análise = Atualizada, Commit analisado = `Stint 500dfd1 · sandbox 4feeeb1+` e o corpo = `docs/sdd/S.5.md`.

**Data:** 19/10/2026 · **Branch:** `phase-3a-validation` (empilhada sobre `phase-2-validation`) · **Stint:** `500dfd1`

## O que foi feito
- Montei a base do pipeline de reuniões (Negócio → Engenharia → Entrega) com tudo falso, e provei a fiação no floci, na pilha local de modelos e numa regressão completa do S.1 a S.4.
- Sete módulos novos, cada um dependendo só do `sandbox-contracts`: business, engineering, delivery, privacy, records, channels e target.
- Contrato de eventos v1 (`contracts/pipeline-events.md` e schemas), com correlação por extensões (`sandboxproject`, `sandboxcycle`, `sandboxsdd`) e dados só com ids e ponteiros.
- Corpus de 14 reuniões em inglês, com gabarito por decisão e PII com formas escritas e faladas; áudio do Kokoro reproduzível pelo hash.
- Portal com papéis (credenciais só de teste) que publica `review.completed` com o `source` confiável.
- Alvo em Python rodando em container sem rede, com limites e argv fixo.
- Orders e Billing trocados para o `SqsWorkerBinding` com `WorkerPool`: p95 caiu de 31,1 s para 7,6 s.

## Resultados
CA1 a CA7 verdes (detalhe e evidências em `docs/sdd/S.5.md`, seção 9, e `docs/sdd/evidence/S.5-*`). Esqueleto: 2 projetos, uma cadeia cada, nenhuma sobra. CA7: 24 s da gravação ao `design-published`, GPU em 7,8 de 8 GB.

## Decisões do Kayan
- 8k: branch empilhada (opção 1). 8a: uma instância por SDD e um coordenador (A). Aprovação sem escolha explícita: valeram as recomendações.
- 18/10: o coordenador espera cada SDD pelo id, e depois, ao saber do limite de 15 min do inbox, passou a **consultar o estado** (`check-sdds`).

## Achados do Stint (nada foi alterado no Stint)
1. Sem `for`/`fork` dinâmico (proposta SDD 3a.8).
2. A invocação de tarefa não leva endereço de resposta (proposta 3a.9: `stintreplyto`).
3. Instância sem cancelamento externo.
4. Re-esperar na chave consumida no mesmo save falha sempre com `CONFLICT` (já é o SDD 3a.10).
5. A janela de eventos adiantados é limitada a 15 min (`PT15M`).
6. A resposta de revisão fica no inbox para as fases seguintes até o TTL.
7. A PII falada escapa do redator de referência (medido no CA7: `redact` devolveu `counts: {}`); camada de forma falada fica para o S.6.
8. O `score` do Jev exige `scoreLevels` configurados.

## Cuidados que valem para as próximas sessões
- **Stint instalado sob versão própria** (`0.1.0-3a-500dfd1`, `infra/install-stint.sh`): um `mvn install` do Stint em outra fase no `~/.m2` já contaminou o jar uma vez.
- O Docker Desktop cai com o C: quase cheio (17 GB livres); ao retomar, conferir `docker compose ps` antes de rodar o gate.
- `reset.sh` e `bootstrap.sh` rodam em paralelo (2 min 40 s); o limite do `Scenario.reset()` é de 5 min.
- Pilha local: `bash infra/local-stack.sh up|down|status` (WSL2, loopback). Liberar a GPU depois com `down`.

## Pendências
- Validado e Mergeado ficam com o Kayan.
- S.6 (PII falada), S.7 (registro), S.8 a S.10 (lógica dos domínios), S.11 (gate da 3a).
