---
title: Webhooks
description: Invio di webhook in uscita per i client API - requisiti dell'endpoint, schema di firma, finestra anti-replay, identificativi di evento e di consegna, rotazione del segreto, nuovi tentativi.
---

# Webhooks { #webhooks }

L'**amministratore aziendale** di un'entità giuridica può sottoscrivere endpoint HTTPS a un insieme
selezionato di eventi (`KYC_APPROVED`, `KYC_REJECTED`, `TRADE_EXECUTED`, eventi degli ordini di
sottoscrizione, ecc.). Registerwerk invia in POST un documento JSON firmato a ogni endpoint sottoscritto.
Questa pagina è il contratto per i destinatari.

!!! note "Ambito"
    I webhook sono un canale di notifica, non una prova giuridica. Fanno fede il registro e il log di audit;
    usate i webhook per avviare le vostre letture.

---

## Requisiti dell'endpoint { #endpoint-requirements }

Registerwerk rifiuta (`400`) un URL che non soddisfa tutte queste condizioni e lo ricontrolla a **ogni
consegna**:

- schema `https`, senza informazioni utente (`user:pass@`), porta `443` o `8443`;
- l'host deve risolversi **solo verso indirizzi pubblici** - loopback, indirizzi privati (RFC 1918),
  link-local (inclusi i metadati cloud `169.254.169.254`), CGNAT (`100.64.0.0/10`), IPv6 unique-local
  (`fc00::/7`), IPv6 mappati su IPv4 e intervalli riservati/multicast vengono rifiutati;
- i reindirizzamenti **non vengono seguiti** - un `3xx` conta come consegna non riuscita;
- timeout di connessione 3 s, timeout di risposta 5 s; il corpo della risposta viene ignorato. Rispondete
  rapidamente con un qualsiasi `2xx` ed elaborate in modo asincrono.

La connessione viene aperta verso l'indirizzo validato, quindi modificare il DNS dopo la registrazione non
può deviare le consegne verso un indirizzo interno.

---

## Gestione delle sottoscrizioni { #managing-subscriptions }

Tutti gli endpoint si trovano sotto `/api/v1/me/webhooks` e richiedono il ruolo `COMPANY_ADMIN`.

| Chiamata | Scopo |
|---|---|
| `POST /` `{ "url", "eventTypes": [] }` | Creare. La risposta contiene il `secret` di firma **una sola volta**. `eventTypes` vuoto significa tutti gli eventi. |
| `GET /` | Elencare le sottoscrizioni (senza mai restituire segreti). `disabledReason` è valorizzato quando la piattaforma ne ha disattivata una (`URL_POLICY`, `CIRCUIT_BREAKER`). |
| `PUT /{id}/enabled` | Attivare o disattivare. La riattivazione rivalida l'URL. |
| `POST /{id}/rotate-secret` | Emettere un nuovo segreto (richiede step-up). Restituito una sola volta. |
| `GET /{id}/deliveries` | Registro delle consegne: `id` (identificativo di consegna), `eventId`, `status`, `outcome`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`. |
| `DELETE /{id}` | Rimuovere. |

`outcome` è volutamente grossolano: `OK`, `RECEIVER_ERROR` (non 2xx), `UNREACHABLE` (connessione o
timeout) o `BLOCKED` (policy sugli URL). Codici HTTP e testi di errore non vengono esposti.

---

## Formato di consegna { #delivery-format }

Header:

| Header | Significato |
|---|---|
| `X-Registerwerk-Event` | Tipo di evento, ad es. `TRADE_EXECUTED` |
| `X-Registerwerk-Event-Id` | Identifica l'evento; **uguale per tutti i sottoscrittori** |
| `X-Registerwerk-Delivery` | Identifica questa consegna; **stabile tra i tentativi** |
| `X-Registerwerk-Timestamp` | Secondi Unix, **rinnovati a ogni tentativo** |
| `X-Registerwerk-Signature` | `v1=<hex>`; durante una rotazione del segreto due valori `v1=` separati da virgola |

Corpo:

```json
{
  "eventId": "6f0c...",
  "deliveryId": "b21e...",
  "eventType": "TRADE_EXECUTED",
  "occurredAt": "2026-09-30T12:00:00Z",
  "data": { "executionId": "..." }
}
```

---

## Verificare una consegna { #verifying }

La firma è `hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + rawBody))`, dove `timestamp` e
`deliveryId` sono i valori degli header e `rawBody` i byte esatti ricevuti (non riserializzare il JSON).

1. Leggere il corpo **grezzo** e gli header.
2. Rifiutare se `abs(now - timestamp) > 300` secondi (finestra anti-replay).
3. Calcolare il valore atteso e confrontarlo con **ogni** valore `v1=` in tempo costante.
4. Deduplicare: saltare l'elaborazione se `deliveryId` (nuovo tentativo) o `eventId` (più sottoscrizioni)
   è già stato elaborato. Restituire `2xx` per i duplicati.

```python
import hashlib, hmac, time

def verify(secret: str, headers: dict, raw_body: bytes, tolerance: int = 300) -> bool:
    ts = headers["X-Registerwerk-Timestamp"]
    if abs(time.time() - int(ts)) > tolerance:
        return False
    msg = f"{ts}.{headers['X-Registerwerk-Delivery']}.".encode() + raw_body
    expected = hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()
    offered = [p.strip()[3:] for p in headers["X-Registerwerk-Signature"].split(",")
               if p.strip().startswith("v1=")]
    return any(hmac.compare_digest(expected, o) for o in offered)
```

!!! warning "Integrazioni precedenti"
    Il precedente header di firma calcolato solo sul corpo è stato **rimosso**: poteva essere riproposto
    all'infinito. I destinatari devono passare allo schema `v1` descritto sopra.

---

## Rotazione del segreto { #secret-rotation }

`POST /{id}/rotate-secret` restituisce un nuovo segreto. Per 24 ore anche il segreto precedente continua a
firmare, quindi ogni consegna porta due valori `v1=`; verificate con il segreto che possedete ancora,
distribuite quello nuovo e dopo la sovrapposizione il vecchio non viene più usato. I segreti sono conservati
cifrati e non possono essere riletti - in caso di smarrimento eseguite la rotazione.

---

## Nuovi tentativi e disattivazione automatica { #retries }

Una consegna non riuscita viene ritentata con backoff esponenziale (circa 1, 2, 4 ... minuti, limite di 1
ora, con jitter) fino a 8 tentativi. Ogni tentativo viene rifirmato con un nuovo timestamp. Dopo 20 tentativi
falliti consecutivi la sottoscrizione viene disattivata automaticamente (`disabledReason =
CIRCUIT_BREAKER`); correggete il destinatario e riattivatela.

---

## Eventi di rifiuto KYC { #kyc-rejection }

`KYC_REJECTED` trasporta soltanto una categoria fissa e mai le motivazioni del revisore:

```json
{ "entityId": "...", "reasonCode": "INFORMATION_INCOMPLETE" }
```

`reasonCode` è uno tra `INFORMATION_INCOMPLETE`, `DOCUMENTS_UNREADABLE`, `INFORMATION_INCONSISTENT`,
`CONTACT_SUPPORT`. Il motivo interno resta esclusivamente nel log di audit.
