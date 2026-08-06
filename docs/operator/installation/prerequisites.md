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
| Operator frontend | 4200 | Public — opened directly, never through Kong |
| Customer frontend | 4201 | Public — opened directly; only its own API calls route through Kong |
| Kong proxy | 8000 | Public — customer-API traffic only, DB-less, no admin GUI |
| Kong admin API | 8001 | Loopback only — `docker exec`/SSH tunnel, never expose publicly |
| zama-relayer (opt-in, `--profile confidential`) | 3005 | Internal only |
| Backend (direct) | 8080 | Internal only (operator frontend and, in prod, Kong both call this) |
| graph-node GraphQL | 8000 | Internal only |
| graph-node admin | 8020 | Internal only |
| PostgreSQL | 5432 | Internal only |
