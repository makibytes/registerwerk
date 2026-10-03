---
title: Abstraction de compte et transactions sponsorisées
description: Comptes intelligents ERC-4337 / EIP-7702, gas sponsorisé, clés d'accès (passkeys) et permis sans gas.
---

# Abstraction de compte et transactions sponsorisées { #account-abstraction-sponsored-transactions }

Registerwerk prend en charge les transactions sponsorisées ERC-4337, les comptes délégués
EIP-7702, la vérification de wallets ERC-1271 et un compte passkey on-chain. Ces fonctions sont
indépendantes de l'[interopérabilité DeFi](./defi-interoperability.md).

## Fondation : `WalletSignatureVerifier` { #foundation-walletsignatureverifier }

`WalletSignatureVerifier` (`orgidentity/api/WalletSignatureVerifier.java`, à la base de `orgidentity/internal/MemberWalletService` et `marketplace/internal/ManifestSigningService`) vérifie les signatures **soit** par récupération ECDSA (EOA classiques), **soit** via ERC-1271 `isValidSignature` (wallets à contrat intelligent), selon le code on-chain de l'adresse revendiquée. C'est le prérequis de tout ce qui suit — sans cela, un compte intelligent ne pourrait jamais se lier comme wallet de membre ni signer un manifeste de marketplace.

## EIP-7702 : la rampe d'accès vers le compte intelligent { #eip-7702-the-smart-account-on-ramp }

L'EIP-7702 (actif depuis la mise à niveau Pectra) permet à un EOA existant de déléguer son code à une implémentation de compte intelligent **tout en conservant exactement la même adresse**. C'est la rampe d'accès naturelle pour Registerwerk en particulier, car chaque partie du modèle existant se fonde sur une adresse de wallet fixe :

- `OrgRegistry._orgOf[wallet]` (`contracts/src/ecosystem/OrgRegistry.sol`) — un wallet, un org, par adresse.
- `IdentityRegistry.registerIdentity(address, ...)` de T-REX — identité/claims enregistrés par adresse.
- `EwpgCompliance.isWhitelisted(address)` — liste blanche indexée par adresse.

Un client qui fait migrer son EOA existant vers un compte intelligent délégué 7702 n'a besoin
d'**aucune migration** de ce qui précède — l'adresse ne change pas, donc l'appartenance à l'org,
l'enregistrement d'identité et les entrées de liste blanche restent tous valides. La seule nouvelle
exigence est le chemin ERC-1271 de `WalletSignatureVerifier` (déjà en place), puisque le code d'un
EOA délégué par 7702 implémente `isValidSignature` comme n'importe quel autre wallet à contrat
intelligent. Le délégué utilisé par le portail client est `Simple7702Account` de viem.
`EwpgPasskeyAccount` (ci-dessous) n'est **pas** un délégué 7702 : sa clé d'accès et son gardien
sont l'état de chaque déploiement, donc un EOA qui lui déléguerait n'aurait aucune clé d'accès
(toute signature est refusée) et partagerait un seul gardien avec tous les autres EOA délégants.

`frontend-customer` centralise l'accès au wallet dans `WalletService` et implémente l'exécution
EIP-7702/ERC-4337 facultative dans `SponsoredTxService`. Le sponsoring exige
`environment.bundlerUrl` et, pour chaque UserOperation, un **bon** (voucher) du backend (section
suivante). Si le backend refuse le bon, le service lève `SponsorshipUnavailableError` avec le
motif ; `sendWithSponsorshipFallback` envoie alors le même appel comme transaction ordinaire payée
par l'utilisateur et le signale. Il ne se replie jamais en silence. L'interface ne crée ni
n'exploite d'instances `EwpgPasskeyAccount`.

## `EwpgPaymaster` — transactions sponsorisées { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` est un **paymaster vérificateur** ERC-4337 (contre
EntryPoint v0.8, pour la prise en charge native d'EIP-7702) qui sponsorise le gas pour les clients
Registerwerk vérifiés.

**Bons.** Une UserOperation n'est sponsorisée que si elle porte un bon signé par le signataire de
bons enregistré pour la politique :

```
paymasterData = policyId (32) ‖ validUntil (6) ‖ validAfter (6) ‖ maxFeePerGasCap (16) ‖ signature (65)
```

