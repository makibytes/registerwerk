---
title: Finality Policy and Reorg Compensation
---

# Finality Policy and Reorg Compensation

This page describes what happens once a block reorg is *detected* (see
[Indexer Resilience](resilience.md) for detection itself): how the registry automatically
undoes the state changes a retracted block caused, what it does when it can't, and how an
operator controls which finality level each kind of action requires before it's allowed to
proceed at all.

!!! note "Reflects the current implementation"
    This page describes the `finality` Spring Modulith module as implemented, not as originally
    scoped — some parts of the original portfolio plan (Track B, phases P3–P8) were deliberately
    narrowed or ruled out after investigation; see the "What's gated today" section below for the
    honest current coverage.

## The three-tier finality model

Every module that persists or reasons about state derived from an indexed on-chain event shares
one vocabulary, `finality.api.FinalityLevel`:

| Level | Meaning |
|---|---|
| `PROVISIONAL` | Seen, but still inside the chain's unsettled window — can be reorged out |
| `SAFE` | Past a fast-but-not-final checkpoint (Ethereum's `safe` tag, Starknet's `ACCEPTED_ON_L2`) — still reorgable in principle, but a materially rarer event than a shallow reorg |
| `FINALIZED` | Past the chain's full finality guarantee — safe to treat as settled |
| `ORPHANED` | The block that caused this state change was reorged out — terminal, not a lattice position; nothing is "at least ORPHANED" |

Solana, Stellar, and Canton are final-on-write (their consensus model has no unsettled window at
all), so every event on those chains is `FINALIZED` the moment it's indexed.

