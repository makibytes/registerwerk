---
title: Impersonation — seeing what they see
description: Acting inside a customer's portal for support: how it works, what it is attributed to, its limits, and how to govern it.
---

# Impersonation — seeing what they see

A customer says the Trading Desk will not let them list a holding. You look at their account in the operator portal and everything appears fine. You ask for a screenshot and get a photograph of a monitor.

**Impersonation ends that loop.** It opens the customer portal with the customer's organisation selected, so you see precisely what they see.

It gives access to a customer's view of their own data, so it is gated: starting a session needs a fresh step-up proof and a written reason, the default is a **read-only** session, and a session that can write needs a second approver and is available only in demo mode.

---

## What it actually is

Not a password reset. Not logging in as them. You never obtain their credentials and they are never signed out.

The start call (`POST /api/v1/impersonation`, step-up reason `ADMIN_IMPERSONATION`) carries the customer, a **mandatory reason** (at least 15 characters) and an optional ticket reference. It returns **no token**. It returns a hand-off URL that holds a **one-time code**, valid for 60 seconds. The customer portal exchanges that code for an httpOnly session cookie; using the code a second time ends the session. The token therefore never passes through the operator's hands or browser history.

The session token behind the cookie carries:

| Claim | Value |
|---|---|
| `sub` | **Your** user id — not theirs |
| `entityId` | The customer organisation you are acting within |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `imp_mode` | `READ_ONLY` (default) or `ACT_ON_BEHALF` |
| `jti` | The id of the `impersonation_session` record |
| `exp` | 30 minutes (`registerwerk.auth.impersonation-ttl-seconds`, default 1800) |

!!! success "The subject stays you, and this is the whole design"
    Because `sub` remains your user id, **every action you take is attributed to you** in the [audit log](../../platform/audit-log.md) — not to the customer, and not to some shared "system" actor.

    A customer can never be blamed for something an operator did while impersonating them, and an operator can never hide behind a customer's identity. If that property did not hold, impersonation would be unusable in a regulated context.

    The `imp: true` flag marks the session as impersonated, so impersonated actions are distinguishable from ordinary ones in the log.

### Modes

| | `READ_ONLY` (default) | `ACT_ON_BEHALF` |
|---|---|---|
| Start endpoint | `POST /api/v1/impersonation` | `POST /api/v1/impersonation/act-on-behalf` |
| Who may start it | `REGISTRY_ADMIN` or `SUPPORT_AGENT` | `REGISTRY_ADMIN` only |
| Step-up | Yes (`ADMIN_IMPERSONATION`) | Yes, plus a **second approver** (`ADMIN_IMPERSONATION_ACT_ON_BEHALF`) |
| What the session can do | Reads only: `POST`, `PUT`, `PATCH` and `DELETE` are refused with `403 IMPERSONATION_READ_ONLY` | Writes, except the deny-list below |
| Production mode | Available | **Refused.** In production mode every live session is enforced as read-only, even a leftover write session |

The deny-list for `ACT_ON_BEHALF` (`registerwerk.auth.impersonation-deny-patterns`) returns `403 IMPERSONATION_ACTION_DENIED` for customer attestations and account administration: trade payment confirmation, dispute and settlement, repo-desk default declarations, company user management, company identity-provider settings, webhooks, organisation identity, and deletion of KYC documents.

!!! note "SUPPORT_AGENT"
    `SUPPORT_AGENT` is an operator-staff role for support work: it can start read-only sessions and list customer entities to pick one, and nothing else. Granting or removing it needs step-up and a second approver, and it is included in access reviews.

Only **active** legal entities can be impersonated, the session is recorded in `impersonation_session` (actor, entity, mode, reason, ticket, approver, expiry), and **the customer's company administrators can see every session on their entity** at `GET /api/v1/company/impersonation-sessions`.

---

## Using it

