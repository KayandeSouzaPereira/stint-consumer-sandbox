# Contrato de eventos do sandbox (SDD S.1, RF6)

Este documento é o **único** acordo entre `sandbox-orders` e `sandbox-billing`. Nenhum dos dois módulos
depende do outro em código: cada um conhece só os tipos, `source` e campos de `data` descritos aqui.

## Regras gerais
- **Formato:** CloudEvents 1.0, modo estruturado JSON, publicado pelo `emit` do Stint (SDD 2.2) no barramento
  EventBridge `sandbox-domain`. O `detail-type` é o `type` do evento, e o `Detail` é o CloudEvent inteiro.
- **Roteamento:** uma regra por tipo no `sandbox-domain` leva o evento à fila de entrada do domínio que o consome
  (`<domínio>-domain-events`), com `InputPath: $.detail`. A regra `spy-all` (`{"source":[{"prefix":""}]}`)
  copia **todo** evento do barramento para `sandbox-spy`, só para observação.
- **Linhagem:** todo evento sai com as extensões do Stint `chainid`, `causationid`, `traceparent` e `tracestate`
  (SDD 2.5). Elas são do engine; o autor do `emit` não as define.
- **Correlação de negócio:** `data.orderId` (string) em todos os eventos. É a chave do `correlate` dos `listen`
  (SDD 2.3) e o que liga um pedido à sua fatura.
- **Entrega:** at-least-once. Os consumidores deduplicam por (`source`, `id`); o Stint já faz isso nos inícios por
  evento (SDD 2.1) e nas retomadas de `listen` (SDD 2.3).
- **Dados grandes:** se o evento passar do limite do EventBridge (≈256 KB), o `data` vai para o bucket
  `sandbox-facts` e o evento leva a extensão `dataref` (SDD 2.2).

## `io.sandbox.billing.invoice-requested.v1`
| Campo | Valor |
|---|---|
| Publica | `sandbox-orders` (pede a fatura de um pedido) |
| `source` | `https://sandbox.stintflow.io/orders` |
| Consome | `sandbox-billing` (inicia o workflow de faturamento por `schedule.on`) |
| Regra | `invoice-requested-to-billing` → `billing-domain-events` |
| Correlação | `data.orderId` |

`data`:
| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `orderId` | string | sim | identificador do pedido |
| `customerId` | string | sim | cliente a faturar |
| `amount` | number | sim | valor total, duas casas decimais |
| `currency` | string | sim | ISO 4217, ex.: `BRL` |
| `items` | array | não | itens do pedido `{sku, quantity, unitPrice}` |

## `io.sandbox.billing.invoice-issued.v1`
| Campo | Valor |
|---|---|
| Publica | `sandbox-billing` (fatura emitida) |
| `source` | `https://sandbox.stintflow.io/billing` |
| Consome | `sandbox-orders` (retoma o `listen` do pedido que espera a fatura) |
| Regra | `invoice-issued-to-orders` → `orders-domain-events` |
| Correlação | `data.orderId` |

`data`:
| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `orderId` | string | sim | o pedido faturado |
| `invoiceId` | string | sim | identificador da fatura |
| `amount` | number | sim | valor faturado |
| `currency` | string | sim | ISO 4217 |
| `issuedAt` | string | sim | instante de emissão, ISO-8601 UTC |
| `invoiceDocument` | string | não | URI do documento da fatura, `s3://sandbox-facts/billing/invoices/<invoiceId>.html` (S.2, 8a). Leitura conforme "Bucket `sandbox-facts`" |

## `io.sandbox.billing.invoice-rejected.v1`
| Campo | Valor |
|---|---|
| Publica | `sandbox-billing` (pedido inválido: nenhuma fatura é emitida) |
| `source` | `https://sandbox.stintflow.io/billing` |
| Consome | `sandbox-orders` (retoma o `listen` do pedido que espera a fatura) |
| Regra | `invoice-rejected-to-orders` → `orders-domain-events` |
| Correlação | `data.orderId` |

`data`:
| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `orderId` | string | sim | o pedido rejeitado |
| `reason` | string | sim | `invalid-amount` (`amount` ausente, não numérico ou ≤ 0) ou `missing-currency` (`currency` ausente ou vazia) |
| `rejectedAt` | string | não | instante da rejeição, ISO-8601 UTC |

Um consumidor trata um `reason` desconhecido como rejeição genérica. Por isso, um valor novo de `reason` é compatível.

## `io.sandbox.orders.order-placed.v1`
| Campo | Valor |
|---|---|
| Publica | `sandbox-orders`, pelo endpoint `POST /orders` (S.3, 8a): é a entrada do pedido |
| `source` | `https://sandbox.stintflow.io/orders` |
| `id` | o `orderId`: o mesmo pedido gera sempre o mesmo evento, e por isso uma só instância (SDD 2.1) |
| Consome | `sandbox-orders` (inicia `place-order` por `schedule.on`). Nenhum outro domínio: os outros só observam pela `spy-all` |
| Regra | `order-placed-to-orders` → `orders-domain-events` |
| Correlação | `data.orderId` |
| Linhagem | o endpoint define `chainid = "chn-" + UUIDv3("order\|" + orderId)`. É a chain de todo o pedido |

`data`:
| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `orderId` | string | sim | identificador do pedido, escolhido pelo cliente (`[A-Za-z0-9-]{1,64}`); é a chave de idempotência |
| `customerId` | string | sim | cliente |
| `recipient` | string | sim | e-mail do cliente |
| `amount` | number | sim | valor total (Orders não valida o valor; quem decide se fatura é o Billing) |
| `currency` | string | sim | ISO 4217 |
| `items` | array | não | itens `{sku, quantity, unitPrice}` |

## `io.sandbox.orders.order-confirmed.v1`
| Campo | Valor |
|---|---|
| Publica | `sandbox-orders` (pedido confirmado depois da fatura) |
| `source` | `https://sandbox.stintflow.io/orders` |
| Consome | **nenhum domínio**: só observação (decisão do S.1, 8a, opção a, em 10/10/2026) |
| Regra | nenhuma regra de domínio; chega apenas a `sandbox-spy` pela `spy-all` |
| Correlação | `data.orderId` |

`data`:
| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `orderId` | string | sim | o pedido confirmado |
| `invoiceId` | string | sim | a fatura que o confirmou |
| `confirmedAt` | string | sim | instante da confirmação, ISO-8601 UTC |

## Bucket `sandbox-facts`: política de acesso (S.2, 8a)
Bucket compartilhado entre os domínios. Cada prefixo tem um único dono que escreve:

| Prefixo | Escreve | Lê |
|---|---|---|
| `facts/<namespace>/…` | o engine do domínio dono do namespace (`dataref` do `emit`, SDD 2.2) | qualquer consumidor do fato |
| `billing/…` | só `sandbox-billing` | `sandbox-orders`, só os objetos citados em `invoiceDocument` |
| `orders/…` | só `sandbox-orders` (reservado) | `sandbox-billing`, se um contrato futuro citar |

A política equivalente está em `infra/policies/sandbox-facts.json` e é aplicada pelo bootstrap. **No floci ela é
declarativa**: o emulador guarda a política mas não aplica IAM (todos usam `test`/`test`). No sandbox, a garantia
real é esta convenção mais o teste de isolamento de cada domínio.

## Mudanças
Um campo novo e opcional em `data` é compatível. Remover ou renomear campo, mudar tipo ou mudar a correlação
exige um tipo novo (`...v2`), publicado em paralelo até os consumidores migrarem.
