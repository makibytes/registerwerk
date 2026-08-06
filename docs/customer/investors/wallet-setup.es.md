---
title: Configuración del monedero
---

# Configuración del monedero

Para mantener y consultar tokens de valores debe conectar un monedero de blockchain a su cuenta del registro. Esta página explica cómo configurar un monedero compatible y conseguir que se admita para tokens ERC-3643.

## Tipos de monedero admitidos

El registro eWpG admite cualquier monedero de autocustodia capaz de producir firmas EIP-712. Monederos recomendados:

| Monedero | Tipo | Redes |
|--------|------|----------|
| MetaMask | Extensión de navegador / móvil | Todas las redes EVM |
| Ledger Live | Hardware | Todas las redes EVM |
| Trezor Suite | Hardware | Todas las redes EVM |
| Phantom | Extensión de navegador / móvil | Solana (y EVM) |
| Rabby | Extensión de navegador | Todas las redes EVM |

!!! tip
    Para uso institucional se recomiendan encarecidamente los monederos de hardware (Ledger, Trezor). Mantienen su clave privada fuera de línea y exigen confirmación física para cada transacción.


## Conectar un monedero

1. Vaya a **Profile → Wallets**
2. Pulse **Connect Wallet**
3. Seleccione su tipo de monedero en la lista
4. Su extensión de monedero se abre y pide conectarse. Apruebe la conexión.
5. El portal le pide **firmar un mensaje** — una firma sin coste de gas que acredita la posesión de la dirección. Fírmela en su monedero.
6. La dirección aparece ya en su lista de monederos.

Puede conectar varios monederos. Las tenencias de todos los monederos conectados se agregan en la vista **Investments**.

## Conseguir la admisión para tokens ERC-3643

Conectar un monedero al portal no lo admite automáticamente para transmisiones de tokens ERC-3643. La admisión es un paso aparte, que realiza el **emisor** del token tras verificar su estado KYC.

El proceso:

1. Conecte su monedero en el portal (como se describe arriba)
2. Facilite su dirección de monedero al emisor (visible en la página **Wallets**)
3. Asegúrese de que su revisión KYC/prevención del blanqueo está completa (consulte **Profile → Identity**)
4. El emisor inscribe su monedero en su contrato de identity registry
5. Recibirá una notificación cuando la admisión esté completa

Tras la admisión podrá recibir tokens en esa dirección. La admisión se guarda on-chain y perdura con independencia del portal.

## Retirar un monedero

Para retirar un monedero de su cuenta:

1. Vaya a **Profile → Wallets**
2. Pulse **Remove** junto a la dirección

Retirar un monedero de su cuenta del portal no lo retira de ninguna lista de admisión on-chain de un emisor. Contacte con cada emisor por separado si desea que su dirección salga de su identity registry.

## Añadir un monedero de Solana

Para tokens basados en Solana:

1. Vaya a **Profile → Wallets**
2. Pulse **Connect Wallet → Solana**
3. Conéctese con Phantom u otro monedero de Solana admitido
4. Firme el mensaje de verificación

Las direcciones de monedero de Solana usan un formato distinto (base58) del de los monederos EVM. El portal muestra ambos formatos uno junto a otro para mayor claridad.

## Buenas prácticas de seguridad

- **Nunca comparta su clave privada** con nadie — ni siquiera con el operador del registro
- Use un monedero dedicado a los valores; evite mezclarlo con actividad DeFi personal
- Active la protección del monedero por contraseña o biometría
- Guarde una copia de su frase semilla en un lugar seguro y fuera de línea
- Para tenencias significativas, use un monedero de hardware

!!! warning
    El operador del registro nunca le pedirá su clave privada ni su frase semilla. Si alguien que dice ser del registro le pide esa información, es una estafa — no la facilite y denúncielo de inmediato.

