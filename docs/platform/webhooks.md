---
title: Webhooks
description: Outbound webhook delivery for API clients - endpoint requirements, signature scheme, replay window, event and delivery ids, secret rotation, retries.
---

# Webhooks { #webhooks }

A legal entity's **company administrator** can subscribe HTTPS endpoints to a curated set of events
(`KYC_APPROVED`, `KYC_REJECTED`, `TRADE_EXECUTED`, subscription-order events, and so on). Registerwerk
POSTs a signed JSON document to each subscribed endpoint. This page is the contract for receivers.

!!! note "Scope"
    Webhooks are a notification channel, not a legal record. Treat the register and the audit log as
    authoritative and use webhooks to trigger your own reads.

---

## Endpoint requirements { #endpoint-requirements }

Registerwerk refuses a URL (`400`) that does not satisfy all of these, and re-checks it on **every
delivery**:

- scheme `https`, no user-info (`user:pass@`), port `443` or `8443`;
- the host must resolve **only to public addresses** - loopback, private (RFC 1918), link-local
  (including cloud metadata `169.254.169.254`), carrier-grade NAT (`100.64.0.0/10`), IPv6 unique-local
  (`fc00::/7`), IPv4-mapped IPv6 and reserved/multicast ranges are all refused;
- redirects are **not followed** - a `3xx` counts as a failed delivery;
- connect timeout 3 s, response timeout 5 s; the response body is ignored. Answer with any `2xx`
  quickly and process asynchronously.

The connection is opened to the address that was validated, so changing DNS records after
registration cannot redirect deliveries to an internal address.

---

## Managing subscriptions { #managing-subscriptions }

All endpoints are under `/api/v1/me/webhooks` and require the `COMPANY_ADMIN` role.

| Call | Purpose |
|---|---|
| `POST /` `{ "url", "eventTypes": [] }` | Create. The response contains the signing `secret` **once**. Empty `eventTypes` means all events. |
| `GET /` | List subscriptions (never returns secrets). `disabledReason` is set when the platform disabled one (`URL_POLICY`, `CIRCUIT_BREAKER`). |
| `PUT /{id}/enabled` | Enable or disable. Re-enabling re-validates the URL. |
| `POST /{id}/rotate-secret` | Issue a new secret (step-up required). Returned once. |
| `GET /{id}/deliveries` | Delivery log: `id` (delivery id), `eventId`, `status`, `outcome`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`. |
| `DELETE /{id}` | Remove. |

`outcome` is deliberately coarse: `OK`, `RECEIVER_ERROR` (non-2xx), `UNREACHABLE` (connection or
timeout) or `BLOCKED` (URL policy). HTTP status codes and error text are not exposed.

---

## Delivery format { #delivery-format }

Headers:

| Header | Meaning |
|---|---|
| `X-Registerwerk-Event` | Event type, e.g. `TRADE_EXECUTED` |
| `X-Registerwerk-Event-Id` | Identifies the event; **the same for every subscriber** of it |
| `X-Registerwerk-Delivery` | Identifies this delivery; **stable across retries** |
| `X-Registerwerk-Timestamp` | Unix seconds, **refreshed on every attempt** |
| `X-Registerwerk-Signature` | `v1=<hex>`; during a secret rotation two `v1=` values, comma-separated |

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

## Verifying a delivery { #verifying }

The signature is `hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + rawBody))`, where
`timestamp` and `deliveryId` are the header values and `rawBody` is the exact bytes received (do not
re-serialize the JSON).

1. Read the **raw** body and the headers.
2. Reject if `abs(now - timestamp) > 300` seconds (replay window).
3. Compute the expected value and compare with **every** `v1=` value using a constant-time comparison.
4. Deduplicate: skip work if you already processed this `deliveryId` (retries) or `eventId` (you may
   have several subscriptions). Return `2xx` for duplicates.

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

!!! warning "Older integrations"
    The earlier body-only signature header (HMAC over the body alone) has been **removed**: it could be
    replayed indefinitely. Receivers must move to the `v1` scheme above.

---

## Secret rotation { #secret-rotation }

`POST /{id}/rotate-secret` returns a new secret. For 24 hours the previous secret keeps signing too, so
each delivery carries two `v1=` values; verify against whichever secret you still hold, deploy the new
one, and the old one stops being used after the overlap. Secrets are stored encrypted at rest and cannot
be retrieved again - rotate if one is lost.

---

## Retries and automatic disabling { #retries }

A failed delivery is retried with exponential backoff (about 1, 2, 4 ... minutes, capped at 1 hour, with
jitter) up to 8 attempts. Every attempt re-signs with a fresh timestamp. After 20 consecutive failed
attempts the subscription is disabled automatically (`disabledReason = CIRCUIT_BREAKER`); fix the
receiver and enable it again.

---

## KYC rejection events { #kyc-rejection }

`KYC_REJECTED` carries only a fixed category and never the reviewer's reasoning:

```json
{ "entityId": "...", "reasonCode": "INFORMATION_INCOMPLETE" }
```

`reasonCode` is one of `INFORMATION_INCOMPLETE`, `DOCUMENTS_UNREADABLE`, `INFORMATION_INCONSISTENT`,
`CONTACT_SUPPORT`. The internal reason is retained in the audit log only.
