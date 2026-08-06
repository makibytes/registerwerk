---
title: Variabili d'ambiente
---

# Variabili d'ambiente { #environment-variables }

Tutta la configurazione viene eseguita tramite variabili d'ambiente. Copia `.env.example` in `.env` e inserisci i valori.

## Database { #database }

| Variabile | Predefinito | Descrizione |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | URL di connessione JDBC |
| `DB_USER` | `registerwerk` | Utente database |
| `DB_PASSWORD` | — | **Obbligatorio** |

## Autenticazione { #authentication }

### Amministrazione integrata (modalità senza IdP) { #built-in-admin-no-idp-mode }

| Variabile | Predefinito | Descrizione |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → modulo nome utente/password nel frontend dell'operatore (FE); `true` → pulsante Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | E-mail dell'utente amministratore precaricato (seed) (solo modalità integrata) |
| `DEFAULT_ADMIN_PASSWORD` | — | Password in testo normale sottoposta ad hashing con BCrypt all'avvio; ruotare modificando e riavviando |
| `JWT_DEV_SECRET` | integrato | Chiave di firma HS256 utilizzata in modalità dev/demo; lasciare non impostato per locale, sovrascrivere nello staging |

### OAuth2 / OIDC (produzione) { #oauth2-oidc-production }

| Variabile | Descrizione |
|---|---|
| `JWT_ISSUER_URI` | URL dell'emittente OIDC: lasciare vuoto per la modalità dev HS256; impostare per la produzione (ad es. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | OIDC ID client utilizzato dal plugin Kong |
| `ENTRA_CLIENT_SECRET` | OIDC segreto client utilizzato dal plugin Kong |
| `ENTRA_ISSUER` | Emittente OIDC configurato nel plugin Kong |

## RPC Blockchain { #blockchain-rpcs }

| Variabile | Catena |
|---|---|
| `ETH_MAINNET_RPC` | Rete principale di Ethereum |
| `ETH_SEPOLIA_RPC` | Ethereum Sepolia |
| `POLYGON_MAINNET_RPC` | Rete principale Polygon |
| `POLYGON_AMOY_RPC` | Polygon Amoy |
| `BASE_MAINNET_RPC` | Rete principale Base |
| `BASE_SEPOLIA_RPC` | Base Sepolia |
| `SOLANA_MAINNET_RPC` | Rete principale Solana |
| `SOLANA_DEVNET_RPC` | Solana Devnet |
| `REGISTRY_WALLET_PRIVATE_KEY` | Chiave del firmatario backend per le operazioni blockchain |
| `REGISTRY_SOLANA_PRIVATE_KEY` | Chiave firmatario Solana opzionale |

## Archiviazione { #storage }

| Variabile | Descrizione |
|---|---|
| `S3_BUCKET` | Nome del bucket S3 per i documenti KYC |
| `S3_ENDPOINT` | URL endpoint compatibile con S3 |
| `S3_ACCESS_KEY` | Chiave di accesso S3 |
| `S3_SECRET_KEY` | Chiave segreta S3 |
| `S3_REGION` | Regione S3 |

I documenti inferiori a 5 MB vengono archiviati in linea come BYTEA in PostgreSQL. I documenti ≥5 MB vengono archiviati in S3.

## Email { #email }

| Variabile | Descrizione |
|---|---|
| `MAIL_HOST` | Host SMTP |
| `MAIL_PORT` | Porta SMTP (predefinita 587) |
| `MAIL_USERNAME` | Nome utente SMTP |
| `MAIL_PASSWORD` | Password SMTP |

## Onboarding { #onboarding }

| Variabile | Descrizione |
|---|---|
| `CUSTOMER_FRONTEND_URL` | Base URL del frontend cliente (per collegamenti email) |
| `FRONTEND_BUILD_ENV` | Destinazione build frontend: `production` o `testnet` |
