---
title: Webhooks
description: Entrega de webhooks salientes para clientes de API - requisitos del endpoint, esquema de firma, ventana antirreproducción, identificadores de evento y de entrega, rotación del secreto, reintentos.
---

# Webhooks { #webhooks }

El **administrador de la empresa** de una persona jurídica puede suscribir endpoints HTTPS a un conjunto
seleccionado de eventos (`KYC_APPROVED`, `KYC_REJECTED`, `TRADE_EXECUTED`, eventos de órdenes de
suscripción, etc.). Registerwerk envía mediante POST un documento JSON firmado a cada endpoint suscrito.
Esta página es el contrato para los receptores.

!!! note "Alcance"
    Los webhooks son un canal de notificación, no un registro jurídico. Son determinantes el registro y el
    registro de auditoría; use los webhooks para desencadenar sus propias consultas.

---

## Requisitos del endpoint { #endpoint-requirements }

Registerwerk rechaza (`400`) toda URL que no cumpla todas estas condiciones y la vuelve a comprobar en
**cada entrega**:

- esquema `https`, sin información de usuario (`user:pass@`), puerto `443` o `8443`;
- el host debe resolverse **únicamente a direcciones públicas**: se rechazan loopback, direcciones privadas
  (RFC 1918), enlace local (incluidos los metadatos de la nube `169.254.169.254`), NAT de operador
  (`100.64.0.0/10`), IPv6 de ámbito único local (`fc00::/7`), IPv6 mapeadas a IPv4 y rangos
  reservados/multidifusión;
- las redirecciones **no se siguen**: un `3xx` cuenta como entrega fallida;
- tiempo de conexión 3 s, tiempo de respuesta 5 s; el cuerpo de la respuesta se ignora. Responda pronto con
  cualquier `2xx` y procese de forma asíncrona.

La conexión se abre hacia la dirección validada, por lo que cambiar el DNS tras el registro no puede
redirigir las entregas a una dirección interna.

---

## Gestión de suscripciones { #managing-subscriptions }

Todos los endpoints están bajo `/api/v1/me/webhooks` y requieren el rol `COMPANY_ADMIN`.

| Llamada | Finalidad |
|---|---|
| `POST /` `{ "url", "eventTypes": [] }` | Crear. La respuesta contiene el `secret` de firma **una sola vez**. `eventTypes` vacío significa todos los eventos. |
| `GET /` | Listar suscripciones (nunca devuelve secretos). `disabledReason` se rellena cuando la plataforma ha desactivado una (`URL_POLICY`, `CIRCUIT_BREAKER`). |
| `PUT /{id}/enabled` | Activar o desactivar. Reactivar vuelve a validar la URL. |
| `POST /{id}/rotate-secret` | Emitir un nuevo secreto (requiere step-up). Se devuelve una sola vez. |
| `GET /{id}/deliveries` | Registro de entregas: `id` (identificador de entrega), `eventId`, `status`, `outcome`, `attemptCount`, `lastAttemptedAt`, `nextAttemptAt`. |
| `DELETE /{id}` | Eliminar. |

`outcome` es deliberadamente grueso: `OK`, `RECEIVER_ERROR` (no 2xx), `UNREACHABLE` (conexión o tiempo
agotado) o `BLOCKED` (política de URL). No se exponen códigos HTTP ni textos de error.

---

## Formato de entrega { #delivery-format }

Cabeceras:

| Cabecera | Significado |
|---|---|
| `X-Registerwerk-Event` | Tipo de evento, p. ej. `TRADE_EXECUTED` |
| `X-Registerwerk-Event-Id` | Identifica el evento; **el mismo para todos los suscriptores** |
| `X-Registerwerk-Delivery` | Identifica esta entrega; **estable entre reintentos** |
| `X-Registerwerk-Timestamp` | Segundos Unix, **renovados en cada intento** |
| `X-Registerwerk-Signature` | `v1=<hex>`; durante una rotación del secreto, dos valores `v1=` separados por coma |

Cuerpo:

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

## Verificar una entrega { #verifying }

La firma es `hex(HMAC-SHA256(secret, timestamp + "." + deliveryId + "." + rawBody))`, donde `timestamp` y
`deliveryId` son los valores de las cabeceras y `rawBody` son exactamente los bytes recibidos (no vuelva a
serializar el JSON).

1. Lea el cuerpo **sin procesar** y las cabeceras.
2. Rechace si `abs(now - timestamp) > 300` segundos (ventana antirreproducción).
3. Calcule el valor esperado y compárelo con **cada** valor `v1=` en tiempo constante.
4. Deduplique: omita el trabajo si ya procesó este `deliveryId` (reintento) o `eventId` (varias
   suscripciones). Devuelva `2xx` para los duplicados.

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

!!! warning "Integraciones anteriores"
    La cabecera de firma anterior, que cubría solo el cuerpo, ha sido **eliminada**: podía reproducirse
    indefinidamente. Los receptores deben migrar al esquema `v1` descrito arriba.

---

## Rotación del secreto { #secret-rotation }

`POST /{id}/rotate-secret` devuelve un nuevo secreto. Durante 24 horas el secreto anterior sigue firmando,
de modo que cada entrega lleva dos valores `v1=`; verifique con el secreto que aún conserve, despliegue el
nuevo, y tras el solapamiento el antiguo deja de usarse. Los secretos se almacenan cifrados y no pueden
recuperarse: rote si pierde uno.

---

## Reintentos y desactivación automática { #retries }

Una entrega fallida se reintenta con retroceso exponencial (aprox. 1, 2, 4 ... minutos, con tope de 1 hora
y con jitter) hasta 8 intentos. Cada intento se vuelve a firmar con una marca de tiempo nueva. Tras 20
intentos fallidos consecutivos la suscripción se desactiva automáticamente (`disabledReason =
CIRCUIT_BREAKER`); corrija el receptor y actívela de nuevo.

---

## Eventos de rechazo KYC { #kyc-rejection }

`KYC_REJECTED` solo lleva una categoría fija y nunca el razonamiento del revisor:

```json
{ "entityId": "...", "reasonCode": "INFORMATION_INCOMPLETE" }
```

`reasonCode` es uno de `INFORMATION_INCOMPLETE`, `DOCUMENTS_UNREADABLE`, `INFORMATION_INCONSISTENT`,
`CONTACT_SUPPORT`. El motivo interno se conserva únicamente en el registro de auditoría.
