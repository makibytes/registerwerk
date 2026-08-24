# Interopérabilité DeFi { #defi-interoperability }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page est une discussion de conception technique. Les dispositifs de DeFi, de négociation,
    de conservation, de prête-nom, de paiement et de prêt décrits pour Registerwerk ne sont pas
    présentés comme légalement autorisés, conformes ou agréés, et ne sont pas prêts pour la
    production. La classification et l'effet juridique exigent un examen propre à l'opérateur, à
    l'instrument, au service, à la contrepartie, à la transaction et à la juridiction.

Registerwerk est destiné à un usage sur les marchés de capitaux réglementés. Ce document explique
où et pourquoi des ponts vers l'écosystème DeFi/Ethereum ont du sens et — tout aussi important —
où ils n'en ont pas, compte tenu de la réalité réglementaire des titres tokenisés, différente de
celle des crypto-actifs.

## Le point de départ réglementaire { #the-regulatory-starting-point }

La classification MiCAR/MiFID II ne peut pas être déduite d'un standard de jeton, d'une étiquette
`eWpG`, ou d'un indicateur de rail de paiement. Le module `payment` stocke les champs de
divulgation et d'attestation saisis par l'opérateur ; il n'établit pas de manière indépendante
qu'un actif est un instrument financier, qu'un stablecoin est un EMT, ou qu'un émetteur ou
prestataire de services est agréé.

Le fait qu'un AMM sans autorisation préalable, un pool de prêt, un dépositaire, un prête-nom ou une
structure omnibus puisse détenir ou transférer un instrument est une question juridique,
réglementaire, de conservation, d'insolvabilité et de conception de produit — pas une question à
laquelle répondent les contrats intelligents ou la configuration de juridiction. Les modèles
ci-dessous sont des options techniques soumises à l'examen des juristes et des responsables du
contrôle.

## Matrice d'interopérabilité par juridiction { #jurisdiction-interoperability-matrix }

Modélisée dans `backend/.../kyc/api/JurisdictionRequirementConfig.ComplianceMetadata` via les
nouveaux champs `defiInteropModel` / `permissionlessAmmAllowed` (énumération `DefiInteropModel`,
même package) :

| Juridiction | Régulateur | `defiInteropModel` | `permissionlessAmmAllowed` | Base |
|---|---|---|---|---|
| `DE_EWPG` | BaFin | `NOMINEE_POOL` | `false` | eWpG Sammelverwahrung (garde collective) |
| `LU_CSSF` | CSSF | `NOMINEE_POOL` | `false` | Holding omnibus dépositaire/conservateur supervisé par la CSSF |
| `FR_AMF` | AMF | `NOMINEE_POOL` | `false` | Régime CMF de teneur de compte-conservation (account-keeper/custodian) |
| `LI_TVTG` | FMA | `NOMINEE_POOL` | `false` | Modèle de conteneur de jetons TVTG + prestataire de services VT agréé |

Les quatre juridictions retombent aujourd'hui sur le même modèle car toutes les quatre reconnaissent
déjà une structure omnibus d'intermédiaire agréé — il n'y avait pas encore de divergence propre à
une juridiction à modéliser. `permissionlessAmmAllowed` vaut `false` partout, et ce délibérément de
façon explicite (pas seulement l'absence d'un `true`), afin qu'un futur ajout de juridiction soit
obligé de prendre une décision active plutôt que d'hériter silencieusement d'une valeur par défaut.
Le périmètre de ce chantier a volontairement été limité aux quatre juridictions déjà intégrées
(`DE_EWPG`, `LU_CSSF`, `FR_AMF`, `LI_TVTG`) ; ajouter des régimes hors UE (par exemple la loi suisse
sur la DLT, qui dispose de sa propre licence d'infrastructure de négociation DLT couvrant
négociation/règlement/conservation combinés) est un ajout naturel pour la suite, une fois que
Registerwerk opérera réellement dans cette juridiction.

