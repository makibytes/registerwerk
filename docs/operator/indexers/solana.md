---
title: Solana (RPC polling)
---

# Solana indexer — RPC polling

Solana balances are indexed by the backend's `SolanaTransferSyncService`, which polls the chain's JSON-RPC
endpoint. There is no separate indexer service, no Geyser plugin and no gRPC stream: the only Solana
configuration is the RPC URL of the chain.

## How it works

Every confirmed Solana deployment's mint address is tracked. A job registers new mints about a minute after
a deployment is confirmed and at start-up; each registration seeds a per-mint cursor
(`solana_mint_sync_cursor`).

A second job runs every 10 minutes (and holds a cluster lock, so only one replica polls):

1. `getSignaturesForAddress(mint)` pages back from the newest signature to the cursor.
2. Every transaction is fetched with `getTransaction` (`jsonParsed`).
3. What happened to the tracked mint is booked as the **balance delta**: `postTokenBalances` minus
   `preTokenBalances`, summed per owner wallet. It does not matter which instruction moved the tokens
   (`transferChecked`, `mintTo`, `burn`, batched or CPI-driven), nor whether the token is SPL or Token-2022.
4. Owners with a positive delta are matched against owners with a negative delta into `TRANSFER` rows; an
   unmatched positive residual is a `MINT`, an unmatched negative one a `BURN`. A Token-2022 transfer fee
   therefore shows up as a small burn next to the transfer.

A `getTransaction` that fails or returns `null` aborts the whole poll for that mint: nothing is saved and the
cursor does not move, so the transaction is retried at the next run. The cursor advances only in the same
database transaction that stores the rows.

## Configuration

Set the RPC endpoints in `.env` (or in the chain configuration of the operator portal):

```bash
SOLANA_MAINNET_RPC=https://mainnet.helius-rpc.com/?api-key=YOUR_KEY
SOLANA_DEVNET_RPC=https://devnet.helius-rpc.com/?api-key=YOUR_KEY
```

The public endpoints (`api.mainnet-beta.solana.com`, `api.devnet.solana.com`) are rate limited and do not
keep long history; use a provider that serves `getSignaturesForAddress` and `getTransaction` for the whole
life of your mints. Nothing else needs to be started.

## Registering a new SPL token

When a Solana asset deployment is created through the API, the backend starts monitoring its mint address
automatically. No manual configuration is needed.

## Monitoring

`IndexerMonitorService` watches the `SOLANA_POLL` state like the other indexers: consecutive RPC errors raise
the state to `ERROR` (after 5 failures) and an alert follows when no poll has succeeded for two hours. See
[Indexer resilience](resilience.md).

!!! warning "Coverage status: partial observation"
    The indexer lists signatures by **mint address**. A plain (legacy) SPL `transfer` instruction does not reference the mint account, so such holder-to-holder movements are not seen. Registerwerk therefore reports Solana deployments as *partially observed* (`plain SPL transfers not observable`): holder sync stays BLOCKED and the register is never marked reconciled from indexed Solana history until token accounts are indexed.
