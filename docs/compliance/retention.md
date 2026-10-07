---
title: Retention matrix
description: Planning retention periods per record class, the legal basis they rest on, who owns them, where the configuration placeholder is, and what is and is not purged automatically.
---

# Retention matrix

!!! warning "Nothing compliance-relevant is purged automatically"
    Registerwerk **does not delete** register entries, KYC evidence, screening decisions, audit events, corporate-action records or regulatory reports when a retention period ends. The periods below are planning defaults for the operator's retention schedule, not a legal determination, and nothing in the product enforces them. The only automatic deletions are the technical artefacts in the last table. Retention decisions, controller and processor roles and the handling of personal data after the period belong to the operator and its data-protection officer; the open questions are recorded under T6-10 on the [open decisions](../platform/known-limitations.md) page.

The periods follow the review programme's interim for decision T6-10: **5 years** for AML/CDD records and **10 years** for register records. They are consistent with the draft [records of processing](ropa.md), which is also a draft. Where the draft ROPA and this page differ, the stricter (longer) period is the one to keep until the DPO decides.

## Compliance and business records (never purged by the product)

| Record class | Where it lives | Planning period | Basis cited in the drafts | Owner (proposed) | Configuration placeholder | Purged automatically |
|---|---|---|---|---|---|---|
| Register entries, holder positions, transfer history, register statements, inspection records | `asset_holder`, `token_transfer`, register statements | 10 years after redemption or cancellation of the instrument | eWpG §15(3) | Registry operator; DPO for the schedule | none | No |
| KYC/KYB evidence (documents, beneficial owners, approval records) | KYC tables, `kyc_document_content` | 5 years after the end of the business relationship (GwG §8). The jurisdiction profiles carry reference values: DE 10, LI 10, LU 5, FR 5 years | GwG §8, TVTG §33, AML Law 2004 Art. 4, LCB-FT | Compliance (MLRO); DPO | `registerwerk.kyc.max-validity-months` (12) bounds the *validity* of an approval, not retention. The profile value `dataRetentionPeriod` is reference data only and nothing reads it for deletion | No |
| Sanctions and PEP screening runs, hits and acceptances | `screening_run`, `screening_hit` | 5 years | GwG §8 | Compliance (MLRO) | `registerwerk.screening.accept-validity-days` (90) bounds an accepted false positive, not retention | No |
| Audit log and its hash chain, signed anchors | `audit_event` (partitioned), anchors | 10 years (draft ROPA) | eWpG §15(3); record integrity | Operator security; DPO | `REGISTERWERK_AUDIT_ANCHOR_S3_RETENTION_DAYS` (3650) sets the Object Lock retention of the daily anchor object; see [Audit log](../platform/audit-log.md) | No |
| Trading records and transaction reports (MiFIR) | trade, listing and reporting tables | 7 years in the draft ROPA (MiFIR Art. 25(1); confirm with legal) | MiFIR | Compliance | none | No |
| Corporate-action entries and income statements | `corporate_action_entry` | Per applicable tax and commercial law; to be confirmed by the operator's tax adviser (nothing decided) | tax and commercial retention rules | Finance; DPO | none | No |
| Register/chain drift incident records | `chain_drift_event` | 10 years (eWpG §16 evidence; treat like register records) | eWpG §16 | Registry operator | none | No |
| Travel Rule messages and counterparty register | `travel_rule_message`, `travel_rule_peer`, CASP register | Open: which IVMS fields survive expiry is undecided (T6-10) | TFR | Compliance | none | No |
| DORA incidents, ICT provider and resilience-test records | `ict_incident*`, `resilience_test`, provider tables | Open; set by the ICT-risk policy | DORA | ICT risk | none | No |
| Operator user accounts | `app_user` | Duration of employment plus 2 years (draft ROPA) | legitimate interest | HR; DPO | none | No (an administrator can delete an account with step-up and a second approver; nothing deletes automatically) |
| Customer support tickets | `support_ticket` | 3 years after last contact (draft ROPA) | legitimate interest | Operations; DPO | none | No |
| Erasure and data-subject requests | erasure request table | Keep as evidence of the decision, with the retained and erased lists | Accountability (GDPR Art. 5(2)); DPO to confirm | DPO | none | No |

Natural-person data subject requests are limited: the export covers legal-entity master data, and an erasure ends as `COMPLETED_PARTIAL` with lists of what was erased, retained (with the legal basis) and not covered. See [Data protection](data-protection.md).

## Technical artefacts (purged automatically)

A nightly job (03:15, `RetentionSweepJob`) deletes aged technical and security artefacts in bounded batches. Each target has its own switch and window under `registerwerk.retention.<target>.enabled`, `.max-age` and `.batch-size`; as environment variables, drop the dashes (`REGISTERWERK_RETENTION_LOGINATTEMPT_MAXAGE`).

| Target (property name) | Default window | What it is |
|---|---|---|
| `login-attempt` | 30 days | Brute-force counters |
| `wallet-bind-challenge` | 30 days | Spent wallet-binding nonces |
| `onboarding-token` | 90 days | Used or expired onboarding tokens |
| `app-user-action-token` | 90 days | Used or expired invitation and reset tokens |
| `webhook-delivery` | 90 days | Terminal webhook deliveries |
| `event-publication` | 30 days | Completed Spring Modulith outbox rows |
| `chaincache-event-inbox` | 30 days | Processed chaincache events |

Other automatic deletions: idempotency records after 48 hours, audit de-duplication ids after 30 days, and soft-deleted operator wallets after `REGISTERWERK_WALLET_RETENTION_DAYS` (default 90), when the encrypted key material is destroyed.

## How to use this page

1. Have the DPO confirm or replace the planning period per row and record the legal basis and the named owner.
2. Do not shorten the technical windows below what your incident and audit needs require; they are independent of the legal periods.
3. If your policy requires deletion at the end of a period, that is a manual, evidenced process today. Registerwerk provides no sweep for these classes; see the known limitations.
