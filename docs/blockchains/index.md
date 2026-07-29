---
title: Supported Blockchains
description: All supported blockchain networks, their capabilities, and how Registerwerk connects to them.
---

# Supported Blockchains

Registerwerk supports eight blockchain types across mainnet and testnet networks. Chain connectivity is managed by the `blockchain` module's `BlockchainClientRegistry`, which selects the best available RPC node for each request.

---

## Quick reference

| Chain type | Token standard(s) | Client library | Networks | Status |
|---|---|---|---|---|
| [Ethereum & EVM](evm.md) | ERC-20/721/1155/3525/3643/4626/7540 | Web3j | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism | Implementation present; production readiness unverified |
| [Confidential EVM](confidential-evm.md) | CONF_ERC20, CONF_ERC3643 | Web3j + Zama SDK | Fhenix, Inco | Implementation present; production readiness unverified |
| [Solana](solana.md) | SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL | Solanaj | mainnet-beta, devnet | Integration present; production readiness unverified |
| [Canton / DAML](canton.md) | DAML_BOND_*, CANTON_TOKEN | DAML Java client | Canton Network, devnet | Optional implementation (`-Pcanton`); production readiness unverified |
| [StarkNet](starknet-stellar.md) | STARKNET_ERC20, STARKNET_ERC3525 | Custom Starknet4j | mainnet, sepolia | ⚠️ Placeholder |
| [Stellar](starknet-stellar.md) | STELLAR_ASSET | Horizon Java SDK | mainnet, testnet | ⚠️ Placeholder |

---

## `BlockchainClientRegistry`

The `BlockchainClientRegistry` (`blockchain/api/`) is the central component for all chain connectivity. It maintains three client tiers for EVM chains:

1. **Node pool** (highest priority) — populated by `RpcNodeHealthService` after each health-check round. Selects the healthiest, lowest-latency node
2. **Dynamic single clients** — one client per enabled `chain_config` row (legacy, refreshed on `ChainConfigUpdatedEvent`)
3. **Static clients** — loaded at startup from `application.yml` properties

### Node selection algorithm

For the node pool, the registry applies this selection logic:

```
1. If any enabled node has exclusive=true → use only exclusive-enabled nodes
2. Otherwise → use all enabled nodes
3. From candidates: prefer healthy nodes with smallest block lag
4. If no healthy candidates → use least-bad (fewest failures, most recent success)
5. If ALL nodes disabled → throw IllegalStateException
```

This provides automatic failover between multiple RPC providers without operator intervention.

---

## Adding a new chain

To add a new EVM-compatible chain:

1. Add the chain to the `Chain` enum in `chain/api/Chain.java`
2. Add the RPC URL to `application.yml` under `registerwerk.evm.chains.<chainName>.<network>.rpcUrl`
3. Deploy the Registerwerk contracts on the new chain (using the existing deployment service)
4. Configure `chain_config` record via the admin API

To add a non-EVM chain requires implementing the corresponding client factory interface and registering the client in `BlockchainConfig`.

---

## Chain identifier format

Chains are identified in the system using `ChainDescriptor(chain, network)`:

```java
new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET)
// → identifier: "ETHEREUM_MAINNET"
```

The `identifier` string is used as the key in dynamic client maps and in `asset_deployment.chain_identifier` for linking deployments to the correct chain.
