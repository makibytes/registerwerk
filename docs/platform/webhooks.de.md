---
title: Webhooks
description: Ausgehende Webhook-Zustellung für API-Clients - Endpunkt-Anforderungen, Signaturverfahren, Replay-Fenster, Event- und Delivery-IDs, Secret-Rotation, Wiederholungen.
---

# Webhooks { #webhooks }

Der **Company-Administrator** einer juristischen Person kann HTTPS-Endpunkte für eine kuratierte Auswahl
von Ereignissen abonnieren (`KYC_APPROVED`, `KYC_REJECTED`, `TRADE_EXECUTED`, Zeichnungsauftrags-Ereignisse
usw.). Registerwerk sendet an jeden abonnierten Endpunkt ein signiertes JSON-Dokument per POST. Diese
Seite ist der Vertrag für Empfänger.

!!! note "Geltungsbereich"
    Webhooks sind ein Benachrichtigungskanal, kein rechtlicher Nachweis. Maßgeblich sind das Register und
    das Audit-Log; nutzen Sie Webhooks, um eigene Abfragen auszulösen.

---

## Anforderungen an den Endpunkt { #endpoint-requirements }

Registerwerk lehnt eine URL (`400`) ab, die nicht alle Bedingungen erfüllt, und prüft sie bei **jeder
Zustellung** erneut:

- Schema `https`, keine Benutzerinformationen (`user:pass@`), Port `443` oder `8443`;
- der Host darf **ausschließlich auf öffentliche Adressen** auflösen - Loopback, private Adressen
  (RFC 1918), Link-Local (einschließlich Cloud-Metadaten `169.254.169.254`), Carrier-Grade-NAT
  (`100.64.0.0/10`), IPv6 Unique-Local (`fc00::/7`), IPv4-gemappte IPv6-Adressen sowie reservierte und
  Multicast-Bereiche werden abgelehnt;
- Weiterleitungen werden **nicht verfolgt** - ein `3xx` gilt als fehlgeschlagene Zustellung;
- Verbindungs-Timeout 3 s, Antwort-Timeout 5 s; der Antwortinhalt wird ignoriert. Antworten Sie schnell mit
  einem beliebigen `2xx` und verarbeiten Sie asynchron.

Die Verbindung wird zu der geprüften Adresse aufgebaut; spätere DNS-Änderungen können Zustellungen daher
nicht auf eine interne Adresse umleiten.

---

## Abonnements verwalten { #managing-subscriptions }

Alle Endpunkte liegen unter `/api/v1/me/webhooks` und erfordern die Rolle `COMPANY_ADMIN`.

| Aufruf | Zweck |
|---|---|
| `POST /` `{ "url", "eventTypes": [] }` | Anlegen. Die Antwort enthält das Signatur-`secret` **einmalig**. Leere `eventTypes` bedeuten alle Ereignisse. |
| `GET /` | Abonnements auflisten (nie mit Secrets). `disabledReason` ist gesetzt, wenn die Plattform eines deaktiviert hat (`URL_POLICY`, `CIRCUIT_BREAKER`). |
| `PUT /{id}/enabled` | Aktivieren oder deaktivieren. Beim Aktivieren wird die URL erneut geprüft. |
| `POST /{id}/rotate-secret` | Neues Secret ausstellen (Step-up erforderlich). Wird einmalig zurückgegeben. |
| `GET /{id}/deliveries` | Zustellprotokoll: `id` (Delivery-ID), `eventId`, `status`, `outcome`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`. |
| `DELETE /{id}` | Entfernen. |

`outcome` ist bewusst grob: `OK`, `RECEIVER_ERROR` (nicht 2xx), `UNREACHABLE` (Verbindung oder Timeout)
oder `BLOCKED` (URL-Richtlinie). HTTP-Statuscodes und Fehlertexte werden nicht offengelegt.

---

## Zustellformat { #delivery-format }

Header:

| Header | Bedeutung |
|---|---|
| `X-Registerwerk-Event` | Ereignistyp, z. B. `TRADE_EXECUTED` |
| `X-Registerwerk-Event-Id` | Kennung des Ereignisses; **für alle Abonnenten identisch** |
| `X-Registerwerk-Delivery` | Kennung dieser Zustellung; **über Wiederholungen stabil** |
| `X-Registerwerk-Timestamp` | Unix-Sekunden, **bei jedem Versuch erneuert** |
| `X-Registerwerk-Signature` | `v1=<hex>`; während einer Secret-Rotation zwei `v1=`-Werte, durch Komma getrennt |

Body:

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

## Zustellung verifizieren { #verifying }

Die Signatur ist `hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + rawBody))`; `timestamp` und
`deliveryId` sind die Header-Werte, `rawBody` sind exakt die empfangenen Bytes (JSON nicht neu serialisieren).

1. Rohen Body und Header lesen.
2. Ablehnen, wenn `abs(now - timestamp) > 300` Sekunden (Replay-Fenster).
3. Erwarteten Wert berechnen und mit **jedem** `v1=`-Wert zeitkonstant vergleichen.
4. Deduplizieren: Arbeit überspringen, wenn die `deliveryId` (Wiederholung) oder `eventId` (mehrere
   Abonnements) bereits verarbeitet wurde. Für Duplikate `2xx` zurückgeben.

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

!!! warning "Ältere Integrationen"
    Der frühere Signatur-Header ausschließlich über den Body wurde **entfernt**: Er ließ sich unbegrenzt
    wiederholen (Replay). Empfänger müssen auf das oben beschriebene `v1`-Verfahren umstellen.

---

## Secret-Rotation { #secret-rotation }

`POST /{id}/rotate-secret` liefert ein neues Secret. Für 24 Stunden signiert auch das bisherige Secret
weiter, sodass jede Zustellung zwei `v1=`-Werte trägt; prüfen Sie gegen das Secret, das Sie noch besitzen,
rollen Sie das neue aus - nach der Überlappung wird das alte nicht mehr verwendet. Secrets werden
verschlüsselt gespeichert und können nicht erneut abgerufen werden - bei Verlust rotieren.

---

## Wiederholungen und automatische Deaktivierung { #retries }

Eine fehlgeschlagene Zustellung wird mit exponentiellem Backoff (etwa 1, 2, 4 ... Minuten, maximal 1
Stunde, mit Jitter) bis zu 8 Mal wiederholt. Jeder Versuch wird mit neuem Zeitstempel neu signiert. Nach 20
aufeinanderfolgenden fehlgeschlagenen Versuchen wird das Abonnement automatisch deaktiviert
(`disabledReason = CIRCUIT_BREAKER`); beheben Sie den Empfänger und aktivieren Sie es erneut.

---

## KYC-Ablehnungsereignisse { #kyc-rejection }

`KYC_REJECTED` enthält nur eine feste Kategorie und nie die Begründung der prüfenden Person:

```json
{ "entityId": "...", "reasonCode": "INFORMATION_INCOMPLETE" }
```

`reasonCode` ist einer von `INFORMATION_INCOMPLETE`, `DOCUMENTS_UNREADABLE`, `INFORMATION_INCONSISTENT`,
`CONTACT_SUPPORT`. Die interne Begründung verbleibt ausschließlich im Audit-Log.
