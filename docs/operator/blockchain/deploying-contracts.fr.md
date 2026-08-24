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

## Mise à niveau des modules de conformité

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
