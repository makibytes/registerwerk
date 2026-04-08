---
id: viewing-investments
title: Viewing Your Investments
sidebar_label: Viewing Investments
---

# Viewing Your Investments

The **Investments** section of the portal shows all security tokens currently held in your registered wallets, along with your full transfer history.

## Holdings overview

Navigate to **Investments → Holdings**. You will see a table of all tokens you currently hold:

| Column | Description |
|--------|-------------|
| **Token name** | The security name (e.g., "Example AG Bond 2025") |
| **ISIN** | International securities identifier |
| **Balance** | Your current token balance |
| **Network** | The blockchain network the token is on |
| **Issuer** | The issuing organization |
| **Standard** | Token standard (ERC-20, ERC-3643, etc.) |
| **Explorer** | Link to the block explorer for the contract |

### Confidential token balances

If you hold a **Confidential ERC-3643** token (on Fhenix or Inco), your balance is displayed as `[encrypted]` in the public view. To decrypt and view your balance, click the **Decrypt Balance** button. This triggers a decryption request using your connected wallet — you will be asked to sign a message to authorize the decryption.

:::note
Decrypted balances are shown only in your browser session and are never sent to the registry servers. The on-chain balance remains encrypted.
:::

## Transfer history

Navigate to **Investments → Transfers** to see your complete on-chain transfer history.

Each transfer record shows:

| Field | Description |
|-------|-------------|
| **Date/time** | UTC timestamp of the block containing the transfer |
| **Token** | Token name and contract address |
| **From** | Sending wallet address |
| **To** | Receiving wallet address |
| **Amount** | Number of tokens transferred |
| **Transaction hash** | On-chain transaction identifier (links to explorer) |
| **Block** | Block number |

### Filtering transfers

Use the filter panel on the left to narrow down the transfer list:

- **Date range** — select a start and end date
- **Token** — filter to a specific token or issuance
- **Direction** — show only incoming, only outgoing, or both
- **Network** — filter by blockchain network

### Exporting your transfer history

Click **Export** in the top-right to download your transfer history:

- **CSV** — suitable for import into Excel or accounting software
- **PDF** — formatted statement with your account details and a digital signature from the registry

## Token detail view

Click any token in your holdings list to open the detail view. This shows:

- Full issuance information (name, ISIN, issuer, legal documents)
- Your current balance and percentage of total supply
- Transfer history for this token only
- The contract address and a link to the block explorer

## Verifying holdings on-chain

You can independently verify your balance on the blockchain without using the registry portal. On any Ethereum-compatible explorer (e.g., Etherscan, Polygonscan):

1. Navigate to the token contract address (shown in the portal)
2. Click **Contract → Read Contract**
3. Call `balanceOf` with your wallet address
4. The returned value matches your balance shown in the portal

This independently confirms that the registry is displaying accurate data.