La signature est une signature EIP-191 (`personal_sign`) sur `EwpgPaymaster.getHash(...)`. Ce
condensé couvre chaque champ de la UserOperation (sender, nonce, `keccak(initCode)`,
`keccak(callData)`, les limites de gas du compte, les limites de gas du paymaster pour la
vérification et le postOp, `preVerificationGas` et `gasFees`), ainsi que `block.chainid`, l'adresse
du paymaster, l'identifiant de politique, la fenêtre de validité et le plafond du prix du gas, sous
l'étiquette de domaine `keccak256("EwpgPaymasterVoucher(v1)")`. Il exclut délibérément les octets
de la signature : ils se trouvent dans `paymasterAndData`, qui fait partie de `userOpHash`, donc un
bon ne peut pas signer `userOpHash`. Un mauvais signataire renvoie `SIG_VALIDATION_FAILED`
(l'EntryPoint signale `AA34`) et un bon expiré renvoie `AA32`. La validation échoue aussi si la
politique est inactive ou non enregistrée, si `maxFeePerGas` dépasse le plafond signé, si
`paymasterPostOpGasLimit` est inférieur à 50 000 gas ou si le sender n'est pas un membre actif
vérifié KYC (défense en profondeur ; le backend le vérifie aussi).

**Émetteur de bons (backend).** `POST /api/v1/gas-sponsorship/vouchers` (JWT client,
`asset/web/GasSponsorshipVoucherController`, `asset/internal/GasSponsorshipVoucherService`) reçoit
l'identifiant du déploiement et la UserOperation préparée. Avant de signer, il vérifie :

- que la politique effective du déploiement est **active** en base de données. Désactiver une
  politique arrête immédiatement les bons, avant même que l'indicateur on-chain soit modifié.
- que le sender est un **wallet de membre actif de l'entité juridique de l'appelant** sur cette
  chaîne.
- que le sender **détient l'actif** : il a une inscription active au registre (pas une ligne de
  nominee pool) de l'entité de l'appelant pour l'actif du déploiement. Les premiers souscripteurs
  sans inscription ne sont pas sponsorisés et paient leur propre gas.
