---
title: Canton / DAML Ledger
description: Canton private ledger and DAML Finance integration for regulated bond instruments.
---

# Canton / DAML Ledger

Canton is a **privacy-first distributed ledger** developed by Digital Asset. Unlike public blockchains, Canton implements **per-sub-transaction privacy**: each participant only sees the contracts they are a party to. This makes Canton attractive for institutional instruments where positions should not be visible to other market participants.

---

## Canton architecture concepts

| Concept | Canton | Registerwerk mapping |
|---|---|---|
| **Ledger** | The Canton distributed ledger | One Canton Network participant node per operator |
| **Party** | A unique cryptographic identity on the ledger | `LegalEntity.cantonPartyId` |
| **Contract** | A DAML contract instance | One per bond or asset position |
| **Choice** | An action that can be exercised on a contract | Corporate action (coupon, redemption) |
| **Synchroniser** | The consensus component | Canton Network global synchroniser |
| **Ledger API** | gRPC API to interact with Canton | `CantonLedgerEndpoint` |

---

## DAML Finance bond types

See [DAML Finance Bonds](../token-standards/canton-daml.md) for the full treatment of bond term configuration and coupon payments.

---

## Connection configuration

The `CantonLedgerEndpoint` connects to a Canton participant node via its **Ledger API** (gRPC):

```yaml
registerwerk:
  canton:
    mainnet:
      ledgerApiUrl: "participant.example.com:5001"
      synchronizerId: "global-synchronizer"
      applicationId: "registerwerk"
      authToken: "${CANTON_MAINNET_TOKEN}"  # JWT for participant auth
    devnet:
      ledgerApiUrl: "localhost:5001"
      synchronizerId: "dev-synchronizer"
```

For the Canton Network (public Canton): obtain a participant node from the Canton Network operator, register your application, and supply the Ledger API URL.

For development: a local Canton sandbox is available via `docker compose -f indexer/canton/docker-compose.yml up`.

---

## Party allocation

Before a customer can participate in Canton-based instruments, they must be allocated a **Canton Party**. This is handled by `CantonPartyAllocator.allocate(entityId)`:

1. Calls the Ledger API `PartyManagementService.allocateParty()`
2. Stores the returned party identifier in `LegalEntity.cantonPartyId`
3. The party identifier is used in all DAML contract references for that entity

Parties are immutable once allocated; a party can never be re-used for a different entity.

---

## Privacy model

Canton's privacy is enforced at the ledger level:

- **Issuer** sees: all contracts for their instruments
- **Investor** sees: only their own position contracts
- **Registry operator** sees: all contracts (as the DAML observer role)
- **Other investors**: cannot see other investors' positions

This is native privacy without encryption — the ledger infrastructure ensures that contract data is only transmitted to parties who are stakeholders in that contract.

---

## The `-Pcanton` Maven profile

Because the DAML SDK and associated JARs are large and not on Maven Central, Canton support is gated behind the `-Pcanton` profile:

```bash
./mvnw verify -Pcanton          # includes Canton
./mvnw verify                   # Canton disabled, stub injected
```

In the absence of `-Pcanton`, `CantonBondDisabledStub` is used. API calls to Canton-based instruments return `503 Service Unavailable` with a message explaining that Canton support requires the `-Pcanton` profile and a running participant node.

---

## Indexer

The Canton indexer uses the **Transaction Service** of the Ledger API to stream all committed transactions. It processes:
- Bond issuance contracts → creates `AssetHolder` records
- Coupon payment events → creates `token_transfer` records of type `COUPON`
- Transfer events → updates `AssetHolder.nominalAmount`

Canton indexer liveness is monitored by `IndexerMonitorService`, same as the EVM and Solana indexers.
