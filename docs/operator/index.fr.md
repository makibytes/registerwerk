---
title: Pour les opérateurs
description: Exploiter un registre Registerwerk — le métier, l'architecture et les processus clients qui constituent l'essentiel du travail.
---

# Pour les opérateurs

**Vous exploitez le registre.** Les clients y émettent des titres, les détiennent, les négocient. Votre travail consiste à décider qui entre, à vérifier ce qu'ils font, à maintenir la plateforme en vie et à aider quand quelque chose tourne mal.

Vous n'avez pas besoin de connaître les marchés de titres aussi profondément qu'un émetteur. Vous devez en savoir assez pour comprendre ce que vous approuvez et pourquoi cela compte.

---

## Par où commencer

<div class="grid cards" markdown>

-   :material-flag:{ .lg .middle } **[Ce que fait un opérateur](getting-started.md)**

    ---

    Le métier dans son ensemble, le portail, et les décisions qui n'appartiennent qu'à vous.

-   :material-sitemap:{ .lg .middle } **[Comment Registerwerk est construit](architecture.md)**

    ---

    L'architecture, présentée sous l'angle de ce qui casse et de ce que cela signifie alors.

-   :material-account-group:{ .lg .middle } **[Servir les clients](customers/index.md)**

    ---

    Intégration, KYC, approbations, assistance, mode support, résiliation. L'essentiel du travail réel.

-   :material-server:{ .lg .middle } **[Installation](installation/prerequisites.md)**

    ---

    La mise en route, des prérequis à la passerelle.

</div>

---

## Les quatre choses que vous seul pouvez faire

Les clients peuvent faire beaucoup. Ces quatre-là vous appartiennent, et vous appartiennent parce que chacune peut causer un dommage difficile voire impossible à réparer.

| | | |
|---|---|---|
| **Admettre une organisation** | Personne n'utilise le registre tant que vous n'avez pas approuvé son entité et son KYC. | [Intégration](customers/onboarding-flow.md) · [KYC](customers/kyc-process.md) |
| **Approuver une émission** | Aucun titre n'existe tant que vous n'avez pas dit oui. | [Approuver les émissions](customers/approving-issuances.md) |
| **Corriger le registre** | Transferts forcés, destructions forcées, blocages de titulaire — les pouvoirs des §24 et §26 eWpG. | [Sperrvermerk](../compliance/sperrvermerk.md) |
| **Agir comme un client** | Le mode support. Puissant et entièrement imputé. | [Mode support](customers/impersonation.md) |

C'est sur la deuxième et la quatrième que les nouveaux opérateurs demandent le plus souvent des repères ; toutes deux ont une page dédiée.

---

## Les habitudes qu'il vaut mieux prendre tôt

!!! tip "Lisez la piste d'audit quand tout va bien"
    Si vous ne l'ouvrez qu'en cas d'incident, vous ne saurez pas à quoi ressemble la normale, et vous ne remarquerez pas la chose qui ne devrait pas s'y trouver.

!!! tip "Voyez la double validation comme une fonctionnalité, pas comme un obstacle"
    Plusieurs opérations exigent une seconde personne : annuler une transaction réglée, approuver le règlement d'une opération sur titres, réinitialiser la MFA d'un client, délivrer un laissez-passer temporaire. Ce sont précisément les opérations où une seule action erronée ou malveillante fait le plus de dégâts.

    Les installations où une personne détient tous les identifiants n'ont de double validation que le nom. C'est l'effectif qui la rend réelle.

!!! tip "Dites « je ne sais pas » à voix haute"
    On vous demandera si un instrument est conforme, si un jeton produit un effet juridique, si un client peut licitement faire telle chose. La plateforme modélise des règles ; elle ne tranche pas.

    Renvoyer une question au conseil juridique est bien plus souvent la bonne réponse que les opérateurs ne l'imaginent.

---

## Ce que vous n'êtes pas

Cela mérite d'être dit, car les clients supposeront le contraire.

- **Vous n'êtes pas leur avocat.** Vous approuvez selon vos critères, pas les leurs.
- **Vous n'êtes pas leur conservateur.** Vous ne pouvez pas récupérer une clé de portefeuille perdue. Vous pouvez exécuter un transfert forcé au titre du §24, qui est une correction formelle, pas une réinitialisation de mot de passe.
- **Vous n'êtes pas un service de valorisation.** Le registre consigne des montants nominaux, pas des prix de marché.
- **Vous n'êtes pas un garant.** Si un émetteur fait défaut, vous en consignez le fait ; vous n'indemnisez pas les titulaires.

---

## Quand quelque chose ne va pas

| | |
|---|---|
| La plateforme se comporte mal | [Dépannage](troubleshooting.md) |
| Quelque chose est tombé | [Supervision](maintenance/monitoring.md) · [Plan de reprise](dr/runbook.md) |
| Client verrouillé dehors | [Assistance deux facteurs](customers/two-factor-support.md) |
| Client perdu | [Mode support](customers/impersonation.md) — voir exactement ce qu'il voit |
