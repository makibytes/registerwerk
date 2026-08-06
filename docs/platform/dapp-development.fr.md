# Créer des dApps pour l'écosystème Registerwerk { #building-dapps-for-the-registerwerk-ecosystem }

Registerwerk fournit un **cadre d'identité et d'autorisation on-chain** sur lequel les institutions
financières construisent des dApps de tokenisation, ainsi qu'une **marketplace** où ces dApps sont
examinées, ancrées on-chain et proposées aux autres participants. Ce guide couvre le flux de travail
du développeur de bout en bout.

## Les briques de base { #the-building-blocks }

| Contrat | Objectif |
|---|---|
| `OrgRegistry` | Lie les wallets de membres à des organisations (une organisation = son adresse ONCHAINID). Chaque wallet appartient au plus à une organisation par chaîne. |
| `PermissionRegistry` | L'opérateur accorde des permissions aux organisations ; les administrateurs d'organisation les délèguent à des rôles de membre et peuvent les marquer comme restreintes par rôle. |
| `EcosystemTrustedIssuersRegistry` | Émetteurs de claims approuvés par sujet de claim ONCHAINID (1 = KYC, 2 = AML, 3 = Accréditation). |
| `DappRegistry` | Ancre les manifestes de marketplace approuvés (keccak256) et les attestations d'instance facultatives. |
| `PermissionOracle` | **La seule adresse que votre dApp mémorise.** Compose tout ce qui précède derrière une façade de requête stable. |

Votre dApp ne parle jamais directement aux registres — uniquement au `PermissionOracle`
(`IPermissionOracle`), que l'opérateur peut repointer vers des registres mis à niveau sans casser
les dApps déjà déployées.

## Écrire un contrat soumis à autorisation { #writing-a-gated-contract }

Héritez de `RegisterwerkGated` (dans `contracts/src/ecosystem/RegisterwerkGated.sol`) et passez
l'adresse de l'oracle dans votre constructeur :

```solidity
import "@registerwerk/ecosystem/RegisterwerkGated.sol";

contract LoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
        // caller's wallet belongs to an active org holding "loandesk.open",
        // and the org's ONCHAINID carries a valid KYC claim
    }
}
```

Modificateurs disponibles :

- `requiresPermission(bytes32 permission)` — octroi au niveau de l'organisation (plus délégation de
  rôle lorsque l'organisation a marqué la permission comme restreinte par rôle).
- `requiresClaim(uint256 topic)` — un claim valide pour le sujet donné sur l'ONCHAINID de
  l'organisation appelante, signé par un émetteur approuvé par l'écosystème.
- `requiresActiveMember` — le wallet de l'appelant est lié à une organisation non suspendue.

Les identifiants de permission sont `keccak256("<your-slug>.<action>")`. Votre slug de marketplace
est votre espace de noms — les manifestes déclarant des permissions en dehors de `<slug>.*` sont
rejetés, à moins que le code n'existe déjà en tant que permission de plateforme.

