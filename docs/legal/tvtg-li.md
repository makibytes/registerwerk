---
title: Liechtenstein — TVTG
description: How Registerwerk implements Liechtenstein TVTG (Token Act) and SPG due diligence obligations.
---

# Liechtenstein — TVTG (Token Act)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings and configured assumptions. It is not Liechtenstein
    legal advice or evidence of instrument classification, registration, regulatory authorisation,
    compliance, or legal effect. Obtain current instrument-, operator-, service-, and deployment-specific review.

Liechtenstein was the first European country to pass comprehensive token-specific legislation. The **Token and Trusted Technologies Service Provider Act** (TVTG, in force 1 January 2020) created a neutral, technology-agnostic legal framework treating tokens as containers for rights of any kind — including financial instruments.

---

## The TVTG model

The TVTG establishes the concept of a **Token** as a data record in a TT (Trusted Technology) system (i.e., a distributed ledger or equivalent cryptographically secured system). Rights are attached to tokens rather than to the underlying asset directly, creating a clean legal separation between the right (token) and its technical representation (blockchain).

This aligns well with Registerwerk's canonical-registry model: the register entry is the legal instrument; the blockchain is a representation.

---

## Applicable regulatory framework

| Regulation | Scope |
|---|---|
| TVTG (LGBl. 2019 Nr. 301) | Token classification, service provider licensing |
| SPG (Sorgfaltspflichtgesetz) | Due diligence / AML for TT service providers |
| VPG (Vermögensverwaltungsgesetz) | Asset management obligations |
| FMA-Wegleitung TVTG | Supervisory guidance from Liechtenstein FMA |
| MiCAR (EU) 2023/1114 | Applies via EEA agreement |
| DORA (EU) 2022/2554 | ICT resilience via EEA agreement |

---

## TT Service Provider licence

Entities operating a TT system for financial instruments must obtain a **TT Service Provider** licence from the **Finanzmarktaufsicht (FMA)**. Registerwerk's `LI_TVTG` configuration stores the operator's licence number. The licence type determines which services may be provided; Registerwerk targets the **TT Token Issuer** and **TT Register Operator** service categories.

---

## TVTG §9 — Token whitepaper obligation

Unlike Germany (no whitepaper required for electronic securities per se) and France (AMF information document), Liechtenstein's TVTG §9 requires a **token whitepaper** for every public token offering. The whitepaper must describe:

- The rights represented by the token
- The technical specification
- Risks to token holders
- Terms and conditions

**Implementation:** Registerwerk stores the token whitepaper document in the `kyc_document` table under type `TOKEN_WHITEPAPER`. For `LI_TVTG` issuers, the deployment workflow blocks token issuance until a `TOKEN_WHITEPAPER` document with `status = APPROVED` is associated with the asset.

---

## Smart contract audit requirement

The FMA guidance recommends (and for certain licence categories requires) an independent audit of the smart contract code before public issuance. Registerwerk stores the audit report as a `kyc_document` of type `SMART_CONTRACT_AUDIT`.

---

## SPG — Due diligence obligations

The **Sorgfaltspflichtgesetz** imposes AML/CFT due diligence obligations on TT service providers equivalent to AMLD5/AMLD6 requirements. Key differences from German GwG:

| Aspect | DE (GwG) | LI (SPG) |
|---|---|---|
| UBO threshold | 25% | 25% |
| PEP screening | Mandatory | Mandatory |
| Retention period | 6 years (GwG §8) | 10 years (TVTG art. 10) |
| Politically exposed persons | Full enhanced DD | Full enhanced DD + FMA notification |
| Beneficial owner register | Transparenzregister | Liechtenstein Handelsregister (UBO section) |

---

## KYC document requirements for `LI_TVTG`

The `LI_TVTG` jurisdiction profile requires:

- **Handelsregisterauszug** (Liechtenstein commercial register extract, ≤ 3 months)
- **UBO declaration** aligned with the Liechtenstein register format
- Identity documents for directors and UBOs
- **Token whitepaper** (`TOKEN_WHITEPAPER`) — mandatory, must be approved before deployment
- **Smart contract audit report** (`SMART_CONTRACT_AUDIT`) — mandatory for public offerings
- **TT Service Provider licence** copy or confirmation
- Annual financial statements (last 2 years)

---

## Retention: 10 years

Liechtenstein requires 10-year retention for all records related to token transactions, matching Germany but exceeding Luxembourg and France. The `LI_TVTG` jurisdiction profile sets `retentionYears = 10`.

---

## MiFIR reporting for Liechtenstein

MiFIR applicability, reporting capacity, competent authority, and channel require current external
review. There is no `LI_TVTG` filing strategy in `MifirReportingService`; the current service
produces only the `DRAFT_UNVALIDATED` prototype described in [MiFIR](../compliance/mifir.md).

---

## FMA incident reporting

DORA/EEA applicability, authority, and deadlines require current external review. The `dora`
module does not route or transmit `LI_TVTG` incident notifications to FMA.

---

## Why Liechtenstein for blockchain-native issuers

Liechtenstein offers the most blockchain-native legal framework in Europe:

- Tokens are legally recognised regardless of the underlying technology
- Any right can be tokenised — financial instruments, real estate, IP rights
- The TVTG is technology-neutral (EVM, UTXO, and DAG all qualify)
- No separate "crypto securities" designation needed — the token itself carries the right

This makes `LI_TVTG` attractive for innovative instrument types such as [ERC-3525 semi-fungible bonds](../token-standards/erc3525.md), [ERC-4626 vault tokens](../token-standards/erc4626.md), and [DAML Finance instruments](../token-standards/canton-daml.md) where no equivalent national instrument type yet exists.
