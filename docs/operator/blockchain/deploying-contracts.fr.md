---
title: Déploiement de contrats
---

# Déploiement de contrats intelligents

## Présentation

Tous les contrats résident dans `contracts/` et sont compilés avec [Foundry](https://book.getfoundry.sh/).

### Architecture du contrat

```
AssetTokenFactory (CREATE2 factory)
├── EwpgERC20       — Fungible security token
├── EwpgERC721      — Non-fungible security token
├── EwpgERC1155     — Multi-token (e.g. bond tranches)
└── EwpgERC3643     — Regulated security token (T-REX / ERC-3643)
    └── EwpgTREXFactory — T-REX suite deployer
        ├── Token
        ├── IdentityRegistry
        ├── IdentityRegistryStorage
        ├── ModularCompliance
        ├── ClaimTopicsRegistry
        └── TrustedIssuersRegistry

ConfidentialERC3643 — Encrypted balances on Fhenix / Inco
```

## Construire

```bash
cd contracts
forge build
```

Les artefacts compilés atterrissent dans `contracts/out/`. Le Maven `web3j-maven-plugin` les lit pour générer des wrappers Java.

## Test

```bash
forge test -vvv
forge coverage
```

Cible : couverture de ligne ≥ 80 %.

## Déployer sur testnet

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Déployer sur le réseau principal

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## Déterminisme CREATE2

`AssetTokenFactory` utilise `CREATE2` avec le sel `keccak256(abi.encode(assetId, tokenStandard))`. Cela signifie :
- Le backend peut pré-calculer l'adresse du contrat **avant** que la transaction soit minée
- L'adresse est stockée immédiatement sous `PENDING` dans `asset_deployment`
- Le déploiement est idempotent : réexécuter le même déploiement produira la même adresse

Comme le sel et l'initcode sont dérivés de calldata publiques, les factories déployées avant que
`deployToken`/`deployVault` ne soient réservées au registre permettent à n'importe qui de rejouer
en premier les calldata du registre et de faire échouer la transaction du registre lui-même
(`CREATE2 failed`). Le backend vérifie donc `predictAddress` avant l'envoi puis à nouveau après un
revert : si un contrat se trouve déjà à cette adresse et que ses `assetId()` et `registry()`
correspondent, il adopte ce contrat et sa transaction de création au lieu d'échouer. Si le contrat
présent ne nous appartient pas (par exemple parce que l'autorité du registre a été transférée), le
déploiement échoue avec une erreur indiquant l'adresse.

!!! note "Déploiement de la factory réservée au registre"
    `registryWallet` est immuable et le `bindFactory` de chaque deployer ne peut être appelé
    qu'une fois : la restriction ne s'applique donc qu'à une nouvelle génération de factory. Pour
    chaque chaîne : déployer les six deployers et une nouvelle `AssetTokenFactory`
    (`script/Deploy.s.sol` / `DeployL2.s.sol` / `DeployTestnet.s.sol`), puis mettre à jour
    `registerwerk.contracts.asset-token-factory.<chain>` et redémarrer le backend. Les jetons
    existants conservent leurs adresses (elles sont stockées dans `asset_deployment`) ; les
    anciennes factories encore actives restent protégées par le contrôle d'adoption du backend
    décrit ci-dessus.

## Mise à niveau des modules de conformité

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

## D'une clé de déploiement unique à un multisig/timelock

!!! warning "Aucun script de ce dépôt ne le fait pour vous"
    Chaque fichier `script/Deploy*.s.sol` signe avec l'unique EOA derrière
    `REGISTRY_WALLET_PRIVATE_KEY` et accorde à cette même adresse `DEFAULT_ADMIN_ROLE` +
    `OPERATOR_ROLE` sur `OrgRegistry`, `PermissionRegistry`, `EcosystemTrustedIssuersRegistry`,
    `PermissionOracle` et `DappRegistry`, ainsi que la propriété `Ownable` de chaque jeton créé par
    `AssetTokenFactory` — de façon permanente, **sans étape de transfert**. `UpgradeCompliance.s.sol`
    d'`EwpgBondDesk` est la seule exception : une variable d'environnement facultative
    `NEW_REGISTRY_WALLET` qui déplace la propriété d'un `WhitelistRegistry` fraîchement déployé, et rien
    d'autre. Passer en production sur le réseau principal avec une clé brute qui ne migre jamais signifie
    qu'un seul ordinateur portable compromis peut geler, transférer de force ou réattribuer les
    permissions de tout le registre.

Ceci est un manuel d'exploitation, pas une modification de contrat — le modèle `AccessControl`/`Ownable` déjà présent dans les contrats est exactement ce dont un multisig a besoin ; rien ici n'exige de changement Solidity ni de nouveau déploiement.

### 1. Mettre en place le multisig avant de déployer

Déployez d'abord un [Gnosis Safe](https://safe.global/) (ou équivalent) sur la chaîne cible, avec des signataires nommément désignés sur des portefeuilles matériels distincts — jamais une seconde clé sur la machine qui a exécuté `forge script`. Un seuil de 3 sur 5 est un point de départ raisonnable pour un opérateur de registre ; ajustez-le à votre propre politique de séparation des tâches.

### 2. Déployer avec l'EOA, puis transférer les droits d'administration dans la même session

Exécutez le script de déploiement exactement comme documenté ci-dessus — l'EOA doit signer lui-même les transactions de déploiement, il n'y a aucun moyen de l'éviter avec ces scripts. Immédiatement après, dans la même fenêtre d'exploitation, pour chaque contrat de l'écosystème :

```solidity
// One transaction pair per AccessControl contract (OrgRegistry, PermissionRegistry,
// EcosystemTrustedIssuersRegistry, PermissionOracle, DappRegistry):
grantRole(DEFAULT_ADMIN_ROLE, safeAddress);
grantRole(OPERATOR_ROLE, safeAddress);
// Only after confirming the Safe can exercise both roles (see step 4):
renounceRole(OPERATOR_ROLE, deployerEoa);
renounceRole(DEFAULT_ADMIN_ROLE, deployerEoa);

// For Ownable contracts (AssetTokenFactory-spawned tokens, EwpgBondDesk-style deployments):
transferOwnership(safeAddress);
```

`AssetTokenFactory.registryWallet` est `immutable` — on ne peut pas le rediriger vers le Safe après le déploiement. Si la factory elle-même doit être sous contrôle multisig, le Safe doit être le déployeur de la factory (c'est-à-dire détenir dès le départ le rôle de `REGISTRY_WALLET_PRIVATE_KEY`, via un lot de transactions Safe plutôt qu'une exécution `forge script` par un EOA), et non quelque chose migré après coup.

### 3. Placer un timelock devant le Safe pour les actions à fort impact

Un multisig seul arrête une clé compromise isolée ; il ne prévient pas à l'avance les parties concernées (émetteurs, investisseurs, autres opérateurs) d'un changement. Pour les actions à réelle portée — révoquer la confiance de `EcosystemTrustedIssuersRegistry`, modifier les droits de `PermissionRegistry` à l'échelle de la plateforme, repointer `PermissionOracle` — faites passer les transactions du Safe par un [TimelockController](https://docs.openzeppelin.com/contracts/5.x/api/governance#TimelockController) (proposer → délai obligatoire → exécuter) au lieu de les exécuter directement. Accordez au timelock `DEFAULT_ADMIN_ROLE` et au Safe `PROPOSER_ROLE`/`EXECUTOR_ROLE` sur le timelock, et non `DEFAULT_ADMIN_ROLE` directement sur les contrats cibles.

### 4. Vérifier avant de renoncer à quoi que ce soit

Avant les appels `renounceRole`/`renounceOwnership` de l'étape 2, exécutez une transaction Safe réelle et réversible sur chaque contrat (par exemple un aller-retour d'octroi/retrait de permission sans effet) et confirmez qu'elle aboutit en chaîne avec le seuil de signataires attendu. `renounceRole` est irréversible — perdre simultanément l'accès à l'EOA et à un quorum Safe fonctionnel verrouille définitivement les fonctions d'administration du contrat.

### 5. Retirer la clé de l'EOA

Une fois confirmé que les rôles/la propriété de chaque contrat ont été déplacés, la clé privée de l'EOA de déploiement n'a plus d'usage légitime. Détruisez-la — ne l'archivez pas « au cas où » ; une clé de déploiement archivée est exactement le risque permanent que tout ce processus vise à supprimer.
