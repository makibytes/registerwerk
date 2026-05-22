---
title: Solana
description: Solana blockchain support — SPL and SPL-2022 token programs for Solana-native securities.
---

# Solana

Solana offers high throughput (50,000+ TPS), sub-second finality, and very low transaction costs. Registerwerk supports Solana-native security tokens through both the classic **SPL Token** program and the extended **Token-2022** (SPL-2022) program.

---

## Supported networks

| Network | Network enum | Endpoint | Use |
|---|---|---|---|
| Solana mainnet-beta | `MAINNET` | `https://api.mainnet-beta.solana.com` | Production |
| Solana devnet | `TESTNET` | `https://api.devnet.solana.com` | Development/testing |

---

## Client library: Solanaj

Registerwerk uses **Solanaj** (Java client library for Solana) via the `SolanaClientFactory`. Key operations:

| Operation | Solanaj API | Used in |
|---|---|---|
| Create mint account | `MintLayout.encode()` + `SystemProgram.createAccount()` | `SolanaTokenService.deploy()` |
| Mint tokens | Token program instruction `mintTo` | `SolanaTokenService.mint()` |
| Transfer | Token program instruction `transfer` | `SolanaTokenService.transfer()` |
| Set authority | Token program instruction `setAuthority` | Admin operations |
| Get balance | `rpcClient.getTokenAccountBalance()` | Indexer, wallet balance |

---

## Token account model

Solana's token model differs significantly from EVM:

- A **mint account** defines the token (equivalent to an ERC-20 contract address)
- Each holder needs a separate **token account** (Associated Token Account, ATA) to hold the token
- Registerwerk's deployment flow automatically creates ATAs for the operator's wallets
- Investor ATAs are created on first receive

`AssetDeployment.contractAddress` stores the Solana **mint address** (base58-encoded public key).

---

## SPL-2022 extensions

For detailed coverage of Token-2022 extensions (InterestBearing, ConfidentialTransfer, TransferHook, PermanentDelegate), see [SPL-2022](../token-standards/spl-2022.md).

---

## Indexer

The Solana indexer listens for transactions on tracked mint accounts using WebSocket subscriptions (via Helius or Shyft enhanced APIs). On each confirmed transaction:

1. Parse the transaction log for token transfer instructions
2. Map from/to Solana accounts to `LegalEntity` records
3. Write a `token_transfer` record (consistent schema with EVM indexer)
4. Update `AssetHolder.nominalAmount`

The `IndexerMonitorService` checks Solana indexer liveness every 5 minutes. If no event is received for more than 30 minutes on an active asset, a `DORA_AVAILABILITY` incident is opened.

---

## Operator wallet on Solana

Registerwerk's Solana wallet is a standard **ed25519 keypair**. The private key is stored encrypted in the operator wallet vault (same KMS/KEK envelope as EVM keystores). The operator wallet is the mint authority and freeze authority for all SPL-2022 tokens.

!!! warning "SOL balance for rent"
    Solana accounts require **rent** (minimum SOL balance) to remain open. Token accounts opened by the deployment service require a small SOL deposit. The `WalletBalanceService` monitors the operator's SOL balance and warns when it falls below 0.5 SOL.
