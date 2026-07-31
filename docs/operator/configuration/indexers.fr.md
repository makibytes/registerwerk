---
title: Configuration de l'indexeur
---

# Configuration de l'indexeur

## Indexeur EVM (graph-node)

graph-node est configuré via `indexer/evm/config/graph-node.toml`. Le fichier TOML définit quelles chaînes sont indexées et quels fournisseurs RPC utiliser.

### Ajout d'une chaîne au graph-node

1. Ajoutez le RPC URL au `.env` :
   ```dotenv
   ARBITRUM_RPC=https://arb1.arbitrum.io/rpc
   ```

2. Ajoutez à `indexer/evm/docker-compose.yml` dans la variable d'environnement `ethereum` :
   ```
   ,arbitrum-one:${ARBITRUM_RPC}
   ```

3. Ajoutez à `indexer/evm/config/graph-node.toml` :
   ```toml
   [chains.arbitrum-one]
   shard = "primary"
   protocol = "ethereum"
   [[chains.arbitrum-one.provider]]
   url = "${ARBITRUM_RPC}"
   features = []
   ```

4. Redémarrez graph-node pour qu'il charge le nouveau réseau et attendez qu'il redevienne opérationnel :

   ```bash
   docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
   ```

5. Configurez chaque source statique à l'aide du suffixe `ARBITRUM` et déployez avec une étiquette de version
unique :

   ```bash
   SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
   ```

Voir [The Graph](../indexers/the-graph.md) pour les variables singleton, les listes multi-instances et les exigences de bloc de déploiement. Ces sorties sont des projections d'événements provisoires, et non la finalité de la chaîne, l'état juridique du registre, les preuves de règlement ou la vérification de l'identité du code.

### Configuration des nouvelles tentatives

`GRAPH_ETHEREUM_REQUEST_RETRIES=10` est défini par défaut. graph-node réessaie les appels RPC ayant échoué avant de marquer l'indexeur comme défaillant.

## Indexeur Solana (Yellowstone)

Yellowstone est configuré via `indexer/solana/config/yellowstone.toml`.

```toml
[grpc]
address = "0.0.0.0:10000"

[upstream]
endpoint = "${YELLOWSTONE_UPSTREAM_ENDPOINT}"
token = "${YELLOWSTONE_TOKEN}"
```

Définissez `YELLOWSTONE_UPSTREAM_ENDPOINT` sur un point de terminaison Solana RPC compatible Geyser.

## Configuration de synchronisation backend

Dans `application.yml` :

```yaml
ewpg:
  indexer:
    graph-node-poll-interval: 30s    # How often to query The Graph
    solana-poll-interval: 10m        # Fallback polling for Solana
    stale-threshold: 2h              # Alert if no sync for this long
```
