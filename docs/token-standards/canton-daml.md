---
title: Registerwerk Daml Bonds (Canton)
description: Custom Canton bond lifecycle templates and their verified implementation boundary.
---

# Registerwerk Daml Bonds (Canton)

Registerwerk ships three custom Daml bond templates under `Registerwerk.Bond.*`. They model an
eWpG instrument's terms and on-ledger lifecycle. They are not Daml Finance templates, and their
choices do not move cash or CIP-0056 holdings.

## Supported deployable types

| Token enum | Template | Lifecycle |
|---|---|---|
| `DAML_BOND_FIXED` | `FixedRateBond` | Coupon authorization, redemption, early call, suspend/resume |
| `DAML_BOND_FLOATING` | `FloatingRateBond` | Rate fixing plus the fixed-bond lifecycle |
| `DAML_BOND_ZERO` | `ZeroCouponBond` | Redemption, early call, suspend/resume |
| `CANTON_TOKEN` | — | Reserved but intentionally rejected until a registry-specific CIP-0056 adapter exists |

CIP-0056 standardizes interoperability APIs and workflows; it does not supply one universal
instrument factory or issuer-admin `Issue`, `ForceTransfer`, or `Pause` choice. Registerwerk
therefore fails `CANTON_TOKEN` operations with an explicit unsupported error instead of sending
guessed template/choice names to a production ledger.

## Terms mapping

`AssetBondTerms` is encoded into `EwpgBondTerms`: asset ID, face value, ISO currency, issue and
maturity dates, day-count convention, payment frequency, callability, and call schedule. The
instrument-specific fields are fixed coupon rate, reference rate/spread/latest fixing, or issue
price. Complete required terms are validated before command construction.

Creation uses `submitAndWaitForTransaction` and extracts exactly one visible created event matching
the expected template. The committed contract ID—not the update ID—is stored as the deployment's
contract address so later exercise commands target a real active contract.

## Lifecycle and settlement meaning

`PayCoupon`, `Redeem`, and `EarlyCall` are ledger records of registry authorization/lifecycle, not
holder payment instructions. The backend's corporate-action flow requires issuer attestation and a
separate operator confirmation before dispatch; after the Daml choice commits, it records the
action as settled. This is suitable only where that dual-control attestation is the authoritative
evidence that the external cash/holding leg was completed. Otherwise integrate a payment adapter
and reconciliation proof before using the `SETTLED` state operationally.

## Privacy-role limitation

The templates declare `registryAdmin` and `issuer` as signatories and `regulatorObserver` as an
observer. The current deployment API supplies only one owner party and fills all three fields with
it. That is a development integration, not real segregation of duties. Production use requires an
extended creation request that resolves three distinct parties and validates their hosting and
authorization.

## Build verification

```bash
cd backend && ./mvnw verify -Pcanton
cd ../daml && dpm build
```

The Maven profile compiles the Ledger API v2 client and Canton services; without it,
`CantonBondDisabledStub` returns an explicit unavailable error. A green profile build proves source
and binding compatibility, not participant connectivity, package installation, party topology, or
production conformance. Run live-participant contract tests before enabling the chain.
