---
title: What Registerwerk is
description: A plain explanation of what the platform does, what it does not do, and what you can expect from it.
---

# What Registerwerk is

**It is a register.** A record of who owns which securities, kept by an operator, with those securities also represented as tokens on a blockchain.

That is the whole idea. Everything else is consequence.

---

## The problem it solves

A security used to be a document. Owning it meant physically holding it, or having a custodian hold it for you. Selling meant handing it over.

That worked, and it was expensive: vaults, couriers, reconciliation, and days between agreeing a trade and completing it.

Electronic securities remove the document. Ownership becomes a register entry. In Germany the **eWpG**, in force since June 2021, makes that legally possible: a security may exist as an entry in a register rather than as a certificate.

Registerwerk implements such a register, and adds a second layer — the same holdings represented as tokens on a blockchain, so transfers can be executed and independently verified without either side trusting the other's records.

---

## The two records

This is the one structural idea worth understanding, because most surprises follow from it.

<div class="grid" markdown>

!!! abstract "The register"
    A database, held by the operator. Names the holder, the amount, restrictions.

    **The record with legal significance.**

!!! abstract "The token"
    A balance in a smart contract on a blockchain. Public and independently verifiable.

    **The record that executes.**

</div>

Software watches the chain and keeps the register in step. Mostly they agree. When they do not, the register is authoritative and the difference is something a human resolves.

[:octicons-arrow-right-24: Holding and custody](lifecycle/holding.md) goes into this properly.

---

## What you can do

| | |
|---|---|
| **Issue** | Create a security, get it approved, deploy it, admit investors, and administer it for its life. |
| **Hold** | Own securities, see your positions, receive statements and payments. |
| **Trade** | Sell before maturity, or buy from other holders. |
| **Borrow** | Pledge holdings as collateral and take a loan against them, where enabled. |
| **Publish** | Build applications on the ecosystem's permission framework and list them. |
| **Audit** | Read across the registry without being able to change anything. |

[:octicons-arrow-right-24: Find your workspace](workspaces/index.md)

---

## Where securities can live

The registry supports several blockchains, chosen per issuance. Each has mainnet and testnet.

| Family | |
|---|---|
| **EVM** | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism |
| **Confidential EVM** | Fhenix, Inco — amounts encrypted on-chain |
| **Solana** | SPL and SPL-2022 |
| **Canton** | A private ledger where counterparties see only their own transactions |
| **Other** | StarkNet, Stellar |

Which one matters more than it may appear: it determines who can see your transactions, what a transfer costs, how fast it settles, and which token standards are available. [Supported blockchains](../blockchains/index.md) compares them.

---

## What it does not do

Being clear about this is more useful than a list of features.

!!! warning "Registerwerk is a reference implementation"
    Working software that models how an electronic securities registry can be built — so the design can be examined, criticised and reused.

    **Using it does not make anybody compliant with the eWpG or any other law.** It does not confer regulatory authorisation, and it does not give a token legal effect as a security. Those depend on the operator's licence, the instrument, the offer, the parties and the deployment.

    You may encounter older material claiming tokens issued here are "legally equivalent to traditional bearer bonds and shares". **That claim is wrong** and has been removed. Whether an instrument has legal effect is determined by law and by how it was actually issued — never by the software that recorded it.

More specifically, it is not:

- **A valuation service.** The register records nominal amounts, not market prices.
- **A custodian of your keys.** You hold your wallet's private key. Nobody can recover it.
- **A trading venue.** It connects to venues; it does not run a market.
- **A payments system.** It supports several payment rails; money moves on those, not here.
- **A guarantor.** If an issuer defaults, the platform records it. It does not make holders whole.

---

## The regulatory background, briefly

The **eWpG** (*Gesetz über elektronische Wertpapiere*) permits electronic securities without a physical document, and requires them to be recorded in a securities register. The sections you will meet most:

| | |
|---|---|
| **§16** | What the register contains and what an entry means. |
| **§17(2)** | Extra content required for individual entries. |
| **§19(2)** | Register statements owed to consumer holders. |
| **§24** | Correcting the register. |

Registerwerk also models Luxembourg (CSSF), France (AMF) and Liechtenstein (TVTG), and touches anti-money-laundering, the Travel Rule, MiFIR reporting, DAC8/CARF, DORA, MiCAR and GDPR.

[:octicons-arrow-right-24: Legal frameworks](../legal/index.md)

!!! note "Every production issuance is approved by the operator first"
    The operator checks issuances against its own admission criteria before anything is deployed. That is an operational control, not a legal opinion about your instrument.

---

## Where next

<div class="grid cards" markdown>

-   **Understand the business**

    ---

    [The life of a security](lifecycle/index.md) — one bond, from idea to repayment. Forty minutes, no background assumed.

-   **Get set up**

    ---

    [Getting your account](onboarding.md) → [Getting verified](kyc.md) → [Connecting a wallet](investors/wallet-setup.md)

-   **Do your job**

    ---

    [Investor](workspaces/investor.md) · [Trader](workspaces/trader.md) · [Issuer](workspaces/issuer.md) · [Auditor](workspaces/auditor.md)

-   **Look something up**

    ---

    [Glossary](glossary.md) · [Questions and answers](faq.md)

</div>
