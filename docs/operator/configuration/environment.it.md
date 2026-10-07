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
| `DB_APP_USER` / `DB_APP_PASSWORD` | `DB_USER` / `DB_PASSWORD` | Login di runtime con cui si connette l'applicazione (`registerwerk_app` nelle configurazioni Compose e Helm fornite). In modalità produzione deve essere diverso dal login proprietario, perché l'applicazione non deve possedere le tabelle di audit |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | Login di migrazione/proprietario usato solo da Flyway (uguale a `DB_USER`/`DB_PASSWORD` in Compose e Helm) |

## Autenticazione { #authentication }

### Amministrazione integrata (modalità senza IdP) { #built-in-admin-no-idp-mode }

| Variabile | Predefinito | Descrizione |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → modulo nome utente/password nel frontend dell'operatore (FE); `true` → pulsante Microsoft |
| `DEFAULT_ADMIN_EMAIL` | — | E-mail dell'utente amministratore precaricato (seed) (solo modalità integrata) |
| `DEFAULT_ADMIN_PASSWORD` | — | Password in testo normale sottoposta ad hashing con BCrypt alla creazione dell'amministratore; usata solo se non esiste alcun `REGISTRY_ADMIN`, mai riapplicata a un account esistente |
| `JWT_DEV_SECRET` | integrato | Chiave di firma HS256 utilizzata in modalità dev/demo; lasciare non impostato per locale, sovrascrivere nello staging |

### OAuth2 / OIDC (produzione) { #oauth2-oidc-production }

| Variabile | Descrizione |
|---|---|
| `JWT_ISSUER_URI` | URL dell'emittente OIDC: lasciare vuoto per la modalità dev HS256; impostare per la produzione (ad es. `https://login.microsoftonline.com/<tenant>/v2.0`) |
| `ENTRA_CLIENT_ID` | ID client della registrazione dell'app API; usato con il secret per l'accesso a Microsoft Graph solo-applicazione (stato a due fattori, console di supporto). Non usato da Kong, che non fa OIDC |
| `ENTRA_CLIENT_SECRET` | Secret client della registrazione dell'app API (credenziale Graph solo-applicazione; obbligatorio con `ENTRA_SUPPORT_ENABLED=true`) |

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

## Modalità produzione e gate di rilascio

Imposta `REGISTERWERK_PRODUCTION_MODE=true` su ogni deployment di produzione. Trasforma i controlli di prontezza da avvisi in rifiuti all'avvio e abilita i controlli solo-produzione. [Modalità produzione e gate di rilascio](../security/production-mode.md) (solo in inglese) elenca ogni gate, la variabile che lo controlla e che cosa viene rifiutato. Quella pagina documenta anche le variabili di approvazione del rilascio e di riconoscimento (`REGISTERWERK_LENDING_RELEASE_APPROVED`, `REGISTERWERK_REPO_DESK_RELEASE_APPROVED`, `REGISTERWERK_TRADING_LEGAL_OPINION_REF`, `REGISTERWERK_AUDIT_ALLOW_OWNER_RUNTIME_ROLE`, `REGISTERWERK_AUDIT_SIGNING_PROVIDER`, `REGISTERWERK_WEBHOOK_ALLOW_INSECURE_URLS`, `REGISTERWERK_WALLET_MASTER_KEY`, `LINK_BY_EMAIL_WITHOUT_VERIFICATION`, `SWAGGER_ENABLED` e altre).

## Altre impostazioni

Calendario del registro, conferme di catena, limitazione degli accessi, negoziazione, Travel Rule, reporting, ancoraggio dell'audit e custodia delle chiavi di firma, con i valori predefiniti e le attese della modalità di produzione, sono nel [riferimento delle impostazioni operative](environment-settings.md) (solo in inglese).
