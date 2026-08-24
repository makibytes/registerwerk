---
title: StarkNet y Stellar
description: Estado y configuración de soporte de blockchain de StarkNet (Cairo ERC-3525) y Stellar (activo nativo).
---

# StarkNet y Stellar { #starknet-stellar }

Registerwerk contiene integraciones funcionales de Starknet y Stellar con límites operativos
explícitos. Ninguna debe considerarse validada para producción sin pruebas específicas de la red.

---

## StarkNet { #starknet }

StarkNet es un rollup de ZK en Ethereum que utiliza el lenguaje de contrato inteligente **Cairo**. Ofrece seguridad equivalente a Ethereum con costos de transacción significativamente más bajos.

### Tipos de tokens admitidos { #supported-token-types }

| Enumeración de tokens | Descripción |
|---|---|
| `STARKNET_ERC20` | Equivalente de ERC-20 en Cairo |
| `STARKNET_ERC3525` | Cairo ERC-3525 semifungible — bonos divididos en tramos |

### Estado { #status }

`StarknetTokenService` envía transacciones Invoke v3 firmadas mediante el Universal Deployer
Contract. La confirmación espera `ACCEPTED_ON_L1` y `StarknetTransferSyncService` indexa eventos
de transferencia ERC-20/ERC-3525.

Los hashes de clase predeterminados para ERC-20 y ERC-3525 son cero y provocan un fallo inmediato.
Antes de desplegar:

1. Compile los contratos Cairo en `contracts/cairo/`
2. Declare la clase de contrato: `starkli declare target/dev/EwpgERC3525.json`
3. Configure `registerwerk.chains.starknet.erc20-class-hash` y/o
   `registerwerk.chains.starknet.erc3525-class-hash`

La integración usa Starknet JSON-RPC y el monedero del operador configurado para
`Chain.STARKNET` y `Network.MAINNET/TESTNET`.

### Redes { #networks }

| Red | Enumeración de red | Notas |
|---|---|---|
| Red principal de StarkNet | `MAINNET` | Producción: se requiere hash de clase |
| StarkNet Sepolia | `TESTNET` | Desarrollo/pruebas |

---

## Stellar { #stellar }

Stellar es una cadena de bloques centrada en pagos con soporte nativo para **Stellar Assets**: representaciones en cadena de cualquier moneda o instrumento.

### Tipo de token admitido { #supported-token-type }

| Enumeración de tokens | Descripción |
|---|---|
| `STELLAR_ASSET` | Activo emitido nativo de Stellar |

### Modelo de activo de Stellar { #stellar-asset-model }

A diferencia de EVM o Solana, Stellar tiene un tipo de activo incorporado a nivel de protocolo. No se necesita implementación de contrato:

1. El titular crea una línea de confianza para el emisor y el código del activo
2. La cuenta emisora envía el activo a la cuenta del titular mediante una operación `Payment`
3. Los saldos se almacenan de forma nativa en los asientos del libro mayor de cuentas de Stellar.

En Registerwerk:
- `AssetDeployment.contractAddress` almacena la **dirección de cuenta emisora** de Stellar (clave pública de Stellar).
- `StellarAssetService` construye y firma el XDR y lo envía mediante la **API Horizon**

### Estado { #status }

`StellarAssetService` registra el ID del activo mediante una transacción `ManageData` firmada e
implementa clawback y autorización de líneas de confianza. No crea líneas de confianza de
titulares ni distribuye un saldo inicial. `StellarTransferSyncService` indexa pagos que incluyen
la cuenta emisora; las transferencias directas entre titulares no están cubiertas. Los despliegues
de Stellar tampoco tienen confirmación automática en `AssetDeploymentService`.