Un troisième modèle, `ORACLE_ONLY`, existe pour le cas de figure (déjà livrable, à risque de garde
nul) où un protocole externe se contente de *lire* les claims du `PermissionOracle` — voir
[dapp-development.md § Composabilité DeFi externe](./dapp-development.md#external-defi-composability).
Aucune juridiction n'est limitée à `ORACLE_ONLY` aujourd'hui, puisque `NOMINEE_POOL` en est un
sur-ensemble strict.

## Le pont prête-nom/omnibus (`NOMINEE_POOL`) { #the-nomineeomnibus-bridge-nominee_pool }

Un nouveau sujet de claim ONCHAINID, `NOMINEE` (sujet 4, aux côtés des sujets existants 1=KYC/
2=AML/3=Accréditation), est émis par un émetteur de confiance vers le propre ONCHAINID d'un
dépositaire/CASP agréé — en utilisant exactement la même mécanique `ClaimIssuanceService`/
`EcosystemTrustedIssuersRegistry` déjà en place pour les claims KYC/AML. L'adresse du contrat de
pool de ce dépositaire est ensuite signalée comme pool prête-nom sur l'`EwpgComplianceModule` du
jeton concerné (`contracts/src/compliance/EwpgModularCompliance.sol`, `setNomineePool`), ce qui :

- **Exempte** l'adresse du pool de `maxBalancePerInvestor` et `maxInvestors` — c'est tout l'intérêt
  de la mesure, puisqu'un pool comptabilise l'exposition économique de nombreux LP derrière une
  seule adresse, et qu'un plafond par investisseur appliqué à cette adresse unique irait à
  l'encontre de l'intention réglementaire du plafond (soit il bloquerait purement et simplement
  toute mise en commun, soit il laisserait silencieusement contourner le plafond en compensant de
  nombreux investisseurs derrière un seul « investisseur »).
- **Conserve** sans condition les contrôles de blocage par pays et de délai de carence sur les
  transferts — l'opérateur du pool lui-même ne doit toujours pas être domicilié dans une
  juridiction bloquée.
- **Ne s'applique qu'aux adresses de contrat** (`to.code.length > 0`) — signaler un EOA comme pool
  prête-nom n'a aucun effet, car l'exemption n'a de sens que pour un véritable contrat de
  conservation mutualisée, pas pour le wallet d'un particulier.

La responsabilité de la transparence KYC/AML sur les propres LP sous-jacents du pool repose
entièrement sur l'opérateur prête-nom/dépositaire, hors chaîne — exactement comme pour le compte
omnibus d'une banque dépositaire traditionnelle aujourd'hui. Le fait qu'un manifeste de dApp
déclare `requiredClaimTopics: [4]` est signalé à l'opérateur examinateur pendant l'approbation
marketplace (`ManifestValidationService`) comme un indicateur exigeant un examen humain de la
licence de dépositaire de l'éditeur, et non comme une déclaration approuvée automatiquement.

## Ce qui attire réellement les fournisseurs de liquidité et les teneurs de marché { #what-actually-attracts-liquidity-providers-and-market-makers }

Avant de décider quoi construire, il vaut la peine d'être honnête sur ce qu'optimise un trader ou
un teneur de marché, et sur la façon dont cela se traduit — ou non — dans un registre de titres
conforme :

- **Les marchés obligataires TradFi ne sont pas liquides avant tout grâce à la négociation
  secondaire.** Ils sont liquides grâce au **repo** (une pension livrée : vendre l'obligation
  maintenant, s'engager à la racheter plus tard à un prix fixe) et au **prêt de titres**. Un
  courtier détenant une position illiquide ne la vend pas pour lever des liquidités : il la met en
  pension au jour le jour ou à terme, conserve son exposition économique, et redéploie les
  liquidités. Les marchés du repo déplacent des milliers de milliards chaque jour, éclipsant le
  volume de la négociation secondaire d'obligations au comptant, précisément parce qu'ils
  permettent à un détenteur d'accéder à de la liquidité *sans* vente pure et simple (pas de prix
  réalisé, pas de perte de potentiel de hausse, pas de décote de vendeur forcé).
- **Les marchés monétaires DeFi (Aave, Compound) sont le même mécanisme, mutualisé et
  algorithmique** : on dépose une garantie, on emprunte contre elle, à un taux fixé par
  l'utilisation en temps réel plutôt que par une négociation bilatérale. Ce qui attire réellement
  les LP vers un marché monétaire, c'est un taux transparent et piloté par l'utilisation, une
  entrée côté offre sans autorisation préalable, et un mécanisme crédible de réalisation de la
  garantie (liquidation) qui préserve les déposants.
- **Les AMM de style Uniswap attirent les LP par les revenus de frais et la création de pool sans
  autorisation préalable** — mais ce modèle suppose que l'actif négocié est fongible, valorisé en
  continu, et qu'il est sûr de laisser un contrat anonyme le détenir en position nette pour le
  compte de nombreuses parties. Rien de tout cela ne tient pour un titre valorisé à la valeur
  liquidative (NAV) et soumis à des conditions d'éligibilité — ce qui explique précisément pourquoi
  ce dépôt exclut un AMM/carnet d'ordres pour la jambe jeton de titre (voir la section sur le
  mécanisme de négociation ci-dessous).
