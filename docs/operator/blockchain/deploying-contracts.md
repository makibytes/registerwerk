---
id: deploying-contracts
title: Deploying Contracts
sidebar_position: 1
---

# Deploying Smart Contracts

## Overview

All contracts live in `contracts/` and are compiled with [Foundry](https://book.getfoundry.sh/).

### Contract architecture

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

## Build

```bash
cd contracts
forge build
```

Compiled artifacts land in `contracts/out/`. The Maven `web3j-maven-plugin` reads these to generate Java wrappers.

## Test

```bash
forge test -vvv
forge coverage
```

Target: ≥80% line coverage.

## Deploy to testnet

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Deploy to mainnet

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## CREATE2 determinism

`AssetTokenFactory` uses `CREATE2` with salt `keccak256(abi.encode(assetId, tokenStandard))`. This means:
- The backend can pre-compute the contract address **before** the transaction is mined
- The address is stored as `PENDING` in `asset_deployment` immediately
- Deployment is idempotent — re-running the same deploy will produce the same address

## Upgrading compliance modules

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```
