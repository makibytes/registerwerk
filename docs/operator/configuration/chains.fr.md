---
title: Configuration de la chaîne
---

# Configuration de la chaîne

Le registre eWpG stocke la configuration de la chaîne dans la table `chain_config`. Cela signifie que vous pouvez ajouter de nouvelles blockchains au moment de l'exécution sans redéployer le backend.

## Chaînes préconfigurées

Les chaînes suivantes sont amorcées par les migrations Flyway :

| Identifiant | Chaîne | Type | ID de chaîne |
|---|---|---|---|
| ETHEREUM_MAINNET | Ethereum | Réseau principal EVM | 1 |
| ETHEREUM_SEPOLIA | Ethereum Sepolia | Réseau de test EVM | 11155111 |
| POLYGON_MAINNET | Polygon | Réseau principal EVM | 137 |
| POLYGON_AMOY | Polygon Amoy | Réseau de test EVM | 80002 |
| BASE_MAINNET | Base | Réseau principal EVM | 8453 |
| BASE_SEPOLIA | Base Sepolia | Réseau de test EVM | 84532 |
| SOLANA_MAINNET | Solana | Réseau principal Solana | — |
| SOLANA_DEVNET | Solana Devnet | Réseau de test Solana | — |
| FHENIX_MAINNET | Fhenix | Réseau principal EVM (FHE) | 21888 |
| FHENIX_HELIUM | Fhenix Helium | Réseau de test EVM (FHE) | 8008135 |
| INCO_MAINNET | Inco | Réseau principal EVM (FHE) | 9090 |
| INCO_RIVEST | Inco Rivest | Réseau de test EVM (FHE) | 21097 |

## Ajout d'une nouvelle chaîne EVM

### Étape 1 — Enregistrer via l'API d'administration

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains \
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

Le `BlockchainClientRegistry` du backend récupère la nouvelle chaîne lors de la prochaine actualisation (toutes les 60 secondes) ou immédiatement via :

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Étape 2 — Ajouter au graph-node

Dans `indexer/evm/docker-compose.yml`, ajoutez à la variable d'environnement `ethereum` :
```
,arbitrum-one:${ARBITRUM_RPC}
```

Dans `indexer/evm/config/graph-node.toml` :
```toml
[chains.arbitrum-one]
shard = "primary"
protocol = "ethereum"
[[chains.arbitrum-one.provider]]
url = "${ARBITRUM_RPC}"
features = []
```

### Étape 3 — Redémarrer graph-node

Rechargez la nouvelle configuration réseau avant de soumettre un déploiement de sous-graphe :

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Attendez que graph-node signale un état opérationnel.

### Étape 4 — Déployer et indexer le sous-graphe

Configurez chaque source statique `*_ARBITRUM` décrite dans [The Graph](../indexers/the-graph.md), puis :

```bash
SUBGRAPH_VERSION_LABEL=arbitrum-20260729-01 ./indexer/evm/deploy-subgraph.sh arbitrum-one
```

L'enregistrement d'une chaîne backend ne découvre pas les sources de données de sous-graphe ni ne prouve leur identité de code.
Les entités résultantes restent des projections provisoires dérivées d'événements.

## Chaînes FHE (Fhenix / Inco)

Les chaînes Fhenix et Inco utilisent Zama fhEVM et prennent en charge les jetons ERC-3643 confidentiels. Elles sont pré-ensemencées dans V15. Déployez le contrat `ConfidentialERC3643` en utilisant :

```bash
forge script script/Deploy.s.sol --rpc-url $FHENIX_HELIUM_RPC --broadcast
```

Le `ConfidentialErc3643Service` du backend gère les opérations de transfert cryptées sur ces chaînes.
