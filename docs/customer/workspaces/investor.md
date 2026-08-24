---
title: Investor
description: For people who hold securities — seeing what you own, what it is worth, and what you are owed.
---

# Investor

**You own securities and you want to keep track of them.** You are not trading actively, not issuing anything, and not looking for leverage. You bought something and you want to know where it stands.

This is the smallest workspace, and deliberately so.

---

## Before anything works

Three things must be true before a security can reach you. If something is not working, it is almost always one of these.

<div class="grid cards" markdown>

-   **1. Your organisation is onboarded**

    ---

    Your company exists in the registry as a legal entity with an active status.

    [:octicons-arrow-right-24: Getting your account](../onboarding.md)

-   **2. Your KYC is approved**

    ---

    The operator has verified your organisation. Not merely submitted — **approved**, and not expired.

    [:octicons-arrow-right-24: Getting verified](../kyc.md)

-   **3. You have registered a wallet**

    ---

    An address the securities can be sent to. Without one there is nowhere to deliver.

    [:octicons-arrow-right-24: Connecting a wallet](../investors/wallet-setup.md)

</div>

!!! warning "Order matters"
    For a regulated instrument such as an [ERC-3643](../../token-standards/erc3643.md) security, your wallet must be admitted to that instrument's identity registry *before* anything can be transferred to you. A transfer to an unregistered wallet does not sit pending — it fails on-chain.

    If an issuer says they have sent you securities and nothing has arrived, this is the first thing to check.

---

## Your day-to-day

### Dashboard

What changed since you last looked: your holdings, recent activity, anything needing attention — an expiring KYC, a pending action, a blocked holding.

### Positions

Everything you hold, across every asset and every chain.

| Column | Read it as |
|---|---|
| **Asset** | Which security. |
| **Nominal amount** | The face value you hold. |
| **Wallet** | Which of your addresses holds it. |
| **Entry type** | Collective or individual — [what that means](../lifecycle/primary-issuance.md#what-a-register-entry-contains). |
| **Status** | Active, or blocked. |

!!! note "Nominal is not market value"
    €100,000 nominal means you are owed €100,000 at maturity. It does not mean the position is worth €100,000 today — a bond can trade above or below face value for its whole life.

    Registerwerk is a register. It records what you hold, not what somebody would pay for it.

### Investments

One holding, in depth. The instrument's terms, its on-chain address and transaction history, corporate actions affecting you, and your register statements.

This is where you go when you need to *prove* something rather than merely see it.

---

## Things that will happen to you

### You will receive a register statement

If you hold under an **individual entry** and you are a consumer, §19(2) eWpG entitles you to a *Registerauszug* — after your initial entry, after every change affecting you, and at least once a year.

These are permanent, reproducible records, not notification emails. [More on statements](../lifecycle/holding.md#your-register-statement).

Institutional holders in a collective entry do not receive them, which is why you may see none.

### You will receive coupons

For a bond, interest arrives on a schedule. Whether *you* receive a given payment depends on the **record date**, not the payment date — hold on the record date and the payment is yours even if you sell the next day.

[:octicons-arrow-right-24: How corporate actions work](../lifecycle/redemption.md)

### Your KYC will expire

Verification has an expiry. When it approaches, the platform warns you; when it lapses, transfers stop.

**This does not take your securities away.** You remain the holder, you remain entitled to payments. You simply cannot move anything until your organisation is re-verified.

### A holding may be blocked

A court order, a sanctions match, a pledge, an unresolved compliance issue. You will see the block and its reason against the position.

You still own it. You cannot move it. [More on blocks](../lifecycle/holding.md#when-a-holding-is-blocked).

---

## Things you cannot do here

Said plainly, so you do not go looking:

- **You cannot sell from the Investor workspace.** Selling requires the `TRADER` role and the [Trader workspace](trader.md).
- **You cannot value your portfolio.** Registerwerk holds no market prices for the securities it registers.
- **You cannot transfer to an arbitrary address.** For regulated instruments the destination must be an admitted holder.
- **You cannot recover a lost wallet key yourself.** See below.

!!! danger "If you lose your wallet key"
    Nobody can restore it. Not the operator, not the issuer.

    Your *claim* survives — the register still records you as holder, and you remain entitled to coupons and repayment. What you have lost is the ability to move the tokens.

    Recovery is an operator-executed **forced transfer** under §24 eWpG: a formal, evidenced correction moving your holding to a new wallet you control. Contact the operator. It requires proof, it requires [four eyes](../../compliance/step-up-mfa.md), and it is not quick.

---

## Where next

- [The life of a security](../lifecycle/index.md) — what is actually happening around you
- [Holding and custody](../lifecycle/holding.md) — where your securities really live
- [Questions and answers](../faq.md)
