---
title: Déployer sur la blockchain
---

# Déployer sur la blockchain

Une fois votre émission approuvée par l'opérateur du registre, vous pouvez déployer le contrat de jeton sur la blockchain. Cette étape est irréversible — l'adresse du contrat devient une partie permanente de l'enregistrement au registre.

## Prérequis

- Le statut de l'émission est **APPROVED**
- Vous avez le rôle **Issuer** ou **Company Admin**
- Pour les émissions ERC-3643 : l'opérateur a prédéployé les contrats de fabrique sur la chaîne cible

## Lancer le déploiement

1. Allez dans **Issuances** et trouvez votre émission (statut : APPROVED)
2. Cliquez sur **Deploy to Blockchain**
3. Une boîte de dialogue de confirmation apparaît, résumant les paramètres du déploiement :

| Paramètre | Valeur |
|-----------|-------|
| Token standard | ERC-3643 |
| Network | Polygon Mainnet |
| ISIN | DE000EXAMPLE0 |
| Name | Example AG Bond 2025 |
| Symbol | EAGB25 |
| Total supply | 10 000 000 |

4. Cliquez sur **Confirm Deployment**

## Ce qui se passe pendant le déploiement

Le backend du registre soumet une transaction de déploiement à la blockchain pour votre compte, au moyen d'un portefeuille déployeur contrôlé par l'opérateur. Vous n'avez aucune transaction à signer vous-même ni d'ETH/MATIC à détenir.

Pour une émission **ERC-3643**, les contrats suivants sont déployés dans l'ordre :

1. **Contrat de jeton** — le jeton ERC-3643 principal
2. **Identity Registry** — associe les adresses de portefeuille des investisseurs à leur ONCHAINID
3. **Identity Registry Storage** — stockage persistant du registre
4. **Claim Topics Registry** — liste les sujets d'attestation KYC requis (p. ex. sujet 1 = KYC, sujet 2 = LCB-FT)
5. **Trusted Issuers Registry** — liste les émetteurs d'identité habilités à délivrer des attestations
6. **Modular Compliance** — conteneur des modules de règles de conformité

Cela prend généralement de 30 à 120 secondes selon la congestion du réseau.

## Suivre l'avancement du déploiement

La page de détail de l'émission affiche un indicateur d'avancement en direct pendant le déploiement. Chaque déploiement de contrat est listé avec son hachage de transaction, qui renvoie à l'explorateur de blocs.

Si une étape échoue (panne réseau, gas insuffisant…), le déploiement est automatiquement retenté jusqu'à trois fois. Si toutes les tentatives échouent, l'émission revient au statut **APPROVED** et vous êtes averti par courriel.

## Après un déploiement réussi

Lorsque tous les contrats sont déployés, l'émission passe au statut **ISSUED**. Vous pouvez voir :

- **Adresse du contrat** — l'adresse du contrat de jeton principal
- **Lien vers l'explorateur de blocs** — vérifier le contrat sur Etherscan, Polygonscan, etc.
- **Transaction de déploiement** — la transaction qui a créé le jeton

!!! tip
    Communiquez l'adresse du contrat et le lien vers l'explorateur à vos investisseurs afin qu'ils puissent vérifier leurs avoirs de manière indépendante.


## Étapes suivantes

- [Ajouter des investisseurs et admettre les portefeuilles](./managing-investors.md)
- Configurer les modules de conformité (les opérateurs le font automatiquement pour les configurations ERC-3643 standard)
- Annoncer l'émission à vos investisseurs
