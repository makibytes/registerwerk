---
title: DAML Finance Bonds (Canton)
description: Canton / DAML Finance bond standards for private ledger deployments.
---

# DAML Finance Bonds (Canton)

Canton is a privacy-first distributed ledger built on the **DAML** smart contract language. DAML Finance provides a library of composable financial primitives for Canton — including bonds, equities, and derivatives. Registerwerk supports three DAML Finance bond types on Canton for private-ledger deployments.

---

## Supported DAML Finance bond types

| Standard | Token enum | Description |
|---|---|---|
| `DAML_BOND_FIXED` | Fixed-rate bond | Known coupon rate, fixed schedule |
| `DAML_BOND_FLOATING` | Floating-rate bond | Rate tied to EURIBOR/SOFR/other reference |
| `DAML_BOND_ZERO` | Zero-coupon bond | No periodic coupon; trades at a discount |
| `CANTON_TOKEN` | Generic Canton asset | Any DAML-based digital asset |

---

## How Canton differs from EVM

| Dimension | EVM (ERC standards) | Canton (DAML Finance) |
|---|---|---|
| Privacy | Public ledger (all participants see state) | Private — each participant sees only their contracts |
| Smart contract language | Solidity / Vyper | DAML (Haskell-like) |
| Finality | Probabilistic (n confirmations) | Deterministic (ledger API acknowledgment) |
| Identity | Wallet address | Canton Party (unique identifier per participant) |
| Off-ledger settlement | Optional | Native: DAML workflow includes settlement |
| Confidential positions | Requires Zama fhEVM | Native — private contracts |

---

## Canton Party allocation

Each `LegalEntity` in Registerwerk has a **Canton Party** — a unique identifier on the Canton ledger. This is managed by the `CantonPartyAllocator` service in the `blockchain` module:

1. When a customer with a Canton-capable instrument is onboarded, `CantonPartyAllocator.allocate(entityId)` registers the entity on the Canton ledger
2. The party identifier is stored in `LegalEntity.cantonPartyId`
3. All DAML Finance contracts reference the Canton Party, not a wallet address

---

## Bond terms mapping

`AssetBondTerms` stores the financial parameters for all bond types:

| Field | DAML_BOND_FIXED | DAML_BOND_FLOATING | DAML_BOND_ZERO |
|---|---|---|---|
| `couponRate` | Fixed (e.g., 5.0%) | Reference rate spread | N/A |
| `referenceRate` | N/A | e.g., EURIBOR_3M | N/A |
| `maturityDate` | ✅ | ✅ | ✅ |
| `paymentFrequency` | ANNUAL / SEMIANNUAL / QUARTERLY / MONTHLY | Same | N/A |
| `dayCountConvention` | ACT_365 / ACT_ACT / 30_360 | Same | ACT_365 |
| `issuePrice` | 100 (par) or discount/premium | Par | Discount (< 100) |

---

## Coupon payment on Canton

For `DAML_BOND_FIXED` and `DAML_BOND_FLOATING`, the `CantonBondOperations.payCoupon()` method exercises the DAML Finance coupon payment workflow:

1. Registerwerk's Canton participant node proposes a coupon payment contract to the issuer's party
2. The issuer's node exercises the coupon lifecycle choice
3. All bond holder parties receive their coupon amounts through DAML's settlement batch
4. The `CorporateAction(type=COUPON, status=SETTLED)` record is updated in Registerwerk's DB

---

## The `-Pcanton` Maven profile

Canton support requires the DAML SDK and associated Java libraries. These are activated via the `-Pcanton` Maven profile:

```bash
cd backend && ./mvnw verify -Pcanton
```

Without this profile, `CantonBondDisabledStub` is injected instead of the real Canton client, and all Canton-related API calls return `503 Service Unavailable` with a descriptive message. This allows the application to start cleanly without a Canton participant node.
