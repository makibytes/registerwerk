---
title: Unidades del registro
description: El registro cuenta unidades enteras - cómo se despliegan los tokens de bonos y fondos (decimals = 0), qué rechaza en cualquier otro token y qué hacer.
---

# Unidades del registro { #register-units }

**El registro cuenta unidades enteras. Un token es una unidad del valor.**

Los importes del registro - el nominal de un titular y cada transferencia indexada - son las **unidades base
sin procesar** del token; el indexador las escribe sin escalar. El cálculo de cupones y reembolso
(`amountPerUnit x nominal`), la emisión en el mercado primario (el importe asignado se envía al token tal cual)
y la negociación secundaria (cantidad y precio por unidad) leen esos importes como unidades enteras. En un token
de 18 decimales cada uno sería erróneo por un factor de 10^18. Por eso el registro no escala: despliega los
tokens de bonos y fondos con `decimals = 0` y rechaza cada uno de esos procesos en un token que no cuente en
unidades enteras.

## Qué se despliega { #what-is-deployed }

| Estándar | Decimales de un nuevo despliegue |
|---|---|
| ERC-20 (`EwpgERC20`), ERC-3643 (T-REX), ERC-721, ERC-1155, ERC-3525 (EVM y Starknet), SPL / Token-2022, bonos Daml | **0** |
| ERC-20 de Starknet (Cairo, fijo en 18), activos Stellar (fijo en 7) | según el contrato - **rechazados** por los procesos siguientes |
| Participaciones de vault ERC-4626 / ERC-7540 (siguen al subyacente), tokens confidenciales, tokens Canton | fuera del control del registro - registrados como *desconocidos*, **rechazados** |

Los decimales se registran en el despliegue (`asset_deployment.token_decimals`). Los despliegues anteriores a esta
regla conservan lo que desplegó el código anterior (por ejemplo 18 para ERC-20 y ERC-3643), por lo que también se
rechazan.

## Qué se rechaza { #what-is-refused }

Todo activo con un despliegue activo (pendiente o confirmado) cuyos decimales no sean exactamente 0 - incluidos
los desconocidos - se rechaza, con fallo cerrado, mediante un `409` que nombra el despliegue y sus decimales:

- **Operaciones corporativas** (cupones, reembolso, dividendos, splits, amortizaciones): no se toma la
  instantánea de la fecha de registro; la operación queda en `SNAPSHOT_BLOCKED` con el motivo, auditada, con
  alerta y se reintenta cada día.
- **Suscripciones**: asignación y liquidación (la emisión).
- **Negociación**: crear una oferta, comprar y liquidar una operación.
- **Reembolso (amortización)**: iniciar el reembolso del valor (los importes quemados son las unidades base brutas del registro).

Un activo sin despliegue (registro fuera de cadena) no tiene nada que escalar y no se ve afectado.

!!! warning "Cómo corregir un activo rechazado"
    El token no puede modificarse en el sitio. Despliegue de nuevo el activo con un token de unidades enteras
    (`decimals = 0`) y traslade el registro a él. Mientras tanto no se paga, emite ni negocia nada.
