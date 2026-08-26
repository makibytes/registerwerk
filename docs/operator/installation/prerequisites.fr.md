---
title: Conditions préalables
---

# Conditions préalables

## Configuration requise pour le serveur

| Composant | Minimum | Recommandé |
|---|---|---|
| CPU | 2 cœurs | 4+ cœurs |
| RAM | 4 Go | 16 Go (graph-node a besoin d'environ 4 Go) |
| Disque | 50 Go SSD | 200 Go NVMe |
| Système d'exploitation | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

## Logiciel

| Outil | Version | Remarques |
|---|---|---|
| Moteur Docker | 25+ | |
| Docker Compose | v2.24+ | Plugin, pas autonome |
| Java JDK | 25 | Eclipse Temurin recommandé |
| Node.js | 22 LTS | Pour les versions frontend |
| Foundry | Dernière stable | `curl -L foundry.paradigm.xyz \| bash` |
| PostgreSQL | 17 | Fourni via Docker ; externe également pris en charge |

## Services externes

- **Fournisseur OAuth2 / OIDC** — ID Microsoft Entra ou Keycloak autogéré
- **Serveur SMTP** — pour les e-mails d'intégration et de notification KYC
- **Stockage compatible S3** — pour les documents KYC ≥5 Mo (AWS S3, MinIO, Cloudflare R2)
- **Points de terminaison EVM RPC** — par chaîne (Infura, Alchemy, QuickNode ou auto-hébergé)
- **Solana RPC** — avec prise en charge Geyser/Yellowstone (Helius, Triton ou validateur auto-hébergé)

## Ports réseau

| Service | Port | Accès |
|---|---|---|
| Frontend opérateur | 44200 | Public — ouvert directement, jamais via Kong |
| Frontend client | 44201 | Public — ouvert directement ; seuls ses propres appels API transitent par Kong |
| proxy Kong | 48000 / 48443 | Public — trafic API client HTTP/HTTPS uniquement, sans base de données, sans interface graphique d'administration |
| API d'administration de Kong | 48001 | Loopback uniquement — tunnel `docker exec`/SSH, ne jamais exposer publiquement |
| Documentation (profil `docs` optionnel) | 48003 | Adresse d'écoute configurable |
| Chaincache Sepolia/Base (optionnel) | 48090 / 48091 | Loopback uniquement |
| Anvil jetable | 48545 | Adresse d'écoute configurable ; reste `anvil:8545` en interne |
| zama-relayer (opt-in, `--profile confidential`) | 43005 | Interne uniquement |
| Backend (direct) | 48080 | Interne uniquement (le frontend opérateur et, en production, Kong appellent tous deux cela) |
| graph-node GraphQL | 8000 | Interne uniquement |
| administration de graph-node | 8020 | Interne uniquement |
| PostgreSQL | 45432 | Loopback uniquement ; `postgres:5432` en interne |