Un exemple minimal exécutable se trouve dans `contracts/test/ecosystem/SampleGatedDapp.t.sol`. Pour
deux dApps de référence entièrement packagées et prêtes pour la marketplace — dont une véritable
intégration ERC-3643 (T-REX) — voir [Exemples de dApps de référence](#reference-example-dapps)
ci-dessous.

## Le manifeste { #the-manifest }

La marketplace stocke **uniquement des métadonnées** : vos conteneurs restent dans votre propre
registre OCI, épinglés par digest. Le manifeste (JSON, schéma :
`backend/src/main/resources/schemas/dapp-manifest.schema.json`) décrit :

```json
{
  "slug": "loandesk",
  "name": "Loan Desk",
  "version": "1.0.0",
  "description": "Institutional loan origination on Registerwerk rails.",
  "category": "lending",
  "contracts": [
    { "name": "LoanDesk", "abiSha256": "<sha256 of the ABI json>" }
  ],
  "requiredPermissions": [
    { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
  ],
  "requiredClaimTopics": [1],
  "images": [
    { "name": "backend",  "role": "backend",
      "ref": "registry.bank.example/loandesk/backend@sha256:…" },
    { "name": "frontend", "role": "frontend",
      "ref": "registry.bank.example/loandesk/frontend@sha256:…" }
  ],
  "deployment": { "composeUrl": "https://…/docker-compose.yml", "composeSha256": "…" },
  "docsUrl": "https://docs.bank.example/loandesk",
  "contact": "dapps@bank.example",
  "license": "commercial",
  "pricingNote": "Contact publisher"
}
```

Règles appliquées à la validation :

- **L'épinglage par digest est obligatoire** — `images[].ref` doit correspondre au motif
  `…@sha256:<64 hex>` ; les références par tag seul sont rejetées.
- Le `slug` du manifeste doit être identique au slug de la fiche marketplace.
- `requiredPermissions[].code` doit relever de votre espace de noms ou être une permission de
  plateforme existante.

## Déclarer des méthodes de paiement { #declaring-payment-methods }

Émettre un jeton d'actif n'est que la moitié de l'histoire — la plupart des dApps ont aussi besoin
d'une jambe cash (paiements de souscription, versements de coupon/dividende, remboursements).
Plutôt que de faire construire et auditer ses propres rails de paiement par chaque éditeur,
l'opérateur du registre organise un catalogue de rails prêts à l'emploi — des stablecoins avec des
champs d'information et d'attestation liés à MiCAR saisis par l'opérateur, l'API de paiement
instantané Pontes, un règlement de type livraison contre paiement au format ERC-7573, et le SEPA
hors-chaîne classique — et votre manifeste peut simplement les référencer par code :

```json
"paymentMethods": [
  { "rail": "aueur", "note": "Primary-market subscription plus coupon and redemption payouts" },
  { "rail": "usdc" },
  { "rail": "erc7573-dvp", "note": "Same-transaction DvP; exact-leg, finality, and legal-register checks remain external" }
]
```

Récupérez le catalogue actuel des rails activés sur `GET /api/v1/payment-rails/catalog` (également
présenté à l'étape « Payment methods » de l'assistant de publication) et copiez un `code`. Chaque
entrée de rail est validée à la soumission **et de nouveau à l'approbation** — un rail que
l'opérateur a désactivé entre-temps bloque l'approbation de la version tant que le manifeste n'a
pas été mis à jour.

C'est indicatif, pas une liste blanche : votre dApp peut toujours implémenter sa propre logique de
paiement. Déclarez-la comme entrée `custom` au lieu d'une référence `rail` :

```json
"paymentMethods": [
  { "custom": { "name": "Own SEPA collection account", "description": "Publisher-run SEPA rail, settled off-chain", "currency": "EUR" } }
]
```

Les entrées `custom` passent la validation sans condition mais sont signalées bien en évidence à
l'opérateur pendant l'examen (et aux investisseurs sur la page de détail du catalogue) — le marché
peut voir précisément ce qui est sorti du chemin pratique des « rails fournis par le registre ».

Pour les dApps qui veulent elles-mêmes proposer une livraison contre paiement atomique (par exemple
un guichet de marché secondaire), le contrat `DvpSettlement` de l'opérateur
(`contracts/src/settlement/DvpSettlement.sol`) implémente une DvP de type ERC-7573 sur une même
chaîne : une partie verrouille la jambe actif ou la jambe paiement en séquestre, la contrepartie
règle les deux jambes atomiquement, ou l'opération expire et celui qui a verrouillé la récupère.
Voir sa NatSpec pour la mise en garde relative au séquestre ERC-3643 (les jetons T-REX exigent que
l'ONCHAINID du contrat de règlement soit vérifié dans le registre d'identité avant de pouvoir être
mis en séquestre — verrouiller plutôt la jambe paiement contourne ce point pour les titres
financiers).

## Flux de publication { #publication-workflow }

1. **Prérequis :** votre entreprise est enregistrée comme organisation on-chain (côté opérateur)
   et votre wallet de publication y est lié (Portail client → Company Admin → Organisation).
2. Portail client → **My dApps** → *New dApp* (slug + chaîne d'ancrage).
3. Collez le manifeste dans l'assistant de publication → la validation côté serveur renvoie les
   erreurs et `manifestHash = keccak256(manifest_raw_bytes)`, sous forme de chaîne hexadécimale
   préfixée par 0x.
4. **Signez** avec un wallet d'organisation lié : `personal_sign` (EIP-191) est appelé avec la
   **chaîne de caractères hexadécimale 0x** comme message — et non les 32 octets bruts du hash.
   C'est un choix délibéré, afin que toute interface de wallet affiche la chaîne hexadécimale
   lisible en cours de signature ; les vérificateurs doivent effectuer la récupération sur la même
   chaîne (voir [Vérification de l'intégrité](#integrity-verification-consumers) ci-dessous).
5. **Soumettez** — l'opérateur du registre examine avec step-up + principe des quatre yeux.
6. À l'approbation, le backend appelle `DappRegistry.registerDapp(keccak256(slug), publisherOrg,
   manifestHash, …)` ; une fois la transaction confirmée, la fiche est active dans le catalogue.

Les mises à jour de version répètent les étapes 3 à 6 ; le nouveau hash est ancré via
`DappRegistry.updateManifest` et la version précédente est marquée comme remplacée.

## Vérification de l'intégrité (côté consommateurs) { #integrity-verification-consumers }

Tout ce qu'il faut pour vérifier une fiche de façon indépendante se trouve dans le détail du
catalogue :

```bash
# 1. The manifest hash must match the onchain anchor
MANIFEST_HASH=$(cast keccak "$(cat manifest.json)")
cast call $DAPP_REGISTRY "getDapp(bytes32)" $(cast keccak "loandesk") --rpc-url $RPC

# 2. The signature must recover to the declared publisher wallet, which must be a bound
#    member wallet of the publisher org. Recovery is over the hex *string* $MANIFEST_HASH
#    (EIP-191 personal_sign), not the raw 32 hash bytes:
cast wallet verify --address $PUBLISHER_WALLET "$MANIFEST_HASH" $SIGNATURE

# 3. Pull images only by the digests listed in the manifest
```

## Attestation d'instance (facultatif) { #instance-attestation-optional }

Les instances de contrat déployées de votre dApp peuvent être attestées dans le `DappRegistry`
(`attestInstance`) par l'administrateur de votre organisation. D'autres contrats peuvent alors
exiger `oracle.isApprovedInstance(caller)` — une couche de composition optionnelle ; elle n'est
délibérément pas intégrée à `hasPermission`, car les déploiements auto-hébergés contrôlent leurs
propres appelants.

## Composabilité DeFi externe { #external-defi-composability }

`PermissionOracle` et `DvpSettlement` sont tous deux librement appelables, sans autorisation
préalable, par **n'importe quel** contrat externe — pas seulement les dApps de la marketplace
Registerwerk. Il n'y a ni `onlyRole` ni liste blanche sur l'un ou l'autre :

- **`PermissionOracle`** — un protocole DeFi externe (son propre pool, coffre ou marché de prêt)
  peut appeler `hasPermission`/`hasClaimTopic`/`isActiveMember` sur n'importe quelle adresse de
  wallet pour conditionner sa *propre* logique aux investisseurs vérifiés par Registerwerk, sans
  jamais toucher un jeton de titre Registerwerk ni détenir un fonds dont l'oracle aurait
  connaissance. C'est le modèle d'interopérabilité `ORACLE_ONLY` (voir `DefiInteropModel` dans le
  module backend `kyc`) — risque de garde nul, puisque l'oracle ne détient jamais rien ; il répond
  uniquement à la question « ce wallet est-il KYC-vérifié pour le sujet X ? ». Un point à bien
  intégrer : l'oracle vérifie l'appartenance à une organisation du **wallet interrogé lui-même**,
  pas celle de l'appelant. Si votre contrat doit lui-même *être* l'identité vérifiée (par exemple
  pour appeler une fonction dApp Registerwerk soumise à autorisation en tant que `msg.sender`),
  l'adresse de votre contrat doit elle-même être intégrée via `OrgRegistry` comme n'importe quel
  autre wallet de membre — il n'existe pas de raccourci générique « tout contrat intelligent
  passe ».
- **`DvpSettlement`** — un séquestre générique de type ERC-7573, sans autorisation préalable,
  utilisable par n'importe quel protocole externe pour des échanges atomiques actif↔stablecoin,
  entièrement indépendant du cadre de permissions de l'écosystème. Lisez attentivement sa mise en
  garde NatSpec avant toute intégration : mettre en séquestre un actif ERC-3643 via `lockAsset`
  exige que `DvpSettlement` lui-même passe `isVerified()` dans le registre d'identité de ce jeton
  (une étape d'intégration unique effectuée par l'agent de registre du jeton) ; appeler
  `lockPayment` à la place évite entièrement ce problème, puisque la jambe titre financier se
  déplace alors directement du vendeur à l'acheteur, en transfert direct au niveau du contrat,
  plutôt que de rester en séquestre. Le fait de passer les contrôles techniques du jeton n'établit
  ni conformité légale ou réglementaire, ni règlement au sens juridique.

Pour la question plus difficile — un protocole externe peut-il *détenir* un jeton de titre
Registerwerk sous forme de solde mutualisé (un pool AMM, un marché de prêt) — voir
[`docs/platform/defi-interoperability.md`](./defi-interoperability.md), qui explique pourquoi cela
exige une structure de nominee/dépositaire agréé (le modèle `NOMINEE_POOL`) plutôt qu'un pool
anonyme sans autorisation, et comment fonctionne l'exemption nominee du `EwpgComplianceModule`.

## Exemples de dApps de référence { #reference-example-dapps }

Trois exemples techniques de référence sont livrés dans ce dépôt avec manifestes, code source
Solidity, tests et un `README`. Ce sont des exemples et non des modèles de produits approuvés ; ils
sont semés comme fiches de démonstration `PUBLISHED` sur la marketplace par
`EcosystemDemoDataSeeder` lorsque `registerwerk.seed-demo-data=true` :

| dApp | Slug | Illustre |
|---|---|---|
| **Boardroom Governance** | `boardroom` | Le cadre de gestion des permissions dans son intégralité : proposer/voter/dépouiller conditionné par des permissions + des claims ONCHAINID (KYC, Accréditation), et le flux **restriction par rôle / délégation par l'administrateur d'organisation** sur `boardroom.tally`. |
| **eWpG Bond Desk** | `bond-desk` | Un exemple technique ERC-3643/T-REX avec une jambe de paiement en jeton configurée. `subscribe` effectue le transfert de paiement et l'émission (mint) en une seule transaction ; `payCoupon`/`redeem` exercent des contrôles de délai et d'idempotence. Ce n'est ni une obligation légalement qualifiée, ni un dispositif de paiement vérifié, ni une preuve de règlement légal. |
| **eWpG Repo & Lending Facility** | `repo-facility` | Un exemple technique de prêt garanti, avec un côté prêteur en stablecoin ouvert et un côté emprunteur soumis au contrat. L'usage en production est bloqué en attendant la qualification juridique, la garde/le contrôle, la réalisation de la garantie, l'oracle, l'insolvabilité, l'éligibilité et l'approbation en matière de sécurité. Les contrôles d'identité du jeton ne suffisent pas, à eux seuls, à rendre la réalisation de la garantie conforme. Voir [Interopérabilité DeFi](./defi-interoperability.md#ewpgrepofacility-the-primary-exit-liquidity-mechanism). |

| | Chemin |
|---|---|
| Contrats | `contracts/src/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility,MockStablecoin}.sol`, `contracts/src/settlement/DvpSettlement.sol` |
| Tests | `contracts/test/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility}.t.sol`, `contracts/test/settlement/DvpSettlementTest.t.sol` |
| Assistant d'amorçage T-REX | `contracts/test/helpers/TrexSuiteDeployer.sol` — la mise en place complète de T-REX + ONCHAINID (autorité d'implémentation, fabrique d'identités, module de conformité), réutilisée par le test et le script de déploiement du bond desk |
| Scripts de déploiement | `contracts/script/DeployEwpgTrexBond.s.sol`, `contracts/script/DeployExampleDapps.s.sol` (boardroom, bond desk), `contracts/script/DeployLiquidityDapps.s.sol` (repo facility, plus `EwpgPaymaster` — conservé dans un script séparé car les deux sont en pragma `^0.8.36` et ne peuvent pas partager une unité de compilation avec les contrats dépendants d'erc3643 ci-dessus ; voir la NatSpec propre à ce script) |
| Manifestes | `backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility}.manifest.json` — également lus directement par le semeur de données de démo (`registerwerk.seed-demo-data=true`), qui publie les trois comme fiches marketplace actives avec des signatures réelles, vérifiables de façon indépendante |
| Guides | `examples/dapps/{boardroom,bond-desk,repo-facility}/README.md` |

Exécutez `forge test --match-path 'test/examples/*'` pour voir les trois exercés de bout en bout, y
compris — pour le bond desk — de véritables identités ONCHAINID et des claims KYC/AML signés par
ECDSA via un `ClaimIssuer` onchain-id.

Deux contrats supplémentaires illustrent le pont `NOMINEE_POOL` et le motif AMM-pour-stablecoins de
[Interopérabilité DeFi](./defi-interoperability.md) — contrairement aux trois dApps ci-dessus, ils
ne sont livrés qu'en Solidity testé (pas de manifeste, non semés comme fiches marketplace actives) :

- `contracts/src/examples/CompliantSecondaryMarket.sol` — un guichet de marché secondaire
  nominee/omnibus conditionné par `secondary-market.trade` + le sujet de claim `NOMINEE` (4) ;
  règle chaque transaction via le `DvpSettlement` ci-dessus, non modifié et sans autorisation
  préalable, et ses exécutions servent aussi de flux de prix pour
  `EwpgRepoFacility.updatePrice`. Tests : `contracts/test/examples/CompliantSecondaryMarket.t.sol`.
- `contracts/src/examples/StablecoinAmm.sol` — un AMM minimal à produit constant, restreint aux
  paires stablecoin uniquement, délibérément **pas** `RegisterwerkGated` (voir sa NatSpec pour
  savoir pourquoi). Tests : `contracts/test/examples/StablecoinAmm.t.sol`.
