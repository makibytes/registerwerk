---
title: Agregar nuevas cadenas
---

# Agregar nuevas cadenas { #adding-new-chains }

Los clientes de la cadena backend se pueden registrar en tiempo de ejecución. La indexación EVM también requiere una configuración de red de graph-node
y un objetivo de implementación compatible con fuentes de contrato explícitas.

## Tipos de cadena admitidos { #supported-chain-types }

| Tipo | Ejemplos |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, cualquier compatible con EVM |
| `SOLANA` | Solana Mainnet, Devnet |

## Agregar una cadena EVM (tutorial completo) { #adding-an-evm-chain-full-walkthrough }

### 1. Regístrese mediante la API de administración { #1-register-via-admin-api }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Implementar contratos { #2-deploy-contracts }

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Agregar a la configuración de graph-node { #3-add-to-graph-node-config }

Consulte [Configuración del indexador](../configuration/indexers.md) para TOML y cambios en docker-compose.

### 4. Reinicie graph-node con la nueva red { #4-restart-graph-node-with-the-new-network }

La API de administración de implementación no puede aceptar un manifiesto para la nueva red hasta que graph-node haya vuelto a cargar
su configuración de cadena:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Verifique que graph-node esté en buen estado antes de continuar.

### 5. Configure e implemente el subgrafo { #5-configure-and-deploy-the-subgraph }

Configure cada fuente `*_OPTIMISM` descrita en [The Graph](../indexers/the-graph.md), luego:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

El subgrafo es una proyección de evento provisional. No establece la finalidad de la cadena, el efecto legal
, el estado del registro autorizado ni la identidad del código implementado.

### 6. Activar la actualización del cliente { #6-trigger-client-refresh }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

El `BlockchainClientRegistry` crea un nuevo cliente Web3j para la cadena inmediatamente.

## RPC de respaldo { #fallback-rpcs }

Puede configurar varias URL de RPC para conmutación por error. La configuración de la cadena almacena `fallback_rpc_urls` como una lista separada por comas. Si el RPC principal falla, el registro prueba las alternativas en orden.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```
