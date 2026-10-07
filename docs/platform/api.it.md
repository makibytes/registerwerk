---
title: REST API Panoramica
description: Struttura URL, autenticazione, risposte agli errori, impaginazione e convenzioni API.
---

# REST API Panoramica { #rest-api-overview }

Tutte le funzionalità di Registerwerk sono esposte tramite REST API in `http://backend:8080`. Il frontend dell'operatore si connette direttamente; il frontend del cliente si connette tramite Kong (`http://kong:8000`). Ogni route mappata è elencata nell'[indice delle route API](api-routes.md) generato (solo in inglese). Esistono un documento OpenAPI 3 e una Swagger UI, ma sono **disattivati per impostazione predefinita** (vedi [OpenAPI / Swagger UI](#openapi-swagger-ui)).

---

## Struttura URL { #url-structure }

| Modello | Autenticazione richiesta | Disponibile per |
|---|---|---|
| `/api/v1/public/**` | No | Tutti |
| `/api/v1/onboarding/token-info/**` | No | Flusso di onboarding del cliente |
| `/api/v1/onboarding/complete` | No | Flusso di onboarding del cliente |
| `/api/v1/**` | JWT richiesto | Utenti autenticati (in base al ruolo) |

---

## Autenticazione { #authentication }

Tutti gli endpoint protetti richiedono:

```
Authorization: Bearer <jwt>
```

**Il backend convalida ogni token stesso, su ogni richiesta.** Kong non convalida i JWT e non dice al backend chi è il chiamante: il suo plug-in `openid-connect` è una funzionalità Enterprise e non è attivo in questa configurazione OSS. Kong inoltre *rimuove* le intestazioni di identità fornite dal client, in modo che nulla possa essere introdotto di nascosto prima del backend.

I token operatore vengono emessi da `POST /api/v1/public/auth/login` (HS256, `iss: registerwerk-local`). I token del cliente vengono emessi dal provider OIDC quando `ENTRA_ENABLED=true` e dallo stesso endpoint locale altrimenti. Un decodificatore di delega instrada sull'intestazione JWS `alg`; entrambi i rami sono vincolati all'issuer (issuer-pinned) e il ramo OIDC è vincolato anche all'audience (audience-pinned). Vedi [Sicurezza e autenticazione](security.md).

---

## Formato della risposta all'errore { #error-response-format }

Tutti gli errori seguono il record `ErrorResponse`:

```json
{
  "status": 404,
  "message": "Asset with id 'abc...' not found",
  "timestamp": "2026-05-22T10:15:30Z",
  "path": "/api/v1/assets/abc..."
}
```

| Stato HTTP | Lanciato da | Causa |
|---|---|---|
| 400 | `IllegalArgumentException` | Input non valido (errore di convalida, valore enum non valido) |
| 401 | `InvalidCredentialsException` | Password errata, JWT scaduto |
| 403 | `AccessDeniedException` | Ruolo insufficiente, è necessario uno step-up |
| 404 | `EntityNotFoundException` | La risorsa non esiste |
| 409 | `InvalidStateTransitionException` | Operazione non consentita nello stato corrente (ad esempio, distribuire una risorsa già distribuita) |
| 500 | Eccezione imprevista | Errore interno del server (dettagli non esposti in prod) |

!!! info "Messaggi di errore in produzione"
    `error.include-message` è impostato su `never` nel profilo `prod`. In fase di sviluppo e test, è `always`. Ciò impedisce alle tracce dello stack di fuoriuscire nelle risposte di produzione.

---

## Impaginazione { #pagination }

Gli endpoint di elenco che paginano accettano `page` (base zero) e `size`, ad esempio:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

La forma della risposta dipende **dal singolo endpoint**: alcuni restituiscono un semplice array JSON (il totale è allora nell'header di risposta `X-Total-Count`, che la configurazione CORS espone ai browser), altri il wrapper `PageResponse` `{ content, totalElements, totalPages, page, size }`. Controlla lo schema dell'endpoint nel documento OpenAPI prima di fare affidamento su una delle due forme.

---

## Idempotency-Key e importi { #idempotency-key-and-amounts }

Gli endpoint di amministrazione/emittente che muovono fondi o modificano lo stato richiedono l'header `Idempotency-Key`: mint, burn, trasferimenti/approvazioni forzati, force-burn, modifiche di blocco e whitelist, amministrazione dei token Solana, operazioni su slot e vault, azioni agente ERC-3643, operazioni e riconciliazione dei mercati di prestito, modifiche ai mezzi di pagamento, importazione di wallet, consegna/completamento dei trasferimenti di registro e rimborso di un asset. Un `POST`, `PUT`, `PATCH` o `DELETE` senza una chiave valida viene rifiutato con `400` e il codice `IDEMPOTENCY_KEY_REQUIRED` (o `IDEMPOTENCY_KEY_INVALID`) prima che venga eseguito qualcosa. Gli altri endpoint restano facoltativi.

- Invii un valore univoco per ogni azione dell'utente (UUID; da 8 a 255 caratteri tra `A-Za-z0-9._:-`) e **riutilizzi lo stesso valore quando ripete la stessa richiesta** dopo un timeout o un `5xx`. La ripetizione restituisce allora il risultato originale (`X-Idempotent-Replay: true`) o la stessa transazione invece di eseguirsi due volte.
- La chiave è riferita al chiamante: l'entità giuridica per i token cliente, l'utente che agisce per i token operatore. La stessa chiave con un altro metodo, percorso o corpo riceve `422`; una richiesta ancora in corso riceve `409`.
- La chiave viene inoltre salvata nella riga outbox della transazione on-chain, così una ripetizione corrisponde alla stessa transazione firmata anche dopo la scadenza della risposta in cache. Le risposte `401`/`403` (comprese le sfide di step-up) e `5xx` non vengono messe in cache: ripetere la richiesta dopo uno step-up con la stessa chiave è sicuro.

**Gli importi sono stringhe decimali.** Invii gli importi dei token (`amount`, `value`, `newCap`, `navPerShare`, ...) come stringhe JSON, ad esempio `"1000000000000000000000"`. Un numero JavaScript perde precisione oltre 2^53. Per una release un numero JSON è ancora accettato se è rappresentabile esattamente (intero inferiore a 2^53 o decimale con al massimo 15 cifre significative) e viene registrato un avviso di deprecazione; tutto il resto riceve `400` con `Invalid amount: ...`.

## Gruppi di route { #route-groups }

L'[indice delle route API](api-routes.md) generato è l'elenco completo, derivato dal codice (metodo, percorso, espressione di ruolo, step-up). I principali percorsi di base:

| Area | Percorso di base |
|---|---|
| Asset e deployment (mint/burn dell'emittente sotto `.../deployments/{depId}/issuer/`, operazioni coattive dell'operatore sotto `.../deployments/{depId}/admin/`) | `/api/v1/assets`, `/api/v1/deployments` |
| Soggetti giuridici e KYC (`/api/v1/entities/{entityId}/kyc/...`), coda di revisione KYC | `/api/v1/entities`, `/api/v1/kyc` |
| Screening sanzioni (`/api/v1/compliance/screening/...`, corrispondenze sotto `/hits/{hitId}/accept`) e altre funzioni di conformità | `/api/v1/compliance` |
| Sperrvermerk (blocchi del detentore) | `/api/v1/holder-blocks` |
| Segnalazioni regolamentari (MiFIR, DAC8) | `/api/v1/regulatory-reporting` |
| Incidenti, fornitori e test di resilienza DORA | `/api/v1/dora` |
| Trading, repo desk, lending, operazioni societarie | `/api/v1/trading`, `/api/v1/repo-desk`, `/api/v1/lending`, `/api/v1/corporate-actions` |
| Coda di approvazione a quattro occhi | `/api/v1/approvals` |
| Registro di audit, verifica della catena | `/api/v1/audit` |
| Amministrazione dell'operatore (utenti, wallet, ...) | `/api/v1/admin` |
| Self-service delle società clienti | `/api/v1/company`, `/api/v1/me` |
| Pubblico, senza autenticazione (chain, capacità della piattaforma, Travel Rule) | `/api/v1/public` |

Approvare un KYC, per esempio, è `POST /api/v1/entities/{entityId}/kyc/approve`: chi avvia l'operazione è un `REGISTRY_ADMIN` o un `COMPLIANCE_OFFICER`, e la chiamata richiede autenticazione rafforzata e un secondo approvatore (vedi la [matrice step-up](../compliance/step-up-matrix.md)).

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

Il documento OpenAPI e la Swagger UI sono serviti **dal backend**, non da questo server di documentazione, e sono **disattivati salvo `SWAGGER_ENABLED=true`** (predefinito `false`, in ogni profilo).

| URL (se attivato) | Descrizione |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Swagger UI interattiva (browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | JSON OpenAPI 3 (leggibile da macchina) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Controllo di salute |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Informazioni di build |

!!! info "Questo sito di documentazione e l'API"
    Questo sito (porta 48003) è un riferimento MkDocs statico — non fa da proxy al backend. Apri i link sopra direttamente in un browser mentre lo stack è in esecuzione (`docker compose up -d`).

!!! warning "Attivare SWAGGER_ENABLED espone la specifica senza autenticazione"
    Quando è attivo, `/swagger-ui.html`, `/swagger-ui/**` e `/api-docs/**` sono pubblici (`permitAll` nella configurazione di sicurezza): chiunque raggiunga il backend può leggere l'intero catalogo di route e schemi. Nulla rifiuta `SWAGGER_ENABLED=true` in modalità produzione. Lascialo disattivato in produzione, oppure metti il backend dietro una allowlist che escluda questi percorsi.
