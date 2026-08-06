---
title: Intégration
---

# Intégration

Ce guide vous accompagne dans l'enregistrement de votre organisation auprès du registre eWpG — du premier courriel d'invitation jusqu'à un compte entièrement configuré.

## Comment fonctionne l'intégration

L'intégration est déclenchée par l'opérateur du registre, et non par une auto-inscription. Le processus comporte ces quatre étapes :

```
Operator creates entity
        |
        v
You receive an invitation email with a one-time token
        |
        v
You redeem the token and configure your organization
        |
        v
Admin activates your account — you can start working
```

## Étape 1 — Recevoir votre invitation

L'opérateur du registre crée une entité (société ou personne physique) pour votre compte. Vous recevrez un courriel du registre ayant pour objet **« Your eWpG Registry Invitation »**, contenant :

- Un **jeton d'intégration** à usage unique (valable 48 heures)
- Un lien vers le portail client

!!! warning "Expiration du jeton"
    Le jeton d'intégration expire au bout de 48 heures. S'il a expiré, contactez l'opérateur du registre pour en demander un nouveau. Ne partagez pas le jeton — il donne un accès complet à la configuration de votre compte.


## Étape 2 — Utiliser le jeton

1. Cliquez sur le lien du courriel d'invitation. Vous arrivez sur le portail client.
2. Il vous sera demandé de vous connecter via votre fournisseur d'identité (voir [Se connecter](./authentication.md)). Pour les nouveaux utilisateurs, il s'agit généralement de Microsoft Entra ID (anciennement Azure AD) avec votre adresse professionnelle.
3. Après connexion, le portail détecte votre jeton d'intégration dans l'URL et active automatiquement votre entité.
4. Vous êtes redirigé vers l'écran **Welcome**, qui affiche le rôle qui vous a été attribué (Issuer, Investor ou Auditor).

## Étape 3 — Configurer votre organisation

Après avoir utilisé le jeton, vous pouvez configurer le profil de votre organisation :

### Informations sur l'organisation

Allez dans **Settings → Organization** et renseignez :

| Champ | Description |
|-------|-------------|
| Legal name | Votre dénomination sociale enregistrée |
| LEI | Identifiant d'entité juridique (obligatoire pour les émetteurs) |
| Registration number | Numéro d'immatriculation de la société |
| Jurisdiction | Pays de constitution |
| Contact email | Contact principal pour les notifications réglementaires |

### Gestion des utilisateurs

Si votre organisation compte plusieurs utilisateurs, allez dans **Settings → Users** et invitez-les par courriel. Chaque utilisateur invité :
- reçoit son propre courriel d'invitation
- se connecte avec sa propre identité d'entreprise
- se voit attribuer l'un des rôles de votre organisation

### Configurer votre propre fournisseur d'identité (facultatif)

Si votre organisation utilise un fournisseur d'identité qui lui est propre (par exemple votre Keycloak, Okta, ou un autre IdP compatible OIDC), vous pouvez le configurer sous **Settings → Identity Provider**.

Vous devrez fournir :

```
OIDC Issuer URL:       https://your-idp.example.com/realms/your-realm
Client ID:             registerwerk-client
```

!!! info "Il n'y a pas de champ pour un secret client"
    La fédération s'établit de locataire à locataire dans votre propre fournisseur d'identité. Registerwerk n'exécute jamais de flux d'autorisation par code contre votre locataire : il n'a donc aucun usage d'un secret client vous appartenant — et le champ a été supprimé plutôt que laissé à collecter un identifiant dont personne n'a besoin. Voir [Administrateur d'entreprise](workspaces/company-admin.md).


Une fois configurée et vérifiée, l'ensemble des utilisateurs de votre organisation sera redirigé vers votre IdP pour l'authentification, au lieu de la connexion Entra ID par défaut.

## Étape 4 — Activation du compte

Votre compte est désormais actif. Selon votre rôle :

- **Émetteurs** : il pourra vous être demandé de compléter un examen KYC/LCB-FT avant de pouvoir déployer des jetons sur le réseau principal. Voir [Créer une émission](lifecycle/primary-issuance.md).
- **Investisseurs** : votre compte est prêt. Vous pouvez connecter un portefeuille et consulter vos avoirs.
- **Auditeurs** : votre compte est prêt. Vous disposez d'un accès en lecture seule à toutes les données du registre.

## Besoin d'aide ?

Si vous rencontrez des problèmes lors de l'intégration, contactez l'opérateur du registre via le lien d'assistance du courriel d'invitation ou le bouton **Help** en pied de page du portail.
