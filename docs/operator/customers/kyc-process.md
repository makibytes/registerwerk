---
id: kyc-process
title: KYC Process
sidebar_label: KYC Process
sidebar_position: 2
---

# KYC Process

This page describes how operators review and approve KYC (Know Your Customer) document submissions from investors and issuers.

## KYC document requirements

The registry requires the following documents depending on entity type:

### Individual investors

| Document | Description |
|----------|-------------|
| Government-issued photo ID | Passport, national ID card, or driving licence |
| Proof of address | Utility bill or bank statement (less than 3 months old) |
| Source of funds declaration | Signed declaration of the source of funds for investment |

### Corporate entities (issuers and corporate investors)

| Document | Description |
|----------|-------------|
| Certificate of incorporation | Or equivalent registration document |
| Memorandum and articles of association | Or equivalent constitutional document |
| UBO register / beneficial ownership declaration | All UBOs with >25% ownership |
| Director identification | Photo ID for all directors |
| Proof of registered address | Utility bill or bank statement |
| LEI certificate | Valid Legal Entity Identifier |

## Reviewing a KYC submission

When an investor or issuer submits their KYC documents, a `KYC_SUBMITTED` event appears in the audit log and the entity's status changes to `PENDING_KYC`.

To review:

1. Navigate to **KYC → Pending Reviews** in the operator frontend
2. Click on the entity name to open the KYC review screen
3. Each submitted document is listed with:
   - Document type
   - File name and size
   - Upload timestamp
   - A preview or download link
4. Review each document:
   - Click **Approve** if the document is satisfactory
   - Click **Flag** if the document needs clarification
5. Once all documents are reviewed, either:
   - Click **Approve KYC** — issues KYC/AML claims on the investor's ONCHAINID and activates the entity
   - Click **Reject KYC** — requires a rejection reason, which is sent to the entity by email

## Document storage

Documents smaller than 5 MB are stored as `BYTEA` in PostgreSQL. Documents of 5 MB or larger are stored in S3 (or the configured S3-compatible storage). The KYC review UI retrieves documents transparently from either location.

Document access is logged in the audit trail for every download.

## Issuing claims on approval

When an operator approves a KYC review, the backend automatically:

1. Constructs a KYC claim (topic ID 1) and AML claim (topic ID 2) for the investor's ONCHAINID
2. Signs both claims with the operator's private key
3. Submits transactions to add the claims to the ONCHAINID contract on all relevant chains
4. Updates the investor's status to `KYC_APPROVED` in the database
5. Sends a confirmation email to the investor

The claim includes a hash of the KYC verification record as the claim data, providing a cryptographic link between the on-chain claim and the off-chain document review.

## KYC expiry and renewal

Claims are issued with an expiry date (default: 365 days). The backend sends reminder emails:

- 30 days before expiry: first reminder
- 7 days before expiry: final reminder
- On expiry day: claims expired notification

Expired claims do not prevent the investor from holding existing tokens, but all new transfers to them will be rejected by the ERC-3643 compliance check.

To re-issue claims after renewal:

1. The investor re-submits updated documents
2. The operator reviews and approves
3. New claims are issued with a fresh 365-day expiry

## AML screening

The registry supports integration with external AML screening providers via a webhook. When an entity submits KYC documents, the backend can automatically submit the entity details to an AML screening API.

Configure the AML screening integration in `.env`:

```bash
AML_SCREENING_ENABLED=true
AML_SCREENING_API_URL=https://your-aml-provider.example.com/api/v1/screen
AML_SCREENING_API_KEY=your_api_key
```

The screening result is shown in the KYC review screen. A positive AML hit blocks the approval workflow and requires escalation to the compliance officer.

# KYC Process

## KYC document handling

KYC documents use a split-table design:
- `kyc_document` — metadata (file name, MIME type, KYC status, expiry date)
- `kyc_document_content` — the actual file bytes (BYTEA)

Files ≥5 MB are stored in S3 instead of BYTEA; the backend routes automatically.

## Uploading a document

```bash
curl -X POST http://localhost:8000/api/v1/entities/{entityId}/kyc/documents \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -F "file=@passport.pdf" \
  -F "documentType=PASSPORT"
```

## KYC status flow

```
NOT_STARTED → IN_PROGRESS → APPROVED → (EXPIRED after expiry date)
                          → REJECTED
```

## On-chain KYC claims (ERC-3643)

When a KYC document is approved and the investor has an ONCHAINID:

1. The backend's `ClaimIssuanceService` signs a KYC claim
2. Issues it to the investor's ONCHAINID contract on-chain
3. Records the claim in `onchain_claim` with topic, issuer address, and expiry

The ERC-3643 token's `TrustedIssuersRegistry` must list the backend's issuer address.

## Claim expiry and renewal

Claims have an `expires_at` timestamp. Before expiry:
1. Operator marks KYC as renewed in the backend
2. Backend issues a new on-chain claim (old one is revoked)
3. `onchain_claim.revoked_at` is set for the old claim

## Monitoring

```bash
# Entities with expiring KYC (next 30 days)
GET /api/v1/admin/kyc/expiring?days=30
```
