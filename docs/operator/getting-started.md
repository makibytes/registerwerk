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
KONG_DB_PASSWORD=kong
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant>/v2.0
ETH_SEPOLIA_RPC=https://rpc.sepolia.org
```

## 2 — Start infrastructure

```bash
docker compose up -d
```

## 3 — Verify health

```bash
# Backend
curl http://localhost:8080/actuator/health

# Kong proxy
curl http://localhost:8000/api/v1/public/chains

# Konga UI
open http://localhost:1337
```

## 4 — Deploy contracts to Sepolia testnet

```bash
cd contracts
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<your-key>
forge script script/DeployTestnet.s.sol --rpc-url $ETH_SEPOLIA_RPC --broadcast
```

Note the printed `AssetTokenFactory` address.

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

```bash
FACTORY_ADDRESS_SEPOLIA=0xYourFactory ./indexer/evm/deploy-subgraph.sh sepolia
docker compose -f indexer/evm/docker-compose.yml up -d
```

## 7 — Open the operator frontend

```bash
cd frontend-operator && npm install && npm start
open http://localhost:4200
```

You now have a running registry connected to Ethereum Sepolia. Continue to [Installation](installation/prerequisites) for production setup.
