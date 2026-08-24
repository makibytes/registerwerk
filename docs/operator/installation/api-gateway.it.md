---
title: API Gateway (Kong)
---

# API Gateway (Kong) { #api-gateway-kong }

Kong 3.8 (OSS, senza DB) si trova di fronte **solo al traffico API del frontend del cliente**. Gestisce la limitazione della velocità,
la memorizzazione nella cache delle risposte e le intestazioni di sicurezza. **Non** fronteggia l'interfaccia utente di nessuno dei due
frontend — entrambe le app vengono sempre aperte direttamente dal browser sulla propria porta (`:4200`, `:4201`) — e
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
| `rate-limiting` | 300 richieste/minuto, 10.000/ora per consumatore |
| `bot-detection` | Blocca gli user agent comuni del crawler/scanner |
| `ip-restriction` | Limita `/api/v1/admin/**` ai CIDR della rete dell'operatore |
| `cors` | Intestazioni multiorigine per il frontend Angular del cliente |
| `request-size-limiting` | Corpo richiesta massimo 20 MB |
| `response-transformer` | Aggiunge intestazioni di sicurezza standard (HSTS, CSP, X-Frame-Options, …) |

`openid-connect` (terminazione JWT al gateway) è **solo Kong Enterprise/Konnect** e non è
attivo in questa configurazione OSS — uno snippet pronto per l'unione si trova in `gateway/plugins/oidc-entra.yml` per le distribuzioni
che eseguono Kong Enterprise. Senza di esso, la convalida JWT e l'estrazione di entità/ruolo avvengono
interamente nel backend Spring, leggendo le attestazioni dal token stesso: Kong non
inserisce mai qui le intestazioni `X-Entity-Id`/`X-Entity-Roles`.

## Kong admin API { #kong-admin-api }

Kong viene eseguito senza DB e in questo stack **non fornisce alcuna GUI di amministrazione** (nessun Konga, nessun Kong Manager: entrambi sono stati
rimossi/mai collegati). L'accesso all'API di amministrazione è intenzionalmente limitato al loopback:

```bash
# Bound to 127.0.0.1:8001 on the host — never expose this publicly, it's unauthenticated
docker compose exec kong kong health
curl http://127.0.0.1:8001/status
```

Per modificare routing/plugin, modifica `gateway/kong.yml` e riavvia il servizio `kong`: è l'unica fonte di verità
in modalità senza DB.
