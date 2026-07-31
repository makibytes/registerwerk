---
title: Voraussetzungen
---

# Voraussetzungen

## Serveranforderungen

| Komponente | Minimum | Empfohlen |
|---|---|---|
| CPU | 2 Kerne | 4+ Kerne |
| RAM | 4 GB | 16 GB (graph-node benötigt ~4 GB) |
| Festplatte | 50 GB SSD | 200 GB NVMe |
| Betriebssystem | Ubuntu 22.04+ | Ubuntu 24.04 LTS |

## Software

| Werkzeug | Version | Hinweise |
|---|---|---|
| Docker Engine | 25+ | |
| Docker Compose | v2.24+ | Plugin, nicht eigenständig |
| Java JDK | 25 | Eclipse Temurin empfohlen |
| Node.js | 22 LTS | Für Frontend-Builds |
| Foundry | Neueste stabile Version | `curl -L foundry.paradigm.xyz \| bash` |
| PostgreSQL | 17 | Über Docker bereitgestellt; extern ebenfalls unterstützt |

## Externe Dienste

- **OAuth2-/OIDC-Anbieter** – Microsoft Entra ID oder selbstverwaltetes Keycloak
- **SMTP-Server** – für Onboarding- und KYC-Benachrichtigungs-E-Mails
- **S3-kompatibler Speicher** – für KYC-Dokumente ≥5 MB (AWS S3, MinIO, Cloudflare R2)
- **EVM-RPC-Endpunkte** – pro Chain (Infura, Alchemy, QuickNode oder selbstgehostet)
- **Solana-RPC** – mit Geyser-/Yellowstone-Unterstützung (Helius, Triton oder selbstgehosteter Validator)

## Netzwerk-Ports

| Dienst | Port | Zugriff |
|---|---|---|
| Operator-Frontend | 4200 | Öffentlich – direkt geöffnet, niemals über Kong |
| Kunden-Frontend | 4201 | Öffentlich – direkt geöffnet; nur seine eigenen API-Aufrufe werden über Kong weitergeleitet |
| Kong-Proxy | 8000 | Öffentlich – nur Kunden-API-Verkehr, DB-los, keine Admin-GUI |
| Kong-Admin-API | 8001 | Nur Loopback – `docker exec`/SSH-Tunnel, nie öffentlich zugänglich machen |
| zama-relayer (opt-in, `--profile confidential`) | 3005 | Nur intern |
| Backend (direkt) | 8080 | Nur intern (wird sowohl vom Operator-Frontend als auch, in Produktion, von Kong aufgerufen) |
| graph-node GraphQL | 8000 | Nur intern |
| graph-node-Admin | 8020 | Nur intern |
| PostgreSQL | 5432 | Nur intern |
