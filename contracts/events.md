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

## Mudanças
Um campo novo e opcional em `data` é compatível. Remover ou renomear campo, mudar tipo ou mudar a correlação
exige um tipo novo (`...v2`), publicado em paralelo até os consumidores migrarem.
