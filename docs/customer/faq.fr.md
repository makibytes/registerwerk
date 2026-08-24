---
title: Questions fréquentes
---

# Questions fréquentes

## Généralités

### Qu'est-ce que le registre eWpG ?

Registerwerk est une implémentation de référence permettant de créer et d'administrer des enregistrements de titres financiers électroniques et les jetons blockchain associés. Le fait qu'un instrument soit juridiquement reconnu au titre de la loi allemande sur les titres électroniques (Elektronisches Wertpapiergesetz — eWpG) dépend de l'instrument, du modèle de registre, de l'opérateur et de l'installation, et doit être examiné de manière externe.

### Le registre est-il réglementé ?

L'agrément est propre à chaque installation et à chaque opérateur. Ce dépôt ne contient aucune preuve qu'un opérateur donné détienne un agrément réglementaire requis. Vérifiez les activités envisagées, les autorisations de l'opérateur et la structure de l'instrument auprès d'un conseil qualifié et de l'opérateur concerné avant toute utilisation.

### Puis-je m'inscrire moi-même ?

Non. L'intégration est déclenchée par l'opérateur. Contactez l'opérateur du registre pour demander votre intégration. Cela garantit que tous les participants sont vérifiés avant d'accéder à la plateforme.

---

## Émetteurs

### Combien de temps prend le processus d'approbation ?

La durée d'examen dépend de l'opérateur et du dossier. Ce dépôt ne définit ni ne garantit un niveau de service de 1 à 3 jours ouvrés ; demandez à l'opérateur responsable la procédure et les délais applicables.

### Puis-je modifier les paramètres du jeton après approbation ?

Non. Dès qu'une émission est à l'état APPROVED, tous les paramètres (nom, ISIN, chaîne, norme de jeton, encours total) sont verrouillés. Vous pouvez retirer la soumission et revenir à DRAFT pour apporter des modifications.

### Que signifie « onchain level » ?

Il détermine la part de votre logique de conformité qui réside sur la blockchain :
- **None** — enregistrement au registre uniquement, aucun contrat intelligent déployé
- **Simple** — contrat de jeton standard déployé, aucune conformité appliquée
- **Control** — contrat ERC-3643 déployé avec des modules de conformité on-chain

### Puis-je déployer sur plusieurs chaînes ?

Actuellement, chaque émission est déployée sur un seul réseau. Pour émettre le même titre sur plusieurs chaînes, vous créeriez des émissions distinctes portant le même ISIN. Contactez l'opérateur du registre si vous avez besoin d'une prise en charge multi-chaînes.

### Qu'advient-il de mon jeton si le registre est hors ligne ?

Une fois un jeton déployé, le contrat peut continuer d'exister indépendamment de cette application, sous réserve du réseau retenu et des contrôles du contrat. Registerwerk conserve un enregistrement opérationnel des titulaires et projette ou rapproche certains états on-chain. Savoir quel enregistrement fait juridiquement foi dépend de l'instrument, du modèle de registre et de la juridiction, et requiert une décision de périmètre approuvée ; un solde indexé ou on-chain ne prouve à lui seul ni la propriété juridique ni l'effet juridique.

---

## Investisseurs

### Ai-je besoin d'un portefeuille particulier pour détenir des jetons de titres ?

Pour les jetons ERC-20, n'importe quel portefeuille EVM standard (MetaMask, Ledger, etc.) convient. Pour les jetons ERC-3643, tout portefeuille EVM prenant en charge ERC-20 convient également — la logique de conformité est dans le contrat, pas dans le portefeuille. Pour les jetons ERC-3643 confidentiels, il vous faut un portefeuille compatible FHE sur le réseau Fhenix ou Inco.

### Pourquoi ne puis-je pas recevoir de jetons à mon adresse de portefeuille ?

Les causes les plus fréquentes sont :
1. Votre portefeuille n'a pas été admis par l'émetteur
2. Vos attestations KYC/LCB-FT ont expiré — vérifiez **Profile → Identity**
3. Votre pays est restreint par un module de conformité sur ce jeton
4. Le jeton est actuellement suspendu

### Comment obtenir l'approbation de mon KYC ?

L'opérateur du registre pilote le processus KYC. Vous serez guidé lors de la remise des documents pendant l'intégration. Si votre KYC est en attente ou a expiré, allez dans **Profile → Identity → Renew KYC**.

### Mes avoirs en jetons sont-ils publics ?

Pour les jetons ERC-20, ERC-721, ERC-1155 et ERC-3643 standards : oui, votre solde est visible sur la blockchain publique par quiconque connaît votre adresse de portefeuille. Pour les jetons ERC-3643 confidentiels : non, votre solde est chiffré on-chain.

---

## Auditeurs

### Les auditeurs peuvent-ils déclencher des transactions ?

Non. Le rôle d'auditeur est strictement en lecture seule. Aucune action d'auditeur ne peut modifier un enregistrement du registre ni déclencher une transaction on-chain.

### Comment vérifier que les données du registre correspondent à la blockchain ?

Chaque enregistrement de transfert dans le registre contient le hachage de la transaction on-chain. Vous pouvez vérifier indépendamment n'importe quel transfert sur l'explorateur de blocs concerné à l'aide de ce hachage. Voir [le guide de l'auditeur](workspaces/auditor.md) pour le détail.

### Puis-je exporter les données d'audit vers mes propres systèmes ?

Oui. La piste d'audit et les vues d'historique des jetons prennent en charge les exports CSV et JSON. Pour de larges plages de dates, les exports sont générés de façon asynchrone et envoyés par courriel.

---

## Technique

### Quelles blockchains sont prises en charge ?

Les chaînes EVM (Ethereum, Polygon, Base), Solana, Canton, StarkNet, Stellar, ainsi que des réseaux EVM confidentiels. Des réseaux de test (Sepolia, Amoy, Base Sepolia, Solana Devnet) sont également disponibles pour les essais. Voir [Blockchains prises en charge](../blockchains/index.md) pour la liste complète et l'usage auquel chacune convient.

### Quelles normes de jetons sont prises en charge ?

ERC-20, ERC-721, ERC-1155, ERC-3525, ERC-3643, ERC-4626, ERC-7540, leurs variantes confidentielles, Solana SPL-2022 et les modèles Daml de cycle de vie obligataire propres à Registerwerk sur Canton. Le déploiement générique `CANTON_TOKEN` est réservé, mais non implémenté. Voir [Choisir une norme de jeton](./issuers/token-standards.md) pour vous orienter.

### Comment accéder à l'API ?

L'API REST est accessible à l'adresse `https://api.registerwerk.example.com`. La documentation se trouve à `/swagger-ui.html`. Il vous faut un jeton JWT de votre fournisseur d'identité pour vous authentifier. Voir [Se connecter](./authentication.md).
