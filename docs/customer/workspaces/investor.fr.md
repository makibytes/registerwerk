---
title: Investisseur
description: Pour ceux qui détiennent des titres — voir ce que vous possédez, ce que cela vaut et ce qui vous est dû.
---

# Investisseur

**Vous détenez des titres et vous voulez les suivre.** Vous ne négociez pas activement, vous n'émettez rien et vous ne cherchez pas d'effet de levier. Vous avez acheté quelque chose et vous voulez savoir où cela en est.

C'est le plus petit espace de travail, et c'est délibéré.

---

## Avant que quoi que ce soit ne fonctionne

Trois conditions doivent être remplies pour qu'un titre puisse vous parvenir. Si quelque chose ne marche pas, c'est presque toujours l'une d'elles.

<div class="grid cards" markdown>

-   **1. Votre organisation est intégrée**

    ---

    Votre société existe au registre en tant qu'entité juridique au statut actif.

    [:octicons-arrow-right-24: Obtenir votre compte](../onboarding.md)

-   **2. Votre KYC est approuvé**

    ---

    L'opérateur a vérifié votre organisation. Pas seulement déposé — **approuvé**, et non expiré.

    [:octicons-arrow-right-24: Vérification](../kyc.md)

-   **3. Vous avez enregistré un portefeuille**

    ---

    Une adresse à laquelle les titres peuvent être envoyés. Sans elle, il n'y a nulle part où livrer.

    [:octicons-arrow-right-24: Connecter un portefeuille](../investors/wallet-setup.md)

</div>

!!! warning "L'ordre compte"
    Pour un instrument réglementé tel qu'un titre [ERC-3643](../../token-standards/erc3643.md), votre portefeuille doit être admis au registre d'identités de cet instrument *avant* que quoi que ce soit puisse vous être transféré. Un transfert vers un portefeuille non enregistré ne reste pas en attente — il échoue on-chain.

    Si un émetteur affirme vous avoir envoyé des titres et que rien n'arrive, c'est la première chose à vérifier.

---

## Votre quotidien

### Dashboard

Ce qui a changé depuis votre dernière visite : vos positions, l'activité récente, tout ce qui demande attention — un KYC qui expire, une opération en attente, une position bloquée.

### Positions

Tout ce que vous détenez, tous actifs et toutes chaînes confondus.

| Colonne | Comment la lire |
|---|---|
| **Asset** | Quel titre. |
| **Nominal amount** | La valeur nominale que vous détenez. |
| **Wallet** | Laquelle de vos adresses la détient. |
| **Entry type** | Inscription collective ou individuelle — [ce que cela signifie](../lifecycle/primary-issuance.md#ce-que-contient-une-inscription-au-registre). |
| **Status** | Active, ou bloquée. |

!!! note "Le nominal n'est pas la valeur de marché"
    100 000 € de nominal signifie que 100 000 € vous seront dus à l'échéance. Cela ne signifie pas que la position vaut 100 000 € aujourd'hui — une obligation peut se négocier au-dessus ou au-dessous du pair toute sa vie.

    Registerwerk est un registre. Il consigne ce que vous détenez, pas ce que quelqu'un vous en donnerait.

### Investments

Une position, en profondeur. Les conditions de l'instrument, son adresse on-chain et son historique de transactions, les opérations sur titres qui vous concernent, et vos relevés de registre.

C'est là que vous allez quand vous devez *prouver* quelque chose plutôt que simplement le consulter.

---

## Ce qui va vous arriver

### Vous recevrez un relevé de registre

Si vous détenez au titre d'une **inscription individuelle** et que vous êtes un consommateur, le §19(2) eWpG vous ouvre droit à un *Registerauszug* — après l'inscription initiale, après chaque changement vous concernant, et au moins une fois par an.

Ce sont des documents permanents et reproductibles, pas des courriels de notification. [En savoir plus](../lifecycle/holding.md#votre-releve-de-registre).

Les titulaires institutionnels d'une inscription collective n'en reçoivent pas — d'où le fait que vous n'en voyiez peut-être aucun.

### Vous recevrez des coupons

Pour une obligation, les intérêts arrivent selon un calendrier. Que *vous* perceviez un versement donné dépend de la **date d'enregistrement**, pas de la date de paiement — détenez à la date d'enregistrement et le versement est à vous, même si vous vendez le lendemain.

[:octicons-arrow-right-24: Comment fonctionnent les opérations sur titres](../lifecycle/redemption.md)

### Votre KYC va expirer

La vérification a une échéance. À l'approche, la plateforme vous alerte ; une fois dépassée, les transferts s'arrêtent.

**Cela ne vous retire pas vos titres.** Vous restez titulaire, vous restez fondé à percevoir les versements. Vous ne pouvez simplement rien déplacer tant que votre organisation n'est pas revérifiée.

### Une position peut être bloquée

Une décision de justice, une correspondance sur une liste de sanctions, un nantissement, une question de conformité non résolue. Vous verrez le blocage et son motif en regard de la position.

Elle vous appartient toujours. Vous ne pouvez pas la déplacer. [En savoir plus](../lifecycle/holding.md#quand-une-position-est-bloquee).

---

## Ce que vous ne pouvez pas faire ici

Dit clairement, pour que vous ne le cherchiez pas :

- **Vous ne pouvez pas vendre depuis l'espace Investor.** Vendre exige le rôle `TRADER` et l'[espace Trader](trader.md).
- **Vous ne pouvez pas valoriser votre portefeuille.** Registerwerk ne détient aucun cours de marché pour les titres qu'il inscrit.
- **Vous ne pouvez pas transférer vers une adresse quelconque.** Pour les instruments réglementés, le destinataire doit être un titulaire admis.
- **Vous ne pouvez pas récupérer vous-même une clé perdue.** Voir ci-dessous.

!!! danger "Si vous perdez la clé de votre portefeuille"
    Personne ne peut la restaurer. Ni l'opérateur, ni l'émetteur.

    Votre *créance* subsiste — le registre continue de vous inscrire comme titulaire, et vous restez fondé à percevoir coupons et remboursement. Ce que vous avez perdu, c'est la faculté de déplacer les jetons.

    La récupération passe par un **transfert forcé** exécuté par l'opérateur au titre du §24 eWpG : une correction formelle et documentée transférant votre position vers un portefeuille que vous contrôlez. Contactez l'opérateur. Cela exige des preuves, cela exige la [double validation](../../compliance/step-up-mfa.md), et ce n'est pas rapide.

---

## Et ensuite

- [La vie d'un titre financier](../lifecycle/index.md) — ce qui se passe réellement autour de vous
- [Détention et conservation](../lifecycle/holding.md) — où résident vraiment vos titres
- [Questions et réponses](../faq.md)
