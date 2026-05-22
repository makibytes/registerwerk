---
title: Ethereum & EVM Chains
description: EVM-compatible blockchain support — Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism.
---

# Ethereum & EVM Chains

Registerwerk supports all major EVM-compatible chains using a single codebase. The [ERC token standards](../token-standards/index.md) deploy identically across all EVM chains; only the RPC endpoint and chain ID change.

---

## Supported EVM chains

| Chain | Chain enum | Network | Chain ID | Notes |
|---|---|---|---|---|
| Ethereum | `ETHEREUM` | MAINNET | 1 | Highest security, highest gas cost |
| Ethereum | `ETHEREUM` | TESTNET | 11155111 (Sepolia) | Development/testing |
| Polygon | `POLYGON` | MAINNET | 137 | Low gas, fast finality |
| Polygon | `POLYGON` | TESTNET | 80002 (Amoy) | Development/testing |
| Base | `BASE` | MAINNET | 8453 | Coinbase L2, low gas |
| Base | `BASE` | TESTNET | 84532 (Sepolia) | Development/testing |
| Arbitrum | `ARBITRUM` | MAINNET | 42161 | Optimistic rollup, EVM-equivalent |
| Arbitrum | `ARBITRUM` | TESTNET | 421614 (Sepolia) | Development/testing |
| Avalanche | `AVALANCHE` | MAINNET | 43114 | C-Chain, high throughput |
| Avalanche | `AVALANCHE` | TESTNET | 43113 (Fuji) | Development/testing |
| Optimism | `OPTIMISM` | MAINNET | 10 | OP Stack L2 |
| Optimism | `OPTIMISM` | TESTNET | 11155420 (Sepolia) | Development/testing |

---

## Client library: Web3j

Registerwerk uses **Web3j** (Java library) for all EVM chain interactions. Key operations:

| Operation | Web3j method | Used in |
|---|---|---|
| Deploy contract | `web3j.ethSendRawTransaction` | All deployment services |
| Read state | `contract.call()` | `TokenAdminService`, indexer |
| Send transaction | `contract.send()` | All admin operations |
| Estimate gas | `web3j.ethEstimateGas` | Fee estimation |
| Subscribe to events | `web3j.ethLogFlowable` | EVM indexer |

The `Web3jClientFactory` bean wraps `Web3j.build(new HttpService(rpcUrl))`. For production, it is recommended to use WebSocket endpoints where available (subscribe to events without polling).

---

## RPC node health

The `RpcNodeHealthService` (`blockchain/internal/`) runs every 60 seconds and checks each registered RPC node:

1. Calls `eth_blockNumber` — measures response time and lag from best (highest block)
2. Updates `RpcNode.healthy`, `RpcNode.consecutiveFailures`, `RpcNode.lagFromBest`
3. Calls `BlockchainClientRegistry.refreshFromNodes()` with the updated states

This means the registry always routes to the fastest, most current node. When a node falls behind by more than a configurable threshold (`rpcNode.maxLagBlocks`), it is marked unhealthy and traffic is diverted to healthy alternatives.

---

## Multi-node configuration

For production deployments, configure multiple RPC providers per chain for high availability:

```yaml
# application.yml (example)
registerwerk:
  evm:
    chains:
      ethereum:
        mainnet:
          rpcUrl: https://eth-mainnet.infura.io/v3/${INFURA_KEY}
```

Additional nodes are added via the admin API (`POST /api/v1/chain-configs/{id}/rpc-nodes`). Setting `exclusive=true` on premium nodes ensures only those nodes are used when healthy.

---

## Gas fee strategy

All EVM transactions use EIP-1559 (dynamic fee) by default:

- `maxFeePerGas` = `baseFee × 1.2` (20% buffer above base)
- `maxPriorityFeePerGas` = configurable per chain (default: 1 Gwei for Ethereum, 30 Gwei for Polygon)
- Gas limit estimated per transaction type (deployment uses `eth_estimateGas`, admin ops use fixed limits with 20% buffer)

The operator wallet must hold sufficient native token (ETH, MATIC, etc.) to pay gas fees. The `WalletBalanceService` checks wallet balances every 5 minutes and emits a notification if a wallet falls below the configurable `minGasWarningThreshold`.
