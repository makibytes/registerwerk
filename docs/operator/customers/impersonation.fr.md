---
title: Mode support — voir ce qu'ils voient
description: Agir à l'intérieur du portail d'un client pour l'assister : comment cela fonctionne, à qui c'est imputé, quelles en sont les limites et comment l'encadrer.
---

# Mode support — voir ce qu'ils voient

Un client dit que le Trading Desk refuse de lui laisser publier une offre. Vous regardez son compte dans le portail opérateur et tout paraît normal. Vous demandez une capture d'écran et recevez la photo d'un moniteur.

**Le mode support met fin à cette boucle.** Il ouvre le portail client avec l'organisation du client sélectionnée, de sorte que vous voyez précisément ce qu'il voit.

Il donne accès à la vue qu'un client a de ses propres données ; il est donc encadré : le démarrage exige une preuve d'authentification renforcée récente et un motif écrit, la session par défaut est en **lecture seule**, et une session pouvant écrire exige un deuxième approbateur et n'existe qu'en mode démo.

---

## Ce que c'est réellement

Pas une réinitialisation de mot de passe. Pas une connexion en tant que lui. Vous n'obtenez jamais ses identifiants et il n'est jamais déconnecté.

L'appel de démarrage (`POST /api/v1/impersonation`, motif d'authentification renforcée `ADMIN_IMPERSONATION`) porte le client, un **motif obligatoire** (au moins 15 caractères) et, en option, une référence de ticket. Il ne renvoie **aucun jeton**, mais une URL de transfert contenant un **code à usage unique**, valable 60 secondes. Le portail client échange ce code contre un cookie de session httpOnly ; rejouer le code met fin à la session. Le jeton ne passe donc jamais entre les mains de l'opérateur ni dans l'historique de son navigateur.

Le jeton de session derrière le cookie porte :

| Revendication | Valeur |
|---|---|
| `sub` | **Votre** identifiant utilisateur — pas le sien |
| `entityId` | L'organisation cliente à l'intérieur de laquelle vous agissez |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `imp_mode` | `READ_ONLY` (par défaut) ou `ACT_ON_BEHALF` |
| `jti` | L'identifiant de l'enregistrement `impersonation_session` |
| `exp` | 30 minutes (`registerwerk.auth.impersonation-ttl-seconds`, 1800 par défaut) |

