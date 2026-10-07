---
title: Approuver une émission
description: La décision qui donne naissance à un titre : que vérifier, ce que signifie et ne signifie pas l'approbation, et ce qui se passe ensuite.
---

# Approuver une émission

Un émetteur a décrit un titre et l'a soumis. Jusqu'à votre approbation, il s'agit d'une description. Après votre approbation, cela peut devenir une obligation légale de cet émetteur détenue par les investisseurs.

Il s'agit de la décision de routine la plus importante qu'un opérateur prend.

---

## Ce que vous décidez réellement

!!! warning "Soyez précis sur ce que signifie l'approbation"
    L'approbation signifie : **cette émission répond aux critères d'admission du registre.**

    Cela ne signifie pas que l'instrument est licite, que l'offre est conforme aux règles du prospectus, que l'émetteur peut légalement l'émettre ou que le jeton a un effet juridique. Ces points dépendent de l'autorisation de l'émetteur, de ses conseils et de sa situation.

    Si un émetteur considère votre approbation comme un avis de conformité, corrigez-le par écrit. Ce malentendu coûte cher plus tard.

---

## Avant de regarder

Confirmez d'abord les choses ennuyeuses — elles disqualifient plus rapidement que tout ce qui est dans les termes :

- [ ] L'entité émettrice est **active**, et son **KYC est approuvé et non expiré**.
- [ ] L'entité est enregistrée en tant qu'émetteur.
- [ ] Il n'y a aucun dossier de [sanctions](../../compliance/sanctions-screening.md) en cours à son encontre.

---

## Que vérifier

### Identité

| | |
|---|---|
| **Nom** | Sensé, et pas trompeusement similaire à un instrument existant. |
| **ISIN** | Unique — la plateforme l'applique. Registerwerk ne délivre pas d'ISIN ; l'émetteur en obtient un auprès de son agence nationale de numérotation. Une émission sans ISIN est autorisée mais limite l'interopérabilité. |
| **Juridiction** | Sélectionne l'ensemble des règles appliquées pendant la durée de vie de l'instrument. Le modifier ultérieurement n'est pas une simple modification de champ. |

### Conditions

Pour une obligation : valeur nominale, devise, dates d'émission et d'échéance, taux du coupon, décompte des jours, fréquence de paiement, callabilité, prix d'émission.

!!! tip "Trois choses qui valent la peine d'être examinées"
    **Échéance avant la date d'émission.** Rare et catastrophique si elle atteint la production — le calendrier des coupons est généré à partir de ces dates.

    **Prix d'émission d'une obligation à coupon zéro.** La valeur par défaut est `1.0` — au pair. Une obligation à coupon zéro au pair ne paie aucun intérêt et rembourse sa valeur nominale : un instrument qui ne rapporte rien. S'il s'agit véritablement d'un coupon zéro, le prix d'émission devrait être une décote. Cette valeur par défaut a provoqué une réelle confusion.

    **Convention de décompte des jours.** Peu glamour, et cela change le montant d'argent qui se déplace. Confirmez qu'elle correspond à la term sheet plutôt que de le supposer.

### Conventions du calendrier des coupons

L'enregistrement des conditions de l'obligation génère le calendrier des coupons à partir duquel travaillent les tâches d'opérations sur titres. Les conventions suivent la pratique ICMA ; chacune est définie dans les conditions, avec les valeurs par défaut suivantes :

| Paramètre | Par défaut | Effet |
|---|---|---|
| Décompte des jours | ACT/ACT (ICMA) | Une période régulière court exactement 1/fréquence ; une première période courte court ses jours réels rapportés à la période régulière notionnelle. ACT/360, ACT/365 (fixe), 30/360 et 30E/360 sont disponibles. |
| Calendrier | Rétropolé depuis l'échéance, première période courte | Les dates régulières sont comptées à rebours depuis l'échéance ; une période irrégulière se place au début. Si l'échéance est une fin de mois, chaque date de coupon est une fin de mois. |
| Convention de jour ouvré | Modified Following | Une date de paiement tombant un jour non ouvré passe au jour ouvré suivant, sauf si cela fait changer de mois — alors au précédent. Le calcul des intérêts utilise toujours les dates non ajustées. |
| Calendrier des jours fériés | TARGET2 | Week-ends, 1er janvier, Vendredi saint, lundi de Pâques, 1er mai, 25 et 26 décembre. |
| Date d'enregistrement | 1 jour ouvré avant le paiement | Les porteurs inscrits au registre à la fin de ce jour reçoivent le coupon. |
| Annonce | 5 jours ouvrés avant la date d'enregistrement | Moment où le coupon est annoncé. |
| Délais de grâce | 30 jours intérêts, 7 jours principal | Durée pendant laquelle un montant impayé après la date de paiement est seulement en retard. |

