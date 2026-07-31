---
title: REST API Panoramica
description: Struttura URL, autenticazione, risposte agli errori, impaginazione e convenzioni API.
---

# REST API Panoramica { #rest-api-overview }

Tutte le funzionalità di Registerwerk sono esposte tramite REST API in `http://backend:8080`. Il frontend dell'operatore si connette direttamente; il frontend del cliente si connette tramite Kong (`http://kong:8000`). L'API è documentata con OpenAPI 3 (interfaccia utente Swagger disponibile su `/swagger-ui.html`).

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

Gli endpoint dell'elenco supportano l'impaginazione basata su cursore con i parametri `page` e `size`:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Le risposte includono un'intestazione `X-Total-Count` con il conteggio totale dei record (prima dell'impaginazione). Il corpo della risposta è sempre un array (mai un oggetto wrapper).

---

## Gruppi API principali { #key-api-groups }

### Asset (`/api/v1/assets`) { #assets-apiv1assets }

| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/assets` | Elenca tutte le risorse (impaginate) |
| `POST` | `/api/v1/assets` | Crea una nuova risorsa |
| `GET` | `/api/v1/assets/{id}` | Ottieni risorsa per ID |
| `POST` | `/api/v1/assets/{id}/deploy` | Distribuisci il token sulla blockchain |
| `POST` | `/api/v1/assets/{id}/mint` | Conia token (minting) |
| `POST` | `/api/v1/assets/{id}/burn` | Distruggi token (burning) (step-up + 4 occhi) |
| `POST` | `/api/v1/assets/{id}/force-transfer` | Trasferimento coattivo (step-up + 4 occhi) |
| `POST` | `/api/v1/assets/{id}/freeze/{address}` | Blocca indirizzo (richiede HolderBlock) |

### Clienti (`/api/v1/customers`) { #customers-apiv1customers }

| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/customers` | Elenca soggetti giuridici |
| `POST` | `/api/v1/customers` | Crea soggetto giuridico |
| `GET` | `/api/v1/customers/{id}` | Ottieni entità |
| `POST` | `/api/v1/customers/{id}/kyc/documents` | Carica il documento KYC |
| `POST` | `/api/v1/customers/{id}/kyc/approve` | Approva KYC (COMPLIANCE_OFFICER + step-up) |
| `GET` | `/api/v1/customers/{id}/beneficial-owners` | Elenca UBO |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Aggiungi UBO |

### Conformità (`/api/v1/compliance`) { #compliance-apiv1compliance }

| Metodo | Percorso | Descrizione |
|---|---|---|
| `POST` | `/api/v1/compliance/screening/entities/{id}/screen` | Avvia uno screening manuale |
| `GET` | `/api/v1/compliance/screening/entities/{id}/runs` | Ottieni la cronologia degli screening |
| `POST` | `/api/v1/compliance/screening/hits/{hitId}/accept` | Accetta/respingi un riscontro |
| `GET` | `/api/v1/holder-blocks` | Elenca tutti gli HolderBlock |
| `POST` | `/api/v1/holder-blocks` | Crea uno Sperrvermerk (blocco del titolare) (step-up + 4 occhi) |
| `POST` | `/api/v1/holder-blocks/{id}/lift` | Rimuovi lo Sperrvermerk (step-up + 4 occhi) |

### Rapporti normativi (`/api/v1/regulatory-reporting`) { #regulatory-reporting-apiv1regulatory-reporting }

| Metodo | Percorso | Descrizione |
|---|---|---|
| `POST` | `/api/v1/regulatory-reporting/mifir` | Attiva l'esportazione MiFIR su richiesta |
| `POST` | `/api/v1/regulatory-reporting/dac8` | Attiva l'esportazione DAC8 su richiesta |
| `GET` | `/api/v1/regulatory-reporting/submissions` | Elenco cronologia invii |

### DORA (`/api/v1/dora`) { #dora-apiv1dora }

| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/dora/incidents` | Elenco incidenti ICT aperti |
| `POST` | `/api/v1/dora/incidents` | Segnala un incidente ICT (Art. 17) |
| `PATCH` | `/api/v1/dora/incidents/{id}/status` | Aggiorna lo stato dell'incidente/causa principale |
| `POST` | `/api/v1/dora/incidents/{id}/report-to-authority` | Registra la relazione iniziale/finale all'autorità (Art. 19) |
| `GET` | `/api/v1/dora/providers` | Elenca il registro dei fornitori ICT terzi (Art. 28) |
| `GET` | `/api/v1/dora/providers/expiring` | Elenco fornitori con contratti in scadenza |
| `GET` | `/api/v1/dora/resilience-tests` | Elenco risultati test di resilienza (Art. 24/25) |
| `GET` | `/api/v1/dora/resilience-tests/overdue` | Elenca i test di resilienza scaduti |
| `POST` | `/api/v1/dora/resilience-tests` | Registra il risultato di un test di resilienza |

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

La specifica OpenAPI e l'interfaccia utente interattiva sono fornite **dal backend** sulla porta 8080, non da questo server di documentazione.

| URL | Descrizione |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interfaccia utente interattiva di Swagger (browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (leggibile dalla macchina) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Controllo dello stato |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Informazioni di build |

!!! info "Questo sito di documentazione rispetto all'API"
    Questo sito (porta 8003) è un riferimento MkDocs statico: non esegue il proxy del backend. Apri i collegamenti sopra direttamente in un browser mentre lo stack è in esecuzione (`docker compose up -d`).

!!! warning "Interfaccia utente Swagger in produzione"
    L'interfaccia utente Swagger è disabilitata nel profilo `prod` Spring. Negli ambienti di sviluppo e staging è accessibile senza autenticazione. In produzione deve essere esplicitamente abilitata e protetta da un elenco di IP consentiti (allowlist) o da un'autenticazione di base.
