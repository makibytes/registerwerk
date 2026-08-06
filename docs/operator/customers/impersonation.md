---
title: Impersonation — seeing what they see
description: Acting inside a customer's portal for support: how it works, what it is attributed to, its limits, and how to govern it.
---

# Impersonation — seeing what they see

A customer says the Trading Desk will not let them list a holding. You look at their account in the operator portal and everything appears fine. You ask for a screenshot and get a photograph of a monitor.

**Impersonation ends that loop.** It opens the customer portal with the customer's organisation selected, so you see precisely what they see.

It is also the most powerful thing you can do without a second person's approval, and it deserves to be used deliberately.

---

## What it actually is

Not a password reset. Not logging in as them. You never obtain their credentials and they are never signed out.

The backend mints a **short-lived token** that carries:

| Claim | Value |
|---|---|
| `sub` | **Your** user id — not theirs |
| `entityId` | The customer organisation you are acting within |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `exp` | Short — the standard token lifetime |

!!! success "The subject stays you, and this is the whole design"
    Because `sub` remains your user id, **every action you take is attributed to you** in the [audit log](../../platform/audit-log.md) — not to the customer, and not to some shared "system" actor.

    A customer can never be blamed for something an operator did while impersonating them, and an operator can never hide behind a customer's identity. If that property did not hold, impersonation would be unusable in a regulated context.

    The `imp: true` flag marks the session as impersonated, so impersonated actions are distinguishable from ordinary ones in the log.

---

## Using it

1. In the operator portal, open the customer's record and choose **Impersonate**.
2. You are handed off to the customer portal at `/admin/handoff`, which consumes the token from the URL fragment and drops you into the dashboard.
3. A **persistent bar** sits at the top of every page: *Acting as **Nordwind Energie GmbH***, with **Switch company** and **Exit impersonation**.
4. Work. Everything you do is logged as you.
5. **Exit impersonation** when finished.

You can also enter without choosing a customer first — the bar reads *Admin mode — no company selected* and offers **Select company**, with a searchable list.

!!! tip "The bar is always visible for a reason"
    Any `REGISTRY_ADMIN` sees the impersonation bar in the customer portal at all times, whether or not a company is selected. It is a standing reminder that you are not an ordinary user of this interface, and it makes accidental work-in-the-wrong-context much harder.

---

## When to use it

**Good reasons**

- Reproducing a customer-reported problem you cannot see in the operator portal.
- Checking what a customer's view looks like after a configuration change.
- Walking a customer through a workflow while they are on the phone.
- Confirming a permission or eligibility issue is what you think it is.

**Bad reasons**

!!! danger "Do not use impersonation to do a customer's work for them"
    Placing an order, creating a listing, or submitting an issuance on a customer's behalf produces a record showing *an operator* took a commercial decision inside a customer's account.

    Even with perfect attribution — perhaps *especially* with perfect attribution — that is a difficult record to explain to a regulator or in a dispute. The customer's intent is nowhere in it.

    Look, diagnose, explain. Let the customer act.

!!! danger "Do not use it to read data you would not otherwise be entitled to"
    Impersonation grants you the customer's view of their own information. Whether *you* are entitled to browse it absent a support reason is a [data-protection](../../compliance/data-protection.md) question, not a technical one. The audit log will show you looked.

---

## Its limits

### It does not work in Entra mode

When `ENTRA_ENABLED=true`, customers sign in through Microsoft Entra ID, which issues sessions directly to each user. Registerwerk cannot mint a session on a customer's behalf, and the backend **refuses** to try.

The customer portal shows an explicit message rather than an unexplained redirect:

> **Impersonation is unavailable.** This portal signs in through Microsoft Entra ID, which issues the session directly to each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the customer to sign in themselves, or use the operator portal's read-only views.

This is a real constraint, not a gap to be worked around. In Entra deployments your support toolkit is the operator portal's own views plus a screen-share.

!!! warning "Plan support processes around this before you switch"
    Operators who have built their support workflow on impersonation and then enable Entra mode discover the loss at the worst moment. Decide how you will support customers without it *before* the switch, not after.

### Other limits

- **The token is short-lived.** Long sessions expire; re-enter rather than trying to extend.
- **You get a fixed role set**, not the specific roles of any individual user. You cannot reproduce a problem that depends on one user's narrower permissions.
- **Step-up and four-eyes still apply.** Impersonation does not bypass them.
- **You cannot impersonate another operator.** It targets customer legal entities only.

---

## Governing it

Impersonation is a standing capability of every `REGISTRY_ADMIN`. That makes it a control question rather than a technical one, and auditors will ask.

!!! tip "Practices worth adopting"

    **Require a reason, recorded outside the platform.** A ticket reference, before the session. The audit log records that you impersonated; it cannot record *why*.

    **Review impersonation events periodically.** They are queryable. A monthly look at who impersonated whom, and matching it to tickets, turns an unbounded power into a supervised one.

    **Keep `REGISTRY_ADMIN` small.** Every holder can impersonate every customer. This is the single strongest argument for a tight admin roster.

    **Tell customers it exists.** Discovering after the fact that operator staff can enter their portal damages trust far more than the capability itself. Framed properly — *we can see what you see, every action is recorded against our name* — it reassures.

    **Never leave a session open.** Exit when finished. An unattended browser in an impersonated session is an unattended browser inside a customer's account.

---

## What an auditor will ask

Have answers ready:

- Who holds `REGISTRY_ADMIN`, and how many people is that?
- How do you tie an impersonation event to a support reason?
- How would you detect impersonation *without* a corresponding ticket?
- Can you demonstrate that impersonated actions are attributed to the operator, not the customer?

The last one is a live demonstration and worth rehearsing: impersonate a test entity, perform a benign action, show the audit entry naming your user with `imp` set.

---

## Where next

- [Two-factor support](two-factor-support.md) — the other big support workflow
- [Audit log](../../platform/audit-log.md)
- [Roles and permissions](roles.md)
