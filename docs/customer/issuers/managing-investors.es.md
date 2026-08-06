---
title: Gestionar inversores
---

# Gestionar inversores

Esta guía explica cómo añadir inversores a su emisión, admitir sus monederos y gestionar su ONCHAINID para los tokens ERC-3643.

## Añadir un inversor

Los inversores deben estar registrados primero como entidades en el registro eWpG. Si su inversor aún no está en el sistema, contacte con el operador del registro para darlo de alta.

Una vez que existe una entidad inversora en el registro:

1. Vaya a su emisión y pulse **Investors → Add Investor**
2. Busque al inversor por nombre, correo o identificador de entidad
3. Selecciónelo y pulse **Add**

El inversor queda ya vinculado a su emisión en la base de datos del registro. Para tokens **Simple** (ERC-20/721/1155) con esto basta — puede transmitir tokens directamente a su monedero.

Para tokens **Control** (ERC-3643) debe además admitir el monedero del inversor (véase abajo).

## Admitir monederos (ERC-3643)

Los tokens ERC-3643 imponen que solo los inversores admitidos y verificados por KYC puedan recibir tokens. La lista de admisión se guarda on-chain en el contrato **Identity Registry**.

### Paso 1 — El inversor facilita su dirección de monedero

El inversor conecta su monedero en el portal del cliente en **Wallets → Connect Wallet** (véase [Configuración del monedero](../investors/wallet-setup.md)) y le comunica la dirección.

### Paso 2 — Verificar que el inversor tiene un ONCHAINID

Todo inversor ERC-3643 debe tener un **ONCHAINID** — un contrato inteligente que actúa como su identidad on-chain. El registro crea uno automáticamente cuando se da de alta la entidad inversora.

Puede comprobarlo en **Investor → [nombre] → ONCHAINID**. Si existe, se muestra la dirección del contrato ONCHAINID.

### Paso 3 — Comprobar las atestaciones KYC/prevención del blanqueo

Los tokens ERC-3643 exigen que los inversores mantengan **atestaciones** válidas en su ONCHAINID — afirmaciones criptográficas emitidas por un proveedor de KYC de confianza. Su emisión exige como mínimo:

- **Tema de atestación 1**: KYC (conocimiento del cliente)
- **Tema de atestación 2**: prevención del blanqueo de capitales

El operador del registro emite estas atestaciones una vez que el inversor completa el proceso de revisión KYC. Su estado se ve en la página de detalle del inversor.

!!! warning
    No puede admitir a un inversor cuyo ONCHAINID no tenga atestaciones KYC/prevención del blanqueo válidas. Intentarlo será rechazado por el identity registry on-chain.


### Paso 4 — Inscribir el monedero en el Identity Registry

Una vez que el inversor tiene un ONCHAINID válido y las atestaciones:

1. Vaya a su emisión → **Investors → [nombre del inversor]**
2. Pulse **Add Wallet**
3. Introduzca la dirección de monedero facilitada por el inversor
4. Pulse **Register on Chain**

El backend del registro envía una transacción al contrato Identity Registry que vincula la dirección del monedero con el ONCHAINID del inversor. Suele tardar de 5 a 15 segundos.

Una vez inscrito, el monedero queda admitido. El inversor ya puede recibir tokens en esa dirección.

## Retirar a un inversor

Para sacar el monedero de un inversor de la lista de admisión:

1. Vaya a **Investors → [nombre del inversor] → Wallets**
2. Pulse **Remove from Whitelist** junto a la dirección
3. Confirme la acción

El registro envía una transacción que retira el monedero del Identity Registry. El inversor ya no podrá recibir tokens, y toda transmisión futura a ese monedero será rechazada automáticamente por el contrato inteligente.

!!! note
    Sacar a un inversor de la lista de admisión no confisca su saldo de tokens existente. Si necesita recuperar tokens (por ejemplo, por una resolución judicial), contacte con el operador del registro — eso exige una operación de transferencia forzosa realizada por el agente del token.


## Módulos de cumplimiento

Para los tokens ERC-3643, el operador configura módulos de cumplimiento que aplican automáticamente reglas adicionales:

| Módulo | Descripción |
|--------|-------------|
| **MaxBalance** | Limita el saldo máximo de tokens que puede mantener un mismo inversor |
| **MaxInvestors** | Pone tope al número total de inversores distintos |
| **CountryRestrict** | Bloquea a los inversores de determinadas jurisdicciones |

Estos módulos se ejecutan automáticamente en cada intento de transmisión. Si una transmisión infringiera la regla de un módulo, se rechaza on-chain sin que usted tenga que hacer nada.

Contacte con el operador del registro si necesita ajustar los parámetros de algún módulo para su emisión.