Customer-facing surfaces (the customer app, and any operator-role that isn't registry staff) never
see these four names directly — see [Two vocabularies, one model](#two-vocabularies-one-model)
below.

## The effect journal (`chain_effect`)

Any module that changes state because of an on-chain event records that change in
`chain_effect`, in the same transaction as the change itself — source block identity (chain,
number, hash, tx hash), which entity was affected, and how to undo it if the source block is
later retracted. Three categories:

- **RECOMPUTE** — the affected row can be re-derived from other still-current data. Currently one
  compensator: holder balances (`asset_holder`, re-run from `token_transfer`).
- **INVERSE_FLIP** — the affected row flips a status field back to its pre-confirmation value.
  Ten compensators today, across `blockchain` (submitted transactions), `asset` (deployment
  confirmations), `orgidentity` (org/member/permission/trusted-issuer confirmations), `erc3643`
  (identity-registry registration/removal, ONCHAINID deployment, KYC/AML claim issuance),
  `marketplace` (dApp version publication).
- **IRREVERSIBLE** — the effect already crossed the system boundary and cannot be undone (e.g. a
  §19 eWpG register statement, once emailed, cannot be un-sent). Kept empty by construction: every
  operation that emits a document outward has a policy hard floor at `FINALIZED` (see below), so by
  the time such a document goes out, the source block can no longer retract deep enough to need an
  IRREVERSIBLE compensator for it.

`CompensationDispatcher` claims a row atomically (so two instances can never double-compensate),
runs the registered compensator, and records the outcome. A compensator failing after retries, or
finding no registered compensator at all for its `effect_type`, or reporting the effect as
genuinely irreversible, all escalate the same way: the row moves to `COMPENSATION_FAILED` or
`IRREVERSIBLE_ESCALATED`, and — this is the part that actually protects the register — **every
`FinalityGate`-gated operation on the affected asset is blocked** until an operator reviews and
acknowledges it (see [Unresolved compensation](#unresolved-compensation) below). A retry job
re-attempts `COMPENSATION_FAILED` rows on its own schedule; `IRREVERSIBLE_ESCALATED` rows are never
retried automatically.

## The gate (`FinalityGate`)

`FinalityGate.check(operation, assetId, tokenStandard, currentLevel)` returns one of:

- **Allowed** — `currentLevel` already satisfies the policy-resolved required level for this
  operation.
- **Blocked, reason `BELOW_REQUIRED`** — not yet, but will be once the source block progresses;
  the response carries the required and current levels so a UI can render "available once
  finalized" rather than a bare rejection.
- **Blocked, reason `ORPHANED`** — the underlying on-chain event this operation depends on no
  longer exists on the canonical chain. Not "still confirming" — actually gone.
- **Blocked, reason `UNRESOLVED_COMPENSATION`** — the affected asset has at least one
  unacknowledged `COMPENSATION_FAILED`/`IRREVERSIBLE_ESCALATED` effect. This blocks *every*
  operation on the asset, regardless of the specific operation's own required level — the freeze
  described above.

The gate is called inside the same transaction as the write it's guarding, immediately before that
write — never at controller entry, never as a decorator — so the check and the write always
observe the same snapshot. A blocked call returns HTTP 409 (`FinalityErrorResponse`), not 403: "not
possible *yet*" is what's actually true, and 403 would corrupt authorization-failure dashboards
that are watching for real access-denial, not a time-bounded wait.

### What's gated today

Sixteen `GatedOperation` values exist, derived from a survey of real mutating call sites across
eight modules. As of this writing, `FinalityGate` is actually *wired* (called) at the five gate
sites in `registerstatement` (§19 statement issuance) and `registertransfer` (extract export,
transfer completion, portfolio migration completion, inspection fulfilment) — the two modules
where an irreversible document leaving the system was the risk the plan specifically wanted closed
first. The remaining modules (`corporateactions`, `trading`, `asset.ASSET_ISSUE`, `marketplace`,
`erc3643`) were investigated and found not to need a *read-side* gate call today — Canton is
final-on-write, and the states those flows would read are already never written except at
`FINALIZED` by an existing poller, so a gate call there would be a permanent no-op. Their
`GatedOperation` enum values exist so the policy that would drive them is configurable ahead of
time, and to document the intended future call site.

## The policy model

`FinalityPolicyProfile` — **FAST** (non-hard-floor operations resolve at `SAFE`), **BALANCED**
(everything resolves at `FINALIZED` — the global default, chosen so adopting the policy model
changed nothing on day one), **CONSERVATIVE** (identical to BALANCED today; a nameable placeholder
for a future stricter tier). Five operations have a non-lowerable hard floor at `FINALIZED`
regardless of profile — every one of them emits a document leaving the system:
`REGISTER_STATEMENT_ISSUE`, `REGISTER_EXTRACT_EXPORT`, `REGISTER_TRANSFER_COMPLETE`,
`TAX_CERTIFICATE_ISSUE`, `REGULATORY_EXPORT`.

Resolution order, most specific wins: **asset override** → **token-standard profile** → **global
profile** → compiled-in default (`BALANCED`, hard floors still apply). Overrides are the audited
escape hatch — each one requires a written reason and is `REGISTRY_ADMIN` + step-up protected.

### Operator UI: Finality Policy

*Compliance → Finality Policy* — set the global profile, set a profile per token standard, or look
up and manage per-asset overrides (search by asset ID, add/remove overrides with a mandatory
reason). Every write is step-up protected; reads are open to `REGISTRY_ADMIN`/`AUDIT`/
`COMPLIANCE_OFFICER`.

### Unresolved compensation

*Compliance → Unresolved Compensation* — every `chain_effect` row currently `COMPENSATION_FAILED`
or `IRREVERSIBLE_ESCALATED`, newest first. Two actions per row, both step-up protected:

- **Retry** — re-runs the registered compensator now, instead of waiting for the automatic retry
  job's next tick. Safe to click repeatedly; a row that's no longer claimable (already resolved,
  or another retry already in flight) is a no-op.
- **Acknowledge** — records that an operator has reviewed the row and accepts proceeding despite
  it, with a mandatory reason. This does **not** change the row's status (the compensation
  genuinely failed, or genuinely can't be undone — that stays true) — it only lifts the gate's
  freeze on the affected asset. Every acknowledgement is audited
  (`CHAIN_EFFECT_ACKNOWLEDGED`, carrying the reason).

## Two vocabularies, one model

The technical vocabulary above (`PROVISIONAL`/`SAFE`/`FINALIZED`/`ORPHANED`) is resolved
server-side by the caller's role — the frontend never chooses which vocabulary to render, so it
can't get this wrong. Operator staff (`REGISTRY_ADMIN`/`AUDIT`/`COMPLIANCE_OFFICER`/
`RELATIONSHIP_MANAGER`) see the technical names as-is; customer-side roles (issuer, investor,
trader, ...) see plain language instead — "Being confirmed" / "Confirmed" / "Settled — final" /
"Did not go through". `indexer.web.TokenTransferMapper` is the first real consumer of this split,
populating `TokenTransferResponse.finalityLabel` alongside the always-technical
`finalityStatus`; the customer app's Investment Detail page renders the transfer history table
using `finalityLabel`.

## Monitoring

- `DeepReorgDetected` (critical) / `ReorgCrossedSafeThreshold` (warning) — a reorg reached an
  already-FINALIZED or already-SAFE block, respectively. See
  [Indexer Resilience](resilience.md#chain-reorg-detection-and-recovery).
- `CompensationEscalated` (critical) — a `chain_effect` row failed to compensate, found no
  registered compensator, or was found irreversible. This is the alert that makes the
  Unresolved-Compensation freeze visible *before* a trader or issuer notices their actions are
  silently blocked, rather than after.

All three are real, evaluated rules in `monitoring/alerts/registerwerk.yml`, not illustrative
examples.
