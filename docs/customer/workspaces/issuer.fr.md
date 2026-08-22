---
title: Émetteur
description: Pour les organisations qui lèvent des fonds en émettant des titres — créer, déployer, administrer et rembourser.
---

# Émetteur

**Vous empruntez de l'argent, ou vous vendez une participation, et vous le faites en émettant un titre financier.** Vous décrivez l'instrument, vous le faites approuver, vous le portez sur une blockchain, vous admettez les investisseurs, vous créez les unités — puis vous administrez la chose pendant des années.

Des trois espaces de travail, c'est celui qui porte le plus de responsabilité. Ce que vous créez ici est une obligation juridique de votre organisation.

---

## Ce que vous y trouvez

| | |
|---|---|
| **Issuances** | Créer et administrer vos titres. L'essentiel. |
| **My dApps** | Publier des applications sur la place de marché — voir [Éditeur de dApp](dapp-publisher.md). |
| **Company Admin** | Gérer vos utilisateurs et votre organisation — voir [Administrateur d'entreprise](company-admin.md). |
| **Marketplace** | Les applications de l'écosystème. |

---

## Avant votre première émission

- **Votre organisation est intégrée et son KYC est approuvé.** Un émetteur dont le KYC a expiré ne peut pas émettre.
- **Vous connaissez votre juridiction.** Ce n'est pas une étiquette — cela sélectionne l'ensemble des règles appliquées à l'instrument pendant toute sa vie. [Cadres juridiques](../../legal/index.md).
- **Vous avez un code ISIN, s'il vous en faut un.** Registerwerk en impose l'unicité mais n'en attribue pas ; il s'obtient auprès de votre agence nationale de codification. Vous pouvez avancer sans, mais l'interopérabilité avec l'extérieur devient plus difficile.
- **Vous avez décidé qui peut le détenir.** Offre au public ? Investisseurs professionnels seulement ? Une seule juridiction ? Cela détermine votre norme de jeton, et en changer ensuite signifie un nouvel instrument.

---

## Créer une émission

*Issuances → New Issuance.* Trois étapes.

=== "1. Caractéristiques"

    Nom, ISIN, juridiction et l'économie de l'instrument. Pour une obligation : valeur nominale, devise, dates d'émission et d'échéance, taux de coupon, convention de décompte des jours, fréquence de paiement, possibilité de remboursement anticipé, et prix d'émission en fraction du nominal.

    **Le prix d'émission** compte pour les obligations à coupon zéro : elles ne versent pas d'intérêts et rémunèrent l'investisseur en étant vendues sous le pair — acheter à 800 €, recevoir 1 000 € à l'échéance. La valeur par défaut est `1.0`.

    **La convention de décompte des jours** (ACT/360, ACT/365, 30/360…) décide comment une année partielle devient une fraction dans le calcul des intérêts. C'est ingrat, et cela change l'argent.

=== "2. Chaîne et norme"

    Quelle blockchain, et quelle norme de jeton.

    Pour un titre réglementé, la réponse est généralement [ERC-3643](../../token-standards/erc3643.md), parce que c'est la norme qui fait respecter *qui peut détenir ceci* dans le jeton lui-même. [ERC-20](../../token-standards/erc20.md) est plus simple et compris partout, mais ignore la notion d'éligibilité — quiconque reçoit une unité la possède.

    Autres formes : ERC-1155 pour plusieurs séries dans un seul contrat, ERC-3525 pour les instruments semi-fongibles, ERC-4626/7540 pour les fonds et les coffres, DAML sur Canton lorsque la confidentialité vis-à-vis des contreparties est requise, SPL-2022 sur Solana.

    [:octicons-arrow-right-24: Choisir une norme de jeton](../issuers/token-standards.md)

=== "3. Vérifier et soumettre"

    Vérifiez et soumettez. Le statut passe de `DRAFT` à `PENDING_APPROVAL` et **l'édition s'arrête**.

---

## Approbation

L'opérateur examine. Ensuite :

| | |
|---|---|
| **Approuvée** | `APPROVED`. Conditions verrouillées. Vous pouvez déployer. |
| **Rejetée** | Retour à `DRAFT` avec un motif consigné. Modifiez et soumettez à nouveau. |

Il n'existe pas d'état `REJECTED` — une émission rejetée revient au brouillon, où elle est modifiable. Le motif est consigné dans la [piste d'audit](../../platform/audit-log.md).

---

## Déployer

*Issuance → Deploy.* Registerwerk envoie la transaction et enregistre l'adresse du contrat. Pour ERC-3643, cela déploie toute la suite — jeton, registre d'identités, registre des émetteurs de confiance, conformité — câblée ensemble.

Le contrat existe désormais et détient **zéro unité**.

[:octicons-arrow-right-24: Déployer sur une blockchain](../issuers/deploying-to-chain.md)

---

## Admettre les investisseurs

*Issuance → Investors.* Chaque investisseur doit être une entité au KYC approuvé, dotée d'un portefeuille enregistré, inscrite au registre d'identités.

!!! warning "C'est une condition préalable, pas de la paperasse"
    Sous ERC-3643, un portefeuille non admis **ne peut pas recevoir de jetons** — le transfert échoue on-chain. Créer les jetons avant d'admettre ne produit que des transactions en échec.

Choisissez le type d'inscription pour chaque titulaire :

- **Collective** (*Sammeleintragung*, inscription collective) — un conservateur détient pour de nombreux investisseurs sous-jacents.
- **Individuelle** (*Einzeleintragung*, inscription individuelle) — l'investisseur est nommé directement, par référence pseudonyme. Le §17(2) eWpG exige un contenu supplémentaire : droits de tiers, restrictions de disposition, mentions relatives à la capacité juridique. Le §19(2) vous oblige à adresser des relevés de registre aux titulaires consommateurs.

Un actif peut porter les deux formes en même temps.

[:octicons-arrow-right-24: Gérer vos investisseurs](../issuers/managing-investors.md)

---

## Créer les titres et mettre en vigueur

*Issuance → Mint.* Les unités viennent à l'existence et sont attribuées aux titulaires. Puis `APPROVED` → `ISSUED`, et l'instrument est en vie.

!!! danger "Créer des jetons, c'est faire naître de la valeur à partir de rien"
    Une erreur ici n'est pas un mauvais chiffre dans un rapport — ce sont de vrais titres entre de mauvaises mains.

    Des règles de contrôle peuvent plafonner ce qu'une adresse pourra jamais recevoir, l'action exige une [authentification renforcée](../../compliance/step-up-mfa.md), et chaque création est journalisée avec un acteur nommé.

---

## Vivre avec : cinq ans d'administration

C'est la partie que l'on sous-estime. L'émission dure une semaine. L'administration, le reste de la décennie.

### Opérations sur titres

Les coupons, et finalement le remboursement, sont créés automatiquement à partir de l'échéancier et avancent au fil de leurs dates — vous ne les créez pas vous-même.

Les dividendes, les fractionnements et les remboursements anticipés sont différents : c'est vous qui les **proposez** (*Émission → Opérations sur titres → Proposer*), et un opérateur examine la proposition — l'approuvant sur le registre, ou la rejetant — avant qu'elle n'aille plus loin.

Quelle que soit la façon dont l'opération a été créée, le règlement exige l'accord de deux parties distinctes : **vous attestez** que l'obligation sous-jacente est prête — les fonds pour un coupon ou un dividende, le mécanisme pour un fractionnement ou un remboursement anticipé — puis **un opérateur confirme** le volet registre/on-chain. Attester est une action authentifiée normale, sans [authentification renforcée](../../compliance/step-up-mfa.md) — seule la confirmation de l'opérateur l'exige. Si vous n'attestez jamais, un opérateur peut passer outre cette exigence ; ce contournement est enregistré comme une exception distincte et durablement visible, jamais indiscernable d'une attestation authentique.

Les trois dates qui décident qui est payé : la **date d'enregistrement** (quiconque détient à cet instant y a droit), la **date de détachement** (à partir de là, le titre se négocie sans le versement), la **date de paiement** (l'argent bouge).

[:octicons-arrow-right-24: Les opérations sur titres en détail](../lifecycle/redemption.md)

### Surveiller votre liste de titulaires

Vos investisseurs se négocient entre eux et vous ne pouvez pas les en empêcher. Ce que vous obtenez, c'est de la visibilité : le registre se met à jour et votre liste de titulaires change.

Surveillez les **plafonds de détention** si votre instrument en comporte — une règle de conformité qui fait échouer les transferts dès qu'une limite est atteinte. Les investisseurs vivent cela comme un échec inexpliqué ; connaître vos propres limites économise du support.

### Relevés de registre

Pour les titulaires consommateurs en inscription individuelle, les relevés du §19(2) sont générés et conservés comme documents de registre. Reproductibles des années plus tard, car un relevé que vous ne pouvez pas reproduire n'est pas une preuve.

### Suspension

`ISSUED` → `SUSPENDED` gèle la négociation sans mettre fin à l'instrument — pour une opération sur titres, un litige ou une erreur soupçonnée. Réversible.

### Remboursement

À l'échéance : photographie des positions, droits, votre attestation et la confirmation d'un opérateur, paiement, jetons détruits, `REDEEMED`. Terminal — on n'en ressort pas.

Les lignes de titulaires sont **supprimées logiquement, jamais effacées** : une inscription au titre du §16 qui disparaît ne peut satisfaire ni les obligations de conservation ni celles d'inviolabilité.

---

## Ce qui va vous surprendre

!!! info "Vous ne pouvez pas bloquer une transaction licite entre titulaires éligibles"
    Une fois émis, l'instrument se négocie selon ses propres règles de conformité. Vous fixez ces règles à l'émission ; vous n'arbitrez pas les transactions individuelles.

!!! info "Vous ne pouvez pas modifier une émission approuvée"
    Les conditions se verrouillent à l'approbation. Un changement signifie une nouvelle émission, ou une correction de l'opérateur avec piste d'audit.

!!! info "Le KYC de vos investisseurs n'est pas votre appréciation"
    L'opérateur vérifie les entités. Vous ne pouvez pas admettre un investisseur que l'opérateur n'a pas approuvé, même si vous le connaissez parfaitement.

!!! info "Un transfert forcé passe par l'opérateur"
    Les corrections au titre du §24 eWpG — clé perdue, décision de justice, inscription erronée — sont des actions de l'opérateur en double validation, pas quelque chose que vous exécutez.

---

## Et ensuite

- [La vie d'un titre financier](../lifecycle/index.md) — l'arc complet, d'un bout à l'autre
- [Choisir une norme de jeton](../issuers/token-standards.md)
- [Administrateur d'entreprise](company-admin.md) — gérer les utilisateurs de votre organisation
