---
title: Configurazione dell'indicizzatore
---

# Configurazione dell'indicizzatore { #indexer-configuration }

## Indicizzatore EVM (graph-node) { #evm-indexer-graph-node }

graph-node è configurato tramite `indexer/evm/config/graph-node.toml`. Il file TOML definisce quali catene sono indicizzate e quali provider RPC utilizzare.

### Aggiunta di una catena a graph-node { #adding-a-chain-to-graph-node }

1. Aggiungi l'URL RPC a `.env`:
   ```dotenv
   ARBITRUM_RPC=https://arb1.arbitrum.io/rpc
   ```

2. Aggiungi a `indexer/evm/docker-compose.yml` nella variabile d'ambiente `ethereum`:
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```

3. Aggiungi a `indexer/evm/config/graph-node.toml`:
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   features = []
   ```

4. Riavvia graph-node in modo che carichi la nuova rete e attendi che diventi integro:

   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```

5. Configura ogni origine statica utilizzando il suffisso `ARBITRUM` e distribuiscila con un'etichetta versione
   univoca:

   ```bash
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

   Consultare [The Graph](../indexers/the-graph.md) per le variabili singleton, gli elenchi multi-istanza e i requisiti del blocco di distribuzione. Questi output sono proiezioni provvisorie di eventi, non la finalità della catena, lo stato giuridicamente rilevante del registro, la prova di regolamento o la verifica dell'identità del codice.

### Configurazione dei tentativi { #retry-configuration }

`GRAPH_ETHEREUM_REQUEST_RETRIES=10` è impostato per impostazione predefinita. graph-node ripete le chiamate RPC non riuscite prima di contrassegnare l'indicizzatore come non riuscito.

## Indicizzatore Solana (Yellowstone) { #solana-indexer-yellowstone }

Yellowstone è configurato tramite `indexer/solana/config/yellowstone.toml`.

```toml
[grpc]
address = "0.0.0.0:10000"

[upstream]
endpoint = "${YELLOWSTONE_UPSTREAM_ENDPOINT}"
token = "${YELLOWSTONE_TOKEN}"
```

Imposta `YELLOWSTONE_UPSTREAM_ENDPOINT` su un endpoint Solana RPC abilitato per Geyser.

## Configurazione sincronizzazione backend { #backend-sync-configuration }

In `application.yml`:

```yaml
ewpg:
  indexer:
    graph-node-poll-interval: 30s    # How often to query The Graph
    solana-poll-interval: 10m        # Fallback polling for Solana
    stale-threshold: 2h              # Alert if no sync for this long
```
