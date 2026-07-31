---
title: Solana
description: Prise en charge de la blockchain Solana — Programmes de jetons SPL et SPL-2022 pour les titres natifs de Solana.
---

# Solana { #solana }

Solana offre un débit élevé (50 000+ TPS), une finalité inférieure à la seconde et des coûts de transaction très faibles. Registerwerk prend en charge les jetons de sécurité natifs de Solana via le programme classique **SPL Token** et le programme étendu **Token-2022** (SPL-2022).

---

## Réseaux pris en charge { #supported-networks }

| Réseau | Énumération du réseau | Point de terminaison | Usage |
|---|---|---|---|
| Solana mainnet-beta | `MAINNET` | `https://api.mainnet-beta.solana.com` | Production |
| Solana devnet | `TESTNET` | `https://api.devnet.solana.com` | Développement/test |

---

## Bibliothèque client : Solanaj { #client-library-solanaj }

Registerwerk utilise **Solanaj** (bibliothèque client Java pour Solana) via le `SolanaClientFactory`. Opérations clés :

| Opération | Solanaj API | Utilisé dans |
|---|---|---|
| Créer un compte mint | `MintLayout.encode()` + `SystemProgram.createAccount()` | `SolanaTokenService.deploy()` |
| Émettre des jetons (mint) | Instruction de programme de jeton `mintTo` | `SolanaTokenService.mint()` |
| Transfert | Instruction de programme de jeton `transfer` | `SolanaTokenService.transfer()` |
| Définir l'autorité | Instruction de programme de jeton `setAuthority` | Opérations d'administration |
| Obtenir le solde | `rpcClient.getTokenAccountBalance()` | Indexeur, solde du portefeuille |

---

## Modèle de compte de jeton { #token-account-model }

Le modèle de jeton de Solana diffère considérablement de celui d'EVM :

- Un **compte mint** définit le jeton (équivalent à une adresse de contrat ERC-20)
- Chaque titulaire a besoin d'un **compte de jeton** distinct (compte de jeton associé, ATA) pour détenir le jeton
- Le flux de déploiement de Registerwerk crée automatiquement des ATA pour les portefeuilles de l'opérateur
- Les ATA des investisseurs sont créés à la première réception

`AssetDeployment.contractAddress` stocke l'**adresse mint** de Solana (clé publique codée en base58).

---

## Extensions SPL-2022 { #spl-2022-extensions }

Pour une couverture détaillée des extensions de Token-2022 (InterestBearing, ConfidentialTransfer, TransferHook, PermanentDelegate), voir [SPL-2022](../token-standards/spl-2022.md).

---

## Indexer { #indexer }

L'indexeur Solana écoute les transactions sur les comptes mint suivis à l'aide d'abonnements WebSocket (via les API améliorées Helius ou Shyft). Sur chaque transaction confirmée :

1. Analyser le journal des transactions pour les instructions de transfert de jeton
2. Associer les comptes Solana source/destination aux enregistrements `LegalEntity`
3. Écrire un enregistrement `token_transfer` (schéma cohérent avec l'indexeur EVM)
4. Mettre à jour `AssetHolder.nominalAmount`

Le `IndexerMonitorService` vérifie l'activité de l'indexeur Solana toutes les 5 minutes. Si aucun événement n'est reçu pendant plus de 30 minutes sur un actif actif, un incident `DORA_AVAILABILITY` est ouvert.

---

## Portefeuille de l'opérateur sur Solana { #operator-wallet-on-solana }

Le portefeuille Solana de Registerwerk est une paire de clés **ed25519** standard. La clé privée est stockée cryptée dans le coffre-fort du portefeuille de l'opérateur (même enveloppe KMS/KEK que les magasins de clés EVM). Le portefeuille de l'opérateur est l'autorité de création et de gel de tous les jetons SPL-2022.

!!! warning "Solde SOL pour le rent"
    Les comptes Solana nécessitent un **rent** (solde minimum de SOL, le mécanisme de loyer de stockage propre à Solana) pour rester ouverts. Les comptes de jetons ouverts par le service de déploiement nécessitent un petit dépôt SOL. Le `WalletBalanceService` surveille le solde SOL de l'opérateur et avertit lorsqu'il tombe en dessous de 0,5 SOL.
