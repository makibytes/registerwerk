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

## Étape 2 — Configurer le registre en tant qu'émetteur de confiance

Le portefeuille de l'opérateur principal du registre doit être enregistré dans le `TrustedIssuersRegistry` afin qu'il puisse émettre des attestations KYC/AML. Cette opération est effectuée une fois par déploiement en usine.

```bash
cast send $TRUSTED_ISSUERS_REGISTRY \
  "addTrustedIssuer(address,uint256[])" \
  $REGISTRY_OPERATOR_ADDRESS "[1,2]" \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Paramètres :
- Premier argument : adresse de l'opérateur de registre (portefeuille de déploiement)
- Deuxième argument : tableau d'ID de sujet d'attestation que cet émetteur est autorisé à signer (1 = KYC, 2 = AML)

Vérifier :

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" \
  $REGISTRY_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

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

1. Construit une attestation avec l'ID de sujet, l'adresse de l'émetteur et un hachage de l'enregistrement de vérification KYC
2. Signe l'attestation avec la clé privée de l'opérateur
3. Appelle `addClaim` sur le contrat ONCHAINID de l'investisseur

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
