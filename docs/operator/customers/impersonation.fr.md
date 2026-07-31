---
title: Mode support — voir ce qu'ils voient
description: Agir à l'intérieur du portail d'un client pour l'assister : comment cela fonctionne, à qui c'est imputé, quelles en sont les limites et comment l'encadrer.
---

# Mode support — voir ce qu'ils voient

Un client dit que le Trading Desk refuse de lui laisser publier une offre. Vous regardez son compte dans le portail opérateur et tout paraît normal. Vous demandez une capture d'écran et recevez la photo d'un moniteur.

**Le mode support met fin à cette boucle.** Il ouvre le portail client avec l'organisation du client sélectionnée, de sorte que vous voyez précisément ce qu'il voit.

C'est aussi la chose la plus puissante que vous puissiez faire sans l'accord d'une seconde personne, et elle mérite d'être employée avec discernement.

---

## Ce que c'est réellement

Pas une réinitialisation de mot de passe. Pas une connexion en tant que lui. Vous n'obtenez jamais ses identifiants et il n'est jamais déconnecté.

Le backend émet un **jeton de courte durée** portant :

| Revendication | Valeur |
|---|---|
| `sub` | **Votre** identifiant utilisateur — pas le sien |
| `entityId` | L'organisation cliente à l'intérieur de laquelle vous agissez |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `exp` | Court — la durée de vie standard d'un jeton |

!!! success "Le sujet reste vous, et c'est toute la conception"
    Parce que `sub` demeure votre identifiant, **chaque action que vous accomplissez vous est imputée** dans la [piste d'audit](../../platform/audit-log.md) — pas au client, ni à un acteur « système » partagé.

    Un client ne peut jamais être tenu pour responsable de ce qu'un opérateur a fait en mode support, et un opérateur ne peut jamais se dissimuler derrière l'identité d'un client. Sans cette propriété, le mode support serait inutilisable dans un contexte réglementé.

    Le drapeau `imp: true` marque la session comme étant en mode support, de sorte que ces actions se distinguent des actions ordinaires dans le journal.

---

## L'utiliser

1. Dans le portail opérateur, ouvrez la fiche du client et choisissez **Impersonate**.
2. Vous êtes transmis au portail client sur `/admin/handoff`, qui consomme le jeton depuis le fragment d'URL et vous dépose sur le tableau de bord.
3. Une **barre persistante** figure en haut de chaque page : *Acting as **Nordwind Energie GmbH***, avec **Switch company** et **Exit impersonation**.
4. Travaillez. Tout ce que vous faites est journalisé à votre nom.
5. Choisissez **Exit impersonation** une fois terminé.

Vous pouvez aussi entrer sans choisir de client au préalable — la barre indique alors *Admin mode — no company selected* et propose **Select company**, avec une liste consultable.

!!! tip "La barre est toujours visible, et ce n'est pas un hasard"
    Tout `REGISTRY_ADMIN` voit la barre du mode support dans le portail client en permanence, qu'une société soit sélectionnée ou non. C'est un rappel constant que vous n'êtes pas un utilisateur ordinaire de cette interface, et cela rend bien plus difficile de travailler par inadvertance dans le mauvais contexte.

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

- **Le jeton est de courte durée.** Les longues sessions expirent ; rentrez à nouveau plutôt que de chercher à prolonger.
- **Vous obtenez un jeu de rôles fixe**, et non les rôles propres à un utilisateur donné. Vous ne pouvez pas reproduire un problème dépendant des permissions plus étroites d'un utilisateur.
- **L'authentification renforcée et la double validation s'appliquent toujours.** Le mode support ne les contourne pas.
- **Vous ne pouvez pas prendre la place d'un autre opérateur.** Il ne vise que les entités juridiques clientes.

---

## L'encadrer

Le mode support est une capacité permanente de tout `REGISTRY_ADMIN`. Cela en fait une question de contrôle plutôt qu'une question technique, et les auditeurs poseront la question.

!!! tip "Pratiques à adopter"

    **Exigez un motif, consigné hors de la plateforme.** Une référence de ticket, avant la session. La piste d'audit consigne que vous avez utilisé le mode support ; elle ne peut pas consigner *pourquoi*.

    **Passez en revue les événements de mode support périodiquement.** Ils sont interrogeables. Un coup d'œil mensuel sur qui a assisté qui, rapproché des tickets, transforme un pouvoir illimité en pouvoir supervisé.

    **Gardez `REGISTRY_ADMIN` restreint.** Chaque détenteur peut entrer chez chaque client. C'est le meilleur argument en faveur d'une liste d'administrateurs réduite.

    **Dites aux clients que cela existe.** Découvrir après coup que le personnel de l'opérateur peut entrer dans leur portail abîme la confiance bien plus que la capacité elle-même. Bien présentée — *nous pouvons voir ce que vous voyez, chaque action est consignée à notre nom* — elle rassure.

    **Ne laissez jamais une session ouverte.** Sortez une fois terminé. Un navigateur laissé sans surveillance en mode support est un navigateur laissé sans surveillance dans le compte d'un client.

---

## Ce qu'un auditeur demandera

Ayez les réponses prêtes :

- Qui détient `REGISTRY_ADMIN`, et cela représente combien de personnes ?
- Comment reliez-vous un événement de mode support à un motif d'assistance ?
- Comment détecteriez-vous un usage du mode support *sans* ticket correspondant ?
- Pouvez-vous démontrer que ces actions sont imputées à l'opérateur et non au client ?

La dernière est une démonstration en direct, qu'il vaut la peine de répéter : entrez chez une entité de test, accomplissez une action anodine, montrez l'entrée d'audit nommant votre utilisateur avec `imp` positionné.

---

## Et ensuite

- [Assistance deux facteurs](two-factor-support.md) — l'autre grand flux d'assistance
- [Piste d'audit](../../platform/audit-log.md)
- [Rôles et permissions](roles.md)
