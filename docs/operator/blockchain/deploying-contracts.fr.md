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