- **Les plateformes d'actifs du monde réel ont déjà appris cette leçon.** Ondo, Centrifuge, Maple
  et BUIDL de BlackRock tirent presque toute leur utilité DeFi du fait d'être déposés en
  **garantie** sur un marché de prêt, et non de la liquidité de négociation au comptant — le RWA
  tokenisé reste dans une position unique, conservée/mutualisée, et c'est la liquidité en
  stablecoin qui se déplace autour.
- **Ce qu'un teneur de marché veut spécifiquement** : un moyen d'être à la fois long et court, de
  se couvrir, de réutiliser le même capital sur plusieurs positions (efficacité du capital), et une
  certitude d'exécution. L'emprunt garanti offre exactement cela à un détenteur — effet de levier
  et efficacité du capital — sans que Registerwerk n'ait jamais à exploiter un moteur
  d'appariement.

Conclusion : **une facilité de référence de prêt garanti est une fonctionnalité de liquidité
potentielle pour Registerwerk, pas un produit juridiquement approuvé.** Elle correspond aussi
parfaitement à la contrainte « ne pas construire de DEX », puisque le prêt garanti n'a jamais été
un carnet d'ordres au départ.

## `EwpgRepoFacility` — le principal mécanisme de liquidité de sortie { #ewpgrepofacility-the-primary-exit-liquidity-mechanism }

`contracts/src/examples/EwpgRepoFacility.sol` est une facilité de référence de repo/prêt garanti à
contrôle d'accès délibérément asymétrique. L'usage en production est bloqué en attendant la
qualification juridique, la garde/le contrôle, la réalisation de la garantie, l'oracle,
l'insolvabilité et l'approbation du contrat intelligent :

