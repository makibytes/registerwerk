---
title: Rôles et permissions
description: Qui utilise Registerwerk, ce que chacun peut faire, et à quelle obligation réglementaire répond chaque rôle.
---

# Rôles et permissions

Registerwerk est multi-locataire : une installation d'opérateur dessert de nombreuses entités juridiques clientes. L'accès est régi par un jeu de rôles défini dans l'énumération `AppRole` et appliqué par `@PreAuthorize` sur chaque méthode de contrôleur.

---

## Vue d'ensemble des rôles

| Rôle | Portail | Qui le détient | Obligation réglementaire |
|---|---|---|---|
| `REGISTRY_ADMIN` | Opérateur | Personnel du registre | §15 eWpG teneur de registre ; §10 GwG responsable LCB-FT |
| `COMPLIANCE_OFFICER` | Opérateur | Équipe conformité / LCB-FT | §7 GwG responsable conformité ; art. 8 AMLD6 |
| `AUDITOR` | Opérateur | Auditeurs internes/externes | §15(3) eWpG accès aux enregistrements |
| `ISSUER` | Client | Émetteurs de titres | §4 eWpG obligations de l'émetteur |
| `INVESTOR` | Client | Titulaires de jetons / investisseurs | |
| `COMPANY_ADMIN` | Client | Administrateurs chez l'émetteur | |
| `TRADER` | Client | Accès d'exécution pour les intégrations de plateformes de négociation | Art. 26 MiFIR déclaration |

---

## Rôles opérateur

### REGISTRY_ADMIN

Le rôle aux privilèges les plus étendus. Un `REGISTRY_ADMIN` peut :

- Créer, modifier et désactiver des [entités juridiques](../intro/concepts.md#entites-clientes)
- Approuver et rejeter des [documents KYC](../compliance/kyc-aml.md)
- Déployer et administrer des [jetons de titres](../token-standards/index.md)
- Inscrire un [Sperrvermerk](../compliance/sperrvermerk.md) (restriction de négociation) — exige une [authentification renforcée](../compliance/step-up-mfa.md)
- Transférer et détruire des jetons de force — exige authentification renforcée + double validation
- Prendre la place d'utilisateurs clients à des fins d'assistance — capacité permanente, voir la réserve ci-dessous
- Accéder à tous les enregistrements de la [piste d'audit](../platform/audit-log.md)
- Déclencher les exports réglementaires [MiFIR](../compliance/mifir.md) et [DAC8](../compliance/dac8.md)

!!! warning "Les opérations forcées exigent un double contrôle"
    Le transfert forcé, la destruction forcée et l'approbation forcée sont des opérations on-chain irréversibles. L'implémentation actuelle exige qu'un second `REGISTRY_ADMIN`, distinct, fournisse le jeton de double contrôle ; il n'existe pas de rôle applicatif `SECOND_APPROVER`. Son adéquation juridique et réglementaire requiert un examen externe.

### COMPLIANCE_OFFICER

Centré sur les fonctions LCB-FT/KYC :

- Examiner et gérer les campagnes et correspondances de [filtrage des sanctions](../compliance/sanctions-screening.md)
- Accepter ou rejeter les correspondances (en double validation pour les entités à haut risque)
- Approuver les documents KYC pour les juridictions qui lui sont assignées
- Inscrire et lever un [Sperrvermerk](../compliance/sperrvermerk.md) — exige une authentification renforcée
- Accéder aux enregistrements d'incidents [DORA](../compliance/dora.md)
- Déclencher un nouveau filtrage des sanctions à la demande

### AUDITOR

Accès en lecture seule à la totalité de la piste d'audit :

- Lire toutes les entrées de la [piste d'audit](../platform/audit-log.md)
- Vérifier l'intégrité de la chaîne de hachage d'audit
- Exporter les enregistrements d'audit pour un examen externe
- Accéder à l'historique des campagnes de filtrage et aux versions des documents KYC

### Approbateur en double validation

L'approbation en double validation est aujourd'hui une capacité d'un second `REGISTRY_ADMIN` distinct, non un rôle applicatif séparé. L'approbateur doit différer de l'initiateur et satisfaire les contrôles d'authentification renforcée configurés.

---

## Rôles client

Les utilisateurs clients accèdent à la plateforme par l'interface client (`:4201`), dont les appels d'API transitent par Kong. Leur JWT porte une revendication `entityId` (également émise sous la forme `entity_id`) indiquant à quelle `LegalEntity` ils appartiennent, et le backend en déduit l'isolation des données à chaque requête.

`X-Entity-Id` est un nom d'*en-tête*, pas une revendication — et un en-tête que Kong **retire** délibérément des requêtes entrantes afin qu'il ne puisse pas être forgé. Rien dans le backend ne s'y fie.

### ISSUER

Un émetteur peut :

- Créer et gérer ses propres définitions d'[actif](../token-standards/index.md)
- Lancer le déploiement d'un jeton (sous réserve, le cas échéant, de l'approbation de l'opérateur)
- Gérer l'intégration des investisseurs pour ses jetons
- Consulter l'historique des [opérations sur titres](../intro/concepts.md) de ses titres
- Télécharger les relevés de positions et les documents réglementaires

### INVESTOR

Un investisseur peut :

- Consulter son portefeuille (jetons détenus, positions)
- Accepter des demandes de transfert
- Consulter l'historique des transactions
- Télécharger ses relevés de positions

### COMPANY_ADMIN

Gère les utilisateurs et les rôles au sein d'une entité juridique cliente :

- Inviter et retirer des utilisateurs de l'entreprise
- Attribuer les rôles `ISSUER` / `INVESTOR` / `TRADER` au sein de son entité
- Consulter le statut KYC de l'entité (sans pouvoir l'approuver — seuls les opérateurs le peuvent)

### TRADER

Un utilisateur, machine ou humain, habilité à interagir avec les intégrations de plateformes de négociation :

- Soumettre et gérer des offres de vente
- Consulter les rapports d'exécution
- Ces actions sont déclarées aux régulateurs via [MiFIR RTS 22](../compliance/mifir.md)

---

## Mode support

Les utilisateurs `REGISTRY_ADMIN` peuvent prendre la place d'un utilisateur client pour investiguer un problème ou aider à l'intégration. Le mode support :

- Émet un jeton de courte durée dont le `sub` reste l'identifiant utilisateur de l'**opérateur**, de sorte que chaque action est imputée à l'opérateur et jamais au client
- Est consigné dans la [piste d'audit](../platform/audit-log.md), marqué par `imp` afin que ces actions restent distinguables
- Est visible par tous les utilisateurs `REGISTRY_ADMIN` grâce à la barre affichée dans l'interface client
- Expire avec le jeton ; rentrez à nouveau plutôt que de chercher à prolonger

!!! warning "Le mode support n'est pas protégé par une authentification renforcée"
    `AdminImpersonationController` ne porte aucun `@RequiresStepUp`. Tout `REGISTRY_ADMIN` peut entrer dans le portail de n'importe quel client sans second défi d'authentification et sans seconde personne.

    Traitez cela comme une question de contrôle plutôt que technique : gardez la liste d'administrateurs réduite, exigez un motif consigné hors de la plateforme, et passez en revue périodiquement les événements. [Mode support](../operator/customers/impersonation.md) traite de son encadrement.

Le mode support est par ailleurs totalement indisponible lorsque `ENTRA_ENABLED=true` — le backend refuse d'émettre une session pour le compte d'un client.
