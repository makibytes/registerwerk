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

## Controles del desk

- **Las cotizaciones están versionadas.** Una cotización sustituida pasa a `SUPERSEDED` y ya no puede aceptarse. La aceptación incluye el `termsHash` del servidor; si difiere (409), revise las condiciones actuales. Las condiciones aceptadas se fijan en la operación. Importes e intereses se redondean a la subunidad de la divisa (ACT/360, ACT/365 para GBP, entre otras).
- **Cada tramo tiene un pagador** (declara «enviado» con referencia) **y un receptor** (confirma o disputa). Una llamada de margen exige referencia de valoración e importe, no puede superar el déficit resultante y concede al menos 24 horas; **solo la confirmación del prestamista la cierra**.
- **El incumplimiento tiene dos pasos:** notificación por el acreedor y, tras el plazo de gracia (24 horas por defecto), declaración, mientras la obligación siga sin cumplirse y la contraparte no haya declarado su cumplimiento. Si el prestatario pagó y el prestamista no devuelve los valores, el *prestatario* puede declarar el incumplimiento.
- **Disputa:** cualquiera de las partes puede congelar la operación; el operador registra el resultado con base jurídica y segundo aprobador, sin decidir sobre el fondo.
- **Sustitución:** solicitud propia; la garantía cambia solo tras confirmar ambos tramos y nunca en operaciones cerradas, incumplidas o en disputa.
- **Acceso:** adhesión de la sociedad, cliente profesional o contraparte elegible y control KYC/cribado. El prestatario debe poseer los valores en el registro; los ya pignorados o listados están bloqueados (gravamen interno, sin Sperrvermerk en el registro). El plazo debe terminar antes del vencimiento o amortización anticipada de la garantía; la amortización se bloquea con un repo abierto. Las operaciones corporativas se anotan; los pagos compensatorios son cuestión de las partes.
- **SFTR:** ambas partes necesitan un LEI; cada operación recibe un UTI y expone los campos SFTR disponibles (`/sftr-fields`). Registerwerk no informa; las partes siguen siendo responsables. La liquidación es bilateral y autoconfirmada, no atómica.

!!! warning "El contrato marco sigue siendo esencial"
    El flujo no sustituye contrato marco, lista de garantías elegibles, agente de valoración, custodia, disputas ni dictamen de netting. DvP sigue siendo preferible a FoP.
