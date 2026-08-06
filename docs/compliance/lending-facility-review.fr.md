---
title: Examen de la conformité des facilités de pension et de prêt
description: Résultats de conformité hiérarchisés pour EwpgRepoFacility et la pile EwpgRepoMarket/Vault/oracle, avec cartographie par juridiction et statut de renforcement.
---

# Examen de la conformité des facilités de pension/prêt {#repolending-facility-compliance-review}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre des constats techniques et des mappages de contrôle prévus. Elle ne constitue pas une
    preuve de conformité légale, d'autorisation réglementaire, d'approbation de produit ou de préparation à la
    production. Les questions de pension, de prêt de titres, de garantie, de conservation, de liquidation,
    d'insolvabilité et de réutilisation nécessitent un examen actuel, spécifique à l'opérateur, au produit, à
    l'instrument, à la transaction et à la juridiction, mené par un conseiller qualifié et les responsables du
    risque concernés.

Date de révision : 2026-07-21. Périmètre : `contracts/src/examples/EwpgRepoFacility.sol`, l'évolution en marchés
isolés sous `contracts/src/lending/` (`EwpgRepoMarket`, `EwpgRepoMarketFactory`, `EwpgRepoVault`,
`oracle/RegisterwerkNavOracle`), et le module de modèle de lecture backend `lending`.
Complète [DeFi Interoperability](../platform/defi-interoperability.md), qui couvre la justification
produit/réglementaire de la conception de la facilité ; ce document est l'analyse des écarts de
sécurité/conformité.

Les constats sont classés **P0** (doit être corrigé ou obtenir une validation juridique avant toute utilisation
en production contre des titres réels), **P1** (devrait être corrigé ; réduction significative du risque, non
bloquant pour la mise en production d'une implémentation de référence), et **P2** (documenté pour information ;
limite MVP acceptable ou nécessite un changement de conception plus important que le périmètre de cette revue).

---

## Résumé : ce qui est livré et ce qui reste ouvert {#summary-shipped-vs-still-open}

| # | Constat | Gravité | Statut |
|---|---|---|---|
| 1 | Disjoncteur d'écart de prix de l'oracle manquant | P0 | **Corrigé** — voir ci-dessous |
| 2 | Vérification d'obsolescence de l'oracle en opt-in, non appliquée aux marchés déployés | P0 | **Corrigé** — voir ci-dessous |
| 3 | Revue juridique du prêt sur marge spécifique à la juridiction non réalisée | P0 | **Ouvert — travail juridique** |
| 4 | `EwpgRepoVault` sans `ReentrancyGuard` | P1 | **Corrigé** — voir ci-dessous |
| 5 | Le registre des garanties peut se désynchroniser du solde de jetons après un transfert forcé | P1 | **Corrigé** — voir ci-dessous |
| 6 | Le `LendingPositionController` du backend n'avait pas de `@PreAuthorize` | P1 | **Corrigé** — voir ci-dessous |
| 7 | Le `borrowPaused` on-chain n'atteignait jamais le backend/frontend | P1 | **Corrigé** — voir ci-dessous |
| 8 | La liquidation n'est pas véritablement sans permission lorsque l'ensemble des liquidateurs vérifiés est restreint | P1 | **Ouvert** |
| 9 | L'indicateur de pool de prête-nom n'est affirmé que hors chaîne, non vérifié on-chain | P2 | **Ouvert** |
| 10 | Le gel du portefeuille de l'emprunteur n'atteint pas les garanties déjà mises en gage | P2 | **Ouvert** |
| 11 | Incohérences entre chaînes de permission et NatSpec (`repo-oracle.*`, `repo-vault.*` contre les constantes réelles `repo-markets.*`) | P2 | **Corrigé** — commentaires uniquement |
| 12 | `EwpgRepoVault.totalAssets()` parcourt la liste complète des marchés sans limite | P2 | **Ouvert** |

---

## P0 — à corriger ou nécessitant une validation avant la production {#p0-must-fix-or-get-sign-off-before-production}

### 1. Disjoncteur d'écart de prix de l'oracle (corrigé) {#1-oracle-price-deviation-circuit-breaker-fixed}

