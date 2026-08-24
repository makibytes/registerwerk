---
title: Regulatory Compliance Scope
---

This page defines what Registerwerk implements for compliance support and what remains with the regulated operator.

## Important disclaimer

Registerwerk is compliance-enabling software, not a legal determination engine. Regulatory obligations depend on jurisdiction, license scope, and supervisory interpretation.

## Jurisdiction profiles in scope

Registerwerk includes jurisdiction identifiers and configurable KYC requirement profiles for:

- `DE_EWPG` (Germany, BaFin, eWpG context)
- `LU_CSSF` (Luxembourg, CSSF)
- `FR_AMF` (France, AMF)
- `LI_TVTG` (Liechtenstein, FMA, TVTG context)

These profiles are operational controls for document collection and approval workflows. They are not legal advice and must be reviewed by legal/compliance teams before production use.

## Controls implemented by platform

- Jurisdiction-specific KYC document checklist evaluation.
- Per-jurisdiction approval state with expiry and rejection reason.
- Mandatory justification (`overrideNote`) for approvals when required evidence is missing, expired, or too old.
- Immutable audit event stream for KYC submissions, approvals, rejections, and overrides.
- Dedicated override report API (`/api/v1/audit/reports/kyc-overrides`) for audit committees.
- API-level authorization for sensitive KYC actions.
- Data retention building blocks in PostgreSQL/S3 with controlled retrieval paths.

## Controls outside platform scope

Operators remain responsible for:

- Licensing and registration status with competent authorities.
- AML/CFT risk methodology and suspicious activity reporting duties.
- Sanctions screening provider quality, tuning, and escalation policy.
- Beneficial ownership verification standards and evidentiary sufficiency.
- MiCA/MiFID/eWpG legal qualification and disclosure obligations.
- Privacy law governance (lawful basis, DPIA decisions, transfer mechanisms, DSAR governance).

## Regulatory references used for baseline alignment

- Germany: eWpG structure and register duties.
- EU: MiCA framework principles for crypto-asset services.
- EU: GDPR principles for lawful processing, minimization, security, accountability.
- Global AML baseline: FATF Recommendations risk-based approach.

## Suggested operator governance pack

Before go-live, maintain these artifacts outside source code and review periodically:

- Jurisdictional legal memo for product scope and licensing boundaries.
- KYC/AML policy with escalation matrix and approval authority levels.
- Sanctions and transaction monitoring operating procedures.
- Data protection controls register (retention, access control, incident response).
- Change management process for jurisdiction profile updates and legal sign-off.
