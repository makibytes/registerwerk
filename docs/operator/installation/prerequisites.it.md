---
title: Prerequisiti
---

# Prerequisiti { #prerequisites }

## Requisiti del server { #server-requirements }

| Componente | Minimo | Consigliato |
|---|---|---|
| CPU | 2 core | 4+ core |
| RAM | 4 GB | 16 GB (graph-node richiede ~4 GB) |
| Disco | 50 GB SSD | 200 GB NVMe |
| Sistema operativo | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

## Software { #software }

| Strumento | Versione | Note |
|---|---|---|
| Docker Engine | 25+ | |
| Docker Compose | v2.24+ | Plugin, non standalone |
| Java JDK | 25 | Consigliato Eclipse Temurin |
| Node.js | 22 LTS | Per le build dei frontend |
| Foundry | Ultima stabile | `curl -L foundry.paradigm.xyz \| bash` |
| PostgreSQL | 17 | Fornito tramite Docker; è supportata anche un'istanza esterna |

## Servizi esterni { #external-services }

- **Provider OAuth2 / OIDC** — Microsoft Entra ID o Keycloak autogestito
- **Server SMTP** — per le e-mail di onboarding e di notifica KYC
- **Archiviazione compatibile con S3** — per documenti KYC ≥5 MB (AWS S3, MinIO, Cloudflare R2)
- **Endpoint EVM RPC** — per catena (Infura, Alchemy, QuickNode o self-hosted)
- **Solana RPC** — con supporto Geyser/Yellowstone (Helius, Triton o validatore self-hosted)

## Porte di rete { #network-ports }

| Servizio | Porta | Accesso |
|---|---|---|
| Frontend dell'operatore | 44200 | Pubblico — aperto direttamente, mai tramite Kong |
| Frontend cliente | 44201 | Pubblico — aperto direttamente; solo le proprie chiamate API passano attraverso Kong |
| Proxy Kong | 48000 / 48443 | Pubblico — solo traffico HTTP/HTTPS dell'API cliente, senza DB, nessuna GUI di amministrazione |
| API di amministrazione di Kong | 48001 | Solo loopback — `docker exec`/tunnel SSH, non esporre mai pubblicamente |
| Documentazione (profilo opzionale `docs`) | 48003 | Indirizzo di bind configurabile |
| Chaincache Sepolia/Base (opzionale) | 48090 / 48091 | Solo loopback |
| Anvil usa e getta | 48545 | Indirizzo di bind configurabile; internamente resta `anvil:8545` |
| zama-relayer (opt-in, `--profile confidential`) | 43005 | Solo interno |
| Backend (diretto) | 48080 | Solo interno (lo chiamano sia il frontend dell'operatore sia, in produzione, Kong) |
| GraphQL di graph-node | 8000 | Solo interno |
| Amministrazione di graph-node | 8020 | Solo interno |
| PostgreSQL | 45432 | Solo loopback; internamente `postgres:5432` |
