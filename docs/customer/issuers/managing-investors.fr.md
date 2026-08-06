---
title: Gérer les investisseurs
---

# Gérer les investisseurs

Ce guide explique comment ajouter des investisseurs à votre émission, admettre leurs portefeuilles et gérer leur ONCHAINID pour les jetons ERC-3643.

## Ajouter un investisseur

Les investisseurs doivent d'abord être enregistrés comme entités dans le registre eWpG. Si votre investisseur n'est pas encore dans le système, contactez l'opérateur du registre pour son intégration.

Une fois qu'une entité investisseur existe dans le registre :

1. Allez sur votre émission et cliquez sur **Investors → Add Investor**
2. Recherchez l'investisseur par nom, courriel ou identifiant d'entité
3. Sélectionnez l'investisseur et cliquez sur **Add**

L'investisseur est désormais rattaché à votre émission dans la base de données du registre. Pour les jetons **Simple** (ERC-20/721/1155), cela suffit — vous pouvez transférer des jetons directement vers son portefeuille.

Pour les jetons **Control** (ERC-3643), vous devez en outre admettre le portefeuille de l'investisseur (voir ci-dessous).

## Admettre des portefeuilles (ERC-3643)

Les jetons ERC-3643 imposent que seuls des investisseurs admis et vérifiés KYC puissent recevoir des jetons. La liste d'admission est conservée on-chain dans le contrat **Identity Registry**.

### Étape 1 — L'investisseur fournit son adresse de portefeuille

L'investisseur connecte son portefeuille dans le portail client sous **Wallets → Connect Wallet** (voir [Configuration du portefeuille](../investors/wallet-setup.md)) et vous communique l'adresse.

### Étape 2 — Vérifier que l'investisseur possède un ONCHAINID

Tout investisseur ERC-3643 doit disposer d'un **ONCHAINID** — un contrat intelligent servant d'identité on-chain. Le registre en crée un automatiquement lors de l'intégration de l'entité investisseur.

Vous pouvez le vérifier sous **Investor → [nom] → ONCHAINID**. L'adresse du contrat ONCHAINID s'affiche s'il existe.

### Étape 3 — Contrôler les attestations KYC/LCB-FT

Les jetons ERC-3643 exigent que les investisseurs détiennent des **attestations** valides sur leur ONCHAINID — des affirmations cryptographiques délivrées par un prestataire KYC de confiance. Votre émission exige au minimum :

- **Sujet d'attestation 1** : KYC (connaissance du client)
- **Sujet d'attestation 2** : LCB-FT (lutte contre le blanchiment)

L'opérateur du registre délivre ces attestations une fois que l'investisseur a suivi le processus d'examen KYC. Vous voyez leur statut sur la page de détail de l'investisseur.

!!! warning
    Vous ne pouvez pas admettre un investisseur dont l'ONCHAINID ne porte pas d'attestations KYC/LCB-FT valides. Toute tentative sera rejetée par le registre d'identités on-chain.


### Étape 4 — Inscrire le portefeuille dans l'Identity Registry

Une fois que l'investisseur dispose d'un ONCHAINID valide et des attestations :

1. Allez sur votre émission → **Investors → [nom de l'investisseur]**
2. Cliquez sur **Add Wallet**
3. Saisissez l'adresse de portefeuille fournie par l'investisseur
4. Cliquez sur **Register on Chain**

Le backend du registre soumet une transaction au contrat Identity Registry, reliant l'adresse du portefeuille à l'ONCHAINID de l'investisseur. Cela prend généralement 5 à 15 secondes.

Une fois inscrit, le portefeuille est admis. L'investisseur peut désormais recevoir des jetons à cette adresse.

## Retirer un investisseur

Pour retirer le portefeuille d'un investisseur de la liste d'admission :

1. Allez dans **Investors → [nom de l'investisseur] → Wallets**
2. Cliquez sur **Remove from Whitelist** à côté de l'adresse
3. Confirmez l'action

Le registre soumet une transaction retirant le portefeuille de l'Identity Registry. L'investisseur ne pourra plus recevoir de jetons, et tout transfert futur vers ce portefeuille sera automatiquement rejeté par le contrat intelligent.

!!! note
    Retirer un investisseur de la liste d'admission ne confisque pas son solde de jetons existant. Si vous devez récupérer des jetons (à la suite d'une décision de justice, par exemple), contactez l'opérateur du registre — cela requiert une opération de transfert forcé effectuée par l'agent du jeton.


## Modules de conformité

Pour les jetons ERC-3643, l'opérateur configure des modules de conformité qui appliquent automatiquement des règles supplémentaires :

| Module | Description |
|--------|-------------|
| **MaxBalance** | Plafonne le solde de jetons qu'un même investisseur peut détenir |
| **MaxInvestors** | Plafonne le nombre total d'investisseurs distincts |
| **CountryRestrict** | Bloque les investisseurs de juridictions déterminées |

Ces modules s'exécutent automatiquement à chaque tentative de transfert. Si un transfert enfreignait la règle d'un module, il est rejeté on-chain sans aucune action de votre part.

Contactez l'opérateur du registre si vous devez ajuster les paramètres d'un module pour votre émission.
