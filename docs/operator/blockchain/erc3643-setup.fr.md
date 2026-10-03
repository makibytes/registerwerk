---
title: Configuration ERC-3643
---

# Configuration ERC-3643 (T-REX)

Ce guide décrit la configuration complète de l'infrastructure ERC-3643 T-REX — du déploiement du contrat à l'émission des attestations KYC auprès des investisseurs.

## Ce qui est déployé

Pour chaque émission d'ERC-3643, l'usine déploie six contrats :

| Contrat | Rôle |
|--------------|------|
| `Token` | Le token ERC-3643 (contrat principal, interface compatible ERC-20) |
| `IdentityRegistry` | Mappe les portefeuilles des investisseurs sur leur ONCHAINID |
| `IdentityRegistryStorage` | Stockage évolutif pour le registre d'identité |
| `ClaimTopicsRegistry` | Définit les ID de sujet d'attestation requis (par exemple, KYC=1, AML=2) |
| `TrustedIssuersRegistry` | Définit quels émetteurs d'identité peuvent signer des attestations |
| `ModularCompliance` | Conteneur pour les modules de règles de conformité enfichables |

Les six sont déployés de manière atomique par le `EwpgTREXFactory` via `AssetTokenFactory`.

## Étape 1 — Déployer la suite d'usine

Assurez-vous que `AssetTokenFactory` et `EwpgTREXFactory` sont déployés conformément à [Déploiement de contrats](./deploying-contracts.md). Confirmez que l'adresse d'usine est définie dans `.env` et que le backend l'a chargée :

```bash
curl http://localhost:48080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Étape 2 — Déployer le ClaimIssuer du registre et l'enregistrer comme émetteur de confiance

Le backend émet les attestations KYC/AML via un **contrat** ONCHAINID `ClaimIssuer`, un par chaîne, dont la clé MANAGEMENT est le signataire du registre utilisé par le backend. Le portefeuille (wallet) signataire ne peut pas lui-même être l'émetteur : `addClaim` d'ONCHAINID appelle `isClaimValid` sur l'émetteur, ce qui échoue (revert) pour un simple portefeuille ; de telles attestations n'atteignent donc jamais la chaîne.

```bash
cd contracts
REGISTRY_WALLET_PRIVATE_KEY=$REGISTRY_SIGNER_KEY \
  forge script script/DeployClaimIssuer.s.sol --rpc-url $RPC_URL --broadcast
# Logs "ClaimIssuer : 0x…". The MANAGEMENT key is the broadcasting wallet, i.e. the
# backend's registry signer (default); CLAIM_ISSUER_MANAGEMENT_KEY is an optional override.
```

!!! warning "Clé de gestion = clé de signature « chaude » par défaut"
    Par défaut, le signataire du registre signe les attestations et contrôle en même temps le jeu de clés du ClaimIssuer (`addKey`/`removeKey`) ainsi que ses mises à niveau ; le backend a besoin des droits MANAGEMENT pour appeler `revokeClaimBySignature`. Traitez le signataire du registre comme une clé de grande valeur (adossée à un KMS/HSM en production). `CLAIM_ISSUER_MANAGEMENT_KEY` peut désigner à la place une clé séparée (froide ou multisig) ; celle-ci doit alors exécuter `addKey(keccak256(abi.encode(registrySigner)), 3, 1)` pour que le signataire puisse signer des attestations, et la révocation par le backend échoue (revert) tant que le signataire ne détient pas aussi une clé MANAGEMENT (purpose 1) : les attestations doivent alors être révoquées depuis la clé de gestion.

Définissez `CLAIM_ISSUER_<CHAIN>` (par exemple `CLAIM_ISSUER_ETH_TESTNET`, lié à `registerwerk.contracts.claim-issuer.<chain>`) et redémarrez le backend. Sans cette valeur, le backend refuse l'émission d'attestations et le déploiement de suites T-REX sur cette chaîne (rejet par défaut), au lieu de diffuser des transactions qui échoueraient. Avant chaque `addClaim`, il vérifie également que le signataire détient une clé sur le ClaimIssuer.

Les nouvelles suites déployées par le backend font confiance à ce ClaimIssuer pour les sujets 1 (KYC) et 2 (AML). Pour une suite déployée **avant** cette modification, enregistrez-le une fois (API opérateur, réservée au propriétaire du `TrustedIssuersRegistry` de la suite) :

```bash
curl -X POST http://localhost:48080/api/v1/assets/$ASSET_ID/erc3643/$DEPLOYMENT_ID/trusted-issuers \
  -H "Authorization: Bearer $OPERATOR_JWT" -H "Content-Type: application/json" \
  -d "{\"issuerAddress\": \"$CLAIM_ISSUER\", \"claimTopics\": [1,2]}"
