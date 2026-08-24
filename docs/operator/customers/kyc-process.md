---
title: KYC Process
---

This page describes the operator workflow for jurisdiction-aware KYC records and checklist decisions. These controls are not yet enforced uniformly across every issuance, deployment, receipt, or transfer path.

## Scope and model

Registerwerk stores:

- Entity-level KYC status (`legal_entity.kyc_status`) for generic onboarding lifecycle.
- Jurisdiction-level KYC approvals (`kyc_jurisdiction_approval`) for issuance eligibility under specific regimes.
- Jurisdiction-tagged documents (`kyc_document.jurisdiction`) where `null` means universal evidence that may satisfy multiple jurisdictions.

## Document handling

- Files smaller than 5 MB are stored in PostgreSQL.
- Files 5 MB and above are stored in S3-compatible object storage.
- Access and state-changing actions are written to the audit trail.

## Uploading KYC evidence

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/documents \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -F "file=@certificate.pdf" \
   -F "documentType=INCORPORATION_CERTIFICATE" \
   -F "jurisdiction=DE_EWPG"
```

If `jurisdiction` is omitted, the document is treated as universal.

## Compliance checklist before approval

Before jurisdiction approval, the backend computes compliance against the configured profile for that jurisdiction.

```bash
GET /api/v1/entities/{entityId}/kyc/compliance/{jurisdiction}
```

The response includes:

- `missingCount`
- `expiredCount`
- `tooOldCount`
- `fullyCompliant` (a code-level name meaning no configured checklist gap, not legal compliance)

## Approving jurisdiction KYC

Authorization model:

- `ROLE_COMPLIANCE_OFFICER`: may approve only if `fullyCompliant=true` for the configured checklist.
- `ROLE_REGISTRY_ADMIN`: may approve cases with or without configured checklist gaps.
- Approval with checklist gaps requires `overrideNote` and is rejected for non-admin users.

Approval with no configured checklist gaps:

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"expiresAt":"2027-01-31"}'
```

If checklist gaps exist, approval is blocked unless an explicit override note is provided:

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"overrideNote":"Approved by compliance officer after manual source-of-funds review."}'
```

When override is used, the note is stored in `kyc_jurisdiction_approval.override_note` and compliance counters are included in the audit event.

## Rejection workflow

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/reject \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"reason":"Missing certified beneficial ownership register extract."}'
```

## On-chain claims and token controls

Jurisdiction approval is available to backend workflows, but a central fail-closed operation gate is not yet applied uniformly. ERC-3643 contracts and ONCHAINID claims provide separate contract-level restrictions where configured; they do not prove complete KYC enforcement or legal eligibility.

## Regulatory note

This feature set supports evidencing KYC controls and auditability. It does not by itself discharge
licensing, suspicious-activity reporting, travel-rule, or local supervisory duties.

## Override reporting

Auditors and admins can list override approvals by jurisdiction and period:

```bash
GET /api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z
```
