---
title: Tableau de bord
---

# Tableau de bord

Le tableau de bord est le premier écran que vous voyez après vous être connecté. Il offre une vue d'ensemble en temps réel de votre activité dans le registre, adaptée à votre rôle.

## Cartes de synthèse

En haut du tableau de bord se trouvent des cartes de synthèse. Celles qui s'affichent dépendent de votre rôle :

### Tableau de bord de l'émetteur

| Carte | Description |
|------|-------------|
| **Active Issuances** | Nombre de jetons actuellement à l'état ISSUED |
| **Pending Approval** | Émissions en attente d'examen par l'opérateur |
| **Total Investors** | Portefeuilles d'investisseurs distincts, tous vos jetons confondus |
| **Networks** | Réseaux blockchain distincts où vous avez déployé des jetons |

### Tableau de bord de l'investisseur

| Carte | Description |
|------|-------------|
| **Token Holdings** | Nombre de jetons de titres distincts que vous détenez |
| **Connected Wallets** | Portefeuilles enregistrés auprès de votre compte |
| **Recent Transfers** | Transferts des 30 derniers jours |

### Tableau de bord de l'auditeur

| Carte | Description |
|------|-------------|
| **Total Issuances** | Toutes les émissions du registre |
| **Transfers (30d)** | Total des événements de transfert on-chain des 30 derniers jours |
| **Active Issuers** | Nombre d'entités émettrices ayant au moins un jeton actif |
| **Pending KYC Reviews** | Dossiers KYC en attente d'examen par l'opérateur (lecture seule) |

## Fil d'activité récente

Sous les cartes de synthèse, le panneau **Recent Activity** affiche les derniers événements intéressant votre compte. Chaque entrée comporte :

- **Horodatage** — quand l'événement s'est produit (votre fuseau horaire local)
- **Type d'événement** — par exemple *Issuance Created*, *Transfer*, *KYC Approved*
- **Objet** — le jeton ou l'entité concernés
- **Réseau** — le réseau blockchain (avec l'icône de la chaîne)

Cliquez sur n'importe quelle ligne d'activité pour aller directement à la page de détail correspondante.

## Actions rapides

Le panneau **Quick Actions** offre une navigation en un clic vers les tâches les plus courantes de votre rôle :

- **Émetteur** : New Issuance, Manage Investors, View Pending Approvals
- **Investisseur** : View Holdings, Connect Wallet, Download Statement
- **Auditeur** : Open Audit Log, Search Transfers, Export Report

## État des réseaux

Le bas du tableau de bord affiche une grille **Network Status** en direct, indiquant si chaque réseau blockchain configuré est actuellement joignable et synchronisé. Un voyant vert signifie que l'indexeur est à jour ; jaune, qu'il a plus de 10 blocs de retard sur la tête de chaîne ; rouge, qu'il est indisponible.

!!! tip
    Si un réseau est au rouge, les données on-chain de ce réseau peuvent être périmées. Attendez quelques minutes et actualisez. Si le problème persiste, contactez l'opérateur du registre.


## Actualisation des données

Les données du tableau de bord s'actualisent automatiquement toutes les 30 secondes. Vous pouvez forcer une actualisation immédiate avec le bouton **Refresh** en haut à droite de chaque panneau.
