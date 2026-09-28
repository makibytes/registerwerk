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

### Coupon schedule conventions

Saving the bond terms generates the coupon schedule the corporate-action jobs work from. Conventions follow ICMA practice; each is set on the terms and defaults as shown:

| Setting | Default | Effect |
|---|---|---|
| Day count | ACT/ACT (ICMA) | Regular periods accrue exactly 1/frequency; a short first period accrues its actual days over the notional regular period. ACT/360, ACT/365 (fixed), 30/360 and 30E/360 are available. |
| Schedule | Rolled back from maturity, short first stub | Regular dates are counted backwards from the maturity date; an irregular period goes at the start. If maturity is a month end, every coupon date is a month end. |
| Business-day convention | Modified Following | A payment date on a non-business day moves to the next business day, unless that crosses into the next month — then to the previous one. Accrual always uses the unadjusted dates. |
| Holiday calendar | TARGET2 | Weekends, 1 January, Good Friday, Easter Monday, 1 May, 25 and 26 December. |
| Record date | 1 business day before payment | Who is on the register at the end of this day receives the coupon. |
| Announcement | 5 business days before the record date | When the coupon is announced. |
| Grace periods | 30 days interest, 7 days principal | How long after the payment date an unpaid amount is still only overdue. |

The coupon per unit is face value × coupon rate × day-count fraction, kept unrounded; rounding happens only on each holder's entitlement. A floating-rate coupon has no amount until its rate is fixed and is not announced before then. Only future payment dates are generated, so terms entered late do not create back-dated coupons. The schedule appears on the asset's **Corporate Actions** tab.

### How coupons and the redemption are raised

- **Announcement.** The coupon (and the final redemption) is raised automatically on its *announcement date*, not on the payment date, so there is time to attest and confirm before payment. The redemption follows the same rule: payment date = maturity adjusted by the business-day convention.
- **Record date.** Entitlements are fixed as of the **end of the record date** (Europe/Berlin), taken from the register as it stood then. Transfers after that instant do not change them. For chain-deployed assets the snapshot waits until the register has been reconciled past the record date and refuses ("unmapped at record date") when a wallet holding units at that time has no register entry. A record date proposed or approved for a dividend, split or call must still be ahead (the next business day at the earliest).
- **Rounding.** Each holder's entitlement is rounded to the currency's minor unit (half-even); the confirmation shows the total paid and the rounding difference.
- **Overdue, missed, default.** An unsettled amount past its payment date first shows as **OVERDUE** (operators only; customers see "payment pending"). Only after the grace period (30 days interest, 7 days principal) does a coupon become **MISSED** and a bond **DEFAULTED**. A settlement clears every one of these states: a settled redemption marks the bond **REDEEMED**, a settled call marks it **CALLED**, and a settled coupon is **PAID**.
- **Two-party control.** The issuer attests; an operator confirms. An operator can never attest as the issuer: the operator route is *Override attestation* (step-up and a reason, audited separately), including while impersonating. A proposal must be approved by a different person than its proposer.
- **Held entitlements.** Nominee-pool (look-through) entitlements are not paid and keep a settled action open, flagged "held entitlements outstanding", until they are resolved.
- **Job order.** 05:30 coupons, 05:45 redemptions, 06:00 daily transitions (Europe/Berlin), so an action raised in the morning is processed the same run.

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

    Terms are set wholesale (with step-up) only until the asset is issued, and ISIN, currency, issue size, denomination and dates cannot be changed through the edit form once it is approved. Later changes are **amendments**: *Edit asset → Amend terms* asks for the legal basis, step-up and a second operator, records every before/after value in the audit log and regenerates the future coupons that have not been announced yet. Paid coupons and coupons already announced are never rewritten. Face value, coupon and maturity of a deployed Canton bond cannot be amended here at all — they are fixed in the ledger instrument.

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


### Subscription orders and register entries

Investors subscribe through the portal. On each order you (or the issuer) work the queue on the asset's **Subscription orders** tab:

1. **Allocate** — fully or scaled down. Issue size and the investor's maximum holding (including their other open allocations) are checked under a lock, so parallel allocations cannot overshoot.
2. Wait for the investor to **accept**. The allocation then carries a payment deadline (default 10 TARGET business days). If it is not paid in time a scheduled job marks it **lapsed** and frees the capacity.
3. **Confirm payment** once the cash is on the account ([step-up](../../compliance/step-up-mfa.md)). For bonds the amount due is allocated × face value × issue price: an underpayment is refused, an overpayment is recorded as *refund due* — the refund itself is a manual payment. For assets without bond terms you enter the amount received.
4. **Settle.** KYC, sanctions screening, Sperrvermerk, register freeze, finality, target market and holding cap are checked again. On a deployed ERC-20 or ERC-3643 asset the units are minted and the register is credited by the holder sync once the transfer is indexed; on other deployed standards the order stays *paid* until you issue the units by hand. Without deployment the register is credited directly.
5. **Release** gives an allocation back with a reason; on a paid order the payment is marked as refund due.

Register entries are yours to make, not the issuer's. On the asset's **Holders** tab, *Add register entry* and *Change §17(2) attributes* both require an instruction (who, and a reference); a blank field means no change, removing a right needs its own checkbox and a second approver. Issuers ask through *change requests*, which you execute (four eyes) or reject. On a deployed asset a manual entry is only a wallet mapping with nominal 0.

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
