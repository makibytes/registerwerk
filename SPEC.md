# Registerwerk — Product Specification

A reference implementation for an electronic-securities registry. Operators run the platform; customers (issuers, investors, auditors) access their own data through it. This specification does not establish eWpG compliance, regulatory authorisation, or legal effect for any instrument or deployment.

## Supported Chains & Token Standards

Chains: Ethereum, Polygon, Base, Arbitrum, Optimism, Avalanche, the confidential EVM chains (Fhenix, Inco), Solana, Starknet, Stellar and Canton (optional, `-Pcanton`). Each chain is a configuration row; the repository integration of a chain does not establish production readiness.

Token standards: ERC-20, ERC-721, ERC-1155, ERC-3643 (T-REX), ERC-3525 (semi-fungible bonds), ERC-4626 / ERC-7540 (vaults), Confidential ERC-20 and ERC-3643 (Zama), SPL / SPL-2022 (Solana), Starknet ERC-20 / ERC-3525 (Cairo), Stellar classic assets, Daml bonds (Canton).

Register units: the register counts whole units. Register tokens are deployed with `decimals = 0` and every flow that turns register units into money or a mint refuses an asset whose live deployment reports another value.

## Onchain Levels

- **none** — PostgreSQL only
- **simple** — token emitted for primary market; investors KYC'd and wallets whitelisted
- **control** — adds contract-level compliance and mint-control mechanisms; it does not make the chain the legal register by default

## Roles

| Role | Authority |
|---|---|
| Registry Admin | Full operator access + compliance override with mandatory justification |
| Compliance Officer | KYC/KYB approval/rejection (compliant cases only); screening review; may act as second approver |
| Audit | Read-only including audit and override reports |
| Relationship Manager | Read-only on the customer entities assigned to the user |
| Support Agent | Start read-only customer impersonation sessions (step-up and recorded reason); nothing else |
| Issuer | Read/write own issuances |
| Investor | Read own investments |
| Trader | Secondary-market listings and executions |
| Company Admin | Manages the own entity's users and IdP settings |
| DApp Publisher | Submits marketplace dApp listings |
| Public | Public asset data (term sheets accessible by token address or ISIN) |

Roles live in the `app_user` row, not in the identity provider. There is no separate second-approver role: dual control (four eyes) is a capability of a second, different, enabled Registry Admin or Compliance Officer.

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
- Step-up MFA on sensitive operations and dual control (four eyes) on the listed ones: the approver's token is single use and bound to the action and to a digest of the exact request (method, path, query and, for all but a few secret-bearing reasons, the canonical body); requests are filed and approved in an in-app approval queue
- Production mode (`REGISTERWERK_PRODUCTION_MODE`): one switch that turns the readiness checks into start-up refusals and limits impersonation to read-only
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

The backend build (`./mvnw verify`, JDK 25) enforces JaCoCo floors: bundle line coverage at least 36 % and branch coverage at least 23 %, plus stricter per-package floors set in `backend/pom.xml`. Integration tests use Testcontainers (PostgreSQL) and Foundry/Anvil for blockchain interactions.

Compliance-critical tests must cover:
- Compliant jurisdiction approval by `COMPLIANCE_OFFICER`
- Rejected non-compliant attempt by non-admin role
- Successful admin override with mandatory note
- Override approvals visible in audit report endpoint
