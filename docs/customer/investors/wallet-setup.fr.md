---
title: Configuration du portefeuille
---

# Configuration du portefeuille

Pour détenir et consulter des jetons de titres, vous devez connecter un portefeuille blockchain à votre compte du registre. Cette page explique comment configurer un portefeuille compatible et le faire admettre pour les jetons ERC-3643.

## Types de portefeuilles pris en charge

Le registre eWpG prend en charge tout portefeuille auto-conservé capable de produire des signatures EIP-712. Portefeuilles recommandés :

| Portefeuille | Type | Réseaux |
|--------|------|----------|
| MetaMask | Extension de navigateur / mobile | Tous les réseaux EVM |
| Ledger Live | Matériel | Tous les réseaux EVM |
| Trezor Suite | Matériel | Tous les réseaux EVM |
| Phantom | Extension de navigateur / mobile | Solana (et EVM) |
| Rabby | Extension de navigateur | Tous les réseaux EVM |

!!! tip
    Pour un usage institutionnel, les portefeuilles matériels (Ledger, Trezor) sont vivement recommandés. Ils gardent votre clé privée hors ligne et exigent une confirmation physique pour chaque transaction.


## Connecter un portefeuille

1. Allez dans **Profile → Wallets**
2. Cliquez sur **Connect Wallet**
3. Choisissez votre type de portefeuille dans la liste
4. Votre extension de portefeuille s'ouvre et demande la connexion. Approuvez-la.
5. Le portail vous demande de **signer un message** — une signature sans frais de gas qui prouve la possession de l'adresse. Signez-la dans votre portefeuille.
6. L'adresse apparaît désormais dans votre liste de portefeuilles.

Vous pouvez connecter plusieurs portefeuilles. Les avoirs de tous les portefeuilles connectés sont agrégés dans la vue **Investments**.

## Se faire admettre pour les jetons ERC-3643

Connecter simplement un portefeuille au portail ne l'admet pas automatiquement pour les transferts de jetons ERC-3643. L'admission est une étape distincte, réalisée par l'**émetteur** du jeton après vérification de votre statut KYC.

Le processus :

1. Connectez votre portefeuille dans le portail (comme décrit ci-dessus)
2. Communiquez votre adresse à l'émetteur (visible sur la page **Wallets**)
3. Assurez-vous que votre examen KYC/LCB-FT est terminé (voir **Profile → Identity**)
4. L'émetteur inscrit votre portefeuille dans son contrat de registre d'identités
5. Vous recevrez une notification lorsque l'admission sera effective

Après l'admission, vous pouvez recevoir des jetons à cette adresse. L'admission est enregistrée on-chain et subsiste indépendamment du portail.

## Retirer un portefeuille

Pour retirer un portefeuille de votre compte :

1. Allez dans **Profile → Wallets**
2. Cliquez sur **Remove** à côté de l'adresse

Retirer un portefeuille de votre compte du portail ne le retire d'aucune liste d'admission on-chain d'un émetteur. Contactez chaque émetteur individuellement si vous souhaitez que votre adresse soit retirée de son registre d'identités.

## Ajouter un portefeuille Solana

Pour les jetons basés sur Solana :

1. Allez dans **Profile → Wallets**
2. Cliquez sur **Connect Wallet → Solana**
3. Connectez-vous avec Phantom ou un autre portefeuille Solana pris en charge
4. Signez le message de vérification

Les adresses de portefeuille Solana utilisent un format différent (base58) de celui des portefeuilles EVM. Le portail affiche les deux formats côte à côte pour plus de clarté.

## Bonnes pratiques de sécurité

- **Ne partagez jamais votre clé privée** avec quiconque — pas même l'opérateur du registre
- Utilisez un portefeuille dédié aux titres ; évitez de le mêler à une activité DeFi personnelle
- Activez la protection par mot de passe ou biométrie du portefeuille
- Sauvegardez votre phrase de récupération dans un lieu sûr et hors ligne
- Pour des avoirs importants, utilisez un portefeuille matériel

!!! warning
    L'opérateur du registre ne vous demandera jamais votre clé privée ni votre phrase de récupération. Si quelqu'un se réclamant du registre réclame ces informations, c'est une escroquerie — n'y répondez pas et signalez-la immédiatement.

