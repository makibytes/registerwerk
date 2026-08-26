---
title: Prerequisites
---

# Prerequisites

## Server requirements

| Component | Minimum | Recommended |
|---|---|---|
| CPU | 2 cores | 4+ cores |
| RAM | 4 GB | 16 GB (graph-node needs ~4 GB) |
| Disk | 50 GB SSD | 200 GB NVMe |
| OS | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

## Software

| Tool | Version | Notes |
|---|---|---|
| Docker Engine | 25+ | |
| Docker Compose | v2.24+ | Plugin, not standalone |
| Java JDK | 25 | Eclipse Temurin recommended |
| Node.js | 22 LTS | For frontend builds |
| Foundry | Latest stable | `curl -L foundry.paradigm.xyz \| bash` |
| PostgreSQL | 17 | Provided via Docker; external also supported |

## External services

- **OAuth2 / OIDC provider** — Microsoft Entra ID or self-managed Keycloak
- **SMTP server** — for onboarding and KYC notification emails
- **S3-compatible storage** — for KYC documents ≥5 MB (AWS S3, MinIO, Cloudflare R2)
- **EVM RPC endpoints** — per chain (Infura, Alchemy, QuickNode, or self-hosted)
- **Solana RPC** — with Geyser/Yellowstone support (Helius, Triton, or self-hosted validator)

## Network ports

| Service | Port | Access |
|---|---|---|
| Operator frontend | 44200 | Public — opened directly, never through Kong |
| Customer frontend | 44201 | Public — opened directly; only its own API calls route through Kong |
| Kong proxy | 48000 / 48443 | Public — customer-API HTTP/HTTPS traffic only, DB-less, no admin GUI |
| Kong admin API | 48001 | Loopback only — `docker exec`/SSH tunnel, never expose publicly |
| Documentation (opt-in, `docs` profile) | 48003 | Configurable bind address |
| Chaincache Sepolia/Base (opt-in) | 48090 / 48091 | Loopback only |
| Disposable Anvil | 48545 | Configurable bind address; container-only RPC remains `anvil:8545` |
| zama-relayer (opt-in, `confidential` profile) | 43005 | Loopback only |
| Backend (direct) | 48080 | Loopback by default (operator frontend and Kong use `backend:8080` internally) |
| graph-node GraphQL | 8000 | Internal only |
| graph-node admin | 8020 | Internal only |
| PostgreSQL | 45432 | Loopback only; containers use `postgres:5432` internally |
