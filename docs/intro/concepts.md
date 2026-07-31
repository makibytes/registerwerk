---
title: Core Concepts
description: Glossary of legal, financial, and technical terms used throughout Registerwerk.
---

# Core Concepts

This glossary defines the terms used across Registerwerk's documentation, code, and user interfaces. Terms are grouped by domain; cross-references point to detailed pages where applicable.

---

## Securities & Issuance

**Security token**
: A blockchain token that represents a financial instrument — a bond, share, fund unit, or other regulated asset. Registerwerk manages security tokens subject to the securities laws of the [supported jurisdictions](../legal/index.md).

**Electronic security (elektronisches Wertpapier)**
: A security that exists exclusively as an entry in a central or decentralised electronic register, with no paper document. Defined in Germany by [eWpG §2](../legal/ewpg.md), and equivalents exist in Luxembourg, France, and Liechtenstein law.

**Issuer**
: The legal entity that creates and offers a security token. In Registerwerk, an issuer is a [Customer](#customer-entities) legal entity with the `ISSUER` role that has passed [KYC/AML](../compliance/kyc-aml.md) approval.

**Investor / Holder**
: A legal entity or natural person that holds a position in a security token. Tracked in the system as an `AssetHolder` record linked through a `HolderIdentity` to either a `LegalEntity` or a `NaturalPerson`.

**ISIN** (International Securities Identification Number)
: A 12-character alphanumeric code uniquely identifying a security globally. Registerwerk stores the ISIN on the `Asset` entity and embeds it in token metadata.

**Asset number**
: Registerwerk's internal sequential identifier for a security, separate from the ISIN. Used in internal workflows and audit references.

**Issuance / Deployment**
: The act of creating a token contract on a blockchain. In Registerwerk, deployment is tracked as an `AssetDeployment` record linking the off-chain `Asset` to its on-chain contract address.

---

## Blockchain Concepts

**Blockchain / Chain**
: A distributed ledger network. Registerwerk supports Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism (EVM), Solana, StarkNet, Stellar, and Canton. See [Supported Blockchains](../blockchains/index.md).

**Token standard**
: A specification defining a token's interface (how it can be transferred, queried, and managed). Examples: ERC-20, ERC-3643, SPL-2022. See [Token Standards](../token-standards/index.md).

**Smart contract**
: Executable code deployed on a blockchain. Registerwerk deploys contracts using [Web3j](https://web3j.io/) (EVM) and Solanaj (Solana). Contract addresses are stored in `AssetDeployment`.

**Transaction (on-chain)**
: A cryptographically signed operation submitted to a blockchain. Every state change is recorded as a `BlockchainTransaction` and linked to the corresponding audit event.

**Chain drift**
: A discrepancy between the on-chain token balance and the Registerwerk database's `AssetHolder.nominalAmount`. The `ChainDriftDetectionJob` checks for drift every 15 minutes per issued asset.

**Canonical registry**
: Registerwerk stores an operational holder record in PostgreSQL and projects or reconciles selected state on-chain. Which record has legal authority is instrument-, register-model-, operator-, and jurisdiction-specific and requires an approved perimeter decision. Neither the database nor the blockchain is universally authoritative.

**Wallet**
: A cryptographic key pair used to sign on-chain transactions. Registerwerk manages operator wallets (key material encrypted at rest) via the `wallet` module.

---

## Regulatory & Compliance

**KYC** (Know Your Customer)
: The process of verifying the identity of a customer — including their business, owners, and beneficial owners — before establishing a business relationship. See [KYC & AML](../compliance/kyc-aml.md).

**KYB** (Know Your Business)
: The corporate equivalent of KYC, focused on verifying the legitimacy and ownership structure of a legal entity.

**AML** (Anti-Money Laundering)
: The body of regulations requiring firms to detect and prevent money laundering. In Germany: GwG; EU-wide: AMLD6 and the forthcoming AMLR.

**PEP** (Politically Exposed Person)
: An individual who holds or has held a prominent public function. PEPs require enhanced due diligence under [GwG §10(2)](../compliance/kyc-aml.md).

**UBO** (Ultimate Beneficial Owner)
: The natural person(s) who ultimately own or control a legal entity, typically at a ≥25% threshold. Tracked in Registerwerk as `BeneficialOwner` linked to a `NaturalPerson`.

**Sanctions screening**
: Checking a person or entity against international sanctions lists (OFAC SDN, EU CFSP, UN 1267, UK HMT, Swiss SECO). See [Sanctions Screening](../compliance/sanctions-screening.md).

**Travel Rule (TFR)**
: Regulation (EU) 2023/1113 requiring originator and beneficiary information to accompany crypto-asset transfers above €1,000 between VASPs. Implemented using the [IVMS-101 data standard](../compliance/travel-rule.md).

**VASP** (Virtual Asset Service Provider)
: A regulated business providing services relating to virtual assets (exchanges, custodians). Registerwerk itself acts as a VASP/CASP when issuing tokens on behalf of third parties.

**CASP** (Crypto-Asset Service Provider)
: The MiCAR term for VASP in EU law.

**Sperrvermerk**
: German legal term for a blocking notation on a securities register entry, restricting transfer or encumbering an asset. Mandated by [eWpG §16](../legal/ewpg.md). See [Sperrvermerk](../compliance/sperrvermerk.md).

**DORA** (Digital Operational Resilience Act)
: EU Regulation 2022/2554 requiring financial entities to manage ICT risks, report major incidents, and maintain a register of ICT third-party providers. See [DORA](../compliance/dora.md).

**LEI** (Legal Entity Identifier)
: A 20-character ISO 17442 code uniquely identifying a legal entity globally. Stored on `LegalEntity` in Registerwerk; recommended for all issuers.

---

## Customer Entities

**Operator**
: The organisation running a Registerwerk deployment. Operators have access to the operator frontend (:4200) and can manage all customers, assets, and compliance data.

**Customer**
: An issuer or investor onboarded by an operator. Customers access the customer frontend (:4201) through the Kong API gateway.

**Legal entity (`LegalEntity`)**
: The core data model for a customer's company. Holds jurisdiction, registration number, LEI, KYC status, and links to beneficial owners and KYC documents.

**Natural person (`NaturalPerson`)**
: An individual — a director, UBO, or investor. The current entity maps PII such as name, date of birth, nationality, and tax ID to ordinary database columns; application-level field encryption is not implemented.

**Beneficial owner (`BeneficialOwner`)**
: Bridges a `LegalEntity` to a `NaturalPerson` with ownership percentage and control type.

---

## Platform-Specific Terms

**Module**
: A Spring Modulith bounded context. Registerwerk has 34 modules, each with an `api/` package (public types) and an `internal/` package (private implementation). See [Module Architecture](../platform/modules.md).

**Step-up authentication**
: A second authentication challenge required before executing high-risk operations (force-transfer, force-burn, KYC override). Enforced by the `@RequiresStepUp` annotation. See [Step-Up MFA](../compliance/step-up-mfa.md).

**4-eyes principle (Vier-Augen-Prinzip)**
: A dual-control requirement where a second authorised approver must confirm an action before it takes effect. Implemented via the `stepup` module.

**Audit chain**
: The tamper-evident sequence of audit events, each containing a hash of the previous entry. Provides cryptographic proof of the completeness and integrity of the audit log. See [Audit Log](../platform/audit-log.md).