Le coupon par unité est valeur nominale × taux du coupon × fraction de décompte, conservé sans arrondi ; seul le droit de chaque porteur est arrondi. Un coupon variable n'a pas de montant avant la fixation de son taux et n'est pas annoncé avant. Seules les dates de paiement futures sont générées : des conditions saisies tardivement ne créent pas de coupons antidatés. Le calendrier apparaît dans l'onglet **Corporate Actions** de l'actif.

### Comment les coupons et le remboursement sont déclenchés

- **Annonce.** Le coupon (et le remboursement final) est déclenché automatiquement à sa *date d'annonce*, et non à la date de paiement, afin de laisser le temps d'attester et de confirmer avant le paiement. Le remboursement suit la même règle : date de paiement = échéance ajustée selon la convention de jour ouvré.
- **Date d'enregistrement.** Les droits sont fixés à la **fin de la date d'enregistrement** (Europe/Berlin), d'après le registre tel qu'il était alors. Les transferts postérieurs ne les modifient pas. Pour les actifs déployés sur une chaîne, l'instantané attend que le registre soit réconcilié au-delà de la date d'enregistrement et est refusé (« unmapped at record date ») si un wallet détenant des unités à ce moment n'a pas d'entrée au registre. Une date d'enregistrement proposée ou approuvée pour un dividende, un split ou un rachat doit rester à venir (au plus tôt le jour ouvré suivant).
- **Arrondi.** Le droit de chaque porteur est arrondi à la plus petite unité de la devise (half-even) ; la confirmation indique le total versé et la différence d'arrondi.
- **En retard, manqué, défaut.** Un montant impayé après la date de paiement apparaît d'abord comme **OVERDUE** (opérateurs uniquement ; les clients voient « paiement en attente »). Ce n'est qu'après le délai de grâce (30 jours intérêts, 7 jours principal) qu'un coupon devient **MISSED** et une obligation **DEFAULTED**. Un règlement efface chacun de ces états : un remboursement réglé met l'obligation à **REDEEMED**, un rachat réglé à **CALLED**, un coupon réglé est **PAID**. Seule une attente côté **émetteur** compte : une opération qui attend la confirmation ou le lancement du règlement par l'opérateur (ou que le registre retient) ne met jamais un coupon en `OVERDUE`/`MISSED` ni une obligation en `OVERDUE`/`DEFAULTED` - elle crée une tâche opérateur et alimente la jauge `registerwerk_corporate_action_operator_side_overdue`. Chaque changement de statut réel est un événement audité (`BOND_MATURED`, `BOND_OVERDUE`, `BOND_DEFAULTED`, `COUPON_OVERDUE`, `COUPON_MISSED`), ouvre une tâche opérateur sur l'émetteur et envoie un e-mail à ses administrateurs ; `DEFAULTED` reste automatique en cas de non-paiement de l'émetteur (solution provisoire, l'événement consigne le fondement).
- **Double contrôle.** L'émetteur atteste ; un opérateur confirme. Un opérateur ne peut jamais attester en tant qu'émetteur : la voie opérateur est *Override attestation* (step-up et motif, audité séparément), y compris en impersonation. Une proposition doit être approuvée par une autre personne que son auteur.
- **Droits retenus.** Les droits des pools de nominees (look-through) ne sont pas versés et maintiennent ouverte une action réglée, signalée « held entitlements outstanding », jusqu'à leur résolution.
- **Ordre des tâches.** 05:30 coupons, 05:45 remboursements, 06:00 transitions quotidiennes (Europe/Berlin) : une action déclenchée le matin est traitée dans la même exécution.

### Chaîne et standard

La norme de jeton correspond-elle à ce qui est revendiqué ?

!!! danger "Un ERC-20 pour un titre financier restreint est l'inadéquation à détecter"
    Si l'instrument ne peut être détenu que par des investisseurs vérifiés ou professionnels, [ERC-20](../../token-standards/erc20.md) ne peut pas l'imposer. Quiconque reçoit une unité en est propriétaire.

    Les instruments restreints doivent utiliser [ERC-3643](../../token-standards/erc3643.md), où l'éligibilité est vérifiée dans le contrat de jeton et les transferts non conformes échouent (revert) on-chain.

    C'est le contrôle technique le plus important de l'examen, car il est invisible par la suite. Rien ne se brise lors de l'approbation. Cela se brise la première fois qu'une unité atteint un portefeuille qui n'aurait jamais dû la détenir — à ce moment-là, 50 000 unités sont déjà en circulation.

Confirmez également que réseau principal ou réseau de test correspond bien à l'intention de l'émetteur. Approuver sur le réseau principal une émission que quelqu'un avait prévue comme simple répétition donne lieu à une conversation délicate.

---

## Décider

=== "Approuver"

    Le statut devient `APPROVED`. **Les conditions sont verrouillées.** L'émetteur peut désormais déployer.

    Les conditions ne peuvent être définies en bloc (avec step-up) que jusqu'à l'émission, et l'ISIN, la devise, le montant d'émission, la valeur unitaire et les dates ne sont plus modifiables par le formulaire d'édition une fois l'actif approuvé. Les changements ultérieurs sont des **amendements** : *Modifier l'actif → Amend terms* exige la base juridique, le step-up et un second opérateur, consigne chaque valeur avant/après dans le journal d'audit et régénère les coupons futurs pas encore annoncés. Les coupons payés ou déjà annoncés ne sont jamais réécrits. La valeur nominale, le coupon et l'échéance d'une obligation Canton déployée ne peuvent pas être amendés ici — ils sont fixés dans l'instrument du ledger.

    Enregistrez la raison pour laquelle vous avez approuvé. Le journal d'audit indique que vous l'avez fait, pas ce qui vous a satisfait.

=== "Rejeter"

    Le statut revient à **`DRAFT`** — modifiable à nouveau — avec votre raison enregistrée.

    Il n'y a pas d'état `REJECTED`. Une émission rejetée est un brouillon. Cela surprend les opérateurs qui s'attendent à un statut sans issue.

    **Écrivez une raison sur laquelle l'émetteur peut agir.** « Non conforme » entraîne une nouvelle soumission identique. « L'instrument est réservé aux investisseurs professionnels mais utilise ERC-20, qui ne peut pas l'imposer — soumettez-le à nouveau en ERC-3643 » en entraîne une correcte.

---

## Après approbation

Vous n'en avez pas fini avec cela. L'émetteur va :

1. **Déployer** le contrat.
2. **Admettre les investisseurs** — chacun ayant besoin d'une entité KYC approuvée et d'un portefeuille enregistré.
3. **Créer (mint)** les unités.
4. **Publier (issue)**, ce qui la rend active.

Vous serez impliqué à nouveau lorsque les investisseurs auront besoin d'être intégrés, et de manière permanente par la suite pour les opérations sur titres.

!!! info "Le règlement d'une OST nécessite un deuxième opérateur"
    L'approbation d'une opération sur titres (OST) pour le règlement nécessite [quatre yeux](../../compliance/step-up-mfa.md).

    Payer la mauvaise liste de détenteurs est l'erreur catastrophique classique dans l'administration des valeurs mobilières, et il est très difficile de l'inverser. Assurez-vous que votre rotation compte réellement deux personnes disponibles lorsque les dates de coupon tombent — un contrôle à quatre yeux que personne ne peut satisfaire un vendredi après-midi est un contrôle qui finit par être contourné.


### Ordres de souscription et inscriptions au registre

Les investisseurs souscrivent via le portail. Vous (ou l'émetteur) traitez la file dans l'onglet **Subscription orders** de l'actif :

1. **Allouer**, en totalité ou de façon réduite. La taille de l'émission et la détention maximale de l'investisseur (y compris ses autres allocations ouvertes) sont vérifiées sous verrou ; des allocations parallèles ne peuvent donc pas dépasser.
2. Attendez que l'investisseur **accepte**. L'allocation porte alors une échéance de paiement (10 jours ouvrés TARGET par défaut). Si elle n'est pas payée à temps, un traitement planifié la marque **caduque** et libère la capacité.
3. **Confirmez le paiement** lorsque les fonds sont sur le compte ([step-up](../../compliance/step-up-mfa.md)). Pour une obligation, le montant dû est unités allouées × valeur nominale × prix d'émission : un paiement insuffisant est refusé, un trop-perçu est enregistré comme *remboursement dû* — le remboursement lui-même est un paiement manuel. Pour les actifs sans conditions d'obligation, vous saisissez le montant reçu.
4. **Régler.** KYC, filtrage des sanctions, Sperrvermerk, gel du registre, finalité, marché cible et plafond de détention sont revérifiés. Sur un actif ERC-20 ou ERC-3643 déployé, les unités sont créées et la synchronisation des titulaires les inscrit au registre une fois le transfert indexé ; pour les autres standards déployés, l'ordre reste *payé* jusqu'à ce que vous émettiez les unités à la main. Sans déploiement, le registre est crédité directement.
5. **Libérer** rend une allocation avec un motif ; sur un ordre payé, le paiement est marqué comme remboursement dû.

!!! note "Contrôles de la partie, portefeuille de règlement et term sheet (phase 6)"
    La soumission et le règlement d'un ordre passent par le même contrôle de partie que le règlement des transactions : l'entité doit être active, avec un KYC approuvé et non expiré, sans résultat de filtrage non résolu (entité et bénéficiaires effectifs) ni Sperrvermerk. Le portefeuille de l'ordre doit être un portefeuille lié par l'entité (défi de liaison signé) ou déjà détenu pour cet actif ; sinon l'ordre est refusé avec « bind the wallet first ». Le filtrage des sanctions de l'adresse par un fournisseur d'analyse blockchain ne fait pas partie de ce contrôle (en suspens, T6-19).

    Le term sheet public (`/api/v1/public/assets/{isin}/termsheet`) n'est servi que pour les actifs émis, suspendus ou remboursés, toujours dans la même version déterministe (celle correspondant au hash en chaîne, sinon le premier téléversement) avec son hash de contenu et son numéro de version. Après l'émission, un nouveau téléversement du term sheet par l'émetteur est refusé ; le remplacer exige un avenant opérateur (`POST /api/v1/assets/{id}/documents/term-sheet-amendment`, step-up et second approbateur) qui conserve l'ancienne version marquée comme remplacée. Savoir si un term sheet doit être public avant l'ouverture de l'offre est une décision en suspens (T6-18). Les exports CSV préfixent d'une apostrophe les cellules commençant par `=`, `+`, `-` ou `@` afin que les tableurs ne les exécutent pas ; les nombres simples restent inchangés.

Les inscriptions au registre relèvent de vous, pas de l'émetteur. Dans l'onglet **Holders** de l'actif, *Add register entry* et *Change §17(2) attributes* exigent chacun une instruction (qui, et une référence) ; un champ vide signifie aucun changement, retirer un droit demande sa propre case et un second approbateur. Les émetteurs demandent par *demandes de modification*, que vous exécutez (quatre yeux) ou rejetez. Sur un actif déployé, une inscription manuelle n'est qu'un rattachement de portefeuille avec nominal 0.

---

## Suspension et remboursement

**Suspendre** (`ISSUED` → `SUSPENDED`) fige les échanges sans mettre fin à l'instrument, pour une opération sur titres, un litige ou une erreur présumée. Réversible.

**Rembourser** est terminal. Il n'y a aucun moyen de sortir de `REDEEMED`.

Les deux actions sont enregistrées avec un acteur nommé.

---

## Où suivant

- [Révision du KYC](kyc-process.md) — la porte avant celle-ci
- [Conception et approbation](../../customer/lifecycle/design.md) — le point de vue de l'émetteur sur la même étape
- [Choisir une norme de jeton](../../customer/issuers/token-standards.md)
