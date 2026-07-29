# Registerwerk — Product Specification

A reference implementation for an electronic-securities registry. Operators run the platform; customers (issuers, investors, auditors) access their own data through it. This specification does not establish eWpG compliance, regulatory authorisation, or legal effect for any instrument or deployment.

## Supported Chains & Token Standards

Chains: Ethereum L1, Ethereum Optimistic L2s (Polygon, Base), Solana.

Token standards: ERC-20, ERC-721, ERC-1155, ERC-3643, Confidential ERC-20 (Zama), Confidential ERC-3643 (Zama + T-REX).

## Onchain Levels

- **none** — PostgreSQL only
- **simple** — token emitted for primary market; investors KYC'd and wallets whitelisted
- **control** — adds contract-level compliance and mint-control mechanisms; it does not make the chain the legal register by default

## Roles

| Role | Authority |
|---|---|
| Registry Admin | Full system + compliance override with mandatory justification |
| Compliance Officer | KYC/KYB approval/rejection (compliant cases only) |
| Audit | Read-only including audit and override reports |
| Issuer | Read/write own issuances |
| Investor | Read own investments |
| Public | Public asset data (term sheets accessible by token address or ISIN) |

## Customer Management

Full lifecycle: onboarding, KYC documents (PDF/images/XML), company rename history, M&A mergers. Each customer entity has a company admin (limited role — manages their own users and IdP settings only).

## Legal / Regulatory Baseline

The platform is intended to provide controls mapped to the following regimes; an operator must
separately determine scope, configure the controls, and obtain the evidence and approvals required:

- Germany eWpG (electronic securities register integrity and traceability)
- EU AML baseline (risk-based KYC/KYB, beneficial ownership)
- FATF risk-based AML/CFT for virtual-asset activity
- GDPR (personal data governance, data minimization, retention)
- MiCA market integrity and disclosure principles

### Required controls

- Jurisdiction-aware KYC requirement profiles and compliance checklist evaluation
- Per-jurisdiction approval state (`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`)
- Immutable audit trail for all compliance decisions and lifecycle events
- Separation of duties: compliance approvals vs. system override authority
- Override path with mandatory `overrideNote`; override reports filterable by jurisdiction and period
- Role-based API authorization with entity ownership checks
- Public/private data partitioning

### Non-goals of software controls

Software does not replace: licensing/registration obligations, mandatory reporting (e.g. SARs), legal classification duties (MiCA/MiFID/eWpG perimeter), sanctions screening policies.

## Onboarding Flow

1. Operator creates entity and generates an onboarding token
2. Entity admin uses the token to set up their entity (users, IdP)
3. Users receive a welcome email with login URL, entity info, and API docs link

## Tests

≥70% line coverage (JaCoCo). Integration tests use Testcontainers (PostgreSQL) and Foundry/Anvil for blockchain interactions.

Compliance-critical tests must cover:
- Compliant jurisdiction approval by `COMPLIANCE_OFFICER`
- Rejected non-compliant attempt by non-admin role
- Successful admin override with mandatory note
- Override approvals visible in audit report endpoint
