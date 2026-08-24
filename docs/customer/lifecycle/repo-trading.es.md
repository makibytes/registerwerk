---
title: 5a. Negociación repo
description: Negociar y gestionar repos bilaterales mediante RFQ dirigidas o difundidas.
---

# Etapa 5a — Negociación repo

Un **acuerdo de recompra (repo)** vincula dos operaciones acordadas conjuntamente: venta de valores por efectivo en la fecha inicial y recompra de valores equivalentes por un importe fijo al vencimiento. La diferencia es el rendimiento repo.

Repo Desk modela este flujo bilateral. Está separado del [préstamo garantizado por valores](repo-lending.md), donde la garantía se deposita en un pool on-chain.

| | Repo Desk | Préstamo garantizado |
|---|---|---|
| Contraparte | Empresas identificadas | Mercado agrupado |
| Estructura | Venta y recompra acordada | Préstamo con garantía |
| Precio | Cotización e importe de recompra fijos | Tipo variable por utilización |
| Riesgo | Haircut, margen, sustitución | LTV, oráculo, liquidación |

## Flujo

1. En **Trader → Repo Desk → New RFQ**, indique préstamo de efectivo, garantía, importe, fechas, tipo indicativo y haircut.
2. Una RFQ **dirigida** solo es visible para las empresas elegidas; una **broadcast** para todos los traders aptos.
3. Un dealer nunca ve cotizaciones rivales. El solicitante compara importe, tipo anual, haircut y validez y acepta una.
4. El importe de recompra se fija con ACT/360. `3,25` significa 3,25 % anual.
5. En apertura y cierre cada receptor confirma la pata de efectivo o valores recibida con una referencia.
6. Llamadas de margen y sustituciones quedan en el historial compartido e inmutable.

!!! warning "El contrato marco sigue siendo esencial"
    El flujo no sustituye contrato marco, lista de garantías elegibles, agente de valoración, custodia, disputas ni dictamen de netting. DvP sigue siendo preferible a FoP.