- **Le côté prêteur (`deposit`/`withdraw`) est ouvert à tout détenteur de stablecoin** — aucun
  contrôle `RegisterwerkGated` du tout. Les déposants ne détiennent jamais qu'une créance sur le
  stablecoin mutualisé ; ils ne touchent jamais le jeton de titre restreint, donc il n'y a aucune
  raison tirée du droit des titres financiers de leur imposer un contrôle d'accès. C'est le levier
  le plus important pour « l'attractivité de Registerwerk à capter de la liquidité sur le marché » :
  moins il y a d'obstacles à la *fourniture* de capital, plus le pool est profond, puisque le
  risque tarifé est intégralement porté par le côté emprunteur (soumis à contrôle d'accès).
- **Le côté emprunteur (`pledgeAndBorrow`) est soumis à contrôle d'accès** — la permission
  `repo-facility.borrow` plus un claim KYC valide — puisque seul un investisseur vérifié peut
  mettre en gage l'actif de garantie restreint. Un emprunteur met en gage, par exemple, une
  position obligataire `EwpgERC3643` et tire du stablecoin jusqu'à une quotité de financement
  (loan-to-value, LTV) configurée, tout en conservant intacts la position obligataire et ses droits
  à coupon/remboursement. C'est l'opération « repo » : de la liquidité sans vente.
- **`repay` et `liquidate` sont délibérément laissés sans contrôle d'accès à ce niveau.** Le
  transfert de la garantie vers l'appelant est lui-même soumis au contrôle du registre d'identité
  T-REX propre au jeton — la transaction d'un appelant non vérifié échoue simplement (`revert`) au
  niveau du jeton. Cela signifie que la réalisation de la garantie peut être techniquement sans
  autorisation préalable pour les destinataires éligibles, mais cela n'établit pas de conformité
  légale ou réglementaire. Le mur `isVerified()` existant ne fournit qu'un contrôle au niveau du
  contrat. Le remboursement est laissé ouvert par principe — réduire son risque et récupérer sa
  propre garantie précédemment mise en gage ne devrait jamais être bloqué par un changement
  administratif de permission.
- **Les intérêts sont fondés sur l'utilisation** (style Aave, `liquidityIndex`/`borrowIndex`, à
  l'échelle WAD), de sorte que les deux côtés se règlent en O(1) quel que soit le nombre de
  participants, et que les déposants voient un rendement transparent, déterminé par le marché,
  plutôt qu'un taux fixe.
- Comme `CompliantSecondaryMarket`, l'adresse propre de la facilité mutualise la garantie de
  nombreux emprunteurs derrière une seule adresse ; tout actif de garantie `EwpgERC3643` a donc
  besoin du même indicateur `EwpgComplianceModule.setNomineePool(token, address(facility), true)`
  avant que des mises en gage dépassant le plafond individuel du premier investisseur ne puissent
  aboutir.

### Mécanisme de négociation, selon le type de paire { #trading-mechanism-split-by-pair-type }

- **Jambes en jeton de titre : appariement RFQ/bilatéral via `DvpSettlement`**
  (`contracts/src/examples/CompliantSecondaryMarket.sol`). Aucune courbe de liaison partagée — les
  cotations sont appariées hors chaîne (ou via une simple fonction de publication de cotation
  on-chain) et réglées en une seule transaction réussie via les primitives `lockAsset`/
  `lockPayment`/`settle` existantes. Le comportement à jambe exacte suppose des jetons sans frais
  de transfert ni rebase ; la finalité et l'inscription au registre légal sont traitées à part.
  Cela évite l'exposition à la perte impermanente et à la manipulation d'oracle sur une obligation
  valorisée à la NAV et potentiellement illiquide — la même raison pour laquelle les places de
  marché réglementées réelles (SDX, MTF du régime pilote DLT de l'UE) utilisent une tarification
  par carnet d'ordres/RFQ plutôt que des courbes à produit constant pour les titres financiers.
  **Son rôle se comprend désormais mieux comme de la découverte de prix alimentant
  `EwpgRepoFacility.updatePrice`** (la dernière exécution constitue une marque de garantie
  légitime) plutôt que comme le lieu de liquidité principal — exactement comme la négociation
  secondaire d'obligations sert surtout à la découverte de prix en TradFi, pendant que le repo fait
  le gros du travail de liquidité. Plusieurs opérateurs prête-nom concurrents peuvent chacun
  déployer leur propre instance et être signalés sur le même jeton, ce qui crée une concurrence de
  type dealer-to-client entre teneurs de marché plutôt qu'un guichet monopolistique unique.
- **Jambes uniquement en stablecoin : un simple AMM à produit constant**
  (`contracts/src/examples/StablecoinAmm.sol`). Réservé aux paires où aucune des deux jambes n'est
  un titre financier (par ex. AUEUR/USDC, toutes deux déclarées via le catalogue de rails
  `PaymentRailType.STABLECOIN` du module `payment`) — le seul endroit où un AMM DeFi classique est
  réellement le choix le moins risqué, puisqu'il n'y a là aucun enjeu d'intégrité de tarification
  de titres financiers.

Les trois dApps de référence héritent de `RegisterwerkGated` de la même façon que
`BoardroomGovernance`/`EwpgBondDesk`. `EwpgRepoFacility` fournit en plus un manifeste complet, un
README et des données de démonstration comme les deux autres exemples phares — voir
[dapp-development.md § Exemples de dApps de référence](./dapp-development.md#reference-example-dapps).
`CompliantSecondaryMarket` et `StablecoinAmm` restent uniquement du Solidity testé (pas de
manifeste, non semés comme fiches marketplace).

## `EwpgRepoMarket` / `EwpgRepoVault` — l'évolution en marchés isolés { #ewpgrepomarket-ewpgrepovault-the-isolated-market-evolution }

`contracts/src/lending/` est l'évolution de style Morpho Blue d'`EwpgRepoFacility`, additive par
rapport à celle-ci (les deux peuvent tourner sur le même écosystème — voir
`script/DeployRepoMarkets.s.sol`). Là où la facilité mutualise chaque type de garantie derrière un
seul pool de trésorerie partagé et une seule paire d'indices partagée, chaque `EwpgRepoMarket`
isole le risque sur exactement une paire `{loanToken, collateralToken}`, déployée via
`EwpgRepoMarketFactory` (CREATE2, contrôle d'accès opérateur). `EwpgRepoVault` est la couche de
curation de style MetaMorpho par-dessus, qui achemine les dépôts des prêteurs entre plusieurs
marchés avec des plafonds par marché. `RegisterwerkNavOracle`/`IRepoOracle` formalisent le schéma
de poussée de NAV de la facilité en une interface autonome et interchangeable. Même contrôle
d'accès asymétrique que la facilité (côté prêteur ouvert, côté emprunteur soumis à KYC +
permission, remboursement/réalisation de la garantie sans contrôle d'accès à cette couche — le mur
T-REX propre au jeton reste le véritable verrou) ; voir la NatSpec de chaque contrat pour le détail
de la mécanique.

Cette évolution résout deux des trois simplifications signalées plus bas pour la facilité : un
facteur de réserve (plafonné à 25 %, réglable par l'opérateur) et une réalisation partielle de la
garantie (facteur de clôture de 50 %, style Aave) existent désormais tous deux dans
`EwpgRepoMarket` — la facilité elle-même est inchangée et reste une implémentation de référence
plus simple. Le troisième point — l'examen juridique du prêt sur marge propre à chaque juridiction
— s'applique identiquement aux deux et **reste ouvert** ; voir l'examen ci-dessous.

## Revue de conformité (2026-07-21) — constats et renforcements { #compliance-review-2026-07-21-findings-and-hardening }

Une revue de conformité complète portant sur `EwpgRepoFacility`, la pile `EwpgRepoMarket`/`Vault`/
oracle, et le modèle de lecture backend `lending` a fait apparaître les points ci-dessous. Détail
complet, correspondance par juridiction et classement de gravité :
`docs/compliance/lending-facility-review.md`. Résumé de ce qui a été livré dans ce chantier par
rapport à ce qui reste ouvert :

**Renforcé (ce chantier) :**

- **Disjoncteur d'écart de prix de l'oracle** — `RegisterwerkNavOracle.pushPrice` rejette
  désormais une poussée s'écartant de plus d'un `maxDeviationBps` configurable par l'opérateur
  (20 % par défaut) par rapport à la marque précédente ; un `pushPriceWithOverride` séparément
  autorisé existe pour les repricings légitimes de grande ampleur. Cela borne le rayon d'impact
  d'une seule clé de flux NAV compromise ou d'une erreur de saisie.
- **Fraîcheur d'oracle obligatoire pour les marchés déployés** — `EwpgRepoMarket` lui-même
  autorise encore `maxPriceAgeSeconds == 0` (contrôle de fraîcheur désactivé) pour les tests
  unitaires en construction directe, mais `EwpgRepoMarketFactory.createMarket` rejette désormais
  `0` — chaque marché déployé par l'opérateur dispose d'une borne de fraîcheur réelle.
- **Garde anti-réentrance d'`EwpgRepoVault`** — le vault était le seul contrat de la pile de prêt
  sans `ReentrancyGuard` sur ses points d'entrée manipulant de la valeur (`deposit`/`mint`/
  `withdraw`/`redeem`/`allocate`/`deallocate`) ; il est désormais protégé comme tous les autres
  contrats de prêt Ewpg*.
- **Rapprochement du grand livre des garanties** (eWpG §24 Berichtigung) — un nouveau
  `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`, soumis à un contrôle
  CONFIGURE, permet à l'opérateur de corriger **à la baisse** (jamais à la hausse) la garantie
  enregistrée d'une position après qu'un `forcedTransfer`/`forceBurn` de l'agent a fait sortir de
  la garantie du pool indépendamment de `repay`/`liquidate`, comblant une lacune où le grand livre
  interne pouvait sinon se désynchroniser du solde réel du jeton.
- **Lacune d'autorisation du `LendingPositionController` backend** — `/api/v1/lending/
  my-positions` et `/supply-positions` ne portaient aucun `@PreAuthorize` ; ils exigent désormais
  une authentification.
- **Le `borrowPaused` on-chain remonte désormais jusqu'au backend/frontend** —
  `LendingMarketService` lit l'indicateur en direct (au mieux ; un échec de lecture on-chain
  retombe sur le statut persisté plutôt que de faire échouer l'affichage) et le reflète comme
  `PAUSED`, comblant la lacune où le statut existait dans le modèle sans jamais être exposé. Le
  stepper d'emprunt affiche désormais un état explicite « marché en pause » plutôt que de laisser
  une tentative d'emprunt échouer (`revert`) on-chain.

**Encore ouvert (voir le document de revue pour le détail complet) :**

- **Examen juridique du prêt sur marge propre à chaque juridiction** — inchangé par rapport à la
  facilité (voir ci-dessous) : la ségrégation de la conservation, l'agrément pour le prêt sur
  marge, et les restrictions de réhypothécation sont une question réglementaire indépendante du
  contrat, et restent non examinées pour `DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG`.
- **La réalisation de la garantie n'est pas véritablement sans autorisation préalable lorsque
  l'ensemble des liquidateurs vérifiés est restreint** — la garantie saisie est livrée au
  liquidateur, si bien que le mur T-REX s'applique aussi à celui-ci ; en l'absence de liquidateur
  éligible, une position dégradée ne peut pas être clôturée. Aucune voie de repli (par exemple un
  agent de dernier recours) n'existe encore pour la réalisation de la garantie.
- **Le statut de pool prête-nom on-chain n'est attesté que hors chaîne** — rien on-chain ne
  vérifie qu'un marché a bien été signalé comme pool prête-nom avant d'accepter des mises en gage
  dépassant le plafond par investisseur ; la première mise en gage dépassant ce plafond échoue
  simplement (`revert`) au niveau du jeton aujourd'hui.
- **Le gel du wallet de l'emprunteur n'atteint pas la garantie déjà mise en gage** — une fois la
  garantie dans le pool, un gel ultérieur sur le wallet propre de l'emprunteur ne s'y applique
  plus, puisque le contrat du pool est, à partir de ce moment, le détenteur enregistré du jeton.

## Prêter contre des titres financiers en garantie : ce qui est réellement mis en œuvre, et ce qui exige encore un feu vert juridique { #lending-against-securities-as-collateral-whats-actually-implemented-vs-what-still-needs-legal-sign-off }

`EwpgRepoFacility` est implémentée et testée (`contracts/test/examples/EwpgRepoFacility.t.sol`)
comme une **implémentation de référence** — la mécanique de prêt garanti, le contrôle d'accès et la
logique de réalisation de la garantie sont réels et corrects, mais les points suivants restent des
simplifications délibérées ou des questions ouvertes avant un déploiement en production :

- **Aucun facteur de réserve de protocole** — 100 % des intérêts de l'emprunteur reviennent
  aujourd'hui aux déposants, ce choix étant maintenu pour garder une comptabilité précisément
  auditable dans une implémentation de référence. Un prélèvement de réserve est un changement
  isolé et additif. (`EwpgRepoMarket` en ajoute déjà un — voir ci-dessus.)
- **Réalisation de la garantie uniquement à facteur de clôture complet** — une position dégradée
  est intégralement réalisée en un seul appel pour la totalité de la dette due, jamais
  partiellement. Les marchés monétaires réels prennent souvent en charge la réalisation partielle
  pour réduire les besoins en capital du liquidateur. (`EwpgRepoMarket` ajoute déjà une
  réalisation partielle/à facteur de clôture — voir ci-dessus.)
- **Les titres financiers en garantie déclenchent toujours leur propre couche réglementaire,
  indépendante de la conception du contrat intelligent** : règles de ségrégation de la
  conservation, agrément pour le prêt sur marge et, selon la juridiction, restrictions de
  réhypothécation, qui ne s'appliquent pas à un simple prêt gagé en espèces. Faites réaliser un
  examen juridique propre à chaque juridiction sur les règles de prêt sur marge, pour `DE_EWPG`/
  `LU_CSSF`/`FR_AMF`/`LI_TVTG`, avant d'exploiter cette facilité contre de véritables titres
  financiers en production — c'est l'un des recoins les plus lourdement réglementés de MiFID II
  et du droit national des titres financiers, et le fait que le contrat soit correct ne remplace
  pas cet examen. **Toujours non examiné à la date de la revue de conformité du 2026-07-21
  ci-dessus** — c'est un travail de juristes, que d'autres modifications du contrat ne peuvent pas
  remplacer.
