---
id: the-graph
title: The Graph (EVM Indexer)
sidebar_label: The Graph
sidebar_position: 1
---

# The Graph — EVM indexing

Registerwerk uses `graph-node` to build provisional, event-derived projections for configured
EVM contracts. Subgraph entities are not chain-finality attestations, legal register entries,
legal settlement evidence, or proof of deployed-code identity. Reconcile the configured chain,
confirmations, contract deployment and authoritative legal register before relying on them.

## Install and verify

```bash
cd indexer/evm/subgraph
npm install
npm test
```

`npm test` checks checked-in ABI/event parity against Forge artifacts, tests manifest rendering,
runs Graph code generation and compiles every mapping.

## Required deployment configuration

The deploy target selects an environment suffix:

| Target | Graph network | Suffix |
|---|---|---|
| `mainnet` | `mainnet` | `MAINNET` |
| `sepolia` | `sepolia` | `SEPOLIA` |
| `polygon` | `polygon` | `POLYGON` |
| `polygon-amoy` | `polygon-amoy` | `POLYGON_AMOY` |
| `base` | `base` | `BASE` |
| `base-sepolia` | `base-sepolia` | `BASE_SEPOLIA` |
| `arbitrum-one` | `arbitrum-one` | `ARBITRUM` |
| `arbitrum-sepolia` | `arbitrum-sepolia` | `ARBITRUM_SEPOLIA` |
| `avalanche` | `avalanche` | `AVALANCHE` |
| `avalanche-fuji` | `avalanche-fuji` | `AVALANCHE_FUJI` |
| `optimism` | `optimism` | `OPTIMISM` |
| `optimism-sepolia` | `optimism-sepolia` | `OPTIMISM_SEPOLIA` |

For each suffix, configure the four singleton sources below. Their start block defaults to zero,
but operators should always use the actual deployment block to make replay scope explicit. Each
source has independent provenance: do not copy one factory's block into the other source fields
unless the deployment receipts actually prove they share that block.

```dotenv
ASSET_TOKEN_FACTORY_ADDRESS_SEPOLIA=0x...
ASSET_TOKEN_FACTORY_START_BLOCK_SEPOLIA=120
REPO_MARKET_FACTORY_ADDRESS_SEPOLIA=0x...
REPO_MARKET_FACTORY_START_BLOCK_SEPOLIA=130
DVP_SETTLEMENT_ADDRESS_SEPOLIA=0x...
DVP_SETTLEMENT_START_BLOCK_SEPOLIA=140
CONFIDENTIAL_FACTORY_ADDRESS_SEPOLIA=0x...
CONFIDENTIAL_FACTORY_START_BLOCK_SEPOLIA=150
```

BondDesk, Stablecoin AMM, and RepoVault deployments are not reliably factory-discoverable. List
every instance explicitly as `address@deploymentBlock`, separated by commas:

```dotenv
BOND_DESK_INSTANCES_SEPOLIA=0xDesk1@123,0xDesk2@456
STABLECOIN_AMM_INSTANCES_SEPOLIA=0xAmm1@123,0xAmm2@456
REPO_VAULT_INSTANCES_SEPOLIA=0xVault1@123,0xVault2@456
```

If the operator configures zero instances for a role, set its list to exactly `NONE`. This is an
operator assertion about configuration, not evidence that no deployment exists on-chain. An unset
or empty list fails closed. The renderer also rejects zero addresses, malformed blocks, and an
address reused by any other static source.

## Deploy

```bash
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

Use `SUBGRAPH_VALIDATE_ONLY=true` to render, generate and compile without submitting a graph-node
deployment. `all` processes every target in the table and therefore requires configuration for
every suffix. A real deployment also requires `SUBGRAPH_VERSION_LABEL`; choose a new label for
every deployment to that graph name. The wrapper rejects an absent label; the operator must ensure
that the supplied label is new. Keep the previous version available until the replacement has
caught up and passed independent event-range reconciliation.

The AssetTokenFactory creates dynamic token data sources from `TokenDeployed` and `VaultDeployed`.
The RepoMarketFactory similarly creates RepoMarket sources from `MarketCreated`. New instances of
the three explicitly listed contract types require a list update and subgraph redeployment.
Factory-emitted addresses, asset IDs, token references, oracle parameters, and observation blocks
are stored as event claims. They do not verify deployed bytecode, deployment provenance, or
linkage to an application database record.

## Projection migration and replay

The ERC-3525 owner/slot notional and ERC-7540 request lifecycle entities require event order from
contract deployment. Existing `HolderBalance` rows for ERC-3525 counted token IDs and cannot be
converted into notional. Do not copy them into `Erc3525OwnerSlotBalance`.

For this schema revision, deploy a fresh subgraph version and replay each source from its true
deployment block. An `INCOMPLETE` projection cannot reconstruct missing owners, slots, values,
request types, or prior RepoVault market configuration. Every RepoVault projection remains
`INCOMPLETE` unless deployment provenance and full replay are proven outside this subgraph; merely
observing the first event at a configured static address does not provide that proof. Keep the old
deployment available for rollback until the new projection has reached the chain head and has been
reconciled independently.

RepoVault `Allocated` and `Deallocated` amounts are projected only as signed net cash flow.
Deallocation can exceed earlier allocation because of interest or loss realization, so this value
is not outstanding principal, scaled market position, or NAV, and a negative total is not by itself
an inconsistency.

## Monitor and query

```bash
curl -s http://localhost:8030/graphql \
  -d '{"query":"{indexingStatuses{subgraph synced health chains{network latestBlock{number}}}}"}' \
  | jq '.data.indexingStatuses[]'
```

`synced: true` describes graph-node progress only; it is not a finality or legal-effect signal.
Query a deployment at `http://localhost:8000/subgraphs/name/<subgraph-name>`.

Common failures are RPC throttling, insufficient memory, stale Forge artifacts, ABI drift, or a
missing static-source configuration. Run `npm test` before diagnosing a deployment failure.
