---
title: Luxembourg — CSSF
description: How Registerwerk implements Luxembourg CSSF regulatory requirements for tokenised securities.
---

# Luxembourg — CSSF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings and configured assumptions. It is not Luxembourg
    legal advice or evidence of instrument classification, regulatory authorisation, compliance,
    or legal effect. Obtain current instrument-, operator-, service-, and deployment-specific review.

Luxembourg is Europe's largest fund domicile and a leading jurisdiction for tokenised fund instruments. The **Commission de Surveillance du Secteur Financier (CSSF)** regulates the use of distributed ledger technology (DLT) for financial instruments under CSSF Circular 19/732 and subsequent guidance.

---

## Applicable regulatory framework

| Regulation | Scope |
|---|---|
| CSSF Circular 19/732 | DLT-based NAV calculation and fund administration |
| CSSF Circular 22/811 | DLT fund services and tokenised instruments |
| AML Law 2004 (amended) | Customer due diligence obligations |
| Law of 5 April 1993 (financial sector) | Authorisation of investment firms |
| MiCAR (EU) 2023/1114 | Crypto-asset service providers |
| DORA (EU) 2022/2554 | ICT operational resilience |

---

## Key differences from Germany

| Dimension | DE (eWpG) | LU (CSSF) |
|---|---|---|
| Authoritative register | DB is canonical (§16 eWpG) | DB is canonical (CSSF guidance) |
| Retention period | 10 years | 5 years |
| MiCAR applicability | Exempted (eWpG tokens ≠ e-money tokens) | Applies to crypto-asset services |
| UBO threshold | 25% (GwG §3) | 25% (AML Law Art. 1(7)) |
| Enhanced DD | PEPs (GwG §10(2)) | PEPs + high-risk third countries |
| Shareholder register | Not required | Required for SICAVs and SICAFs |
| Source of funds declaration | Optional | Mandatory for all customers |

---

## KYC document requirements for `LU_CSSF`

In addition to the common documents (certificate of incorporation, commercial register extract), the `LU_CSSF` jurisdiction profile requires:

- **Registre des Bénéficiaires Effectifs (RBE) extract** — Luxembourg beneficial owner register
- **Shareholder register** — for investment companies (SICAV/SICAF/SIF)
- **Source of funds declaration** — signed by the customer's legal representative
- **CSSF-specific AML questionnaire**
- Annual reports (last 2 years)

See [KYC & AML](../compliance/kyc-aml.md) for the full document lifecycle.

---

## Fund token specifics

Luxembourg is the primary home for tokenised fund instruments. Registerwerk supports the CSSF-preferred token standards for this use case:

| Instrument type | Token standard | Registerwerk support |
|---|---|---|
| Synchronous fund (daily NAV) | [ERC-4626](../token-standards/erc4626.md) | Full — `AssetVaultState`, `VaultNavStrike` |
| Asynchronous fund (T+1 / T+2) | [ERC-7540](../token-standards/erc7540.md) | Full — `VaultRequest`, request/claim flow |
| Bond with tranches | [ERC-3525](../token-standards/erc3525.md) | Full — `AssetSlot` (tranche) |
| Regulated equity / bond | [ERC-3643](../token-standards/erc3643.md) | Full — T-REX identity-bound |

The `AssetVaultState` entity tracks NAV per share. `VaultNavStrike` records each NAV calculation point, giving regulators a timestamped audit trail of all pricing decisions.

---

## Settlement timing

Current settlement obligations require external review. The `trading` module can record a
`settledAt` timestamp, but the [MiFIR](../compliance/mifir.md) prototype does not validate
settlement status or a regulatory settlement window before selecting rows.

---

## CSSF incident reporting

Under DORA Art. 19 (transposed in Luxembourg via the DORA implementation act), major ICT incidents must be reported to the CSSF:

- **Initial notification**: within 4 business hours of classification as major
- **Intermediate report**: within 72 hours
- **Final report**: within 1 month

The `DoraService` stores manually classified incidents and application reminder timestamps. It
does not determine the legally correct classification/deadlines or route notifications to CSSF.
See [DORA](../compliance/dora.md).

---

## MiCAR obligations (LU_CSSF)

Luxembourg's transposition of MiCAR makes it applicable to crypto-asset service providers operating from Luxembourg. For Registerwerk deployments with `LU_CSSF` as the primary jurisdiction:

- The operator must hold a CASP licence from CSSF (or a passportable licence from another EU member state)
- The [Travel Rule](../compliance/travel-rule.md) applies to all crypto-asset transfers ≥ €1,000
- The [DAC8/CARF](../compliance/dac8.md) component produces `DRAFT_UNVALIDATED` prototype output;
  it does not file with ACD or prove authority delivery or acceptance
