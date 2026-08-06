---
title: Riferimento API
---

# Riferimento API { #api-reference }

Il registro eWpG fornisce un REST API per tutte le operazioni del registro. Questa pagina fornisce una panoramica della struttura di API, dell'autenticazione e dei collegamenti alla documentazione interattiva in tempo reale.

## Documentazione interattiva { #interactive-documentation }

L'interfaccia utente Swagger è disponibile all'indirizzo:

```
http://localhost:8080/swagger-ui.html
```

Per la produzione:

```
https://api.registerwerk.example.com/swagger-ui.html
```

La specifica completa OpenAPI 3 (JSON) è disponibile all'indirizzo:

```
http://localhost:8080/v3/api-docs
```

## Autenticazione { #authentication }

Tutti gli endpoint API (tranne `/api/v1/public/**`) richiedono un token Bearer JWT:

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

Vedere [Autenticazione](../customer/authentication.md) per come ottenere un token.

## Gruppi di API { #api-groups }

### Endpoint pubblici (`/api/v1/public/`) { #public-endpoints-apiv1public }

Nessuna autenticazione richiesta.

| Metodo | Percorso | Descrizione |
|--------|------|-------------|
| `GET` | `/api/v1/public/chains` | Elenca tutte le catene abilitate |
| `GET` | `/api/v1/public/health` | Controllo dello stato di base |

### Endpoint del cliente (`/api/v1/`) { #customer-endpoints-apiv1 }

Richiedono autenticazione. Le risposte hanno come ambito l'entità autenticata.

| Metodo | Percorso | Descrizione |
|--------|------|-------------|
| `GET` | `/api/v1/issuances` | Elenca le emissioni per la tua entità |
| `POST` | `/api/v1/issuances` | Crea una nuova emissione |
| `GET` | `/api/v1/issuances/{id}` | Ottieni i dettagli dell'emissione |
| `PUT` | `/api/v1/issuances/{id}` | Aggiorna l'emissione (solo DRAFT) |
| `POST` | `/api/v1/issuances/{id}/submit` | Invia per approvazione |
| `POST` | `/api/v1/issuances/{id}/deploy` | Esegui il deployment sulla blockchain |
| `POST` | `/api/v1/issuances/{id}/suspend` | Sospendi il token |
| `POST` | `/api/v1/issuances/{id}/redeem` | Contrassegna come rimborsato |
| `GET` | `/api/v1/issuances/{id}/investors` | Elenca gli investitori |
| `POST` | `/api/v1/issuances/{id}/investors` | Aggiungi investitore |
| `DELETE` | `/api/v1/issuances/{id}/investors/{investorId}` | Rimuovi investitore |
| `POST` | `/api/v1/issuances/{id}/investors/{investorId}/whitelist` | Inserisci il wallet in whitelist on-chain |
| `GET` | `/api/v1/investments` | Elenca le posizioni in token detenute (investitore) |
| `GET` | `/api/v1/transfers` | Elenca i trasferimenti per la tua entità |
| `GET` | `/api/v1/audit-log` | Pista di controllo (limitata alla tua entità) |
| `GET` | `/api/v1/profile` | Il tuo profilo di entità |
| `POST` | `/api/v1/wallets` | Registra un wallet |
| `DELETE` | `/api/v1/wallets/{address}` | Rimuovi un wallet |

### Endpoint di amministrazione (`/api/v1/admin/`) { #admin-endpoints-apiv1admin }

Richiedono il ruolo `REGISTRY_ADMIN`.

| Metodo | Percorso | Descrizione |
|--------|------|-------------|
| `GET` | `/api/v1/admin/entities` | Elenca tutte le entità |
| `POST` | `/api/v1/admin/entities` | Crea entità e invia l'invito |
| `PATCH` | `/api/v1/admin/entities/{id}/status` | Aggiorna lo stato dell'entità |
| `GET` | `/api/v1/admin/kyc` | Elenca le verifiche KYC in attesa |
| `POST` | `/api/v1/admin/kyc/{id}/approve` | Approva KYC |
| `POST` | `/api/v1/admin/kyc/{id}/reject` | Rifiuta KYC |
| `POST` | `/api/v1/admin/issuances/{id}/approve` | Approva emissione |
| `POST` | `/api/v1/admin/issuances/{id}/reject` | Rifiuta emissione |
| `GET` | `/api/v1/admin/chains` | Elenca tutte le catene |
| `POST` | `/api/v1/admin/chains` | Aggiungi una catena |
| `PATCH` | `/api/v1/admin/chains/{chainId}` | Aggiorna la configurazione della catena |
| `POST` | `/api/v1/admin/chains/refresh` | Ricarica i client della catena |
| `GET` | `/api/v1/admin/audit-log` | Pista di controllo completa (tutte le entità) |

