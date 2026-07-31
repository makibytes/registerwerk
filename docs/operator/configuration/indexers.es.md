---
title: Configuración del indexador
---

# Configuración del indexador { #indexer-configuration }

## Indexador EVM (graph-node) { #evm-indexer-graph-node }

graph-node se configura a través de `indexer/evm/config/graph-node.toml`. El archivo TOML define qué cadenas están indexadas y qué proveedores RPC usar.

### Agregar una cadena a graph-node { #adding-a-chain-to-graph-node }

1. Agregue RPC URL a `.env`:
   ```dotenv
   ARBITRUM_RPC=https://arb1.arbitrum.io/rpc
   ```

2. Agregue a `indexer/evm/docker-compose.yml` en la var env `ethereum`:
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```

3. Añadir a `indexer/evm/config/graph-node.toml`:
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   features = []
   ```

4. Reinicie graph-node para que cargue la nueva red y espere a que quede en buen estado (healthy):

   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```

5. Configure cada fuente estática usando el sufijo `ARBITRUM` e impleméntela con una etiqueta de versión
única:

   ```bash
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

Consulte [The Graph](../indexers/the-graph.md) para conocer variables singleton, listas de instancias múltiples y requisitos de bloques de implementación. Estas salidas son proyecciones de eventos provisionales, no finalidad en cadena, estado de registro legal, evidencia de liquidación o verificación de identidad de código.

### Configuración de reintentos { #retry-configuration }

`GRAPH_ETHEREUM_REQUEST_RETRIES=10` está configurado de forma predeterminada. graph-node reintenta las llamadas RPC fallidas antes de marcar el indexador como fallido.

## Indexador Solana (Yellowstone) { #solana-indexer-yellowstone }

Yellowstone se configura a través de `indexer/solana/config/yellowstone.toml`.

```toml
[grpc]
address = "0.0.0.0:10000"

[upstream]
endpoint = "${YELLOWSTONE_UPSTREAM_ENDPOINT}"
token = "${YELLOWSTONE_TOKEN}"
```

Configure `YELLOWSTONE_UPSTREAM_ENDPOINT` en un punto final Solana RPC habilitado para Geyser.

## Configuración de sincronización de backend { #backend-sync-configuration }

En `application.yml`:

```yaml
ewpg:
  indexer:
    graph-node-poll-interval: 30s    # How often to query The Graph
    solana-poll-interval: 10m        # Fallback polling for Solana
    stale-threshold: 2h              # Alert if no sync for this long
```
