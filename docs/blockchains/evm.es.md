---
title: Cadenas Ethereum y EVM
description: Compatibilidad con blockchain compatible con EVM: Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism.
---

# Cadenas Ethereum y EVM { #ethereum-evm-chains }

Registerwerk contiene objetivos de configuración para las redes EVM que se enumeran a continuación. El soporte de producción no se establece por el mero hecho de compartir el bytecode: el comportamiento de RPC, la finalidad, el código implementado/identidad de administrador, la indexación, las operaciones, las tarifas y la aplicabilidad legal requieren verificación por red.

---

## Cadenas EVM admitidas { #supported-evm-chains }

| Cadena | Enumeración de cadena | Red | ID de cadena | Notas |
|---|---|---|---|---|
| Ethereum | `ETHEREUM` | MAINNET | 1 | Máxima seguridad, mayor coste de gas |
| Ethereum | `ETHEREUM` | TESTNET | 11155111 (Sepolia) | Desarrollo/pruebas |
| Polygon | `POLYGON` | MAINNET | 137 | Gas bajo, finalidad rápida |
| Polygon | `POLYGON` | TESTNET | 80002 (Amoy) | Desarrollo/pruebas |
| Base | `BASE` | MAINNET | 8453 | Coinbase L2, gas bajo |
| Base | `BASE` | TESTNET | 84532 (Sepolia) | Desarrollo/pruebas |
| Arbitrum | `ARBITRUM` | MAINNET | 42161 | Rollup optimista, equivalente a EVM |
| Arbitrum | `ARBITRUM` | TESTNET | 421614 (Sepolia) | Desarrollo/pruebas |
| Avalanche | `AVALANCHE` | MAINNET | 43114 | Cadena C, alto rendimiento |
| Avalanche | `AVALANCHE` | TESTNET | 43113 (Fuji) | Desarrollo/pruebas |
| Optimism | `OPTIMISM` | MAINNET | 10 | Pila OP L2 |
| Optimism | `OPTIMISM` | TESTNET | 11155420 (Sepolia) | Desarrollo/pruebas |

---

## Biblioteca cliente: Web3j { #client-library-web3j }

Registerwerk utiliza **Web3j** (biblioteca Java) para todas las interacciones de la cadena EVM. Operaciones clave:

| Operación | Método Web3j | Utilizado en |
|---|---|---|
| Implementar contrato | `web3j.ethSendRawTransaction` | Todos los servicios de implementación |
| Leer estado | `contract.call()` | `TokenAdminService`, indexador |
| Enviar transacción | `contract.send()` | Todas las operaciones de administración |
| Estimar gas | `web3j.ethEstimateGas` | Estimación de tarifas |
| Suscribirse a eventos | `web3j.ethLogFlowable` | Indexador EVM |

El bean `Web3jClientFactory` envuelve `Web3j.build(new HttpService(rpcUrl))`. Para producción, se recomienda utilizar puntos finales WebSocket cuando estén disponibles (suscribirse a eventos sin sondeo).

---

## Estado del nodo RPC { #rpc-node-health }

El `RpcNodeHealthService` (`blockchain/internal/`) se ejecuta cada 60 segundos y verifica cada nodo RPC registrado:

1. Llama a `eth_blockNumber`: mide el tiempo de respuesta y el retraso desde el mejor (bloque más alto)
2. Actualiza `RpcNode.healthy`, `RpcNode.consecutiveFailures`, `RpcNode.lagFromBest`
3. Llama a `BlockchainClientRegistry.refreshFromNodes()` con los estados actualizados

Esto significa que el registro siempre enruta al nodo más rápido y actual. Cuando un nodo se retrasa por más de un umbral configurable (`rpcNode.maxLagBlocks`), se marca como no saludable y el tráfico se desvía a alternativas saludables.

---

## Configuración de múltiples nodos { #multi-node-configuration }

Para implementaciones de producción, configure múltiples proveedores RPC por cadena para alta disponibilidad:

```yaml
# application.yml (example)
registerwerk:
  evm:
    chains:
      ethereum:
        mainnet:
          rpcUrl: https://eth-mainnet.infura.io/v3/${INFURA_KEY}
```

Los nodos adicionales se agregan a través de la API de administración (`POST /api/v1/chain-configs/{id}/rpc-nodes`). Configurar `exclusive=true` en nodos premium garantiza que solo se utilicen esos nodos cuando estén en buen estado.

---

## Estrategia de tarifa de gas { #gas-fee-strategy }

Todas las transacciones EVM utilizan EIP-1559 (tarifa dinámica) de forma predeterminada:

- `maxFeePerGas` = `baseFee × 1.2` (20% de búfer por encima de la base)
- `maxPriorityFeePerGas` = configurable por cadena (predeterminado: 1 Gwei para Ethereum, 30 Gwei para Polygon)
- Límite de gas estimado por tipo de transacción (la implementación usa `eth_estimateGas`, las operaciones administrativas usan límites fijos con 20% buffer)

El monedero del operador debe contener suficiente token nativo (ETH, MATIC, etc.) para pagar las tarifas del gas. El `WalletBalanceService` verifica los saldos del monedero cada 5 minutos y emite una notificación si un monedero cae por debajo del `minGasWarningThreshold` configurable.
