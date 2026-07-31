---
title: Obligations DAML Finance (Canton)
description: Normes d'obligations Canton / DAML Finance pour les déploiements sur registre privé.
---

# Obligations DAML Finance (Canton) { #daml-finance-bonds-canton }

Canton est un registre distribué axé sur la confidentialité, construit sur le langage de contrat intelligent **DAML**. DAML Finance fournit une bibliothèque de primitives financières composables pour Canton, notamment des obligations, des actions et des produits dérivés. Registerwerk prend en charge trois types d'obligations DAML Finance sur Canton pour les déploiements sur registre privé.

---

## Types d'obligations DAML Finance pris en charge { #supported-daml-finance-bond-types }

| Norme | Énumération de jetons | Description |
|---|---|---|
| `DAML_BOND_FIXED` | Obligation à taux fixe | Taux de coupon connu, échéancier fixe |
| `DAML_BOND_FLOATING` | Obligation à taux variable | Taux lié à EURIBOR/SOFR/autre référence |
| `DAML_BOND_ZERO` | Obligation à coupon zéro | Pas de coupon périodique ; se négocie avec une décote |
| `CANTON_TOKEN` | Actif Canton générique | Tout actif numérique basé sur DAML |

---

## En quoi Canton diffère d'EVM { #how-canton-differs-from-evm }

| Dimension | EVM (normes ERC) | Canton (DAML Finance) |
|---|---|---|
| Confidentialité | Registre public (tous les participants voient l'état) | Privé — chaque participant ne voit que ses propres contrats |
| Langage de contrat intelligent | Solidity / Vyper | DAML (proche de Haskell) |
| Finalité | Probabiliste (n confirmations) | Déterministe (accusé de réception du Ledger API) |
| Identité | Adresse de wallet | Party Canton (identifiant unique par participant) |
| Règlement hors registre | Facultatif | Natif : le workflow DAML inclut le règlement |
| Positions confidentielles | Nécessite Zama fhEVM | Natif — contrats privés |

---

## Attribution de la Party Canton { #canton-party-allocation }

Chaque `LegalEntity` de Registerwerk possède une **Party Canton** — un identifiant unique sur le ledger Canton. Ceci est géré par le service `CantonPartyAllocator` du module `blockchain` :

1. Lorsqu'un client disposant d'un instrument compatible Canton est intégré, `CantonPartyAllocator.allocate(entityId)` enregistre l'entité sur le ledger Canton
2. L'identifiant de party est stocké dans `LegalEntity.cantonPartyId`
3. Tous les contrats DAML Finance font référence à la Party Canton, jamais à une adresse de wallet

---

## Correspondance des modalités obligataires { #bond-terms-mapping }

`AssetBondTerms` stocke les paramètres financiers pour tous les types d'obligations :

| Champ | DAML_BOND_FIXED | DAML_BOND_FLOATING | DAML_BOND_ZERO |
|---|---|---|---|
| `couponRate` | Fixe (par ex. 5,0 %) | Marge sur taux de référence | N/A |
| `referenceRate` | N/A | par ex. EURIBOR_3M | N/A |
| `maturityDate` | ✅ | ✅ | ✅ |
| `paymentFrequency` | ANNUAL / SEMIANNUAL / QUARTERLY / MONTHLY | Idem | N/A |
| `dayCountConvention` | ACT_365 / ACT_ACT / 30_360 | Idem | ACT_365 |
| `issuePrice` | 100 (au pair) ou décote/prime | Au pair | Décote (< 100) |

---

## Paiement de coupon sur Canton { #coupon-payment-on-canton }

Pour `DAML_BOND_FIXED` et `DAML_BOND_FLOATING`, la méthode `CantonBondOperations.payCoupon()` exécute le workflow de paiement de coupon DAML Finance :

1. Le nœud participant Canton de Registerwerk propose un contrat de paiement de coupon à la Party de l'émetteur
2. Le nœud de l'émetteur exerce le choix (choice) du cycle de vie du coupon
3. Toutes les Party détentrices de l'obligation reçoivent leurs montants de coupon via le lot de règlement DAML
4. L'enregistrement `CorporateAction(type=COUPON, status=SETTLED)` est mis à jour dans la base de données de Registerwerk

---

## Le profil Maven `-Pcanton` { #the-pcanton-maven-profile }

La prise en charge de Canton nécessite le DAML SDK et les bibliothèques Java associées. Ils sont activés via le profil Maven `-Pcanton` :

```bash
cd backend && ./mvnw verify -Pcanton
```

Sans ce profil, `CantonBondDisabledStub` est injecté à la place du client Canton réel, et tous les appels API liés à Canton renvoient `503 Service Unavailable` avec un message descriptif. Cela permet à l'application de démarrer proprement sans nœud participant Canton.