## Risposte agli errori { #error-responses }

Tutti gli errori seguono un formato standard:

```json
{
  "timestamp": "2025-04-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "ISSUANCE_INVALID_STATE",
  "message": "Cannot submit issuance in state ISSUED",
  "path": "/api/v1/issuances/abc123/submit"
}
```

Codici di errore comuni:

| Codice | HTTP | Descrizione |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | JWT mancante o non valido |
| `FORBIDDEN` | 403 | Ruolo insufficiente per questa operazione |
| `NOT_FOUND` | 404 | La risorsa non esiste |
| `ISSUANCE_INVALID_STATE` | 422 | Transizione di stato non consentita |
| `BLOCKCHAIN_ERROR` | 502 | Chiamata RPC alla catena non riuscita |
| `INDEXER_UNAVAILABLE` | 503 | Graph Node non raggiungibile |

## Limitazione della velocità { #rate-limiting }

La velocità delle chiamate API è limitata al gateway Kong:

- 300 richieste/minuto per consumatore autenticato
- 10 richieste/minuto per gli endpoint relativi all'autenticazione

Le intestazioni dei limiti di velocità sono incluse nelle risposte:

```
X-RateLimit-Limit-Minute: 300
X-RateLimit-Remaining-Minute: 287
```

# Riferimento API { #api-reference }

La specifica OpenAPI completa è disponibile all'indirizzo:

```
http://localhost:8080/v3/api-docs
http://localhost:8080/swagger-ui.html
```

## Endpoint chiave { #key-endpoints }

### Entità { #entities }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/entities` | Elenca tutte le entità |
| `POST` | `/api/v1/entities` | Crea entità |
| `GET` | `/api/v1/entities/{id}` | Ottieni l'entità |
| `PUT` | `/api/v1/entities/{id}` | Aggiorna entità |
| `GET` | `/api/v1/entities/{id}/kyc/documents` | Elenca i documenti KYC |
| `POST` | `/api/v1/entities/{id}/kyc/documents` | Carica documento KYC |

### Asset { #assets }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/assets` | Elenca tutte le risorse |
| `POST` | `/api/v1/assets` | Crea risorsa |
| `GET` | `/api/v1/assets/{id}` | Ottieni risorsa |
| `POST` | `/api/v1/assets/{id}/deployments` | Esegui il deployment sulla catena |
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/history` | Cronologia trasferimenti |
| `GET` | `/api/v1/assets/{id}/holders` | Elenca i titolari |

### ERC-3643 { #erc-3643 }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/erc3643` | Ottieni la suite T-REX |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/compliance-modules` | Aggiungi modulo |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/trusted-issuers` | Aggiungi emittente |

### ONCHAINID { #onchainid }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/identities` | Elenca identità |
| `POST` | `/api/v1/identities` | Crea ONCHAINID |
| `POST` | `/api/v1/identities/{id}/claims` | Rilascia l'attestazione KYC |
| `DELETE` | `/api/v1/identities/{id}/claims/{claimId}` | Revoca attestazione |

### Admin { #admin }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/admin/chains` | Elenca le configurazioni della catena |
| `POST` | `/api/v1/admin/chains` | Aggiungi catena |
| `PUT` | `/api/v1/admin/chains/{id}` | Aggiorna catena |
| `POST` | `/api/v1/admin/chains/refresh` | Ricarica i client Web3j |
| `GET` | `/api/v1/audit` | Interroga la pista di controllo |

### Pubblico (nessuna autenticazione) { #public-no-auth }
| Metodo | Percorso | Descrizione |
|---|---|---|
| `GET` | `/api/v1/public/assets/by-address/{address}` | Cerca token per indirizzo |
| `GET` | `/api/v1/public/chains` | Elenca le catene attive |
| `GET` | `/api/v1/onboarding/token-info/{token}` | Convalida il token di onboarding |
| `POST` | `/api/v1/onboarding/complete` | Completa l'onboarding con token |
