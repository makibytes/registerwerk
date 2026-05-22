---
title: StarkNet & Stellar
description: StarkNet (Cairo ERC-3525) and Stellar (native asset) blockchain support status and configuration.
---

# StarkNet & Stellar

StarkNet and Stellar are partially supported in Registerwerk. The infrastructure plumbing (client wiring, deployment service skeletons, token standard enums) is in place, but both chains have **placeholder values** that must be replaced before production use.

---

## StarkNet

StarkNet is a ZK-rollup on Ethereum using the **Cairo** smart contract language. It offers Ethereum-equivalent security with significantly lower transaction costs.

### Supported token types

| Token enum | Description |
|---|---|
| `STARKNET_ERC20` | Cairo ERC-20 equivalent |
| `STARKNET_ERC3525` | Cairo ERC-3525 semi-fungible — tranched bonds |

### Status

⚠️ **The StarkNet class hash is a zero placeholder.** Before deploying StarkNet tokens in production:

1. Compile the Cairo contracts under `contracts/cairo/`
2. Declare the contract class: `starkli declare target/dev/EwpgERC3525.json`
3. Replace the class hash in `StarknetTokenService` configuration with the declared class hash

The `StarknetTokenService` uses a custom Java client (Starknet4j) configured via `Chain.STARKNET` + `Network.MAINNET/TESTNET`.

### Networks

| Network | Network enum | Notes |
|---|---|---|
| StarkNet mainnet | `MAINNET` | Production — class hash required |
| StarkNet Sepolia | `TESTNET` | Development/testing |

---

## Stellar

Stellar is a payments-focused blockchain with native support for **Stellar Assets** — on-chain representations of any currency or instrument.

### Supported token type

| Token enum | Description |
|---|---|
| `STELLAR_ASSET` | Stellar-native issued asset |

### Stellar asset model

Unlike EVM or Solana, Stellar has a built-in asset type at the protocol level. No contract deployment is needed:

1. The **issuing account** creates a trustline from the holder account
2. The issuing account sends the asset to the holder account via a `Payment` operation
3. Balances are stored natively in Stellar account ledger entries

In Registerwerk:
- `AssetDeployment.contractAddress` stores the Stellar **issuing account address** (Stellar public key)
- `StellarAssetService` uses the **Horizon API** (Java SDK) to submit transactions

### Status

⚠️ **Stellar support is a placeholder.** The `StellarAssetService` skeletons are in place but the full implementation (trustline management, compliance, indexer) is not yet complete.

---

## Roadmap note

Both StarkNet and Stellar represent active development areas. The infrastructure exists to enable contributions. Priority considerations:

- **StarkNet ERC-3525**: High value for [Liechtenstein TVTG](../legal/tvtg-li.md) issuers who prefer ZK-proven settlement over optimistic rollups
- **Stellar**: Useful for cross-border payment securities and stablecoins in emerging markets

To contribute an implementation, follow the pattern of the EVM deployment services (`Erc20DeploymentService`, `Erc3525DeploymentService`) and implement the same `TokenDeploymentPort` interface.
