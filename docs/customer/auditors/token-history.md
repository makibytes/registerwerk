---
id: token-history
title: Token Transfer History
sidebar_label: Token History
---

# Token Transfer History

The token history view provides a complete, verifiable record of every transfer that has occurred for a given security token. This data is sourced directly from blockchain event logs via The Graph indexer, ensuring it cannot be altered.

## Accessing token history

There are two ways to reach the token history:

1. **From the issuance list**: Navigate to **Issuances**, find the token, and click **View History**
2. **From the audit log**: Click on any `TRANSFER` event to open the token history filtered to that token

## Understanding the transfer table

| Column | Description |
|--------|-------------|
| **Block** | Block number on the target blockchain |
| **Timestamp** | Block timestamp (UTC) |
| **Transaction** | Transaction hash (links to block explorer) |
| **From** | Sending wallet address |
| **From identity** | Investor name mapped to this wallet (if registered) |
| **To** | Receiving wallet address |
| **To identity** | Investor name mapped to this wallet (if registered) |
| **Amount** | Token amount transferred |
| **Log index** | Position of the Transfer event within the transaction |

## Mint and burn events

Token creation (minting) and destruction (burning) are shown as special transfers:

- **Mint** — From address is the zero address (`0x000...000`). Occurs when the issuer initially distributes tokens.
- **Burn** — To address is the zero address. Occurs when tokens are redeemed/burned at maturity.

## Verifying on-chain independently

Every transfer record includes a transaction hash. To independently verify any transfer:

1. Copy the transaction hash from the portal
2. Go to the relevant block explorer (e.g., Etherscan for Ethereum, Polygonscan for Polygon)
3. Paste the transaction hash in the search bar
4. Look at the **Logs** tab and find the `Transfer(address,address,uint256)` event
5. Confirm the from, to, and amount match what the registry shows

This proves the registry is faithfully reflecting on-chain data. If you find a discrepancy, report it to the registry operator immediately.

## Holder snapshot

The **Holder Snapshot** feature reconstructs the ownership state of a token at any historical block:

1. Navigate to the token history page
2. Click **Holder Snapshot**
3. Enter a block number or date
4. Click **Generate Snapshot**

The snapshot shows every wallet address and its balance at the specified point in time. This is useful for:
- Determining who was entitled to a coupon or dividend at a record date
- Regulatory reporting on investor counts at a specific date
- Forensic analysis following a dispute

Snapshots can be exported as CSV.

## Cap table view

Click **Cap Table** to see the current ownership distribution aggregated by investor identity (not by raw wallet address). This shows:

- Each investor's total balance across all their registered wallets
- Percentage of total supply
- KYC/AML status

:::note
Investors with unregistered wallets (wallets not linked to an identity in the registry) appear under "Unknown / Unidentified" in the cap table. For ERC-3643 tokens this should be zero, as all wallets must be registered before receiving tokens.
:::