**Avant :** `RegisterwerkNavOracle.pushPrice` (ainsi que `EwpgRepoFacility.updatePrice`) acceptait n'importe quel
prix non nul sans aucune limite par rapport à la marque précédente. Une seule clé `PUSH_PRICE` compromise ou
victime d'une erreur de saisie pouvait marquer une garantie arbitrairement haute — permettant un emprunt excessif
qui vide le pool — ou arbitrairement basse, déclenchant des liquidations massives inutiles.

**Correction :** `RegisterwerkNavOracle.pushPrice` échoue désormais (`revert`) avec `ExcessiveDeviation` si le
nouveau prix s'écarte de plus de `maxDeviationBps` (2000 par défaut = 20 %, ajustable par l'opérateur via
`setMaxDeviationBps`) par rapport à la marque précédente. La toute première publication de prix pour un actif
n'est pas plafonnée (aucune marque antérieure à laquelle se comparer). Un `pushPriceWithOverride` séparément
autorisé (protégé par `OVERRIDE_PRICE`, distinct du `PUSH_PRICE` ordinaire) existe pour une répercussion de prix
légitime de grande ampleur, de sorte qu'une simple clé d'automatisation du flux NAV ne peut pas contourner le
disjoncteur à elle seule.

**Non corrigé dans cette revue :** `EwpgRepoFacility.updatePrice` (l'ancienne facilité mutualisée) n'a toujours
pas de plafond d'écart — la facilité est traitée comme une implémentation de référence figée, et le correctif a
été apporté à la nouvelle pile `EwpgRepoMarket`/oracle qui la remplace. Si la facilité reste utilisée en
production, il faut y porter le même disjoncteur.

Tests : `contracts/test/lending/RegisterwerkNavOracle.t.sol` (7 tests).

### 2. Vérification d'obsolescence de l'oracle en opt-in, non appliquée aux marchés déployés (corrigé) {#2-oracle-staleness-opt-in-not-enforced-for-deployed-markets-fixed}

**Avant :** `EwpgRepoMarket._currentPrice()` rejetait déjà une marque périmée lorsque `maxPriceAgeSeconds != 0`,
mais `0` (obsolescence désactivée) était un argument de constructeur valide, sans aucune protection empêchant un
opérateur de déployer ainsi un marché réel — par accident, ou via une clé d'opérateur compromise choisissant
délibérément de désactiver la seule protection contre un flux de prix figé ou retenu.

**Correction :** `EwpgRepoMarket` lui-même est inchangé (la construction directe avec `maxPriceAgeSeconds == 0`
fonctionne toujours, intentionnellement, pour les tests unitaires). `EwpgRepoMarketFactory.createMarket` — le
seul chemin qui déploie un marché réel approuvé par l'opérateur — échoue désormais (`revert`) avec
`InvalidMaxPriceAge` si `maxPriceAgeSeconds == 0`.

Tests : `contracts/test/lending/EwpgRepoMarketFactory.t.sol::test_createMarket_revertsWithZeroMaxPriceAge`.

### 3. Revue juridique du prêt sur marge spécifique à la juridiction (ouvert — travail juridique) {#3-jurisdiction-specific-margin-lending-legal-review-open-counsel-work}

**Constat, inchangé par rapport à l'avis préexistant dans `defi-interoperability.md` :** mettre en gage un titre
comme garantie de prêt déclenche une couche réglementaire indépendante de la correction du smart contract —
règles de ségrégation de la conservation, licences de prêt sur marge et, selon la juridiction, restrictions de
réhypothécation qu'un simple prêt garanti en espèces ne déclenche jamais.

| Juridiction | Régulateur | Régime concerné | Statut |
|---|---|---|---|
| `DE_EWPG` | BaFin | Règles de prêt sur marge du KWG / Wertpapierleihe, conservation eWpG | Non examiné |
| `LU_CSSF` | CSSF | Règles CSSF du conservateur/dépositaire sur la réhypothécation | Non examiné |
| `FR_AMF` | AMF | Restrictions du CMF sur le teneur de compte-conservation | Non examiné |
| `LI_TVTG` | FMA | Ségrégation de la conservation des conteneurs de jetons TVTG | Non examiné |

**Aucun renforcement supplémentaire du contrat ne peut s'y substituer.** Ce constat est reporté sans changement —
il est explicitement hors du périmètre d'une revue de conformité limitée au code, et nécessite un avis juridique
externe spécifique à la juridiction avant d'exploiter `EwpgRepoFacility` ou `EwpgRepoMarket` contre des titres
réels en production.

---

## P1 — à corriger {#p1-should-fix}

### 4. `EwpgRepoVault` sans `ReentrancyGuard` (corrigé) {#4-ewpgrepovault-missing-reentrancyguard-fixed}

**Avant :** `EwpgRepoVault` était le seul contrat de la pile de prêt (`EwpgRepoFacility` et `EwpgRepoMarket`
protègent tous deux chaque fonction de mutation d'état) sans `ReentrancyGuard` — ses fonctions ERC-4626 héritées
`deposit`/`mint`/`withdraw`/`redeem`, ainsi que ses propres `allocate`/`deallocate`, effectuent toutes des appels
externes vers le jeton sans protection contre la réentrance au niveau du coffre.

**Correction :** `EwpgRepoVault` hérite désormais de `ReentrancyGuard`. `deposit`/`mint`/`withdraw`/`redeem` sont
surchargées uniquement pour ajouter `nonReentrant` autour de l'implémentation OZ (aucun changement de logique) ;
`allocate`/`deallocate` ont reçu le modificateur directement.

Tests : la suite existante `contracts/test/lending/EwpgRepoVault.t.sol` continue de passer sans changement (la
protection est additive ; aucun changement de comportement pour les appelants légitimes).

### 5. Le registre des garanties peut se désynchroniser du solde de jetons après un transfert forcé (corrigé) {#5-collateral-ledger-can-desync-from-token-balance-after-a-forced-transfer-fixed}

**Avant :** un `forcedTransfer` ou `forceBurn` réalisé par l'émetteur/l'agent sur le jeton de garantie (une
Berichtigung au sens du §24 eWpG, ou une action de gel ordonnée par un tribunal/au titre de l'AWG-GwG au niveau
du jeton) peut faire sortir des jetons du solde d'`EwpgRepoMarket` sans passer par `repay`/`liquidate` — la
comptabilité interne du marché, `positions[borrower].collateralAmount`, n'a aucun moyen de l'observer. Faute de
rapprochement, la garantie enregistrée dépasse ce que le marché peut réellement livrer, si bien qu'un
`repay`/`liquidate` ultérieur échoue (`revert`) ou, pire, surpaie en puisant dans les fonds d'autres
participants.

**Correction :** une nouvelle fonction `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`,
protégée par le rôle CONFIGURE, permet à l'opérateur de corriger à la baisse la garantie enregistrée d'une
position, jusqu'au montant qu'une transaction de transfert forcé donnée a effectivement retiré. La fonction
prend le montant corrigé comme paramètre explicite — plutôt que de tenter de le déduire du `balanceOf(this)`
agrégé du jeton — car ce solde additionne tous les emprunteurs du marché ; seul un rapprochement hors chaîne
portant sur la transaction de transfert forcé spécifique (le même acte d'opérateur qui l'a ordonnée) permet
d'attribuer correctement la réduction à un emprunteur donné. L'invariant appliqué on-chain est à sens unique :
l'appel échoue (`ReconciliationWouldIncreaseCollateral`) si le nouveau montant n'est pas strictement inférieur au
montant actuellement enregistré, de sorte qu'il ne peut jamais fabriquer une garantie qui n'a jamais été mise en
gage.

Tests : `contracts/test/lending/EwpgRepoMarket.t.sol` (`test_reconcileCollateral_*`, 4 tests).

### 6. Le `LendingPositionController` du backend n'avait pas de `@PreAuthorize` (corrigé) {#6-backend-lendingpositioncontroller-had-no-preauthorize-fixed}

**Avant :** `GET /api/v1/lending/my-positions` et `/supply-positions` ne portaient aucune annotation
`@PreAuthorize`, ni au niveau de la méthode ni au niveau de la classe, contrairement à tous les autres
contrôleurs destinés aux clients de ce module. Le périmètre d'accès était purement implicite — un appel non
authentifié résolvait un `appUserId` nul et renvoyait silencieusement une liste vide plutôt que d'être franchement
rejeté.

**Correction :** `@PreAuthorize("isAuthenticated()")` ajouté au niveau de la classe, conformément au modèle
utilisé par `PositionStatementController`/`SteuerbescheinigungController` pour les autres points de terminaison
de lecture destinés aux clients.

### 7. Le `borrowPaused` on-chain n'atteignait jamais le backend/frontend (corrigé) {#7-on-chain-borrowpaused-never-reached-the-backendfrontend-fixed}

**Avant :** `EwpgRepoMarket.borrowPaused` (ainsi que `LendingMarketStatus.PAUSED` dans le modèle de lecture du
backend) existait, mais rien ne mettait jamais le statut persisté en base à `PAUSED` — l'indicateur on-chain et
le modèle de lecture étaient déconnectés, si bien qu'un marché en pause continuait de s'afficher partout comme
`ACTIVE`, et la tentative d'emprunt d'un trader échouait simplement on-chain (`revert`) sans avertissement
préalable.

**Correction :** `LendingMarketService.resolveEffectiveStatus` lit l'indicateur on-chain en direct pour tout
marché dont le statut persisté est `ACTIVE`, et répercute `PAUSED` dans chaque réponse de liste/détail sans
modifier la ligne en base (une lecture en direct, pas un changement d'état) — un échec de lecture on-chain
revient au statut persisté plutôt que de faire échouer tout le listing, selon le même principe de best-effort
déjà utilisé pour les lectures du facteur de santé des positions. L'assistant d'emprunt côté client affiche
désormais explicitement un état « marché temporairement en pause » plutôt que de laisser la transaction échouer.

Tests : `LendingMarketServiceTest` (`activeMarketReflectsOnchainBorrowPaused`,
`activeMarketStaysActiveWhenNotPaused`, `retiredMarketSkipsOnchainCheck`,
`onchainReadFailureFallsBackToPersistedStatus`).

### 8. La liquidation n'est pas véritablement sans permission lorsque l'ensemble des liquidateurs vérifiés est restreint (ouvert) {#8-liquidation-not-truly-permissionless-when-the-verified-liquidator-set-is-thin-open}

**Constat :** `liquidate` n'est nominalement pas restreint au niveau de la couche `RegisterwerkGated` (documenté
comme « sans permission, comme sur Aave, car le mur T-REX du jeton lui-même effectue gratuitement le travail de
conformité »). Ce n'est que partiellement vrai : le mur T-REX contrôle le **destinataire** de la garantie saisie
(le liquidateur), pas seulement l'emprunteur. Si l'ensemble des adresses vérifiées T-REX, non gelées et non
bloquées par pays disposées à liquider est restreint, une position dégradée peut n'avoir aucun liquidateur
éligible — la position reste sous l'eau, au détriment des déposants, sans voie de repli pour la clôturer. Un
liquidateur vérifié proche de `maxBalancePerInvestor` sur le jeton de garantie est lui aussi empêché de recevoir
la garantie saisie, à moins que le liquidateur lui-même ne soit signalé comme prête-nom.

**Recommandation :** concevoir un mécanisme de liquidation par agent de dernier recours (par exemple une adresse
contrôlée par l'opérateur, préalablement signalée comme pool de prête-nom, habilitée à liquider et à redistribuer
immédiatement — ou entreposer — la garantie saisie) pour les marchés où l'on ne peut pas présumer que l'ensemble
des liquidateurs vérifiés est suffisamment profond. Non implémenté dans cette revue — il s'agit d'une nouvelle
conception du contrôle d'accès, pas d'un correctif ponctuel.

---

## P2 — documenté, acceptable pour l'instant ou nécessitant un travail de conception plus important {#p2-documented-acceptable-for-now-or-requires-larger-design-work}

### 9. Le statut de pool de prête-nom n'est affirmé que hors chaîne (ouvert) {#9-nominee-pool-status-is-asserted-off-chain-only-open}

L'ensemble du modèle de mutualisation repose sur une action d'opérateur hors chaîne
(`EwpgModularCompliance.setNomineePool`) signalant le marché comme pool de prête-nom sur le jeton de garantie,
ainsi que sur un KYC/AML hors chaîne en transparence (*look-through*) des propres déposants du pool (voir
[DeFi Interoperability § passerelle prête-nom/omnibus](../platform/defi-interoperability.md#the-nomineeomnibus-bridge-nominee_pool)).
Rien dans les contrats de prêt n'atteste on-chain que cet indicateur a bien été positionné avant l'acceptation
des mises en gage — la première mise en gage qui dépasserait le plafond par investisseur échoue simplement au
niveau du jeton si l'indicateur est absent, ce qui constitue un filet de sécurité fonctionnel mais ne fournit
aucun signal proactif. **Recommandation :** un événement on-chain corrélant le déploiement d'un marché à son
indicateur de pool de prête-nom (par exemple la factory lisant et journalisant l'indicateur au moment de
`createMarket`) améliorerait l'auditabilité sans changer le modèle de sécurité. Reporté comme amélioration
d'observabilité souhaitable, mais non comme une lacune du modèle de conformité lui-même.

### 10. Le gel du portefeuille de l'emprunteur n'atteint pas les garanties déjà mises en gage (ouvert) {#10-borrower-wallet-freeze-doesnt-reach-already-pledged-collateral-open}

Une fois la garantie mise en gage dans un marché, c'est le contrat du pool — et non l'emprunteur — qui est le
titulaire enregistré du jeton. Un Sperrvermerk au sens du §16 eWpG, ou un gel AWG/GwG ultérieur sur le
portefeuille propre de l'emprunteur, ne contrôle plus cette garantie déjà mise en gage, puisque la vérification
de gel porte sur l'adresse `from` d'un transfert, et que le pool est le `from` de tout mouvement ultérieur. La
question de savoir si cela satisfait l'intention réglementaire d'un gel de portefeuille est elle-même une
question juridique, liée au constat n° 3 ci-dessus, et non une lacune du smart contract appelant un correctif de
code évident (geler la *position* plutôt que le portefeuille exigerait un nouvel état et un nouveau point de
contrôle dans chaque contrat de prêt). Documenté pour que la revue juridique du constat n° 3 l'examine
explicitement.

### 11. Incohérences entre chaînes de permission et NatSpec (corrigé — commentaires uniquement) {#11-permission-string-natspec-mismatches-fixed-comment-only}

Deux incohérences entre le commentaire de documentation et la constante réellement appliquée (un problème
d'hygiène de gouvernance/audit — une chaîne erronée pourrait induire en erreur quiconque accorde des permissions
en se fiant à la documentation plutôt qu'au code) :

- le commentaire de documentation de `RegisterwerkNavOracle.pushPrice` indiquait `repo-oracle.push-price` ; la
  constante réelle est `PUSH_PRICE = keccak256("repo-markets.push-price")`. Corrigé.
- le commentaire de documentation au niveau du contrat `EwpgRepoVault` indiquait `repo-vault.curate` ; la
  constante réelle est `CURATE = keccak256("repo-markets.curate-vault")`. Corrigé.

Aucun changement de comportement — les deux constantes étaient déjà correctes et déjà rattachées à l'espace de
noms de la liste marketplace `repo-markets`, conformément à la règle de nommage de `ManifestValidationService` ;
seule la documentation était erronée.

### 12. `EwpgRepoVault.totalAssets()` parcourt la liste complète des marchés (ouvert) {#12-ewpgrepovaulttotalassets-iterates-the-full-market-list-open}

`totalAssets()` boucle sur tous les marchés jamais ajoutés (y compris ceux désactivés) à chaque calcul du prix de
la part — chaque conversion `deposit`/`withdraw`/`mint`/`redeem` supporte ce coût. Pour la poignée de marchés
qu'un coffre de curation gère en pratique, cela est négligeable, mais une croissance illimitée du nombre de
marchés finirait par devenir un problème de coût de gas. Limite MVP acceptable ; un `totalAssets` borné/paginé
(ou excluant les marchés désactivés de la boucle) est un raffinement v2 naturel si un coffre venait à approcher
des dizaines de marchés.

---

## Vérification {#verification}

- Contrats : `cd contracts && forge test --match-path "test/lending/*" -vv` — 45 tests, tous réussis (0
  régression par rapport à la suite de prêt préexistante) ; suite complète `forge test` — 388 réussis, 0 échoué,
  18 ignorés.
- Backend : `cd backend && ./mvnw verify` — 436 tests unitaires + 30 tests d'intégration réussis, tous les seuils
  de couverture JaCoCo (y compris les planchers critiques pour la conformité `registerstatement`/adjacents au
  prêt) atteints.
