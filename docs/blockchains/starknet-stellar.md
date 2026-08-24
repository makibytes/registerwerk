---
title: StarkNet & Stellar
description: StarkNet (Cairo ERC-3525) and Stellar (native asset) blockchain support status and configuration.
---

# StarkNet & Stellar

Registerwerk contains working Starknet and Stellar integrations with explicit operational limits.
Neither integration should be treated as production-validated without network-specific testing.

---

## StarkNet

StarkNet is a ZK-rollup on Ethereum using the **Cairo** smart contract language. It offers Ethereum-equivalent security with significantly lower transaction costs.

### Supported token types

| Token enum | Description |
|---|---|
| `STARKNET_ERC20` | Cairo ERC-20 equivalent |
| `STARKNET_ERC3525` | Cairo ERC-3525 semi-fungible — tranched bonds |

### Status

`StarknetTokenService` submits signed Invoke v3 transactions through the Universal Deployer
Contract. Deployment confirmation waits for `ACCEPTED_ON_L1`, and
`StarknetTransferSyncService` indexes ERC-20/ERC-3525 transfer events.

The default ERC-20 and ERC-3525 class hashes are zero and fail fast. Before deploying:

1. Compile the Cairo contracts under `contracts/cairo/`
2. Declare the contract class: `starkli declare target/dev/EwpgERC3525.json`
3. Set `registerwerk.chains.starknet.erc20-class-hash` and/or
   `registerwerk.chains.starknet.erc3525-class-hash`

The integration uses Starknet JSON-RPC and the operator wallet configured for
`Chain.STARKNET` plus `Network.MAINNET/TESTNET`.

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

1. A holder creates a trustline for the issuer and asset code
2. The issuing account sends the asset to the holder account via a `Payment` operation
3. Balances are stored natively in Stellar account ledger entries

In Registerwerk:
- `AssetDeployment.contractAddress` stores the Stellar **issuing account address** (Stellar public key)
- `StellarAssetService` builds and signs the required XDR and submits it through the **Horizon API**

### Status

`StellarAssetService` records the Registerwerk asset ID with a signed `ManageData` transaction
and implements clawback and trustline authorization controls. It does not create holder
trustlines or distribute an initial balance. `StellarTransferSyncService` indexes payments that
touch the issuer account; direct holder-to-holder transfers are outside its current coverage.
Stellar deployments also have no automated chain-confirmation path in `AssetDeploymentService`.
