---
id: getting-started
title: Getting Started (15-Minute Quickstart)
sidebar_position: 1
---

# Getting Started

This guide gets a fully functional eWpG Registry running in 15 minutes using Docker Compose.

## Prerequisites

- Docker 25+ and Docker Compose v2
- Java 25 JDK (for local backend development)
- Node.js 22+ (for frontend development)
- `forge` CLI (Foundry) for smart contract deployment

## 1 — Clone and configure

```bash
git clone https://github.com/your-org/registerwerk.git
cd registerwerk
git submodule update --init --recursive
cp .env.example .env
```

Open `.env` and fill in at minimum:

```dotenv
DB_PASSWORD=registerwerk
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant>/v2.0
ETH_SEPOLIA_RPC=https://rpc.sepolia.org
```

Kong runs DB-less (its declarative config is `gateway/kong.yml`), so no separate Kong
database credentials are needed.

## 2 — Start infrastructure

```bash
docker compose up -d
```

## 3 — Verify health

```bash
# Backend
curl http://localhost:8080/actuator/health

# Kong proxy (customer-API path only — the operator frontend bypasses Kong entirely)
curl http://localhost:8000/api/v1/public/chains

# Kong runs DB-less with no admin GUI. Its admin API is bound to the host's loopback
# only (127.0.0.1:8001) — reach it via docker exec, never expose it publicly:
docker compose exec kong kong health
```

Both frontends are already up too: http://localhost:4200 (operator) and
http://localhost:4201 (customer) — `docker compose up -d` starts them alongside the backend.

## 4 — Deploy contracts to Sepolia testnet

```bash
cd contracts
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<your-key>
forge script script/DeployTestnet.s.sol --rpc-url $ETH_SEPOLIA_RPC --broadcast
```

Record every deployed static contract address and its deployment block, including the factories,
DvP settlement, and any BondDesk, AMM, or RepoVault instances.

## 5 — Register the chain in the backend

```bash
curl -X POST http://localhost:8000/api/v1/admin/chains \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "identifier": "ETHEREUM_SEPOLIA",
    "displayName": "Ethereum Sepolia",
    "chainType": "EVM",
    "networkType": "TESTNET",
    "chainId": 11155111,
    "rpcUrl": "https://rpc.sepolia.org",
    "blockExplorerUrl": "https://sepolia.etherscan.io",
    "graphNodeUrl": "http://graph-node:8000/subgraphs/name",
    "graphSubgraphName": "ewpg/ethereum-sepolia"
  }'
```

## 6 — Start the indexer

Configure all `*_SEPOLIA` variables and explicit instance lists documented in
[The Graph](indexers/the-graph), then:

```bash
# graph-node and IPFS must be ready before the deployment command submits anything
docker compose -f indexer/evm/docker-compose.yml up -d
SUBGRAPH_VERSION_LABEL=sepolia-20260729-01 ./indexer/evm/deploy-subgraph.sh sepolia
```

The index is a provisional event-derived projection, not a chain-finality, legal-register,
settlement, or deployed-code-identity attestation.

## 7 — Open the operator frontend

Already running from step 2 at http://localhost:4200. For hot-reload during frontend
development instead, stop that container and run it locally:

```bash
docker compose stop frontend-operator
cd frontend-operator && npm install && npm start
open http://localhost:4200
```

You now have a running registry connected to Ethereum Sepolia. Continue to [Installation](installation/prerequisites) for production setup.
