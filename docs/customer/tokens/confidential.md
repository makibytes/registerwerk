---
id: confidential
title: Confidential ERC-3643
sidebar_label: Confidential Tokens
---

# Confidential ERC-3643

Confidential ERC-3643 extends the standard T-REX security token with **Fully Homomorphic
Encryption (FHE)**, using Zama's fhEVM technology. Investor balances and transfer amounts are
encrypted on-chain — not visible to the public — while compliance enforcement (KYC, freeze,
pause) still applies.

## What is Fully Homomorphic Encryption?

FHE allows computation to happen directly on encrypted data, without ever decrypting it. The
smart contract can check whether a transfer is compliant and update balances while the actual
numbers stay hidden — even from the blockchain nodes processing the transaction.

## Why confidentiality matters for institutional securities

Public blockchains are transparent by design, which creates real problems for institutional
holders:

- **Market impact** — visible large positions can move prices against the holder
- **Competitive sensitivity** — position sizes can reveal strategy
- **Regulatory requirements** — some jurisdictions restrict disclosure of holdings to other
  market participants

## Who can see what

Only you can decrypt your own balance — not other investors, not the public. The registry
operator and an independent auditor can also decrypt your balance (that's a regulatory
requirement, not a bug: eWpG registry oversight and audit obligations don't disappear because a
token is confidential), and the issuer can see all holders' balances on their own asset. Nobody
else can — this is enforced on-chain by Zama's access-control list, not by trusting Registerwerk's
backend: the platform itself cannot decrypt your balance unless it holds a key you're aware of
(the operator's, for reporting and reconciliation obligations).

## Which chains support this

Zama's fhEVM runs on **Ethereum and Base** (Sepolia testnet today has real, documented
infrastructure; mainnet addresses were still being finalised at time of writing). It does **not**
run on Fhenix or Inco — those are separate FHE ecosystems with incompatible cryptography.

## How it works in the portal

1. Open a confidential holding from **My Investments**. If your wallet isn't connected yet,
   click **Connect Wallet**.
2. Click **Reveal My Balance** — your wallet signs a request, and your balance is decrypted
   directly against Zama's relayer. Registerwerk's servers never see the plaintext amount; this
   happens entirely in your browser.
3. To send a confidential transfer, enter the recipient and amount and click **Transfer** — the
   amount is encrypted in your browser before it ever leaves your machine, and your wallet submits
   the encrypted transaction. Standard compliance checks (KYC, freeze, pause) still apply on-chain.
4. If you're an issuer, your issuance's **Confidential Balances** panel lets you (as a registered
   viewer) reveal any holder's balance and confidentially mint new tokens to an investor.

## Current status

| Capability | Status |
|---|---|
| Encrypted balances/transfers on-chain | ✅ Implemented |
| You revealing your own balance in the portal | ✅ Implemented — client-side, via your connected wallet |
| You submitting a confidential transfer in the portal | ✅ Implemented — client-side encryption |
| Issuer revealing any holder's balance / confidential minting | ✅ Implemented |
| Registry-initiated compulsory cancellation (§26 Einziehung) on an encrypted amount | ✅ Available to the operator |
| Regulator-triggered aggregate disclosure (not your individual balance) | ✅ Implemented on-chain |
| Freeze/pause/forced-transfer on confidential ERC-3643 via the operator | ❌ Not yet wired — only forced-burn has a confidential-specific path today |

Ask your registry operator whether confidential issuance is active for a specific asset if you
have questions about the current rollout.
