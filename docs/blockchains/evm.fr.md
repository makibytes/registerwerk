---
title: Chaînes Ethereum et EVM
description: Prise en charge de la blockchain compatible EVM – Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism.
---

# Chaînes Ethereum et EVM { #ethereum-evm-chains }

Registerwerk contient des cibles de configuration pour les réseaux EVM répertoriés ci-dessous. La prise en
charge en production ne se déduit pas du simple partage du bytecode : le comportement RPC, la finalité, le
code déployé/l'identité de l'administrateur, l'indexation, les opérations, les frais et l'applicabilité
légale nécessitent une vérification par réseau.

---

## Chaînes EVM prises en charge { #supported-evm-chains }

| Chaîne | Énumération de chaîne | Réseau | ID de chaîne | Remarques |
|---|---|---|---|---|
| Ethereum | `ETHEREUM` | MAINNET | 1 | Sécurité maximale, coût de gas le plus élevé |
| Ethereum | `ETHEREUM` | TESTNET | 11155111 (Sepolia) | Développement/test |
| Polygon | `POLYGON` | MAINNET | 137 | Gas faible, finalité rapide |
| Polygon | `POLYGON` | TESTNET | 80002 (Amoy) | Développement/test |
| Base | `BASE` | MAINNET | 8453 | L2 de Coinbase, gas faible |
| Base | `BASE` | TESTNET | 84532 (Sepolia) | Développement/test |
| Arbitrum | `ARBITRUM` | MAINNET | 42161 | Rollup optimiste, équivalent EVM |
| Arbitrum | `ARBITRUM` | TESTNET | 421614 (Sepolia) | Développement/test |
| Avalanche | `AVALANCHE` | MAINNET | 43114 | C-Chain, débit élevé |
| Avalanche | `AVALANCHE` | TESTNET | 43113 (Fuji) | Développement/test |
| Optimism | `OPTIMISM` | MAINNET | 10 | OP Stack L2 |
| Optimism | `OPTIMISM` | TESTNET | 11155420 (Sepolia) | Développement/test |

---

## Bibliothèque cliente : Web3j { #client-library-web3j }

Registerwerk utilise **Web3j** (bibliothèque Java) pour toutes les interactions avec les chaînes EVM. Opérations clés :

| Opération | Méthode Web3j | Utilisée dans |
|---|---|---|
| Déployer un contrat | `web3j.ethSendRawTransaction` | Tous les services de déploiement |
| Lire l'état | `contract.call()` | `TokenAdminService`, indexeur |
| Envoyer une transaction | `contract.send()` | Toutes les opérations d'administration |
| Estimer le gas | `web3j.ethEstimateGas` | Estimation des frais |
| S'abonner aux événements | `web3j.ethLogFlowable` | Indexeur EVM |

Le bean `Web3jClientFactory` encapsule `Web3j.build(new HttpService(rpcUrl))`. En production, il est recommandé d'utiliser des points de terminaison WebSocket lorsqu'ils sont disponibles (abonnement aux événements sans interrogation).

---

## Santé des nœuds RPC { #rpc-node-health }

Le `RpcNodeHealthService` (`blockchain/internal/`) s'exécute toutes les 60 secondes et vérifie chaque nœud RPC enregistré :

1. Appelle `eth_blockNumber` — mesure le temps de réponse et le retard par rapport au meilleur (bloc le plus élevé)
2. Met à jour `RpcNode.healthy`, `RpcNode.consecutiveFailures`, `RpcNode.lagFromBest`
3. Appelle `BlockchainClientRegistry.refreshFromNodes()` avec les états mis à jour

Le registre est ainsi toujours acheminé vers le nœud le plus rapide et le plus à jour. Lorsqu'un nœud accuse un retard supérieur à un seuil configurable (`rpcNode.maxLagBlocks`), il est marqué non sain et le trafic est redirigé vers des alternatives saines.

---

## Configuration multi-nœuds { #multi-node-configuration }

Pour les déploiements en production, configurez plusieurs fournisseurs RPC par chaîne afin d'assurer une haute disponibilité :

```yaml
# application.yml (example)
registerwerk:
  evm:
    chains:
      ethereum:
        mainnet:
          rpcUrl: https://eth-mainnet.infura.io/v3/${INFURA_KEY}
```

Des nœuds supplémentaires sont ajoutés via l'API d'administration (`POST /api/v1/chain-configs/{id}/rpc-nodes`). Le réglage de `exclusive=true` sur les nœuds premium garantit que seuls ces nœuds sont utilisés lorsqu'ils sont sains.

---

## Stratégie de frais de gas { #gas-fee-strategy }

Toutes les transactions EVM utilisent EIP-1559 (frais dynamiques) par défaut :

- `maxFeePerGas` = `baseFee × 1.2` (marge de 20 % au-dessus du frais de base)
- `maxPriorityFeePerGas` = configurable par chaîne (par défaut : 1 Gwei pour Ethereum, 30 Gwei pour Polygon)
- Limite de gas estimée par type de transaction (le déploiement utilise `eth_estimateGas`, les opérations d'administration utilisent des limites fixes avec une marge de 20 %)

Le wallet de l'opérateur doit détenir suffisamment de jeton natif (ETH, MATIC, etc.) pour payer les frais de gas. Le `WalletBalanceService` vérifie les soldes des wallets toutes les 5 minutes et émet une notification si un wallet passe sous le `minGasWarningThreshold` configurable.
