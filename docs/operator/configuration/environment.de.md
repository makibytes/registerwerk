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
| `DB_PASSWORD` | — | **Erforderlich** (Passwort der Migrations-/Eigentümer-Anmeldung) |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `DB_USER` / `DB_PASSWORD` | Laufzeit-Anmeldung, mit der sich die Anwendung verbindet (`registerwerk_app` in den mitgelieferten Compose- und Helm-Setups). Sie muss im Produktionsmodus von der Eigentümer-Anmeldung abweichen, weil die Anwendung die Audit-Tabellen nicht besitzen darf |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | Migrations-/Eigentümer-Anmeldung, nur von Flyway genutzt (in Compose und Helm auf `DB_USER`/`DB_PASSWORD` gesetzt) |

## Authentifizierung

### Integrierter Administrator (kein IdP-Modus)

| Variable | Standard | Beschreibung |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → Benutzername/Passwort-Formular im Operator-Frontend; `true` → Microsoft-Schaltfläche |
| `DEFAULT_ADMIN_EMAIL` | — | E-Mail des vorbelegten Admin-Benutzers (nur integrierter Modus) |
| `DEFAULT_ADMIN_PASSWORD` | — | Klartext-Passwort, beim Anlegen des Administrators mit BCrypt gehasht; nur verwendet, wenn kein `REGISTRY_ADMIN` existiert, wird auf ein vorhandenes Konto nie erneut angewendet |
| `JWT_DEV_SECRET` | integriert | HS256-Signaturschlüssel, der im Entwicklungs-/Demomodus verwendet wird; für lokal nicht festgelegt lassen, im Staging überschreiben |

### OAuth2 / OIDC (Produktion)

| Variable | Beschreibung |
|---|---|
| `JWT_ISSUER_URI` | OIDC-Aussteller-URL – leer lassen für HS256-Entwicklungsmodus; für die Produktion setzen (z. B. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | Client-ID der API-App-Registrierung; zusammen mit dem Secret für app-only-Zugriff auf Microsoft Graph (Zwei-Faktor-Status, Support-Konsole). Wird nicht von Kong genutzt, das kein OIDC macht |
| `ENTRA_CLIENT_SECRET` | Client-Secret der API-App-Registrierung (app-only-Graph-Zugangsdaten; erforderlich bei `ENTRA_SUPPORT_ENABLED=true`) |

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

## Produktionsmodus und Freigabe-Gates

Setzen Sie `REGISTERWERK_PRODUCTION_MODE=true` in jeder Produktionsinstallation. Es macht aus den Bereitschaftsprüfungen Startverweigerungen und aktiviert die nur für die Produktion geltenden Kontrollen. [Produktionsmodus und Freigabe-Gates](../security/production-mode.md) (nur Englisch) listet jedes Gate, die steuernde Variable und was verweigert wird. Die Seite dokumentiert auch die Freigabe- und Bestätigungsvariablen (`REGISTERWERK_LENDING_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_RELEASE_APPROVED`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF`, `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE`, `REGISTERWERK_AUDIT_SIGNING_PROVIDER`, `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS`, `REGISTERWERK_WALLET_MASTER_KEY`, `LINK_BY_EMAIL_WITHOUT_VERIFICATION`, `SWAGGER_ENABLED` und weitere).

## Weitere Einstellungen

Registerkalender, Bestätigungstiefen, Anmelde-Drosselung, Handel, Travel Rule, Reporting, Audit-Anker und Signatur-Verwahrung samt Standardwerten und den Erwartungen des Produktionsmodus stehen in der [Referenz der Betriebseinstellungen](environment-settings.md) (nur auf Englisch).
