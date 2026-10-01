---
title: API Gateway (Kong)
---

# API Gateway (Kong) { #api-gateway-kong }

Kong 3.8 (OSS, senza DB) si trova di fronte **solo al traffico API del frontend del cliente**. Gestisce la limitazione della velocità,
la memorizzazione nella cache delle risposte e le intestazioni di sicurezza. **Non** fronteggia l'interfaccia utente di nessuno dei due
frontend — entrambe le app vengono sempre aperte direttamente dal browser sulla propria porta (`:44200`, `:44201`) — e
il **frontend dell'operatore ignora completamente Kong**, anche per le proprie chiamate API (il suo nginx inoltra
`/api/` direttamente a `backend:8080`). La convalida JWT e l'estrazione di entità/ruolo avvengono sempre nel
backend Spring stesso, dalle attestazioni del token stesso — non tramite alcuna intestazione iniettata da
Kong — nella configurazione OSS fornita da questo repository.

## Avvio di Kong { #starting-kong }

```bash
docker compose up -d kong
```

Kong funziona in modalità DB-less (dichiarativa): legge `gateway/kong.yml` direttamente tramite
`KONG_DECLARATIVE_CONFIG` e non necessita di un database proprio.

## Configurazione dichiarativa { #declarative-configuration }

Kong è configurato tramite `gateway/kong.yml` in formato deck. Per applicare le modifiche:

```bash
deck sync --config gateway/kong.yml
```

## Plugin chiave { #key-plugins }

Solo i plugin Kong OSS in bundle sono attivi per impostazione predefinita (vedi `gateway/kong.yml`):

| Plug-in | Scopo |
|---|---|
| `proxy-cache` | Memorizza nella cache le risposte GET 200 dei percorsi pubblici per 30-60 secondi |
| `request-transformer` | Rimuove qualsiasi `X-Entity-Id`/`X-Entity-Roles` fornito dal client sui percorsi pubblici, in modo che nulla possa essere introdotto di nascosto prima ancora che il backend veda la richiesta |
| `rate-limiting` | 300 richieste/minuto, 10.000/ora per IP client (basato su Redis, condiviso tra le repliche Kong) |
| `bot-detection` | Blocca gli user agent comuni del crawler/scanner |
| `ip-restriction` | Limita `/api/v1/admin/**` ai CIDR della rete dell'operatore, confrontati con l'IP client reale |
| `cors` | Intestazioni multiorigine per il frontend Angular del cliente |
| `request-size-limiting` | Corpo richiesta massimo 20 MB |
| `response-transformer` | Aggiunge intestazioni di sicurezza standard (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (terminazione JWT al gateway) è **solo Kong Enterprise/Konnect** e non è
attivo in questa configurazione OSS — uno snippet pronto per l'unione si trova in `gateway/plugins/oidc-entra.yml` per le distribuzioni
che eseguono Kong Enterprise. Senza di esso, la convalida JWT e l'estrazione di entità/ruolo avvengono
interamente nel backend Spring, leggendo le attestazioni dal token stesso: Kong non
inserisce mai qui le intestazioni `X-Entity-Id`/`X-Entity-Roles`.

## Gestione dell'IP del client

Il rate limiting, l'`ip-restriction` di amministrazione e la limitazione dei login del backend dipendono dall'indirizzo reale del client; un `X-Forwarded-For` fornito dal client non viene mai creduto:

- Gli nginx **sovrascrivono** `X-Forwarded-For` con il peer TCP osservato (mai accodare). Dietro l'ingress Helm, nginx ripristina prima l'indirizzo reale tramite `ingress.trustedCidrs`.
- Kong si fida di `X-Forwarded-For` solo dalla rete nginx/ingress (`KONG_TRUSTED_IPS`, `KONG_REAL_IP_HEADER=X-Forwarded-For`, `KONG_REAL_IP_RECURSIVE=off`; nel chart `kong.env.trusted_ips`).
- Il backend si fida dell'header solo da `REGISTERWERK_AUTH_TRUSTED_PROXIES` (impostato esplicitamente in Compose e nel chart).
- La allowlist admin di Compose include `192.168.0.0/16` e `::1` per la demo locale. In Helm è generata da `kong.adminAllowCidrs` (obbligatorio, nessun default).

`scripts/check-client-ip.sh` verifica su uno stack in esecuzione che valori `X-Forwarded-For` falsificati non azzerino il contatore del limite. L'ingress API di Helm punta a Kong, mai al backend, e risponde 404 a `/actuator/*` eccetto health.

## Kong admin API { #kong-admin-api }

Kong viene eseguito senza DB e in questo stack **non fornisce alcuna GUI di amministrazione** (nessun Konga, nessun Kong Manager: entrambi sono stati
rimossi/mai collegati). L'accesso all'API di amministrazione è intenzionalmente limitato al loopback:

```bash
# Bound to 127.0.0.1:48001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:48001/status
```

Per modificare routing/plugin, modifica `gateway/kong.yml` e riavvia il servizio `kong`: è l'unica fonte di verità
in modalità senza DB.