```

Vérifier :

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" $CLAIM_ISSUER --rpc-url $RPC_URL
# Expected: true
```

Pour les dApps de l'écosystème contrôlées via `PermissionOracle`, enregistrez le même ClaimIssuer dans l'`EcosystemTrustedIssuersRegistry`, via l'administration des émetteurs de confiance de l'opérateur du registre.

!!! note
    La révocation d'une attestation la retire de l'identité **et** appelle `revokeClaimBySignature` sur le ClaimIssuer. La seconde étape empêche quiconque de rajouter ultérieurement la signature publiée. Les deux étapes exigent que le signataire du registre détienne la clé MANAGEMENT du ClaimIssuer.

## Étape 3 — Configurer les sujets d'attestation

Le `ClaimTopicsRegistry` répertorie tous les sujets d'attestation requis pour l'éligibilité au transfert :

```bash
cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 1 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 2 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

| Identifiant du sujet | Signification |
|--------------|---------|
| 1 | KYC — vérification d'identité |
| 2 | AML — Contrôle anti-blanchiment d'argent |

Le backend provisionne automatiquement ces sujets lors de la création d'une nouvelle émission T-REX.

## Étape 4 — Enregistrer les contrats ONCHAINID des investisseurs

Lorsqu'un investisseur est intégré, le backend déploie un contrat ONCHAINID pour lui et l'enregistre dans le registre d'identité. Cela se produit automatiquement lorsque vous ajoutez un investisseur à la liste blanche via l'interface de l'opérateur.

Chaque enregistrement exige un pays au format numérique ISO 3166-1. La boîte de dialogue de
l'opérateur le préremplit à partir du pays d'enregistrement KYC de l'entité juridique, et l'API
rejette un pays manquant ou le pays `0`. Dès qu'un pays est bloqué pour un jeton,
`EwpgComplianceModule` rejette les transferts vers un portefeuille (wallet) dont le pays enregistré
est `0`. Le tableau du registre d'identité signale ces portefeuilles par **Country missing** (pays
manquant). Corrigez-les avec `updateCountry(address,uint16)` sur l'Identity Registry.

Pour vérifier que l'ONCHAINID d'un investisseur est enregistré :

```bash
cast call $IDENTITY_REGISTRY \
  "contains(address)(bool)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

Pour rechercher l'adresse ONCHAINID d'un portefeuille :

```bash
cast call $IDENTITY_REGISTRY \
  "identity(address)(address)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
```

## Étape 5 — Émission des attestations KYC/AML

Après l'approbation KYC dans l'interface de l'opérateur, le backend émet automatiquement des attestations sur l'ONCHAINID de l'investisseur :

1. Construit les données d'attestation `abi.encode(topic, scheme=1, claimIssuer, expiresAt, "")`
2. Signe `keccak256(abi.encode(identity, topic, data))` (préfixe EIP-191) avec le signataire du registre
3. Appelle `addClaim(topic, 1, claimIssuer, signature, data, "")` sur le contrat ONCHAINID de l'investisseur, avec le contrat ClaimIssuer de la chaîne comme émetteur

Les attestations incluent une date d'expiration (par défaut : 365 jours). Le backend planifie des e-mails de rappel d'expiration et peut réémettre des attestations lors du renouvellement.

Pour vérifier manuellement les attestations sur un ONCHAINID :

```bash
cast call $INVESTOR_ONCHAINID \
  "getClaimIdsByTopic(uint256)(bytes32[])" 1 \
  --rpc-url $RPC_URL
# Returns array of claim IDs for topic 1 (KYC)
```

## Étape 6 — Modules de conformité

Configurez les modules de conformité par émission à partir de l'interface de l'opérateur sous **Émissions → [émission] → Modules de conformité**.

### Module MaxBalance

Limite le solde maximum de jetons qu'un seul investisseur peut détenir.

Configurer via l'interface de l'opérateur ou directement :

```bash
cast send $MAX_BALANCE_MODULE \
  "setMaxBalance(address,uint256)" $TOKEN_ADDRESS 100000 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Module MaxInvestors

Plafonne le nombre total de détenteurs de jetons distincts (utile pour les limites d'exemption du règlement D) :

```bash
cast send $MAX_INVESTORS_MODULE \
  "setMaxInvestors(address,uint256)" $TOKEN_ADDRESS 499 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### Module CountryRestrict

Bloque les investisseurs des codes de pays numériques ISO 3166-1 spécifiés :

