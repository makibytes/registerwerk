---
title: Registro de auditoría
description: Registro de auditoría de cadena hash a prueba de manipulaciones: esquema, verificación de integridad y gestión de particiones.
---

# Registro de auditoría { #audit-log }

Las rutas de aplicación auditadas emiten un `AuditEvent`; aún no se ha demostrado la cobertura para cada mutación de estado.
La tabla `audit_event` es de solo inserción (append-only), encadenada mediante hash y particionada por PostgreSQL por mes. Estos son solo controles técnicos: la integridad, la retención, la supervisión operativa y la adecuación legal según eWpG, GwG, DORA o GDPR requieren evidencia separada y revisión externa.

---

## Esquema { #schema }

```sql
CREATE TABLE audit_event (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_no     BIGINT       GENERATED ALWAYS AS IDENTITY,
    event_type      TEXT         NOT NULL,
    actor_id        UUID,                        -- NULL for system-initiated events
    entity_id       UUID,                        -- The primary entity affected
    asset_id        UUID,                        -- If asset-related
    jurisdiction    TEXT,                        -- Jurisdiction context
    payload         JSONB        NOT NULL,       -- Full event details
    prev_hash       BYTEA,                       -- SHA-256 of previous entry
    entry_hash      BYTEA        NOT NULL,       -- SHA-256(prev_hash ‖ payload ‖ sequence_no)
    signature       BYTEA,                       -- Ed25519 over entry_hash (optional)
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id        TEXT                         -- OpenTelemetry trace ID
) PARTITION BY RANGE (occurred_at);
```

---

## Cadena hash { #hash-chain }

Cada `AuditEvent` lleva:

- `prev_hash` — el `entry_hash` de la fila inmediatamente anterior (por `sequence_no`)
- `entry_hash` — `SHA-256(prev_hash ‖ canonical_json(payload) ‖ sequence_no)`

El primer evento de la cadena tiene `prev_hash = null`; su `entry_hash` es `SHA-256(null ‖ payload ‖ 1)`.

```mermaid
graph LR
    E1["seq=1<br/>prev_hash=null<br/>entry_hash=H1"] --> E2["seq=2<br/>prev_hash=H1<br/>entry_hash=H2"]
    E2 --> E3["seq=3<br/>prev_hash=H2<br/>entry_hash=H3"]
    E3 --> En["seq=n<br/>prev_hash=H(n-1)<br/>entry_hash=Hn"]
```

**Detección de manipulación:** Si se modifica alguna fila, su `entry_hash` ya no coincidirá con `SHA-256(prev_hash ‖ payload ‖ sequence_no)`. El `prev_hash` de cada fila posterior también será incorrecto. `AuditChainVerificationService.verify()` detecta esto y devuelve el número de secuencia del primer enlace roto.

---

## Aplicación de solo inserción (append-only) { #append-only-enforcement }

Un disparador (trigger) de PostgreSQL en `audit_event` genera una excepción ante cualquier `UPDATE` o `DELETE`:

```sql
CREATE TRIGGER audit_event_no_update_delete
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION raise_immutable_exception();
```

Incluso el superusuario de la base de datos no puede modificar registros sin deshabilitar primero este disparador, lo que a su vez requiere un procedimiento de emergencia (break-glass) y genera una entrada de registro `pg_audit`.

---

## Ancla diaria { #daily-anchor }

Cada 24 horas, `AuditChainVerificationService` agrega un **evento ancla**:

- `event_type = AUDIT_ANCHOR`
- `payload` contiene el `entry_hash` del último evento del día y una marca de tiempo UTC
- Opcionalmente, el hash de anclaje se escribe en la red principal de Ethereum como una transacción con datos de llamada (calldata), creando una referencia cruzada pública e inmutable

El ancla permite a los auditores externos verificar que la cadena de auditoría en una fecha determinada coincida con un hash conocido, sin necesidad de reproducir toda la cadena desde génesis.

---

## Tipos de eventos { #event-types }

| Tipo de evento | Activador |
|---|---|
| `ASSET_CREATED` / `ASSET_DEPLOYED` / `ASSET_STATUS_CHANGED` | Ciclo de vida del activo |
| `KYC_SUBMITTED` / `KYC_APPROVED` / `KYC_REJECTED` / `KYC_EXPIRED` | Flujo de trabajo KYC |
| `HOLDER_BLOCK_CREATED` / `HOLDER_BLOCK_LIFTED` / `HOLDER_BLOCK_EXPIRED` | Sperrvermerk |
| `SCREENING_RUN_COMPLETED` / `SCREENING_HIT_ACCEPTED` | Filtrado de sanciones |
| `FORCE_TRANSFER` / `FORCE_BURN` / `FORCE_APPROVE` | Operaciones privilegiadas sobre tokens |
| `STEP_UP_ISSUED` / `DUAL_CONTROL_CONFIRMED` / `PROTECTED_OPERATION_EXECUTED` | Autenticación reforzada (step-up) |
| `IMPERSONATION_STARTED` / `IMPERSONATION_ENDED` | Suplantación de administrador |
| `ICT_INCIDENT_CREATED` / `ICT_INCIDENT_RESOLVED` | Incidentes DORA |
| `REGREPORT_SUBMITTED` | Archivo MiFIR / DAC8 |
| `NATURAL_PERSON_REDACTED` | Borrado de GDPR |
| `AUDIT_ANCHOR` | Ancla hash diaria |

---

## Gestión de particiones { #partition-management }

`audit_event` está particionada por rango por `occurred_at` (particiones mensuales):

- Partición activa: `audit_event_YYYY_MM` para el mes actual
- Un trabajo `@Scheduled(cron = "0 0 1 1 * *")` crea los próximos 6 meses de particiones antes de tiempo
- `audit_event_default` detecta cualquier evento que quede fuera de una partición definida (nunca debería ocurrir si el trabajo se ejecuta correctamente)

!!! warning "Caducidad de la partición"
    El esquema inicial se envía con particiones durante 3 meses. El trabajo de creación de partición programada debe ejecutarse antes de que caduque la última partición, o los eventos caerán en `audit_event_default` (lo que desencadena un incidente DORA `MEDIUM` automáticamente).

---

## Verificando la cadena de auditoría { #verifying-the-audit-chain }

```
GET /api/v1/admin/audit/verify
```

Devuelve:

```json
{
  "status": "OK",
  "lastVerifiedAt": "2026-05-22T03:00:00Z",
  "lastSequenceNo": 1847293,
  "lastEntryHash": "a3f7...",
  "brokenAt": null
}
```

Si `brokenAt` no es nulo, contiene el `sequence_no` de la primera entrada donde se rompe la cadena hash. Esto activa un `IctIncident` automático de gravedad `MAJOR` y categoría `INTEGRITY`.