!!! success "Le sujet reste vous, et c'est toute la conception"
    Parce que `sub` reste votre identifiant utilisateur, **chaque action que vous accomplissez vous est attribuée** dans le [journal d'audit](../../platform/audit-log.md) — pas au client, ni à un acteur « système » partagé.

    Un client ne peut jamais être tenu pour responsable de ce qu'un opérateur a fait en mode support, et un opérateur ne peut jamais se dissimuler derrière l'identité d'un client. Sans cette propriété, le mode support serait inutilisable dans un contexte réglementé.

    Le drapeau `imp: true` marque la session comme étant en mode support, de sorte que ces actions se distinguent des actions ordinaires dans le journal.

### Modes

| | `READ_ONLY` (par défaut) | `ACT_ON_BEHALF` |
|---|---|---|
| Point d'entrée de démarrage | `POST /api/v1/impersonation` | `POST /api/v1/impersonation/act-on-behalf` |
| Qui peut démarrer | `REGISTRY_ADMIN` ou `SUPPORT_AGENT` | `REGISTRY_ADMIN` uniquement |
| Authentification renforcée | Oui (`ADMIN_IMPERSONATION`) | Oui, plus un **deuxième approbateur** (`ADMIN_IMPERSONATION_ACT_ON_BEHALF`) |
| Ce que la session peut faire | Lecture seule : `POST`, `PUT`, `PATCH` et `DELETE` sont refusés avec `403 IMPERSONATION_READ_ONLY` | Écriture, sauf la liste d'interdictions ci-dessous |
| Mode production | Disponible | **Refusé.** En mode production, toute session active est appliquée en lecture seule, même une session d'écriture résiduelle |

La liste d'interdictions de `ACT_ON_BEHALF` (`registerwerk.auth.impersonation-deny-patterns`) renvoie `403 IMPERSONATION_ACTION_DENIED` pour les attestations du client et l'administration des comptes : confirmation de paiement, contestation et règlement des trades, déclarations de défaut du repo desk, gestion des utilisateurs de l'entreprise, paramètres du fournisseur d'identité de l'entreprise, webhooks, identité d'organisation et suppression de documents KYC.

!!! note "SUPPORT_AGENT"
    `SUPPORT_AGENT` est un rôle de personnel opérateur dédié au support : il peut démarrer des sessions en lecture seule et lister les entités clientes pour en choisir une, rien d'autre. L'attribuer ou le retirer exige une authentification renforcée et un deuxième approbateur ; il est inclus dans les revues d'accès.

Seules les entités juridiques **actives** peuvent être utilisées en mode support. La session est enregistrée dans `impersonation_session` (acteur, entité, mode, motif, ticket, approbateur, expiration), et **les administrateurs de l'entreprise du client voient chaque session sur leur entité** à `GET /api/v1/company/impersonation-sessions`.

---

## L'utiliser

1. Dans le portail opérateur, ouvrez la fiche du client et choisissez **Impersonate**. Saisissez le motif (et une référence de ticket si vous en avez une). La boîte de dialogue propose la lecture seule ; le mode écriture n'apparaît qu'en mode démo.
2. Vous êtes transféré au portail client à `/admin/handoff`. Le fragment d'URL porte le `code` à usage unique, `entityId` et `entityName` ; le portail échange le code contre son cookie de session et vous dépose sur le tableau de bord.
3. Une **barre permanente** se trouve en haut de chaque page : *Acting as **Nordwind Energie GmbH*** (dans une session en lecture seule : *Viewing … (read-only support session - changes are blocked)*), avec **Switch company** et **Exit impersonation**.
4. Regardez et diagnostiquez. Tout ce que vous faites est journalisé à votre nom.
5. Choisissez **Exit impersonation** une fois terminé. La session prend fin et est journalisée ; sans cela, elle expire après 30 minutes.

Vous pouvez aussi entrer sans avoir choisi de client — la barre indique alors *Admin mode — no company selected* et propose **Select company**, avec une liste de recherche. Un `SUPPORT_AGENT` arrive sur ce sélecteur d'entreprise après la connexion.

!!! tip "La barre est toujours visible, et ce n'est pas un hasard"
    Tout `REGISTRY_ADMIN` voit la barre d'usurpation dans le portail client en permanence, qu'une société soit sélectionnée ou non. C'est un rappel constant que vous n'êtes pas un utilisateur ordinaire de cette interface, et elle rend bien plus difficile de travailler par erreur dans le mauvais contexte.

---

## Quand l'utiliser

**Bonnes raisons**

- Reproduire un problème signalé par un client et invisible dans le portail opérateur.
- Vérifier à quoi ressemble la vue d'un client après un changement de configuration.
- Guider un client dans un enchaînement pendant qu'il est au téléphone.
- Confirmer qu'un problème de permission ou d'éligibilité est bien celui que vous croyez.

**Mauvaises raisons**

!!! danger "N'utilisez pas le mode support pour faire le travail du client à sa place"
    Passer un ordre, créer une offre de vente ou soumettre une émission au nom d'un client produit un enregistrement montrant qu'*un opérateur* a pris une décision commerciale dans le compte d'un client.

    Même avec une imputation parfaite — peut-être *surtout* avec une imputation parfaite — c'est un enregistrement difficile à expliquer à un régulateur ou dans un litige. L'intention du client n'y figure nulle part.

    Regardez, diagnostiquez, expliquez. Laissez le client agir.

!!! danger "Ne l'utilisez pas pour lire des données auxquelles vous n'auriez pas droit autrement"
    Le mode support vous donne la vue du client sur ses propres informations. Savoir si *vous* avez le droit de les consulter en l'absence d'un motif d'assistance est une question de [protection des données](../../compliance/data-protection.md), pas une question technique. La piste d'audit montrera que vous avez regardé.

---

## Ses limites

### Il ne fonctionne pas en mode Entra

Lorsque `ENTRA_ENABLED=true`, les clients se connectent via Microsoft Entra ID, qui délivre les sessions directement à chaque utilisateur. Registerwerk ne peut pas émettre une session pour le compte d'un client, et le backend **refuse** d'essayer.

Le portail client affiche un message explicite plutôt qu'une redirection inexpliquée :

> **Impersonation is unavailable.** This portal signs in through Microsoft Entra ID, which issues the session directly to each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the customer to sign in themselves, or use the operator portal's read-only views.

C'est une contrainte réelle, pas une lacune à contourner. Dans les installations Entra, votre panoplie d'assistance se compose des vues du portail opérateur et du partage d'écran.

!!! warning "Prévoyez vos processus d'assistance en conséquence avant de basculer"
    Les opérateurs qui ont bâti leur flux d'assistance sur le mode support puis activent Entra découvrent la perte au pire moment. Décidez comment vous assisterez les clients sans lui *avant* la bascule, pas après.

### Autres limites

- **La session est de courte durée.** Elle expire après 30 minutes ; rentrez à nouveau (avec un nouveau motif) plutôt que d'essayer de la prolonger.
- **Le code de transfert est à usage unique et vaut 60 secondes.** Si le portail ne le récupère pas à temps, ou s'il est rejoué, la session prend fin ; recommencez.
- **Vous obtenez un ensemble de rôles fixe**, et non les rôles propres à un utilisateur donné. Vous ne pouvez pas reproduire un problème qui dépend des permissions plus restreintes d'un utilisateur.
- **L'authentification renforcée et les quatre yeux ne sont pas contournés.** Le démarrage exige votre propre preuve d'authentification renforcée ; une session d'écriture exige en plus un deuxième approbateur. Dans une session, les opérations protégées du client restent protégées, et la file d'approbation refuse les sessions en mode support.
- **Vous ne pouvez pas usurper un autre opérateur.** Il ne vise que les entités juridiques clientes.

---

## L'encadrer

Le mode support est ouvert à tout `REGISTRY_ADMIN` et à tout `SUPPORT_AGENT`. C'est donc une question de contrôle autant que de technique, et les auditeurs poseront la question.

!!! tip "Pratiques à adopter"

    **Rendez le motif utile.** La plateforme refuse un démarrage sans motif d'au moins 15 caractères et l'enregistre, avec la référence de ticket facultative, dans `impersonation_session` et dans l'événement d'audit. Mettez le numéro de ticket dans le champ ticket et écrivez dans le motif ce que vous devez voir.

    **Passez régulièrement en revue les événements de mode support.** Ils sont interrogeables (noms d'événements ci-dessous). Un examen mensuel de qui a ouvert quoi, rapproché des tickets, transforme un pouvoir étendu en pouvoir supervisé. Les administrateurs de l'entreprise du client peuvent faire le même contrôle de leur côté.

    **Privilégiez `SUPPORT_AGENT` pour le personnel du support.** Ce rôle démarre des sessions en lecture seule et rien d'autre ; le support n'a alors pas besoin d'un compte `REGISTRY_ADMIN`.

    **Gardez `REGISTRY_ADMIN` restreint.** Chaque titulaire peut démarrer des sessions pour chaque client actif.

    **Dites aux clients que cela existe.** Découvrir après coup que le personnel de l'opérateur peut entrer dans leur portail nuit bien plus à la confiance que la capacité elle-même. Bien présenté — *nous pouvons voir ce que vous voyez, chaque action est enregistrée à notre nom, et vos administrateurs peuvent consulter chaque session* — cela rassure.

    **Ne laissez jamais une session ouverte.** Quittez une fois terminé. Un navigateur laissé sans surveillance dans une session en mode support est un navigateur sans surveillance dans le compte d'un client (elle expire toutefois après 30 minutes).

---

## Ce qu'un auditeur demandera

Ayez des réponses prêtes :

- Qui détient `REGISTRY_ADMIN` ou `SUPPORT_AGENT`, et combien de personnes cela représente-t-il ?
- Comment relier un événement de mode support à un motif d'assistance ? (Le motif et le ticket figurent dans `impersonation_session` et dans l'événement `ADMIN_IMPERSONATION_STARTED`.)
- Comment détecteriez-vous un mode support *sans* ticket correspondant ?
- Pouvez-vous démontrer que les actions effectuées en mode support sont attribuées à l'opérateur, et non au client ?
- Le mode support en écriture est-il désactivé en production ? (Oui : le mode production refuse `ACT_ON_BEHALF` et rabaisse toute session active en lecture seule.)

La piste d'audit contient les événements `ADMIN_IMPERSONATION_STARTED`, `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` et `ADMIN_IMPERSONATION_ENDED` ; les requêtes d'une session portent la marque `imp`. La question de l'attribution est une démonstration en direct qui mérite d'être répétée : ouvrez une entité de test, regardez une page, montrez les entrées d'audit qui nomment votre utilisateur avec `imp` positionné, et montrez la session dans la vue des administrateurs de l'entreprise du client.

---

## Et ensuite

- [Assistance deux facteurs](two-factor-support.md) — l'autre grand flux d'assistance
- [Piste d'audit](../../platform/audit-log.md)
- [Rôles et permissions](roles.md)