1. In the operator portal, open the customer's record and choose **Impersonate**. Enter the reason (and a ticket reference if you have one). The dialog offers read-only; the write mode appears only in demo mode.
2. You are handed off to the customer portal at `/admin/handoff`. The URL fragment carries the one-time `code`, the `entityId` and the `entityName`; the portal exchanges the code for its session cookie and drops you into the dashboard.
3. A **persistent bar** sits at the top of every page: *Acting as **Nordwind Energie GmbH*** (in a read-only session: *Viewing … (read-only support session - changes are blocked)*), with **Switch company** and **Exit impersonation**.
4. Look and diagnose. Everything you do is logged as you.
5. **Exit impersonation** when finished. The session ends and is audited; if you do not, it expires after 30 minutes.

You can also enter without choosing a customer first — the bar reads *Admin mode — no company selected* and offers **Select company**, with a searchable list. A `SUPPORT_AGENT` lands on this company picker after sign-in.

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

- **The session is short-lived.** It expires after 30 minutes; re-enter (with a fresh reason) rather than trying to extend.
- **The hand-off code is single use and lives 60 seconds.** If the portal does not pick it up in time, or the code is replayed, the session ends; start again.
- **You get a fixed role set**, not the specific roles of any individual user. You cannot reproduce a problem that depends on one user's narrower permissions.
- **Step-up and four-eyes are not bypassed.** Starting a session needs your own step-up proof; a write session also needs a second approver. Inside a session, the customer's own protected operations remain protected, and the approval queue refuses impersonation sessions.
- **You cannot impersonate another operator.** It targets customer legal entities only.

---

## Governing it

Impersonation is available to every `REGISTRY_ADMIN` and every `SUPPORT_AGENT`. That makes it a control question as well as a technical one, and auditors will ask.

!!! tip "Practices worth adopting"

    **Make the reason meaningful.** The platform refuses a start without a reason of at least 15 characters and records it, with the optional ticket reference, in `impersonation_session` and in the audit event. Put the ticket number in the ticket field, and write what you need to see in the reason.

    **Review impersonation events periodically.** They are queryable (see the event names below). A monthly look at who impersonated whom, matched to tickets, turns a wide capability into a supervised one. The customer's company administrators can run the same check from their side.

    **Prefer `SUPPORT_AGENT` for support staff.** It can start read-only sessions and nothing else, so support work does not need a `REGISTRY_ADMIN` account.

    **Keep `REGISTRY_ADMIN` small.** Every holder can start sessions for every active customer.

    **Tell customers it exists.** Discovering after the fact that operator staff can enter their portal damages trust far more than the capability itself. Framed properly — *we can see what you see, every action is recorded against our name, and your administrators can list every session* — it reassures.

    **Never leave a session open.** Exit when finished. An unattended browser in an impersonated session is an unattended browser inside a customer's account (it does expire after 30 minutes).

---

## What an auditor will ask

Have answers ready:

- Who holds `REGISTRY_ADMIN` or `SUPPORT_AGENT`, and how many people is that?
- How do you tie an impersonation event to a support reason? (The reason and ticket are recorded in `impersonation_session` and the `ADMIN_IMPERSONATION_STARTED` event.)
- How would you detect impersonation *without* a corresponding ticket?
- Can you demonstrate that impersonated actions are attributed to the operator, not the customer?
- Is write impersonation disabled in production? (Yes: production mode refuses `ACT_ON_BEHALF` and downgrades any live session to read-only.)

The audit trail has the events `ADMIN_IMPERSONATION_STARTED`, `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` and `ADMIN_IMPERSONATION_ENDED`; requests made inside a session carry the `imp` marker. The attribution question is a live demonstration and worth rehearsing: impersonate a test entity, look at a page, show the audit entries naming your user with `imp` set, and show the session in the customer's company-administrator view.

---

## Where next

- [Two-factor support](two-factor-support.md) — the other big support workflow
- [Audit log](../../platform/audit-log.md)
- [Roles and permissions](roles.md)
