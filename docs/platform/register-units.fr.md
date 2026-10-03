---
title: Unités du registre
description: Le registre compte des unités entières - comment les jetons d'obligations et de fonds sont déployés (decimals = 0), ce que le registre refuse sur tout autre jeton et que faire.
---

# Unités du registre { #register-units }

**Le registre compte des unités entières. Un jeton est une unité du titre.**

Les montants du registre - la quantité nominale d'un porteur et chaque transfert indexé - sont les **unités de
base brutes** du jeton ; l'indexeur les écrit sans mise à l'échelle. Le calcul des coupons et du remboursement
(`amountPerUnit x nominal`), la création sur le marché primaire (le montant alloué est envoyé tel quel au jeton)
et la négociation secondaire (quantité et prix par unité) lisent ces montants comme des unités entières. Sur un
jeton à 18 décimales, chacun serait faux d'un facteur 10^18. Le registre ne met donc rien à l'échelle : il
déploie les jetons d'obligations et de fonds avec `decimals = 0` et refuse chacun de ces traitements sur un jeton
qui ne compte pas en unités entières.

## Ce qui est déployé { #what-is-deployed }

| Standard | Décimales d'un nouveau déploiement |
|---|---|
| ERC-20 (`EwpgERC20`), ERC-3643 (T-REX), ERC-721, ERC-1155, ERC-3525 (EVM et Starknet), SPL / Token-2022, obligations Daml | **0** |
| ERC-20 Starknet (Cairo, fixé à 18), actifs Stellar (fixé à 7) | selon le contrat - **refusés** par les traitements ci-dessous |
| Parts de coffres ERC-4626 / ERC-7540 (suivent le sous-jacent), jetons confidentiels, jetons Canton | hors du contrôle du registre - enregistrés comme *inconnus*, **refusés** |

Les décimales sont enregistrées sur le déploiement (`asset_deployment.token_decimals`). Les déploiements antérieurs
à cette règle conservent ce que l'ancien code avait déployé (par exemple 18 pour ERC-20 et ERC-3643) ; ils sont
donc refusés eux aussi.

## Ce qui est refusé { #what-is-refused }

Tout actif ayant un déploiement actif (en attente ou confirmé) dont les décimales ne sont pas exactement 0 - y
compris inconnues - est refusé, en échec fermé, par un `409` qui nomme le déploiement et ses décimales :

- **Opérations sur titres** (coupons, remboursement, dividendes, divisions, rachats) : l'instantané de la date
  d'enregistrement n'est pas pris ; l'opération est mise en attente en `SNAPSHOT_BLOCKED` avec le motif, auditée,
  signalée et réessayée chaque jour.
- **Souscriptions** : allocation et règlement (la création).
- **Négociation** : création d'une offre, achat et règlement d'une transaction.
- **Remboursement (annulation)** : lancement du remboursement du titre (les montants annulés sont les unités de base brutes du registre).

Un actif sans déploiement (registre hors chaîne) n'a rien à mettre à l'échelle et n'est pas concerné.

!!! warning "Comment corriger un actif refusé"
    Le jeton ne peut pas être modifié sur place. Déployez à nouveau l'actif avec un jeton en unités entières
    (`decimals = 0`) et transférez-y le registre. D'ici là, rien n'est payé, créé ni négocié.
