---
title: CASP register import
description: Bulk-updating the counterparty CASP authorization register from a CSV — preview the diff, then commit with the diff digest.
---

# CASP register import

The CASP authorization register drives the [Travel Rule](travel-rule.md) counterparty check: an outbound transfer to a counterparty that the register blocks is refused, and an inbound Travel Rule message from a blocked or revoked sender is stored as `REJECTED_CASP`. Entries mirror the ESMA and national registers. Editing one entry at a time is possible (`PUT /api/v1/compliance/casp-register`); for a register export, use the two-step import.

!!! note "Not a source of truth"
    Registerwerk does not verify a counterparty's authorisation. The register holds what your compliance team entered or imported, and the check is only as good as that data and its freshness.

## Who and what

Both steps are available to `REGISTRY_ADMIN` and `COMPLIANCE_OFFICER` (operator portal: *Compliance → CASP Register → Import CSV*). The preview writes nothing and needs no step-up. The commit needs **step-up and a second approver** (reason `CASP_REGISTER_IMPORT`; see [Approval queue](../operator/security/approval-queue.md)).

## The CSV

Canonical columns (header matching is case-insensitive; `;` or `,` is detected from the header line):

```
legal_name;vasp_did;lei;home_member_state;status;authorization_id;valid_from;valid_until;notes
```

| Column | Rule |
|---|---|
| `legal_name` | Required |
| `status` | Required; see the mapping below |
| `vasp_did` | If empty, the identifier `lei:<LEI>` is synthesised from `lei`. One of the two must be present |
| `lei` | Optional; a LEI that already belongs to another entry fails that row |
| `home_member_state` | Optional, ISO 3166-1 alpha-2 |
| `valid_from`, `valid_until` | Optional, ISO dates `YYYY-MM-DD` |

Status values are mapped tolerantly, including ESMA's British spelling:

| Input (case-insensitive) | Stored status |
|---|---|
| `AUTHORIZED`, `AUTHORISED`, `GRANTED`, `LICENSED`, `LICENCED` | `AUTHORIZED` |
| `TRANSITIONAL`, `GRANDFATHERED`, `TRANSITION`, `TRANSITIONAL_PERIOD` | `TRANSITIONAL` |
| `NOT_AUTHORIZED`, `NOT_AUTHORISED`, `PENDING`, `REFUSED`, `REJECTED` | `NOT_AUTHORIZED` |
| `REVOKED`, `WITHDRAWN`, `LAPSED`, `SUSPENDED` | `REVOKED` |

Anything else fails the row. An import cannot create a `THIRD_COUNTRY_REVIEWED` entry; those need a named reviewer, a second approver and an expiry, and are created one by one.

The file is limited to 5,000,000 characters. Rows are upserted keyed by `vasp_did` (then LEI). The import is best-effort per row: a bad row is reported and skipped, good rows proceed, and only the first 20 error messages are returned.

## Step 1: preview

`POST /api/v1/compliance/casp-register/import/preview` with the CSV as the request body (`Content-Type: text/csv` or `text/plain`). Nothing is written. The response:

| Field | Meaning |
|---|---|
| `created`, `updated` | Rows that would create a new entry or change an existing one |
| `statusChanged` | Rows (counted within the two above) whose status differs from the stored one |
| `failed`, `errors` | Rows that cannot be imported and the first 20 reasons, by line number |
| `committed` | `false` |
| `diffDigest` | SHA-256 of the CSV text |

Review the status changes in particular: a row that turns `AUTHORIZED` into `REVOKED` blocks a counterparty immediately after the commit.

## Step 2: commit

`POST /api/v1/compliance/casp-register/import?diffDigest=<digest>` with the **same** CSV. The server recomputes the digest and refuses the commit with `400` ("digest mismatch") if the text differs from the one previewed, so what was reviewed is what is written. The second approver's approval is bound to the method, path and query, which includes the digest; the body is not part of the approval because it is not JSON, but the digest pins it.

A row that lifts a `NOT_AUTHORIZED` or `REVOKED` status needs a `REGISTRY_ADMIN` as the second approver; with any other approver that row fails. The preview does not check this, so a commit can report failures the preview did not.

The commit writes one row to `casp_register_import` (source, counts, digest, actor, approver) and an audit event `CASP_REGISTER_IMPORTED`; each changed entry is audited as for a single edit.

## Operating notes

- Transform the ESMA export into the canonical columns outside Registerwerk and keep the original file with your evidence; the import keeps the digest, not the file.
- Registerwerk does not remove entries that are absent from the CSV. Withdrawn authorisations arrive as rows with a withdrawn status, never as deletions; deleting an entry is a separate step-up and second-approver action.
- What the register decides (`REGISTERWERK_TRAVEL_RULE_MICA_ENFORCEMENT_DATE`, default 2026-07-01): `NOT_AUTHORIZED` and `REVOKED` always block; `TRANSITIONAL` blocks on and after the enforcement date; `AUTHORIZED` blocks once `valid_until` has passed; a counterparty with no entry is blocked on and after the date. Importing stale `TRANSITIONAL` rows therefore does not keep a counterparty usable.
