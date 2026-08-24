---
title: Wallet Setup
---

# Wallet Setup

To hold and view security tokens, you must connect a blockchain wallet to your registry account. This page explains how to set up a compatible wallet and get it whitelisted for ERC-3643 tokens.

## Supported wallet types

The eWpG Registry supports any self-custody wallet that can produce EIP-712 signatures. Recommended wallets:

| Wallet | Type | Networks |
|--------|------|----------|
| MetaMask | Browser extension / mobile | All EVM networks |
| Ledger Live | Hardware | All EVM networks |
| Trezor Suite | Hardware | All EVM networks |
| Phantom | Browser extension / mobile | Solana (and EVM) |
| Rabby | Browser extension | All EVM networks |

!!! tip
    For institutional use, hardware wallets (Ledger, Trezor) are strongly recommended. They keep your private key offline and require physical confirmation for every transaction.


## Connecting a wallet

1. Navigate to **Profile → Wallets**
2. Click **Connect Wallet**
3. Select your wallet type from the list
4. Your wallet extension opens and asks you to connect. Approve the connection.
5. The portal asks you to **sign a message** — this is a gas-free signature that proves ownership of the wallet address. Sign it in your wallet.
6. The wallet address now appears in your wallet list.

You can connect multiple wallets. Holdings from all connected wallets are aggregated in the **Investments** view.

## Getting whitelisted for ERC-3643 tokens

Simply connecting a wallet to the portal does not automatically whitelist it for ERC-3643 token transfers. Whitelisting is a separate step performed by the **issuer** of the token after they verify your KYC status.

The process:

1. Connect your wallet in the portal (as described above)
2. Provide your wallet address to the issuer (visible on the **Wallets** page)
3. Ensure your KYC/AML review is complete (check **Profile → Identity**)
4. The issuer registers your wallet in their Identity Registry contract
5. You will receive a notification when whitelisting is complete

After whitelisting, you can receive tokens at that wallet address. The whitelisting is stored on-chain and persists independently of the portal.

## Removing a wallet

To remove a wallet from your account:

1. Navigate to **Profile → Wallets**
2. Click **Remove** next to the wallet address

Removing a wallet from your portal account does not remove it from any issuer's on-chain whitelist. Contact each issuer individually if you want your address removed from their Identity Registry.

## Adding a Solana wallet

For Solana-based tokens:

1. Navigate to **Profile → Wallets**
2. Click **Connect Wallet → Solana**
3. Connect using Phantom or another supported Solana wallet
4. Sign the verification message

Solana wallet addresses use a different format (base58) from EVM wallets. The portal displays both formats side by side for clarity.

## Security best practices

- **Never share your private key** with anyone — not even the registry operator
- Use a dedicated wallet for securities; avoid mixing with personal DeFi activity
- Enable wallet password / biometric protection
- Back up your seed phrase in a secure, offline location
- For significant holdings, use a hardware wallet

!!! warning
    The registry operator will never ask for your private key or seed phrase. If someone claiming to be from the registry asks for this information, it is a scam — do not comply and report it immediately.

