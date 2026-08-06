---
title: Umgebungsvariablen
---

# Umgebungsvariablen

Alle Konfiguration erfolgt über Umgebungsvariablen. Kopieren Sie `.env.example` nach `.env` und tragen Sie die Werte ein.

## Datenbank

| Variable | Standard | Beschreibung |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | JDBC-Verbindungs-URL |
| `DB_USER` | `registerwerk` | Datenbankbenutzer |
| `DB_PASSWORD` | — | **Erforderlich** |

## Authentifizierung

### Integrierter Administrator (kein IdP-Modus)

| Variable | Standard | Beschreibung |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → Benutzername/Passwort-Formular im Operator-Frontend; `true` → Microsoft-Schaltfläche |
| `DEFAULT_ADMIN_EMAIL` | — | E-Mail des vorbelegten Admin-Benutzers (nur integrierter Modus) |
| `DEFAULT_ADMIN_PASSWORD` | — | Klartext-Passwort, beim Start mit BCrypt gehasht; rotieren, indem Sie es ändern und neu starten |
| `JWT_DEV_SECRET` | integriert | HS256-Signaturschlüssel, der im Entwicklungs-/Demomodus verwendet wird; für lokal nicht festgelegt lassen, im Staging überschreiben |

### OAuth2 / OIDC (Produktion)

| Variable | Beschreibung |
|---|---|
| `JWT_ISSUER_URI` | OIDC-Aussteller-URL – leer lassen für HS256-Entwicklungsmodus; für die Produktion setzen (z. B. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | OIDC-Client-ID, die vom Kong-Plugin verwendet wird |
| `ENTRA_CLIENT_SECRET` | OIDC-Client-Geheimnis, das vom Kong-Plugin verwendet wird |
| `ENTRA_ISSUER` | OIDC-Aussteller, der im Kong-Plugin konfiguriert ist |

## Blockchain-RPCs

| Variable | Chain |
|---|---|
| `ETH_MAINNET_RPC` | Ethereum Mainnet |
| `ETH_SEPOLIA_RPC` | Ethereum Sepolia |
| `POLYGON_MAINNET_RPC` | Polygon Mainnet |
| `POLYGON_AMOY_RPC` | Polygon Amoy |
| `BASE_MAINNET_RPC` | Base Mainnet |
| `BASE_SEPOLIA_RPC` | Base Sepolia |
| `SOLANA_MAINNET_RPC` | Solana Mainnet |
| `SOLANA_DEVNET_RPC` | Solana Devnet |
| `REGISTRY_WALLET_PRIVATE_KEY` | Backend-Signaturschlüssel für Blockchain-Operationen |
| `REGISTRY_SOLANA_PRIVATE_KEY` | Optionaler Solana-Signaturschlüssel |

## Storage

| Variable | Beschreibung |
|---|---|
| `S3_BUCKET` | S3-Bucket-Name für KYC-Dokumente |
| `S3_ENDPOINT` | S3-kompatible Endpunkt-URL |
| `S3_ACCESS_KEY` | S3-Zugriffsschlüssel |
| `S3_SECRET_KEY` | S3-Geheimschlüssel |
| `S3_REGION` | S3-Region |

Dokumente kleiner als 5 MB werden inline als BYTEA in PostgreSQL gespeichert. Dokumente ≥5 MB werden in S3 gespeichert.

## Email

| Variable | Beschreibung |
|---|---|
| `MAIL_HOST` | SMTP-Host |
| `MAIL_PORT` | SMTP-Port (Standard 587) |
| `MAIL_USERNAME` | SMTP-Benutzername |
| `MAIL_PASSWORD` | SMTP-Passwort |

## Onboarding

| Variable | Beschreibung |
|---|---|
| `CUSTOMER_FRONTEND_URL` | Basis-URL des Kunden-Frontends (für E-Mail-Links) |
| `FRONTEND_BUILD_ENV` | Frontend-Build-Ziel: `production` oder `testnet` |
