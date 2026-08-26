---
title: Configuración de cadena
---

# Configuración de cadena { #chain-configuration }

El Registro eWpG almacena la configuración de cadena en la tabla `chain_config`. Esto significa que puede agregar nuevas cadenas de bloques en tiempo de ejecución sin volver a implementar el backend.

## Cadenas preconfiguradas { #pre-configured-chains }

Las siguientes cadenas son iniciadas por las migraciones de Flyway:

| Identificador | Cadena | Tipo | ID de cadena |
|---|---|---|---|
| ETHEREUM_MAINNET | Ethereum | Red principal EVM | 1 |
| ETHEREUM_SEPOLIA | Ethereum Sepolia | Red de prueba EVM | 11155111 |
| POLYGON_MAINNET | Polygon | Red principal EVM | 137 |
| POLYGON_AMOY | Polygon Amoy | Red de prueba EVM | 80002 |
| BASE_MAINNET | Base | Red principal EVM | 8453 |
| BASE_SEPOLIA | Base Sepolia | Red de prueba EVM | 84532 |
| SOLANA_MAINNET | Solana | Red principal de Solana | — |
| SOLANA_DEVNET | Solana Devnet | Red de prueba de Solana | — |
| FHENIX_MAINNET | Fhenix | Red principal EVM (FHE) | 21888 |
| FHENIX_HELIUM | Fhenix Helium | Red de prueba EVM (FHE) | 8008135 |
| INCO_MAINNET | Inco | Red principal EVM (FHE) | 9090 |
| INCO_RIVEST | Inco Rivest | Red de prueba EVM (FHE) | 21097 |

## Agregar una nueva cadena EVM { #adding-a-new-evm-chain }

### Paso 1: registrarse mediante la API de administración { #step-1-register-via-admin-api }

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "ARBITRUM_MAINNET",
    "displayName": "Arbitrum One",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 42161,
    "rpcUrl": "https://arb1.arbitrum.io/rpc",
    "wsUrl": "wss://arb1.arbitrum.io/ws",
    "blockExplorerUrl": "https://arbiscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/arbitrum-mainnet"
  }'
```

El `BlockchainClientRegistry` del backend recoge la nueva cadena en la siguiente actualización (cada 60 segundos) o inmediatamente a través de:

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Paso 2: agregar a graph-node { #step-2-add-to-graph-node }

En `indexer/evm/docker-compose.yml`, agregue a la var de entorno `ethereum`:
```
,arbitrum-one:${ARBITRUM_RPC}
```

En `indexer/evm/config/graph-node.toml`:
```toml
[chains.arbitrum-one]
shard = "primary"
protocol = "ethereum"
[[chains.arbitrum-one.provider]]
url = "${ARBITRUM_RPC}"
features = []
```

### Paso 3: reiniciar graph-node { #step-3-restart-graph-node }

Vuelva a cargar la nueva configuración de red antes de enviar una implementación de subgrafo:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Espere hasta que graph-node informe que está en buen estado.

### Paso 4: implementar e indexar el subgrafo { #step-4-deploy-and-index-subgraph }

Configure cada fuente estática `*_ARBITRUM` descrita en [The Graph](../indexers/the-graph.md), luego:

```bash
SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
```

El registro de una cadena de backend no descubre fuentes de datos de subgrafos ni prueba la identidad de su código.
Las entidades resultantes siguen siendo proyecciones provisionales derivadas de eventos.

## Cadenas FHE (Fhenix / Inco) { #fhe-chains-fhenix-inco }

Las cadenas Fhenix e Inco utilizan Zama fhEVM y admiten tokens ERC-3643 confidenciales. Están precargadas en V15. Implemente el contrato `ConfidentialERC3643` usando:

```bash
forge script script/Deploy.s.sol --rpc-url $FHENIX_HELIUM_RPC --broadcast
```

El `ConfidentialErc3643Service` del backend maneja operaciones de transferencia cifradas en estas cadenas.
