---
title: Rôles et permissions
description: Qui utilise Registerwerk, ce que chacun peut faire, et à quelle obligation réglementaire répond chaque rôle.
---

# Rôles et permissions

Registerwerk est multi-locataire : une installation d'opérateur dessert de nombreuses entités juridiques clientes. L'accès est régi par un jeu de rôles défini dans l'énumération `AppUserRole` et appliqué par `@PreAuthorize` sur chaque méthode de contrôleur.

---

## Vue d'ensemble des rôles

| Rôle | Portail | Qui le détient | Obligation réglementaire |
|---|---|---|---|
| `REGISTRY_ADMIN` | Opérateur | Personnel du registre | §15 eWpG teneur de registre ; §10 GwG responsable LCB-FT |
| `COMPLIANCE_OFFICER` | Opérateur | Équipe conformité / LCB-FT | §7 GwG responsable conformité ; art. 8 AMLD6 |
| `AUDIT` | Opérateur | Auditeurs internes/externes | §15(3) eWpG accès aux enregistrements |
| `SUPPORT_AGENT` | Opérateur | Personnel du support | Sessions client en lecture seule uniquement ; aucune fonction réglementaire |
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
- Démarrer des sessions de [mode support](#mode-support) en lecture seule à des fins d'assistance (authentification renforcée et motif consigné ; les sessions d'écriture n'existent qu'en mode démo)
- Accéder à tous les enregistrements de la [piste d'audit](../platform/audit-log.md)
- Déclencher les exports réglementaires [MiFIR](../compliance/mifir.md) et [DAC8](../compliance/dac8.md)

!!! warning "Les opérations forcées exigent un double contrôle"
    Le transfert forcé, la destruction forcée et l'approbation forcée sont des opérations irréversibles sur la chaîne. L'implémentation actuelle exige qu'un second opérateur distinct (un `REGISTRY_ADMIN` ou un `COMPLIANCE_OFFICER`) donne l'approbation en double validation ; il n'existe pas de rôle applicatif `SECOND_APPROVER`. Son adéquation juridique et de politique interne doit faire l'objet d'une revue externe.

### COMPLIANCE_OFFICER

Centré sur les fonctions LCB-FT/KYC :

- Examiner et gérer les campagnes et correspondances de [filtrage des sanctions](../compliance/sanctions-screening.md)
- Accepter ou rejeter les correspondances (toujours avec authentification renforcée et un deuxième approbateur)
- Approuver les documents KYC pour les juridictions qui lui sont assignées
- Consulter les [Sperrvermerk](../compliance/sperrvermerk.md) (leur inscription et leur levée sont réservées à `REGISTRY_ADMIN`, avec authentification renforcée et un deuxième approbateur)
- Accéder aux enregistrements d'incidents [DORA](../compliance/dora.md)
- Déclencher un nouveau filtrage des sanctions à la demande

### AUDIT

Accès en lecture seule à la totalité de la piste d'audit :

- Lire toutes les entrées de la [piste d'audit](../platform/audit-log.md)
- Vérifier l'intégrité de la chaîne de hachage d'audit
- Exporter les enregistrements d'audit pour un examen externe
- Accéder à l'historique des campagnes de filtrage et aux versions des documents KYC

### Approbateur en double validation

L'approbation en double validation est aujourd'hui une capacité d'un second utilisateur distinct qui détient `REGISTRY_ADMIN` ou `COMPLIANCE_OFFICER`, non un rôle applicatif séparé. L'approbateur doit différer de l'initiateur, être toujours actif dans la base de données et satisfaire les contrôles d'authentification renforcée configurés. Les demandes peuvent être déposées et approuvées dans la file d'approbation de l'application (voir [Authentification renforcée et quatre yeux](../compliance/step-up-mfa.md)).

### SUPPORT_AGENT

Personnel de l'opérateur pour l'assistance client. Un `SUPPORT_AGENT` peut lister les entités clientes et démarrer des sessions de [mode support](#mode-support) en **lecture seule** (authentification renforcée et motif requis). Il ne peut rien modifier et n'a aucune fonction réglementaire. L'attribution ou le retrait du rôle exige une authentification renforcée et un deuxième approbateur.

---

## Rôles client

Les utilisateurs clients accèdent à la plateforme par l'interface client (`:44201`), dont les appels d'API transitent par Kong. Leur JWT porte une revendication `entityId` (également émise sous la forme `entity_id`) indiquant à quelle `LegalEntity` ils appartiennent, et le backend en déduit l'isolation des données à chaque requête.

`X-Entity-Id` est un nom d'*en-tête*, pas une revendication — et un en-tête que Kong **retire** délibérément des requêtes entrantes afin qu'il ne puisse pas être forgé. Rien dans le backend ne s'y fie.

### ISSUER

Un émetteur peut :

- Créer et gérer ses propres définitions d'[actif](../token-standards/index.md)
- Lancer le déploiement d'un jeton (sous réserve, le cas échéant, de l'approbation de l'opérateur)
- Gérer l'intégration des investisseurs pour ses jetons
- Proposer des [opérations sur titres](../intro/concepts.md) — dividendes, fractionnements, remboursements anticipés — pour examen par l'opérateur, et retirer une proposition avant son examen
- Attester qu'une opération sur titres est prête à être réglée — la première des deux parties requises, aux côtés de la confirmation d'un opérateur
- Consulter l'historique des opérations sur titres de ses titres
- Télécharger les relevés de positions et les documents réglementaires

### INVESTOR

Un investisseur peut :

- Consulter son portefeuille (jetons détenus, positions)
- Accepter des demandes de transfert
- Consulter l'historique des transactions
- Consulter les opérations sur titres affectant ses positions et télécharger les confirmations de règlement
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
- La plateforme conserve un registre des ordres et exécutions (export opérateur sous `/api/v1/admin/trading/order-history`) ; elle ne transmet **pas** de déclarations MiFIR RTS 22 — voir [MiFIR](../compliance/mifir.md) (brouillon, non validé)

---

## Mode support

Le mode support (« impersonation ») permet au personnel de l'opérateur d'ouvrir le portail client à l'intérieur de l'organisation d'un client pour examiner des problèmes. Il est encadré et en lecture seule par défaut :

- Le démarrage exige une [authentification renforcée](../compliance/step-up-mfa.md) et un motif écrit obligatoire (au moins 15 caractères, plus une référence de ticket facultative)
- Le mode par défaut est la **lecture seule** ; le mode écriture (`ACT_ON_BEHALF`) exige un deuxième approbateur et n'existe **qu'en mode démo**. En mode production, toute session est en lecture seule
- `REGISTRY_ADMIN` et `SUPPORT_AGENT` peuvent démarrer des sessions en lecture seule ; seul `REGISTRY_ADMIN` peut démarrer une session d'écriture. `SUPPORT_AGENT` ne peut rien faire d'autre
- L'appel de démarrage ne renvoie aucun jeton : un code à usage unique (60 secondes) est échangé contre un cookie de session httpOnly. La session dure 30 minutes au plus
- Le `sub` du jeton reste l'identifiant utilisateur de l'**opérateur**, de sorte que chaque action est imputée à l'opérateur et jamais au client ; `imp` la marque dans la [piste d'audit](../platform/audit-log.md)
- Les sessions sont consignées et visibles des administrateurs de l'entreprise du client
- Il est visible par tous les utilisateurs `REGISTRY_ADMIN` grâce à la barre affichée dans l'interface client

Le mode support est totalement indisponible lorsque `ENTRA_ENABLED=true` — le backend refuse d'émettre une session pour le compte d'un client. [Mode support](../operator/customers/impersonation.md) détaille le fonctionnement et l'encadrement.
