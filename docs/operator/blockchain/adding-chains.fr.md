---
title: Ajout de nouvelles chaînes
---

# Ajout de nouvelles chaînes

Les clients de la chaîne backend peuvent être enregistrés au moment de l'exécution. L'indexation EVM nécessite également une configuration réseau de graph-node
et une cible de déploiement prise en charge avec des sources de contrat explicites.

## Types de chaîne pris en charge

| Type | Exemples |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, tout compatible EVM |
| `SOLANA` | Solana Mainnet, Devnet |

## Ajout d'une chaîne EVM (procédure complète)

### 1. Enregistrer via l'API d'administration

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

### 2. Déployer les contrats

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Ajouter à la configuration de graph-node

Voir [Configuration de l'indexeur](../configuration/indexers.md) pour le TOML et les modifications de docker-compose.

### 4. Redémarrer graph-node avec le nouveau réseau

L'API d'administration des déploiements ne peut pas accepter de manifeste pour le nouveau réseau tant que graph-node n'a pas
rechargé sa configuration de chaîne :

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Vérifiez que graph-node fonctionne correctement avant de continuer.

### 5. Configurer et déployer le sous-graphe

Configurez chaque source `*_OPTIMISM` décrite dans [The Graph](../indexers/the-graph.md), puis :

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

Le sous-graphe est une projection d'événement provisoire. Il n'établit ni la finalité de la chaîne, ni l'effet juridique, ni l'état faisant autorité du registre, ni l'identité du code déployé.

### 6. Déclencher l'actualisation du client

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Le `BlockchainClientRegistry` crée immédiatement un nouveau client Web3j pour la chaîne.

## RPC de secours

Vous pouvez configurer plusieurs URL RPC pour le basculement. La configuration de la chaîne stocke `fallback_rpc_urls` sous forme de liste séparée par des virgules. Si le RPC principal échoue, le registre tente des solutions de secours dans l'ordre.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```
