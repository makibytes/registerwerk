---
title: Blockchains prises en charge
description: Tous les réseaux blockchain pris en charge, leurs capacités, et la façon dont Registerwerk s'y connecte.
---

# Blockchains prises en charge

Registerwerk prend en charge huit types de blockchains, sur réseaux principaux et réseaux de test. La connectivité des chaînes est gérée par le `BlockchainClientRegistry` du module `blockchain`, qui sélectionne le meilleur nœud RPC disponible pour chaque requête.

---

## Référence rapide

| Type de chaîne | Norme(s) de jeton | Bibliothèque cliente | Réseaux | État |
|---|---|---|---|---|
| [Ethereum et EVM](evm.md) | ERC-20/721/1155/3525/3643/4626/7540 | Web3j | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism | Implémentation présente ; maturité pour la production non vérifiée |
| [EVM confidentielle](confidential-evm.md) | CONF_ERC20, CONF_ERC3643 | Web3j + SDK Zama | Fhenix, Inco | Implémentation présente ; maturité pour la production non vérifiée |
| [Solana](solana.md) | SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL | Solanaj | mainnet-beta, devnet | Intégration présente ; maturité pour la production non vérifiée |
| [Canton / DAML](canton.md) | DAML_BOND_*, CANTON_TOKEN | Client Java DAML | Canton Network, devnet | Implémentation optionnelle (`-Pcanton`) ; maturité pour la production non vérifiée |
| [StarkNet](starknet-stellar.md) | STARKNET_ERC20, STARKNET_ERC3525 | Starknet4j maison | mainnet, sepolia | ⚠️ Espace réservé |
| [Stellar](starknet-stellar.md) | STELLAR_ASSET | SDK Java Horizon | mainnet, testnet | ⚠️ Espace réservé |

---

## `BlockchainClientRegistry`

Le `BlockchainClientRegistry` (`blockchain/api/`) est le composant central de toute la connectivité aux chaînes. Pour les chaînes EVM, il maintient trois niveaux de clients :

1. **Pool de nœuds** (priorité la plus élevée) — alimenté par le `RpcNodeHealthService` après chaque tour de contrôle de santé. Il retient le nœud le plus sain et le moins latent
2. **Clients uniques dynamiques** — un client par ligne `chain_config` activée (héritage, rafraîchi sur `ChainConfigUpdatedEvent`)
3. **Clients statiques** — chargés au démarrage depuis les propriétés d'`application.yml`

### Algorithme de sélection des nœuds

Pour le pool de nœuds, le registre applique la logique de sélection suivante :

```
1. If any enabled node has exclusive=true → use only exclusive-enabled nodes
2. Otherwise → use all enabled nodes
3. From candidates: prefer healthy nodes with smallest block lag
4. If no healthy candidates → use least-bad (fewest failures, most recent success)
5. If ALL nodes disabled → throw IllegalStateException
```

Cela assure une bascule automatique entre plusieurs fournisseurs RPC sans intervention de l'opérateur.

---

## Ajouter une nouvelle chaîne

Pour ajouter une nouvelle chaîne compatible EVM :

1. Ajoutez la chaîne à l'énumération `Chain` dans `chain/api/Chain.java`
2. Ajoutez l'URL RPC dans `application.yml` sous `registerwerk.evm.chains.<chainName>.<network>.rpcUrl`
3. Déployez les contrats Registerwerk sur la nouvelle chaîne (avec le service de déploiement existant)
4. Configurez l'enregistrement `chain_config` via l'API d'administration

Ajouter une chaîne non EVM suppose d'implémenter l'interface de fabrique de client correspondante et d'enregistrer le client dans `BlockchainConfig`.

---

## Format de l'identifiant de chaîne

Les chaînes sont identifiées dans le système par `ChainDescriptor(chain, network)` :

```java
new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET)
// → identifier: "ETHEREUM_MAINNET"
```

La chaîne de caractères `identifier` sert de clé dans les tables de clients dynamiques et dans `asset_deployment.chain_identifier`, pour rattacher les déploiements à la bonne chaîne.
