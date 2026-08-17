---
title: StarkNet et Stellar
description: Statut et configuration de la prise en charge des blockchains StarkNet (Cairo ERC-3525) et Stellar (actif natif).
---

# StarkNet & Stellar { #starknet-stellar }

Registerwerk contient des intégrations Starknet et Stellar fonctionnelles avec des limites
opérationnelles explicites. Aucune ne doit être considérée comme validée en production sans tests
propres au réseau.

---

## StarkNet { #starknet }

StarkNet est un rollup ZK sur Ethereum utilisant le langage de contrat intelligent **Cairo**. Il offre une sécurité équivalente à celle d'Ethereum avec des coûts de transaction nettement inférieurs.

### Types de jetons pris en charge { #supported-token-types }

| Énumération de jetons | Description |
|---|---|
| `STARKNET_ERC20` | Équivalent Cairo de l'ERC-20 |
| `STARKNET_ERC3525` | ERC-3525 semi-fongible en Cairo — obligations en tranches |

### Statut { #status }

`StarknetTokenService` soumet des transactions Invoke v3 signées via l'Universal Deployer
Contract. La confirmation attend `ACCEPTED_ON_L1` et `StarknetTransferSyncService` indexe les
événements de transfert ERC-20/ERC-3525.

Les hachages de classe ERC-20 et ERC-3525 par défaut valent zéro et provoquent un échec immédiat.
Avant tout déploiement :

1. Compilez les contrats Cairo sous `contracts/cairo/`
2. Déclarez la classe de contrat : `starkli declare target/dev/EwpgERC3525.json`
3. Configurez `registerwerk.chains.starknet.erc20-class-hash` et/ou
   `registerwerk.chains.starknet.erc3525-class-hash`

L'intégration utilise Starknet JSON-RPC et le portefeuille opérateur configuré pour
`Chain.STARKNET` et `Network.MAINNET/TESTNET`.

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

1. Le titulaire crée une ligne de confiance pour l'émetteur et le code d'actif
2. Le compte émetteur envoie l'actif au compte titulaire via une opération `Payment`
3. Les soldes sont stockés de manière native dans les écritures du grand livre du compte Stellar

Dans Registerwerk :
- `AssetDeployment.contractAddress` stocke l'**adresse du compte émetteur** Stellar (clé publique Stellar)
- `StellarAssetService` construit et signe le XDR puis le soumet via l'**API Horizon**

### Statut { #status_1 }

`StellarAssetService` enregistre l'identifiant Registerwerk avec une transaction `ManageData`
signée et fournit le clawback ainsi que l'autorisation des lignes de confiance. Il ne crée pas les
lignes de confiance des titulaires et ne distribue pas de solde initial.
`StellarTransferSyncService` indexe les paiements impliquant le compte émetteur ; les transferts
directs entre titulaires ne sont pas couverts. Les déploiements Stellar n'ont pas non plus de
confirmation automatique dans `AssetDeploymentService`.
