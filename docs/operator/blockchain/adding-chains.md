---
title: Adding New Chains
---

# Adding New Chains

Backend chain clients can be registered at runtime. EVM indexing also requires graph-node network
configuration and a supported deploy target with explicit contract sources.

## Supported chain types

| Type | Examples |
|---|---|
| `EVM` | Ethereum, Polygon, Base, Arbitrum, Fhenix, Inco, any EVM-compatible |
| `SOLANA` | Solana Mainnet, Devnet |

## Adding an EVM chain (full walkthrough)

### 1. Register via Admin API

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "identifier": "OPTIMISM_MAINNET",
    "displayName": "Optimism",
    "chainType": "EVM",
    "networkType": "MAINNET",
    "chainId": 10,
    "rpcUrl": "https://mainnet.optimism.io",
    "wsUrl": "wss://mainnet.optimism.io",
    "blockExplorerUrl": "https://optimistic.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/optimism-mainnet"
  }'
```

### 2. Deploy contracts

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://mainnet.optimism.io \
  --broadcast
```

### 3. Add to graph-node config

See [Indexer Configuration](../configuration/indexers.md) for the TOML and docker-compose changes.

### 4. Restart graph-node with the new network

The deployment admin API cannot accept a manifest for the new network until graph-node has
reloaded its chain configuration:

```bash
docker compose -f indexer/evm/docker-compose.yml up -d --force-recreate graph-node
```

Verify graph-node is healthy before continuing.

### 5. Configure and deploy the subgraph

Configure every `*_OPTIMISM` source described in [The Graph](../indexers/the-graph.md), then:

```bash
SUBGRAPH_VERSION_LABEL=optimism-20260729-01 ./indexer/evm/deploy-subgraph.sh optimism
```

The subgraph is a provisional event projection. It does not establish chain finality, legal
effect, authoritative register state, or deployed-code identity.

### 6. Trigger client refresh

```bash
curl -X POST http://localhost:48000/api/v1/admin/chains/refresh \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

The `BlockchainClientRegistry` creates a new Web3j client for the chain immediately.

## Fallback RPCs

You can configure multiple RPC URLs for failover. The chain config stores `fallback_rpc_urls` as a comma-separated list. If the primary RPC fails, the registry tries fallbacks in order.

```json
{
  "rpcUrl": "https://mainnet.optimism.io",
  "fallbackRpcUrls": "https://optimism.publicnode.com,https://rpc.ankr.com/optimism"
}
```

## RPC node governance and trust

Which RPC node the registry listens to decides which chain state it believes, so nodes are governed like keys:

- **Step-up and second approver.** Adding, updating, enabling, disabling, pinning (`exclusive`) and deleting a node (`/api/v1/admin/chains/{chainId}/nodes...`), and resetting the genesis pin, require a step-up token and a second approver (`X-Dual-Control-Token`, reason `RPC_NODE_CHANGE`). The audit event `RPC_NODE_*` records the acting operator, the second approver and the old and new URL; secrets in URLs (userinfo, query values, key-like path segments) are redacted.
- **https only.** Node URLs must use `https`/`wss`. Plain `http`/`ws` is accepted only for loopback or private hosts and only with `registerwerk.rpc.allow-insecure-private=true` (demo stacks). `chain_config.rpc_allowed_hosts` (comma-separated, `*.example.org` allowed) optionally restricts the hosts of a chain. The rules apply when a node is added or its URL changes.
- **Chain identity.** On add, and on every health round, `eth_chainId` must equal the pinned `chain_config.chain_id` and the genesis block hash must equal `chain_config.genesis_hash` (Solana: `getGenesisHash`). The first matching node pins the genesis hash. A mismatching node is refused on add; later it is marked unhealthy with reason `CHAIN_MISMATCH` and is never routed to, not even as a last resort. After a legitimate devnet reset, clear the pin with `POST /api/v1/admin/chains/{chainId}/nodes/genesis-pin/reset` (step-up and second approver); the next health round pins the new genesis.
- **Best block is a median.** Lag is measured against the median height of the routing candidates (the lower one of two), not the maximum. A node ahead of that reference by more than `registerwerk.rpc.max-plausible-jump-blocks` (default 1000) is quarantined (`IMPLAUSIBLE_HEIGHT`).
- **Fast failover.** A node becomes unhealthy on its first failed probe and needs two consecutive good probes to return (`health_reason` `RECOVERING`). With two or more routable nodes, reads that fail with a transport error are retried on the next-ranked node within the same call (at most three attempts); the failed node is ranked last for 30 seconds. Transactions are broadcast to one node only. After a node accepted a broadcast, reads prefer that node for two minutes (read-your-writes). Multi-call lending reads (quotes, position refresh) run at one block number, and a lending health factor is only reported reliable when the routed node is at most one block behind.
- **Second-source confirmation.** Before a registry-mutating transaction (forced transfer, burn, mint, freeze, pause, claims, identity registry, ownership, roles; configurable with `registerwerk.blockchain.tx.second-source-methods`) is completed, at least two healthy nodes must return the same block hash, recipient contract and sender via `eth_getTransactionByHash`. A disagreement holds the transaction (`registerwerk.confirmation.second_source_mismatch`, ERROR log). With fewer than two healthy nodes the transaction proceeds and `registerwerk.confirmation.single_source` counts it. Fee data of the primary node is cross-checked with a second node: the fee is `min(A, B x 1.5)`.
- **Finality parameters** come from `chain_config` and `registerwerk.blockchain.tx.*` only. A chaincache node stays a probe source for block hashes, but its self-declared SAFE/FINALIZED level is capped by the configured depth or tags. Which finality level legally suffices for an entry per chain model is an open decision (T4-08).
- **Chain id must be pinned.** Signing refuses every transaction on an EVM chain whose `chain_config.chain_id` is empty (`chain id unpinned`). At startup the backend logs an ERROR listing enabled EVM chains that have CONFIRMED deployments but no chain id, and exposes the gauge `registerwerk_evm_chains_unpinned` (alert `EvmChainIdUnpinned`). There is deliberately no automatic backfill: set the network's id explicitly (`PATCH /api/v1/admin/chains/{id}` with `chainId`) before the next transaction.
