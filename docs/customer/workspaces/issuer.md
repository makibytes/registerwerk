---
title: Issuer
description: For organisations that raise money by issuing securities — creating, deploying, administering and redeeming them.
---

# Issuer

**You are borrowing money, or selling a stake, and you are doing it by issuing a security.** You describe the instrument, get it approved, put it on a blockchain, admit investors, create the units, and then administer the thing for years.

Of the three workspaces this is the one with the most responsibility attached. What you create here is a legal obligation of your organisation.

---

## What is here

| | |
|---|---|
| **Issuances** | Create and administer your securities. The main event. |
| **My dApps** | Publish applications to the marketplace — see [dApp publisher](dapp-publisher.md). |
| **Company Admin** | Manage your users and organisation — see [Company administrator](company-admin.md). |
| **Marketplace** | Ecosystem applications. |

---

## Before your first issuance

- **Your organisation is onboarded and its KYC is approved.** An issuer whose KYC has lapsed cannot issue.
- **You know your jurisdiction.** This is not a label — it selects the whole body of rules applied to the instrument for its life. [Legal frameworks](../../legal/index.md).
- **You have an ISIN, if you need one.** Registerwerk enforces uniqueness but does not issue them; you get one from your national numbering agency. You can proceed without, but interoperating with the outside world becomes harder.
- **You have decided who may hold it.** Public offer? Professional investors only? Single jurisdiction? This determines your token standard, and changing it later means a new instrument.

---

## Creating an issuance

*Issuances → New Issuance.* Three steps.

=== "1. Details"

    Name, ISIN, jurisdiction, and the economics. For a bond: face value, currency, issue and maturity dates, coupon rate, day count convention, payment frequency, callability, and issue price as a fraction of face value.

    **Issue price** matters for zero-coupon bonds, which pay no interest and compensate the investor by being sold below par — buy at €800, receive €1,000 at maturity. It defaults to `1.0`.

    **Day count convention** (ACT/360, ACT/365, 30/360…) decides how a partial year becomes a fraction when interest is computed. It is unglamorous and it changes the money.

=== "2. Chain & standard"

    Which blockchain, and which token standard.

    For a regulated security the answer is usually [ERC-3643](../../token-standards/erc3643.md), because it is the one that enforces *who may hold this* in the token itself. [ERC-20](../../token-standards/erc20.md) is simpler and understood everywhere, but has no concept of eligibility — anyone who receives a unit owns it.

    Other shapes: ERC-1155 for many series in one contract, ERC-3525 for semi-fungible instruments, ERC-4626/7540 for funds and vaults, DAML on Canton where counterparty privacy is required, SPL-2022 on Solana.

    [:octicons-arrow-right-24: Choosing a token standard](../issuers/token-standards.md)

=== "3. Review & submit"

    Check it and submit. Status goes `DRAFT` → `PENDING_APPROVAL` and **editing stops**.

---

## Approval

The operator reviews. Then:

| | |
|---|---|
| **Approved** | `APPROVED`. Terms locked. You may deploy. |
| **Rejected** | Back to `DRAFT` with a recorded reason. Edit and resubmit. |

There is no `REJECTED` state — a rejected issuance returns to draft, where it is editable. The reason is recorded in the [audit log](../../platform/audit-log.md).

---

## Deploying

*Issuance → Deploy.* Registerwerk sends the transaction and records the contract address. For ERC-3643 this deploys the whole suite — token, identity registry, trusted issuers registry, compliance — wired together.

The contract now exists and holds **zero units**.

[:octicons-arrow-right-24: Deploying to a blockchain](../issuers/deploying-to-chain.md)

---

## Admitting investors

*Issuance → Investors.* Each investor must be a KYC-approved entity with a registered wallet, added to the identity registry.

!!! warning "This is a precondition, not paperwork"
    Under ERC-3643 an unadmitted wallet **cannot receive tokens** — the transfer reverts on-chain. Minting before admitting produces failed transactions and nothing else.

