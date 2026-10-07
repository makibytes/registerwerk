---
title: Forward pricing and the dealing cut-off
description: Operator guide for ERC-7540 fund vaults — configuring the dealing cut-off, how requests get a dealing point, what the portal shows, and what production mode refuses.
---

# Forward pricing and the dealing cut-off

A fund must not let an investor deal at a price that is already known. An ERC-7540 vault therefore carries a **dealing cut-off**. A request placed before the cut-off settles at a NAV struck after that cut-off; a request placed after it waits for the *next* dealing point. This page is the operator's side of it. The contract behaviour is described in [ERC-7540](../../token-standards/erc7540.md#forward-pricing-and-dealing-cut-off).

!!! note "What the control proves"
    The contract proves that the NAV used for a fulfilment was struck after the request's dealing point. It does not prove that the NAV is a correct valuation as of that point; striking it is still an operator input.

## Concepts

| Term | Meaning |
|---|---|
| Cut-off | A time of day in UTC (`cutoffSecondsOfDay`, 0 to 86399) |
| Dealing period | The spacing of dealing points (`periodSeconds`; one day by default; 3,600 seconds to 31 days accepted) |
| Dealing point | The first cut-off boundary **strictly after** the moment the request was placed, recorded with the request |
| NAV strike | `POST /api/v1/deployments/{depId}/nav-strike`; the contract remembers when the latest one happened |

A fulfilment is accepted only if the latest NAV was struck at or after the request's dealing point. Otherwise the transaction would revert with `NavNotStruckAfterDealingPoint`; the portal prevents it instead (below). A later change of the cut-off does not move the dealing points of requests that already exist. Requests placed before a cut-off was configured keep the old behaviour.

## Configuration

Every new ERC-7540 vault is configured automatically once its deployment is confirmed, using the registry signer:

| Variable | Default | Meaning |
|---|---|---|
| `VAULT_DEALING_CUTOFF_UTC` | `17:00` | Cut-off as `HH:mm` UTC. Blank or `off` disables the automatic step |
| `VAULT_DEALING_PERIOD_SECONDS` | `86400` | Dealing period; validated at start-up (3,600 seconds to 31 days) |

The automatic step is best-effort: it never undoes the deployment confirmation, and a failure is logged as an error with the fix. A vault that was not configured (signer down, or the automatic step switched off) has no cut-off until you set it.

## Setting or changing the cut-off by hand

Operator portal: asset detail, ERC-7540 **vault requests**, then *Set dealing cut-off* (or *Change dealing cut-off*). The dialog asks for the cut-off time and the period, then for step-up and a second approver. Over the API:

```
POST /api/v1/deployments/{depId}/dealing-cutoff
Idempotency-Key: <uuid>
{ "cutoffSecondsOfDay": 61200, "periodSeconds": 86400 }
```

`REGISTRY_ADMIN` only; step-up and a **second approver** (reason `VAULT_DEALING_CUTOFF`, see the [approval queue](../security/approval-queue.md)); the approval is bound to the body. The request is simulated first, then sent through the outbox as a registry transaction, and audited (`TOKEN_ADMIN_SET_DEALING_CUTOFF`, with the cut-off and period). ERC-7540 vaults only.

## What the portal shows

- **Dealing banner** above the requests: cut-off, period, the next dealing point and when the last NAV was struck on-chain. A warning variant appears when no cut-off is configured or when it cannot be read.
- **Dealing point (UTC)** column per request.
- **Waiting for NAV strike** chip and a disabled *Fulfil* button (also in bulk selection) with a tooltip, until a NAV has been struck after the request's dealing point. *Cancel* and *Force cancel* are unaffected.
- `GET /api/v1/deployments/{depId}/vault-state` carries a `dealing` object (`configured`, `cutoffSecondsOfDay`, `periodSeconds`, `nextDealingPoint`, `navStruckAt`); each row of `GET .../vault-requests` has `dealingPoint` and `awaitingNavStrike`.

A fulfilment that fails on-chain because no NAV was struck after the dealing point is not a failure: it is cleared for resubmission and logged at INFO as waiting for the next NAV strike.

## Daily routine

1. After the cut-off, strike the NAV with `nav-strike`. Strike it **after** the cut-off, or the requests of that dealing day stay in *Waiting for NAV strike*.
2. Fulfil the requests whose chip has gone; the minimum settlement delay of the vault still applies.
3. Requests placed after the cut-off belong to the next dealing day.

## Production mode

- Subscription orders for an asset are refused (`409`) while a confirmed ERC-7540 vault of the asset has no dealing cut-off, or while it cannot be read (fail closed).
- At start-up, an `ERROR` line lists such vaults.
- Neither check blocks start-up, and neither applies in demo mode, where the demo vaults stay unconfigured and settle as before.

## Limits

- `requestDeposit` and `requestRedeem` are signed by the investor's wallet, so the backend cannot stop them. Production mode refuses subscription *orders* in Registerwerk and warns; an investor calling the contract directly still creates a request, which then simply waits for its dealing point.
- Issuing or reactivating an asset is not gated on the cut-off, because the asset is issued before its vault exists.
- The dealing point of a settled request is not stored by the backend, so settled rows show no dealing point.
