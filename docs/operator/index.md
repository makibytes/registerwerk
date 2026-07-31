---
title: For operators
description: Running a Registerwerk registry — the job, the architecture, and the customer-facing processes that make up most of the work.
---

# For operators

**You run the registry.** Customers issue securities into it, hold them, trade them. Your job is to decide who gets in, check what they are doing, keep the platform alive, and help when something goes wrong.

You do not need to understand securities markets in the depth an issuer does. You do need to understand enough to know what you are approving and why it matters.

---

## Where to start

<div class="grid cards" markdown>

-   :material-flag:{ .lg .middle } **[What an operator does](getting-started.md)**

    ---

    The job in full, the portal, and the decisions that are yours alone.

-   :material-sitemap:{ .lg .middle } **[How Registerwerk is built](architecture.md)**

    ---

    The architecture, framed around what breaks and what it means when it does.

-   :material-account-group:{ .lg .middle } **[Serving customers](customers/index.md)**

    ---

    Onboarding, KYC, approvals, support, impersonation, offboarding. Most of the actual work.

-   :material-server:{ .lg .middle } **[Installation](installation/prerequisites.md)**

    ---

    Getting it running, from prerequisites to gateway.

</div>

---

## The four things only you can do

Customers can do a great deal. These four are yours, and they are yours because each one can cause harm that is difficult or impossible to reverse.

| | | |
|---|---|---|
| **Admit an organisation** | Nobody uses the registry until you approve their entity and their KYC. | [Onboarding](customers/onboarding-flow.md) · [KYC](customers/kyc-process.md) |
| **Approve an issuance** | No security exists until you say yes. | [Approving issuances](customers/approving-issuances.md) |
| **Correct the register** | Forced transfers, forced burns, holder blocks — the §24 and §26 eWpG powers. | [Sperrvermerk](../compliance/sperrvermerk.md) |
| **Act as a customer** | Impersonation, for support. Powerful and fully attributed. | [Impersonation](customers/impersonation.md) |

The second and fourth are where new operators most often want guidance, and both have dedicated pages.

---

## The habits worth forming early

!!! tip "Read the audit log when nothing is wrong"
    If the only time you open it is during an incident, you will not know what normal looks like, and you will not notice the thing that should not be there.

!!! tip "Treat four-eyes as a feature, not an obstacle"
    Several operations require a second person: reversing a settled trade, approving a corporate action's settlement, resetting a customer's MFA, issuing a temporary access pass. These are exactly the operations where a single mistaken or malicious action is worst.

    Deployments where one person holds every credential have four-eyes controls in name only. Staffing is what makes them real.

!!! tip "Say 'I don't know' out loud"
    You will be asked whether an instrument is compliant, whether a token has legal effect, whether a customer may lawfully do something. The platform models rules; it does not adjudicate them.

    Referring a question to counsel is the correct answer far more often than operators expect it to be.

---

## What you are not

Worth stating, because customers will assume otherwise.

- **You are not their lawyer.** You approve against your own criteria, not theirs.
- **You are not their custodian.** You cannot recover a lost wallet key. You can execute a §24 forced transfer, which is a formal correction, not a password reset.
- **You are not a valuation service.** The registry records nominal amounts, not market prices.
- **You are not a guarantor.** If an issuer defaults, you record the fact; you do not make holders whole.

---

## When something is wrong

| | |
|---|---|
| Platform misbehaving | [Troubleshooting](troubleshooting.md) |
| Something is down | [Monitoring](maintenance/monitoring.md) · [DR runbook](dr/runbook.md) |
| Customer locked out | [Two-factor support](customers/two-factor-support.md) |
| Customer confused | [Impersonation](customers/impersonation.md) — see exactly what they see |
| Known defects | [Assurance review ledger](../assurance-review-ledger.md) |