- le **périmètre** (par défaut, jusqu'à décision produit contraire) : chaque appel du lot
  `execute`/`executeBatch` cible le contrat du jeton du déploiement sans valeur, et `initCode` est
  vide ou se limite au marqueur EIP-7702. Les déploiements par factory sont refusés.
- le **gas** : `maxFeePerGas` ≤ `registerwerk.paymaster.max-fee-per-gas-cap-wei` (le plafond est
  signé dans le bon), la somme des limites de gas ≤ `max-total-gas` et le gas de postOp ≥ 50 000.
- le **plafond mensuel** de la politique (`monthlyCapEth`). Chaque bon émis compte pour son coût
  dans le pire cas (`Σ limites de gas × maxFeePerGas`, le préfinancement de l'EntryPoint) et est
  enregistré dans `gas_sponsorship_voucher`, de sorte que le plafond s'applique avant le règlement
  de toute opération. Un bon ne compte qu'une fois par `(politique, sender, nonce de la
  UserOperation)` : une nouvelle demande pour le même nonce remplace le bon précédent. Une entité
  juridique peut utiliser au plus `registerwerk.paymaster.entity-monthly-cap-share` (10 % par
  défaut) du plafond par mois, afin qu'une seule organisation n'épuise pas le budget d'un émetteur
  au détriment des autres détenteurs.

Chaque bon est valable `voucher-validity-seconds` (300 par défaut) et émet l'événement d'audit
`GAS_SPONSORSHIP_VOUCHER_ISSUED`. La clé de signature de développement est
`registerwerk.paymaster.voucher-signer-key` (`REGISTERWERK_PAYMASTER_VOUCHER_SIGNER_KEY`). Elle est
encapsulée dans l'abstraction `EvmSigner` du module wallet ; en production, elle passe en KMS/HSM.
Elle ne doit **jamais** être le wallet de signature des claims (émetteur de confiance) : une clé de
bons peut dépenser le budget de sponsoring. Vide, elle désactive le sponsoring. Les adresses du
paymaster se configurent par chaîne sous `registerwerk.paymaster.addresses`
(`PAYMASTER_<CHAIN>_<NETWORK>`). Le `policyId` on-chain d'une ligne `GasSponsorshipPolicy` est
`keccak256(id.toString())`.

**Comptabilité du budget.**

- `registerPolicy(policyId, signer, orgCap)` enregistre l'appelant comme **financeur** (funder) de
  la politique, le signataire de bons et un plafond par org non nul. La politique peut être
  financée dans le même appel.
- `fundSponsorship(policyId)` recharge. Seul le financeur peut l'appeler, et seul lui peut changer
  de signataire (`setPolicySigner`), car un signataire peut dépenser la politique.
- La validation **réserve** le `maxCost` de l'opération sur le solde de la politique, de sorte que
  plusieurs opérations d'un même bundle ne peuvent pas toutes être validées sur le même solde. Elle
  vérifie aussi le plafond de l'org du sender par rapport à dépensé + réservé + `maxCost`. Le
  plafond est lié à `orgOf(sender)`, donc de nouveaux wallets de la même org ne le multiplient pas.
- `postOp` n'échoue jamais. Il comptabilise `min(maxCost, actualGasCost + (postOpGasLimit +
  10 000) × feePerGas)` et restitue le reste de la réservation. EntryPoint v0.7/v0.8 transmettent à
  `postOp` le coût *avant* l'ajout du gas propre au postOp et de la pénalité pour gas inutilisé ;
  ne comptabiliser que `actualGasCost` ferait dériver les livres au-dessus du dépôt réel. Le montant
  comptabilisé est une borne supérieure ; la petite différence reste dans le dépôt comme excédent.
- `depositSurplus()` = dépôt dans l'EntryPoint − (Σ soldes + Σ réservations). Il ne doit jamais
  devenir négatif. Le surveiller comme `paymaster_deposit_minus_booked_wei` avec alerte sous 0.

**Propriété et contrôles (on-chain).**

- `setPolicyActive(policyId, bool)` est l'interrupteur d'urgence on-chain. Le financeur ou un
  détenteur de `paymaster.configure` peut l'appeler.
- `withdrawPolicy(policyId, amount)` restitue le budget non réservé depuis le dépôt de
  l'EntryPoint. Le financeur ou un détenteur de `paymaster.configure` peut la déclencher, mais elle
  **paie toujours le financeur enregistré**. Personne ne peut la rediriger.
- `addStake(unstakeDelaySec)` (exige `paymaster.configure`), `unlockStake()` et `withdrawStake()`
  gèrent le stake dans l'EntryPoint. Le premier staker est enregistré comme `stakeFunder` et le
  stake est toujours restitué à cette adresse. Seul `stakeFunder` peut appeler `unlockStake()` :
  sans stake, les bundlers publics écartent le paymaster, donc un autre détenteur de
  `paymaster.configure` ne peut pas lancer le retrait du stake.

!!! note "Org opératrice et permissions de sponsor"
    Le paymaster est construit avec `(oracle, entryPoint, operatorOrg)`. `paymaster.configure`
    (l'interrupteur d'arrêt, le plafond par org, le déclenchement de `withdrawPolicy` et la gestion
    du stake décrits ci-dessus) ne fonctionne que pour les wallets de cette `operatorOrg` ; une org
    étrangère détenant la même permission ne peut administrer ni une politique ni le stake.
    `registerPolicy` et `addStake` sont bornés de la même façon : `registerPolicy` exige la
    permission `paymaster.register-policy` via l'org de l'appelant, que l'opérateur accorde à chaque
    sponsor (sa propre org et celles des émetteurs), si bien qu'un wallet non approuvé par
    l'opérateur ne peut ni enregistrer ni squatter un identifiant de politique publié.

**Interface opérateur.** La page de détail d'actif de `frontend-operator` comporte un onglet
**Gas Sponsorship** par déploiement (définir/supprimer une dérogation propre au déploiement). La
page de détail client en comporte un pour les émetteurs (définir la valeur par défaut de l'émetteur
dont héritent les nouveaux déploiements). Tous deux s'appuient sur
`core/api/gas-sponsorship.service.ts`. L'onglet de l'actif affiche aussi l'état on-chain de la
politique : indicateur d'activité, solde disponible et réservé, plafond par org, financeur et
signataire de bons (`GET /assets/{id}/deployments/{depId}/gas-sponsorship/onchain`). Il avertit
lorsqu'une politique est désactivée en base mais encore active on-chain.
`GET /gas-sponsorship/voucher-signer` renvoie l'adresse que chaque politique doit enregistrer comme
signataire.

- Script de déploiement : `contracts/script/DeployLiquidityDapps.s.sol` déploie `EwpgPaymaster`
  (EntryPoint par défaut `ERC4337Utils.ENTRYPOINT_V08`) aux côtés de `EwpgRepoFacility`.
- Données de démo : `EcosystemDemoDataSeeder` initialise trois lignes `GasSponsorshipPolicy` — la
  valeur par défaut propre à Meridian Capital (sponsor `ISSUER`), la valeur par défaut d'Aurora
  Finance financée par l'opérateur à la place (sponsor `OPERATOR`, illustrant l'autre type de
  sponsor), et une dérogation au niveau déploiement sur le Green Bond phare de Meridian
  (`OPERATOR`, illustrant la priorité d'une dérogation sur une valeur par défaut).
- Tests : `contracts/test/ecosystem/EwpgPaymaster.t.sol` fait passer chaque chemin sponsorisé par
  `handleOps` du **véritable EntryPoint v0.8.0** (vendorisé pour les tests uniquement sous
  `contracts/test/aa-v08/`), avec des tests de régression pour les scénarios de siphonnage cités
  dans le déploiement ci-dessous. `backend/.../asset/internal/GasSponsorshipVoucherServiceTest.java`
  et `unit/GasSponsorshipVoucherDigestTest.java` lient le condensé Java à celui de Solidity par un
  vecteur de test partagé.

### Stake, déploiement et retrait du paymaster précédent { #paymaster-operations }

La validation écrit du stockage (réservations) et lit d'autres contrats (`PermissionOracle`) ; selon
ERC-7562, les bundlers publics n'acceptent donc le paymaster que s'il a un **stake**. Par chaîne :

| Chaîne | Stake conseillé | Délai d'unstake |
|---|---|---|
| Ethereum mainnet | ≥ 1 ETH | ≥ 1 jour (86 400 s) |
| L2 (Base, Arbitrum, Optimism, Polygon) | minimum du bundler (souvent 0,1–1 du jeton natif) | ≥ 1 jour |
| Testnets | minimum du bundler | ≥ 1 jour |

Vérifiez le minimum publié par le fournisseur du bundler avant de staker. Un stake dont le délai est
plus court que celui exigé par le bundler est traité comme absent.

Déploiement :

1. Le paymaster déployé avant ce changement (HEAD `b810acb` et antérieurs) est immuable et non sûr :
   tout membre pouvait dépenser n'importe quelle politique, le prix du gas n'était pas borné et un
   bundle pouvait dépenser au-delà du solde. **Cessez de le financer dès maintenant.** Il n'a pas de
   fonction de retrait, donc plus aucune option de financement ne pointe vers lui.
2. Déployez le nouvel `EwpgPaymaster` avec son `operatorOrg` (`PAYMASTER_OPERATOR_ORG` dans `DeployLiquidityDapps.s.sol`, par défaut l'org du déployeur). Appelez `addStake` depuis le wallet de l'opérateur et
   définissez `registerwerk.paymaster.addresses.<chain>` ainsi que la clé du signataire de bons.
3. L'opérateur accorde `paymaster.register-policy` à chaque org sponsor (la sienne et celles des émetteurs) et `paymaster.configure` uniquement à l'org opératrice. Chaque financeur appelle ensuite `registerPolicy(keccak256(policyRowId), voucherSigner, orgCap)` avec le
   budget.
4. Consignez par chaîne l'ETH restant dans l'ancien paymaster (`EntryPoint.balanceOf(old)`) comme
   **solde bloqué**. Il ne peut être consommé que par des opérations sponsorisées, ce qu'il faut
   éviter au vu des défauts ci-dessus.

Limite connue : `registerPolicy` attribue un identifiant de politique au premier sponsor approuvé
qui l'enregistre. Un sponsor qui devance l'enregistrement (front-running) peut bloquer cet
identifiant, sans pouvoir prendre de fonds ; le financeur enregistre alors la politique sous un
nouvel identifiant de ligne. Les wallets sans `paymaster.register-policy` ne le peuvent plus.

## `EwpgPasskeyAccount` — signataires par clé d'accès pour le retail { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` est un compte intelligent ERC-4337 minimal,
sécurisé par une clé d'accès WebAuthn/secp256r1 au lieu d'une clé ECDSA gérée par une phrase de
récupération, composant trois éléments déjà vendorisés via `contracts/lib/openzeppelin-contracts`
(aucune nouvelle dépendance) : `Account` d'OZ (`validateUserOp` ERC-4337), `SignerWebAuthn`
(vérification de signature par clé d'accès) et `ERC7821` (exécution par lot minimale). Il implémente
également ERC-1271, ce qui lui permet de se lier comme wallet de membre Registerwerk exactement comme
n'importe quel autre wallet à contrat intelligent. Il est déployé comme compte propre à chaque
client et n'est **pas un délégué EIP-7702** : sans clé d'accès dans le stockage propre du compte,
toute vérification de signature échoue.

Le gardien est un argument explicite du constructeur, jamais le déployeur par accident. Les appels
peuvent être classés comme courants, d'administration ou de récupération selon la cible et le
sélecteur. Les lots EntryPoint/ERC-7821 refusent les opérations d'administration et de
récupération, de sorte qu'une clé de session compromise ou une UserOperation sponsorisée ne peut pas
les exécuter. `guardianExecute` est une **dérogation de conservation complète**, pas un chemin
purement protecteur : le gardien peut effectuer n'importe quel appel depuis le compte sans
timelock ni cosignature de la clé d'accès, et il définit lui-même la table des rôles. La question
de savoir si un gardien détenu par le registre, avec un contrôle unilatéral sur des comptes retail,
est voulu reste une décision de conservation ouverte (agrément et information). D'ici là, traitez la
clé du gardien comme la conservation des actifs du compte.

Associé à `EwpgPaymaster`, le parcours d'un investisseur particulier depuis l'onboarding jusqu'à sa
première souscription ne nécessite ni phrase de récupération ni jeton de gas — authentification
biométrique par clé d'accès et exécution sponsorisée. Remarque : `contracts/foundry.toml` active
désormais l'optimiseur Solidity (`optimizer = true`, `optimizer_runs = 200`, conformément à la
valeur par défaut de la bibliothèque OZ vendorisée) — l'analyse de la signature WebAuthn atteint
« stack too deep » sans cela.

Les tests (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) construisent de véritables
assertions d'authentification WebAuthn à l'aide des cheatcodes P256 natifs de Foundry
(`vm.publicKeyP256`/`vm.signP256`), y compris un exemple travaillé du seul piège non évident :
`abi.encode(structValue)` ajoute un mot de décalage supplémentaire de premier niveau pour une struct
contenant des champs dynamiques, ce que `WebAuthn.tryDecodeAuth` n'attend pas — encodez plutôt les
champs de la struct comme des arguments séparés (voir l'assistant `_sign` du test et son commentaire
en ligne). `test_eip7702DelegateHasNoSignerAndFailsClosed` montre qu'un EOA qui délègue à une
instance n'a pas de signataire et ne peut pas être contrôlé par le déployeur de l'instance.

## Permis sans gas { #gasless-permits }

`EwpgBondDesk.subscribeWithPermit` consomme un `permit` EIP-2612 signé au lieu d'exiger une transaction `approve` préalable distincte — cela divise par deux le nombre de transactions et s'associe naturellement au sponsoring `EwpgPaymaster` (permit + exécution sponsorisée = UX sans jeton de gas). `MockStablecoin` implémente désormais `ERC20Permit` afin que l'exemple/les tests puissent exercer ce parcours de bout en bout (`test_subscribeWithPermit_succeedsWithoutPriorApproval` dans `contracts/test/examples/EwpgBondDesk.t.sol`). Tous les rails de paiement réels ne prennent pas en charge cela : USDC implémente EIP-2612 nativement ; vérifiez la prise en charge d'AllUnity Euro avant de câbler `subscribeWithPermit` en production — le chemin `subscribe` classique reste disponible dans tous les cas.

## Formats de signature { #signature-formats }

La liaison du wallet et la signature des manifestes utilisent `personal_sign`.
`WalletSignatureVerifier` accepte ce format pour les EOA et les wallets ERC-1271, mais pas les
signatures de données typées EIP-712.
