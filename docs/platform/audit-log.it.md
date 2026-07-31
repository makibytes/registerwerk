---
title: Pista di controllo
description: Pista di controllo a catena hash a prova di manomissione — schema, verifica dell'integrità e gestione delle partizioni.
---

# Pista di controllo { #audit-log }

I percorsi applicativi sottoposti a controllo emettono un `AuditEvent`; la copertura non è ancora stata dimostrata per ogni mutazione di stato.
La tabella `audit_event` è di sola aggiunta, concatenata con hash e partizionata PostgreSQL per mese. Questi sono
solo controlli tecnici: completezza, conservazione, monitoraggio operativo e adeguatezza legale ai sensi di
eWpG, GwG, DORA o GDPR richiedono prove separate e revisione esterna.

---

## Schema { #schema }

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

## Catena hash { #hash-chain }

Ogni `AuditEvent` trasporta:

- `prev_hash` — `entry_hash` della riga immediatamente precedente (tramite `sequence_no`)
- `entry_hash` — `SHA-256(prev_hash ‖ canonical_json(payload) ‖ sequence_no)`

Il primo evento della catena ha `prev_hash = null`; il suo `entry_hash` è `SHA-256(null ‖ payload ‖ 1)`.

```mermaid
graph LR
    E1["seq=1<br/>prev_hash=null<br/>entry_hash=H1"] --> E2["seq=2<br/>prev_hash=H1<br/>entry_hash=H2"]
    E2 --> E3["seq=3<br/>prev_hash=H2<br/>entry_hash=H3"]
    E3 --> En["seq=n<br/>prev_hash=H(n-1)<br/>entry_hash=Hn"]
```

**Rilevamento manomissione:** Se una riga viene modificata, la relativa `entry_hash` non corrisponderà più a `SHA-256(prev_hash ‖ payload ‖ sequence_no)`. Anche `prev_hash` di ogni riga successiva sarà errato. `AuditChainVerificationService.verify()` lo rileva e restituisce il numero di sequenza del primo collegamento interrotto.

---

## Applicazione di sola aggiunta { #append-only-enforcement }

Il trigger PostgreSQL su `audit_event` solleva un'eccezione su qualsiasi `UPDATE` o `DELETE`:

```sql
CREATE TRIGGER audit_event_no_update_delete
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION raise_immutable_exception();
```

Anche il superutente del database non può modificare i record senza prima disabilitare questo trigger, che a sua volta richiede una procedura break-glass e genera una voce di registro `pg_audit`.

---

## Ancoraggio giornaliero { #daily-anchor }

Ogni 24 ore, `AuditChainVerificationService` aggiunge un **evento di ancoraggio**:

- `event_type = AUDIT_ANCHOR`
- `payload` contiene `entry_hash` dell'ultimo evento del giorno e un timestamp UTC
- Facoltativamente, l'hash di ancoraggio viene scritto sulla rete principale di Ethereum come transazione calldata, creando un riferimento incrociato pubblico e immutabile

L'ancora consente agli auditor esterni di verificare che la catena di audit in una determinata data corrispondesse a un hash noto, senza la necessità di riprodurre l'intera catena dalla genesi.

---

## Tipi di eventi { #event-types }

| Tipo evento | Trigger |
|---|---|
| `ASSET_CREATED` / `ASSET_DEPLOYED` / `ASSET_STATUS_CHANGED` | Ciclo di vita dell'asset |
| `KYC_SUBMITTED` / `KYC_APPROVED` / `KYC_REJECTED` / `KYC_EXPIRED` | Flusso di lavoro KYC |
| `HOLDER_BLOCK_CREATED` / `HOLDER_BLOCK_LIFTED` / `HOLDER_BLOCK_EXPIRED` | Sperrvermerk |
| `SCREENING_RUN_COMPLETED` / `SCREENING_HIT_ACCEPTED` | Screening sanzioni |
| `FORCE_TRANSFER` / `FORCE_BURN` / `FORCE_APPROVE` | Operazioni token privilegiate |
| `STEP_UP_ISSUED` / `DUAL_CONTROL_CONFIRMED` / `PROTECTED_OPERATION_EXECUTED` | Autenticazione avanzata |
| `IMPERSONATION_STARTED` / `IMPERSONATION_ENDED` | Modalità supporto (impersonation) |
| `ICT_INCIDENT_CREATED` / `ICT_INCIDENT_RESOLVED` | Incidenti DORA |
| `REGREPORT_SUBMITTED` | Archiviazione MiFIR / DAC8 |
| `NATURAL_PERSON_REDACTED` | GDPR cancellazione |
| `AUDIT_ANCHOR` | Ancoraggio hash giornaliero |

---

## Gestione delle partizioni { #partition-management }

`audit_event` è suddiviso in intervalli da `occurred_at` (partizioni mensili):

- Partizione attiva: `audit_event_YYYY_MM` per il mese corrente
- Un lavoro `@Scheduled(cron = "0 0 1 1 * *")` crea i successivi 6 mesi di partizioni in anticipo
- `audit_event_default` rileva tutti gli eventi che non rientrano in una partizione definita (non dovrebbe mai verificarsi se il lavoro viene eseguito correttamente)

!!! warning "Scadenza partizione"
    Lo schema iniziale viene fornito con partizioni per 3 mesi. Il processo di creazione della partizione pianificata deve essere eseguito prima della scadenza dell'ultima partizione, altrimenti gli eventi rientreranno in `audit_event_default` (che attiva automaticamente un incidente DORA `MEDIUM`).

---

## Verifica della catena di controllo { #verifying-the-audit-chain }

```
GET /api/v1/admin/audit/verify
```

Restituisce:

```json
{
  "status": "OK",
  "lastVerifiedAt": "2026-05-22T03:00:00Z",
  "lastSequenceNo": 1847293,
  "lastEntryHash": "a3f7...",
  "brokenAt": null
}
```

Se `brokenAt` non è nullo, contiene `sequence_no` della prima voce in cui la catena hash è interrotta. Ciò attiva un `IctIncident` automatico di gravità `MAJOR` e categoria `INTEGRITY`.