```bash
# Block US (840) and CN (156)
cast send $COUNTRY_RESTRICT_MODULE \
  "batchRestrictCountries(address,uint16[])" \
  $TOKEN_ADDRESS "[840,156]" \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### EwpgComplianceModule (module propre à Registerwerk)

`EwpgComplianceModule` réunit le nombre maximal d'investisseurs, le solde maximal par investisseur,
les pays bloqués, le délai minimal entre transferts (cooldown) et l'exemption des pools de
nominees. Tous ses paramètres sont stockés par contrat `ModularCompliance`. Ses setters prennent
cette adresse de conformité comme premier argument, par exemple
`setMaxInvestors(address compliance, uint256)`.

- **Qui peut le configurer.** Seul le propriétaire du contrat de conformité peut appeler un
  setter, ou la conformité elle-même via `callModuleFunction`. Tout autre appelant échoue avec
  `CallerNotComplianceAdmin`. T-REX transfère la propriété en deux étapes : après le déploiement
  d'une suite, le portefeuille du registre n'est donc que propriétaire *en attente*. Le backend
  appelle `acceptOwnership()` sur la conformité au moment du déploiement. Si cette étape a échoué,
  il la rejoue avant la modification suivante d'un module de conformité.
- **Ajout depuis le backend.** Le backend lie le module, envoie les setters et relit le résultat
  avec `getConfig(address)` et `isCountryBlocked(address,uint16)`. Il n'écrit la ligne en base
  que lorsque chaque valeur est on-chain. Si la configuration échoue, il délie le module et
  signale l'erreur.
- **« Investisseur » signifie ONCHAINID.** Les soldes et le nombre d'investisseurs sont cumulés
  par identité : plusieurs portefeuilles liés à un même ONCHAINID partagent un plafond de solde et
  comptent pour un seul investisseur. Les transferts entre deux portefeuilles de la même identité
  sont toujours autorisés.
- **Pays inconnu.** Tant qu'au moins un pays est bloqué, un destinataire sans pays enregistré
  (`0`) est rejeté.

#### Migrer une suite en production vers le module corrigé

Les contrats déployés avant ce correctif utilisent l'ancien module. Dans celui-ci, **n'importe
qui** peut modifier les paramètres, et les limites s'appliquent par portefeuille et non par
identité. Le module n'est pas évolutif (upgradeable) : chaque suite en production doit donc passer
à un module nouvellement déployé.

1. Déployer le nouvel `EwpgComplianceModule`.
2. En tant que propriétaire de la conformité (appelez d'abord `acceptOwnership()` si vous n'êtes
   encore que propriétaire en attente), exécuter `addModule(newModule)` sur la
   `ModularCompliance` de la suite.
3. Configurer le nouveau module à partir des valeurs enregistrées en base
   (`erc3643_compliance_module`) : `setMaxInvestors`, `setMaxBalance`, `setTransferCooldown`,
   `blockCountry` et `setNomineePool`. Ne recopiez pas les valeurs de l'état on-chain de l'ancien
   module : n'importe qui a pu les modifier.
4. Reprendre les titulaires existants :
   `syncHolders(compliance, wallets)` avec chaque portefeuille de `erc3643_identity_registry` pour
   la suite (ainsi que tout autre portefeuille de titulaire connu de l'indexeur). L'appel est
   idempotent : il peut être exécuté par lots et relancé sans risque.
5. Comparer `getConfig(compliance)` à la base, y compris le nombre d'investisseurs par rapport au
   nombre d'ONCHAINID distincts ayant un solde positif.
6. Exécuter `removeModule(oldModule)` sur la conformité.

!!! warning "Un ancien module ne peut pas être lié depuis le backend"
    Après avoir lié un module, le backend relit la configuration avec `getConfig(address)`. Un
    module déployé avant ce correctif ne possède pas `getConfig` : son ajout depuis le backend est
    donc toujours annulé. Ne réessayez pas : redéployez `EwpgComplianceModule` et liez la nouvelle
    instance.

Tant qu'une suite n'est pas migrée, déclenchez une alerte pour tout écart entre
`isCountryBlocked` / les limites de l'ancien module et la base. Signalez également aux opérateurs,
comme tâche `updateCountry`, chaque entrée du registre d'identité ayant le pays `0`, vérifiée
on-chain avec `investorCountry(wallet)`.

## Étape 7 — Rôles d'agent

Le portefeuille du backend du registre doit détenir des rôles d'agent sur chaque jeton déployé pour effectuer les opérations de gestion. Le script de déploiement les accorde automatiquement.

| Rôle | Permet |
|------|--------|
| Agent d'enregistrement d'identité | `registerIdentity`, `updateIdentity`, `deleteIdentity` |
| Agent de jetons | `mint`, `burn`, `freezePartialTokens`, `forcedTransfer` |
| Agent de conformité | `addModule`, `removeModule`, `callModuleFunction` |

Pour accorder manuellement des rôles d'agent (si nécessaire) :

```bash
cast send $IDENTITY_REGISTRY \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $TOKEN \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```
