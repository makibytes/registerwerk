---
title: Deploying Contracts
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
export REGISTRY_WALLET_PRIVATE_KEY=0x<key>

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

## From a single deployment key to a multisig/timelock

!!! warning "No script in this repo does this for you"
    Every `script/Deploy*.s.sol` file signs with the single EOA behind
    `REGISTRY_WALLET_PRIVATE_KEY` and grants that same address `DEFAULT_ADMIN_ROLE` +
    `OPERATOR_ROLE` on `OrgRegistry`, `PermissionRegistry`, `EcosystemTrustedIssuersRegistry`,
    `PermissionOracle`, and `DappRegistry`, plus `Ownable` ownership of every token
    `AssetTokenFactory` spawns — permanently, with **no transfer-away step**. `EwpgBondDesk`'s
    `UpgradeCompliance.s.sol` is the sole exception: an optional `NEW_REGISTRY_WALLET` env var
    that moves ownership of a freshly-deployed `WhitelistRegistry`, and nothing else. Shipping
    to mainnet with a raw key that never migrates means one compromised laptop can freeze,
    force-transfer, or re-permission the entire registry.

This is an operational runbook, not a contract change — the `AccessControl`/`Ownable` model
already in the contracts is exactly what a multisig needs; nothing here requires a Solidity
change or new deployment.

### 1. Stand up the multisig before you deploy

Deploy a [Gnosis Safe](https://safe.global/) (or equivalent) on the target chain first, with
signers held by named individuals on separate hardware wallets — never a second key on the
same machine that ran `forge script`. A 3-of-5 threshold is a reasonable starting point for a
registry operator; tune to your own segregation-of-duties policy.

### 2. Deploy with the EOA, then move admin rights in the same session

Run the deploy script exactly as documented above — the EOA has to sign the deployment
transactions themselves, there's no way around that with these scripts. Immediately
afterward, in the same operational window, for every ecosystem contract:

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

`AssetTokenFactory.registryWallet` is `immutable` — it cannot be repointed at the Safe after
deployment. If the factory itself needs multisig control, the Safe must be the deployer of
the factory (i.e. hold `REGISTRY_WALLET_PRIVATE_KEY`'s role from the start, via a Safe
transaction batch rather than an EOA `forge script` run), not something migrated to after the
fact.

### 3. Put a timelock in front of the Safe for high-impact actions

A multisig alone stops a single compromised key; it does not give affected parties (issuers,
investors, other operators) advance warning of a change. For actions with real blast radius —
revoking `EcosystemTrustedIssuersRegistry` trust, changing `PermissionRegistry` grants
platform-wide, re-pointing `PermissionOracle` — route the Safe's transactions through a
[TimelockController](https://docs.openzeppelin.com/contracts/5.x/api/governance#TimelockController)
(propose → mandatory delay → execute) instead of executing directly. Grant the timelock
`DEFAULT_ADMIN_ROLE` and the Safe the `PROPOSER_ROLE`/`EXECUTOR_ROLE` on the timelock, not
`DEFAULT_ADMIN_ROLE` on the target contracts directly.

### 4. Verify before renouncing anything

Before the `renounceRole`/`renounceOwnership` calls in step 2, execute one real, reversible
Safe transaction against each contract (e.g. a no-op permission grant/revoke round-trip) and
confirm it lands on-chain with the expected signer threshold. `renounceRole` is irreversible —
losing simultaneous access to both the EOA and a working Safe quorum permanently locks the
contract's admin functions.

### 5. Retire the EOA key

Once every contract's roles/ownership have been confirmed moved, the deployer EOA's private
key has no further legitimate use. Destroy it — do not archive "just in case"; an archived
deployment key is the same standing risk this whole process exists to remove.
