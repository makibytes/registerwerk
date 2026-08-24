---
title: Approving an issuance
description: The decision that brings a security into existence — what to check, what approval does and does not mean, and what happens next.
---

# Approving an issuance

An issuer has described a security and submitted it. Until you approve, it is a description. After you approve, it can become a legal obligation of that issuer held by investors.

This is the most consequential routine decision an operator makes.

---

## What you are actually deciding

!!! warning "Be precise about what approval means"
    Approval means: **this issuance meets the registry's criteria for admission.**

    It does not mean the instrument is lawful, that the offer complies with prospectus rules, that the issuer may lawfully issue it, or that the token has legal effect. Those depend on the issuer's authorisation, their advice and their circumstances.

    If an issuer treats your approval as a compliance opinion, correct them in writing. That misunderstanding is expensive later.

---

## Before you look

Confirm the boring things first — they disqualify faster than anything in the terms:

- [ ] The issuing entity is **active**, and its **KYC is approved and unexpired**.
- [ ] The entity is registered as an issuer.
- [ ] There is no open [sanctions](../../compliance/sanctions-screening.md) matter against it.

---

## What to check

### Identity

| | |
|---|---|
| **Name** | Sensible, and not misleadingly similar to an existing instrument. |
| **ISIN** | Unique — the platform enforces this. Registerwerk does not issue ISINs; the issuer obtains one from their national numbering agency. An issuance without one is permitted but limits interoperability. |
| **Jurisdiction** | Selects the entire body of rules applied for the instrument's life. Changing it later is not a field edit. |

### Terms

For a bond: face value, currency, issue and maturity dates, coupon rate, day count, payment frequency, callability, issue price.

!!! tip "Three things worth a second look"
    **Maturity before issue date.** Rare, and catastrophic if it reaches production — the coupon schedule is generated from these.

    **Issue price on a zero-coupon bond.** It defaults to `1.0` — par. A zero-coupon bond at par pays no interest and repays face value: an instrument returning nothing. If it is genuinely zero-coupon, the issue price should be a discount. This default has caused real confusion.

    **Day count convention.** Unglamorous, and it changes how much money moves. Confirm it matches the term sheet rather than assuming.

### Chain and standard

Does the token standard fit what is being claimed?

!!! danger "An ERC-20 for a restricted security is the mismatch to catch"
    If the instrument may only be held by verified or professional investors, [ERC-20](../../token-standards/erc20.md) cannot enforce that. Anybody who receives a unit owns it.

    Restricted instruments should use [ERC-3643](../../token-standards/erc3643.md), where eligibility is checked in the token contract and non-compliant transfers revert on-chain.

    This is the single most important technical check in the review, because it is invisible afterwards. Nothing breaks at approval. It breaks the first time a unit reaches a wallet that should never have held it — by which point 50,000 units are in circulation.

Also confirm mainnet versus testnet is what the issuer intended. Approving a mainnet issuance somebody meant as a rehearsal is an awkward conversation.

---

## Deciding

=== "Approve"

    Status becomes `APPROVED`. **Terms lock.** The issuer may now deploy.

    Record why you approved. The audit log captures that you did, not what satisfied you.

=== "Reject"

    Status returns to **`DRAFT`** — editable again — with your reason recorded.

    There is no `REJECTED` state. A rejected issuance is a draft. This surprises operators expecting a dead-end status.

    **Write a reason the issuer can act on.** "Non-compliant" produces a resubmission of the same thing. "Instrument is restricted to professional investors but uses ERC-20, which cannot enforce that — resubmit as ERC-3643" produces a correct one.

---

## After approval

You are not finished with it. The issuer will:

1. **Deploy** the contract.
2. **Admit investors** — each needing an approved KYC entity and a registered wallet.
3. **Mint** the units.
4. **Issue**, making it live.

You will be involved again when investors need onboarding, and permanently thereafter for corporate actions.

!!! info "Corporate action settlement needs a second operator"
    Approving a corporate action for settlement requires [four eyes](../../compliance/step-up-mfa.md).

    Paying the wrong holder list is the classic catastrophic error in securities administration, and it is very hard to reverse. Make sure your rota actually has two available people when coupon dates fall — a four-eyes control nobody can satisfy on a Friday afternoon is a control that gets bypassed.

---

## Suspension and redemption

**Suspend** (`ISSUED` → `SUSPENDED`) freezes trading without ending the instrument, for a corporate action, a dispute, or a suspected error. Reversible.

**Redeem** is terminal. There is no way out of `REDEEMED`.

Both are recorded with a named actor.

---

## Where next

- [Reviewing KYC](kyc-process.md) — the gate before this one
- [Design and approval](../../customer/lifecycle/design.md) — the issuer's view of the same step
- [Choosing a token standard](../../customer/issuers/token-standards.md)
