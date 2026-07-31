---
title: StarkNet y Stellar
description: Estado y configuración de soporte de blockchain de StarkNet (Cairo ERC-3525) y Stellar (activo nativo).
---

# StarkNet y Stellar { #starknet-stellar }

StarkNet y Stellar son parcialmente compatibles con Registerwerk. La infraestructura subyacente (cableado del cliente, esqueletos de servicio de implementación, enumeraciones estándar de tokens) está en su lugar, pero ambas cadenas tienen **valores de marcador de posición** que deben reemplazarse antes del uso en producción.

---

## StarkNet { #starknet }

StarkNet es un rollup de ZK en Ethereum que utiliza el lenguaje de contrato inteligente **Cairo**. Ofrece seguridad equivalente a Ethereum con costos de transacción significativamente más bajos.

### Tipos de tokens admitidos { #supported-token-types }

| Enumeración de tokens | Descripción |
|---|---|
| `STARKNET_ERC20` | Equivalente de ERC-20 en Cairo |
| `STARKNET_ERC3525` | Cairo ERC-3525 semifungible — bonos divididos en tramos |

### Estado { #status }

⚠️ **El hash de clase StarkNet es un marcador de posición cero.** Antes de implementar tokens StarkNet en producción:

1. Compile los contratos Cairo en `contracts/cairo/`
2. Declare la clase de contrato: `starkli declare target/dev/EwpgERC3525.json`
3. Reemplace el hash de clase en la configuración `StarknetTokenService` con el hash de clase declarado

El `StarknetTokenService` utiliza un cliente Java personalizado (Starknet4j) configurado a través de `Chain.STARKNET` + `Network.MAINNET/TESTNET`.

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

1. La **cuenta emisora** crea una línea de confianza desde la cuenta del titular
2. La cuenta emisora envía el activo a la cuenta del titular mediante una operación `Payment`
3. Los saldos se almacenan de forma nativa en los asientos del libro mayor de cuentas de Stellar.

En Registerwerk:
- `AssetDeployment.contractAddress` almacena la **dirección de cuenta emisora** de Stellar (clave pública de Stellar).
- `StellarAssetService` utiliza **Horizon API** (Java SDK) para enviar transacciones

### Estado { #status }

⚠️ **El soporte de Stellar es un marcador de posición.** Los esqueletos de `StellarAssetService` están en su lugar, pero la implementación completa (administración de línea de confianza, cumplimiento, indexador) aún no está completa.

---

## Nota de la hoja de ruta { #roadmap-note }

Tanto StarkNet como Stellar representan áreas de desarrollo activo. La infraestructura existe para permitir las contribuciones. Consideraciones prioritarias:

- **StarkNet ERC-3525**: alto valor para los emisores de [Liechtenstein TVTG](../legal/tvtg-li.md) que prefieren la liquidación probada por ZK a los rollups optimistas
- **Stellar**: útil para valores de pago transfronterizos y monedas estables en mercados emergentes

Para contribuir con una implementación, siga el patrón de los servicios de implementación EVM (`Erc20DeploymentService`, `Erc3525DeploymentService`) e implemente la misma interfaz `TokenDeploymentPort`.
