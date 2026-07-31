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
