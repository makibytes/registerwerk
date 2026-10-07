---
title: Riferimento API
---

# Riferimento API

Registerwerk espone una API REST per tutte le operazioni del registro. Questa pagina è il punto di ingresso dell'operatore; le convenzioni (errori, paginazione, idempotenza, importi) sono nella [panoramica dell'API REST](../platform/api.md), e ogni route è elencata nell'[indice delle route API](../platform/api-routes.md) generato (solo in inglese).

## Documentazione interattiva

Il documento OpenAPI e la Swagger UI sono **disattivati per impostazione predefinita**. Imposta `SWAGGER_ENABLED=true` perché il backend li serva:

```
http://localhost:48080/swagger-ui.html
http://localhost:48080/api-docs
```

!!! warning "La specifica non è autenticata quando è attiva"
    Con `SWAGGER_ENABLED=true` questi percorsi sono `permitAll`. Nulla rifiuta l'impostazione in modalità produzione. Tienila disattivata nei deployment esposti a Internet.

## Autenticazione

Tutti gli endpoint dell'API, tranne `/api/v1/public/**` (e gli endpoint dei token di onboarding), richiedono un JWT Bearer:

```bash
curl http://localhost:48080/api/v1/entities \
  -H "Authorization: Bearer <jwt>"
```

I token dell'operatore provengono da `POST /api/v1/public/auth/login` (il portale operatore lo usa direttamente). Con `ENTRA_ENABLED=true` i token cliente provengono da Entra; altrimenti dallo stesso endpoint locale. Vedi [Sicurezza e autenticazione](../platform/security.md) e, per il lato cliente, [Accesso](../customer/authentication.md). Il backend valida da sé ogni token; Kong no.

## Dove trovare un endpoint

| Esigenza | Dove |
|---|---|
| Ogni route, la sua espressione di ruolo e il suo requisito di step-up | [Indice delle route API](../platform/api-routes.md) |
| Quali route richiedono autenticazione rafforzata o un secondo approvatore, e quali operazioni vincolano il corpo della richiesta | [Matrice step-up](../compliance/step-up-matrix.md) |
| Schemi di richiesta e di risposta | Documento OpenAPI (`SWAGGER_ENABLED=true`) |
| Formato degli errori, paginazione, `Idempotency-Key`, importi decimali | [Panoramica dell'API REST](../platform/api.md) |

## Risposte di errore

Gli errori usano il record `ErrorResponse` (`status`, `message`, `timestamp`, `path`); la corrispondenza degli stati (400, 401, 403, 404, 409, 500) è documentata nella [panoramica dell'API REST](../platform/api.md#error-response-format). Alcuni endpoint aggiungono un `code` leggibile da macchina, per esempio `IDEMPOTENCY_KEY_REQUIRED`, `IMPERSONATION_READ_ONLY` o `IMPERSONATION_ACTION_DENIED`.

## Limitazione della frequenza

Le chiamate all'API cliente passano da Kong, che limita ogni IP client a 300 richieste al minuto e 10.000 all'ora, conteggiate in Redis così che il limite sia condiviso tra le repliche di Kong (vedi [Gateway API](installation/api-gateway.md)). Le chiamate dal portale operatore non passano da Kong e non sono soggette a questo limite. Le risposte includono header di limite di frequenza (`X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`); un limite superato riceve risposta `429`.
