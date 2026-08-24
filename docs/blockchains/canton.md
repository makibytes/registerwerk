---
title: Canton / Daml Ledger
description: Current Canton Ledger API and custom Daml bond integration, including its production limits.
---

# Canton / Daml Ledger

Canton provides privacy by synchronizing only the Daml sub-transactions a participant is entitled
to see. Registerwerk's optional `-Pcanton` build integrates with a participant's Ledger API v2 over
gRPC. This repository includes custom Registerwerk bond-lifecycle templates; it does not embed Daml
Finance, and it does not yet provide a production `CANTON_TOKEN`/CIP-0056 workflow adapter.

## What is implemented

| Area | Current implementation |
|---|---|
| Connection | Ledger API v2 command, state, update, and party-management services |
| Bond contracts | Custom `FixedRateBond`, `FloatingRateBond`, and `ZeroCouponBond` templates |
| Lifecycle | Create, coupon authorization, rate fixing, redemption, early call, suspend/resume |
| Contract identity | Deployment stores the committed Daml contract ID; the update ID is retained as transaction evidence |
| Indexing | Resumable Ledger API update stream and durable offset for CIP-0056 `Holding` create/archive events |
| Finality | A committed Ledger API update is recorded as `FINALIZED`; there is no EVM-style confirmation window |

The bond choices record lifecycle authorization on ledger. They do not themselves move a cash leg
or distribute holder balances. Corporate-action `SETTLED` therefore relies on the issuer/operator
attestation workflow plus the committed lifecycle choice; a production deployment must connect and
reconcile its actual payment/holding workflow before treating that state as independent proof of
cash settlement.

## Connection configuration

Use an explicit scheme. `grpcs://` (or `https://`) enables TLS; `grpc://` is explicit plaintext for
local development. A legacy scheme-less value is accepted as plaintext with a warning and should
not be used in production.

```yaml
registerwerk:
  canton:
    mainnet:
      ledger-api-url: "grpcs://participant.example.com:5001"
      synchronizer-id: "global-synchronizer"
      application-id: "registerwerk"
      auth-token: "${CANTON_MAINNET_TOKEN}"
    devnet:
      ledger-api-url: "grpc://localhost:5001"
      synchronizer-id: "dev-synchronizer"
      application-id: "registerwerk"
```

Only host and optional port are accepted: user-info, paths, query strings, and fragments are
rejected. A bearer token containing CR/LF is rejected before gRPC metadata is built. `grpcs://`
defaults to port 443 and `grpc://` to 5001 when the port is omitted.

## Parties and privacy

`CantonPartyAllocator.allocateParty(walletId, chainConfig, displayName)` calls the Ledger API admin
service and stores the returned party ID as the Canton `OperatorWallet.address`, with its local
wallet context in the configured wallet store. It does not write a `LegalEntity.cantonPartyId`
field—no such field exists.

Community Canton does not mint a party-specific token through this path. Locally hosted parties use
the participant channel's configured bearer credential; imported wallets may carry externally
supplied party context. In the current bond deployment encoder, `registryAdmin`, `issuer`, and
`regulatorObserver` are all populated from the one deployment owner party. That preserves template
validity for development but does not establish real three-party segregation. A production adapter
must obtain and pass distinct parties before relying on the template's privacy/approval roles.

## Build and compatibility

```bash
cd backend
./mvnw verify -Pcanton
```

The profile compiles against Daml/Canton 3.5.2 Ledger API v2 bindings and the custom DAR targets
Daml-LF 2.1. Package-name addressing (`#registerwerk-canton`) is used instead of a package hash.
Without the profile, `CantonBondDisabledStub` fails bond operations explicitly. The profile build
and unit tests verify Java/protobuf compatibility, but a live participant conformance test is still
required for each target Canton release and synchronizer policy.

## Indexer boundary

`CantonTransferSyncService` resumes from the numeric Ledger API offset stored in
`indexer_state.last_synced_signature`. It mirrors open CIP-0056 Holdings so an archive can recover
the consumed owner/instrument/amount, then classifies same-transaction archive/create pairs as
transfers and unmatched events as burns/mints. This indexer is for token-standard Holdings; it is
not a bond-coupon cash-settlement indexer. Since `CANTON_TOKEN` deployment is deliberately disabled
until a registry-specific CIP-0056 adapter exists, treat this stream as adapter infrastructure, not
as an end-to-end generic-token claim.