Choose the entry type per holder:

- **Collective** (*Sammeleintragung*) — a custodian holds for many underlying investors.
- **Individual** (*Einzeleintragung*) — the investor is named directly, by pseudonymous reference. §17(2) eWpG requires additional content: third-party rights, disposal restrictions, legal-capacity notes. §19(2) obliges you to send register statements to consumer holders.

An asset may hold both forms at once.

[:octicons-arrow-right-24: Managing your investors](../issuers/managing-investors.md)

---

## Minting and issuing

*Issuance → Mint.* Units come into existence and are assigned to holders. Then `APPROVED` → `ISSUED` and the instrument is live.

!!! danger "Minting creates value from nothing"
    An error here is not a wrong number in a report — it is real securities in the wrong hands.

    Mint control rules can cap what an address may ever receive, the action requires [step-up authentication](../../compliance/step-up-mfa.md), and every mint is logged with a named actor.

---

## Living with it: five years of administration

This is the part people underestimate. Issuance is a week. Administration is the rest of the decade.

### Corporate actions

Coupons, and eventually redemption, are raised automatically from the payment schedule and advanced through their dates — you don't create these yourself.

Dividends, splits, and early calls are different: you **propose** them (*Issuance → Corporate Actions → Propose*), and an operator reviews the proposal — approving it onto the register, or rejecting it — before it goes any further.

Whichever way an action was raised, settlement needs sign-off from two separate parties before it executes: **you attest** that the underlying obligation is ready — the cash for a coupon or dividend, the mechanics for a split or an early call — and then **an operator confirms** the register/on-chain side. Attesting is a normal authenticated action, not [step-up](../../compliance/step-up-mfa.md) — only the operator's confirmation is step-up gated. If you never log in to attest, an operator can override the requirement instead; that override is recorded as a distinct, permanently visible exception, never silently indistinguishable from your own attestation.

The three dates that decide who gets paid: **record date** (whoever holds at this instant is entitled), **ex date** (from here it trades without the payment), **payment date** (money moves).

[:octicons-arrow-right-24: Corporate actions in detail](../lifecycle/redemption.md)

### Watching your holder list

Your investors trade with each other and you cannot stop them. What you get is visibility: the register updates and your holder list changes.

Watch for **holder caps** if your instrument has them — a compliance rule that reverts transfers once a limit is reached. Investors experience this as an unexplained failure, so knowing your own limits saves support traffic.

### Register statements

For individual-entry consumer holders, §19(2) statements are generated and retained as register records. Reproducible years later, because a statement you cannot produce again is not evidence.

### Suspension

`ISSUED` → `SUSPENDED` freezes trading without ending the instrument — for a corporate action, a dispute, or a suspected error. Reversible.

### Redemption

At maturity: snapshot, entitlements, your attestation and an operator's confirmation, payment, tokens burned, `REDEEMED`. Terminal — there is no way out of it.

Holder rows are **soft-deleted, never removed**: a §16 register entry that vanishes cannot satisfy retention or tamper-evidence obligations.

---

## Things that will surprise you

!!! info "You cannot block a lawful trade between eligible holders"
    Once issued, the instrument trades under its own compliance rules. You set those rules at issuance; you do not adjudicate individual trades.

!!! info "You cannot edit an approved issuance"
    Terms lock at approval. A change means a new issuance, or an operator correction with an audit trail.

!!! info "Your investors' KYC is not your judgement"
    The operator verifies entities. You cannot admit an investor the operator has not approved, however well you know them.

!!! info "A forced transfer needs the operator"
    §24 eWpG corrections — a lost key, a court order, an erroneous entry — are operator actions under four eyes, not something you perform.

---

## Where next

- [The life of a security](../lifecycle/index.md) — the whole arc, end to end
- [Choosing a token standard](../issuers/token-standards.md)
- [Company administrator](company-admin.md) — managing your organisation's users
