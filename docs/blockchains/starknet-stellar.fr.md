---
title: StarkNet et Stellar
description: Statut et configuration de la prise en charge des blockchains StarkNet (Cairo ERC-3525) et Stellar (actif natif).
---

# StarkNet & Stellar { #starknet-stellar }

StarkNet et Stellar sont partiellement pris en charge dans Registerwerk. La plomberie de l'infrastructure (câblage client, squelettes de service de déploiement, énumérations standard de jetons) est en place, mais les deux chaînes ont des **valeurs d'espace réservé** qui doivent être remplacées avant utilisation en production.

---

## StarkNet { #starknet }

StarkNet est un rollup ZK sur Ethereum utilisant le langage de contrat intelligent **Cairo**. Il offre une sécurité équivalente à celle d'Ethereum avec des coûts de transaction nettement inférieurs.

### Types de jetons pris en charge { #supported-token-types }

| Énumération de jetons | Description |
|---|---|
| `STARKNET_ERC20` | Équivalent Cairo de l'ERC-20 |
| `STARKNET_ERC3525` | ERC-3525 semi-fongible en Cairo — obligations en tranches |

### Statut { #status }

⚠️ **Le hachage de classe StarkNet est un espace réservé à zéro.** Avant de déployer des jetons StarkNet en production :

1. Compilez les contrats Cairo sous `contracts/cairo/`
2. Déclarez la classe de contrat : `starkli declare target/dev/EwpgERC3525.json`
3. Remplacez le hachage de classe dans la configuration `StarknetTokenService` par le hachage de classe déclaré

Le `StarknetTokenService` utilise un client Java personnalisé (Starknet4j) configuré via `Chain.STARKNET` + `Network.MAINNET/TESTNET`.

### Réseaux { #networks }

| Réseau | Énumération du réseau | Remarques |
|---|---|---|
| StarkNet mainnet | `MAINNET` | Production — hachage de classe requis |
| StarkNet Sepolia | `TESTNET` | Développement/test |

---

## Stellar { #stellar }

Stellar est une blockchain axée sur les paiements avec prise en charge native des **Stellar Assets** — représentations on-chain de n'importe quelle devise ou instrument.

### Type de jeton pris en charge { #supported-token-type }

| Énumération de jetons | Description |
|---|---|
| `STELLAR_ASSET` | Actif natif émis par Stellar |

### Modèle d'actif Stellar { #stellar-asset-model }

Contrairement à EVM ou Solana, Stellar dispose d'un type d'actif intégré au niveau du protocole. Aucun déploiement de contrat n'est nécessaire :

1. Le **compte émetteur** crée une ligne de confiance (trustline) à partir du compte titulaire
2. Le compte émetteur envoie l'actif au compte titulaire via une opération `Payment`
3. Les soldes sont stockés de manière native dans les écritures du grand livre du compte Stellar

Dans Registerwerk :
- `AssetDeployment.contractAddress` stocke l'**adresse du compte émetteur** Stellar (clé publique Stellar)
- `StellarAssetService` utilise l'**API Horizon** (SDK Java) pour soumettre les transactions

### Statut { #status_1 }

⚠️ **La prise en charge de Stellar est un espace réservé.** Les squelettes de `StellarAssetService` sont en place, mais la mise en œuvre complète (gestion des lignes de confiance, conformité, indexeur) n'est pas encore terminée.

---

## Note sur la feuille de route { #roadmap-note }

StarkNet et Stellar représentent tous deux des zones de développement actives. L'infrastructure existe pour permettre les contributions. Considérations prioritaires :

- **StarkNet ERC-3525** : forte valeur pour les émetteurs [Liechtenstein TVTG](../legal/tvtg-li.md) qui préfèrent un règlement prouvé par ZK aux rollups optimistes
- **Stellar** : utile pour les titres de paiement transfrontaliers et les stablecoins sur les marchés émergents

Pour contribuer à une implémentation, suivez le modèle des services de déploiement EVM (`Erc20DeploymentService`, `Erc3525DeploymentService`) et implémentez la même interface `TokenDeploymentPort`.
