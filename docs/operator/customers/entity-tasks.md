---
title: Entity tasks
description: Operator work items raised by customer lifecycle events, and how to work through them.
---

# Entity tasks

Some customer changes leave work that a person must finish: re-checking KYC after a reinstatement, re-establishing on-chain identity, reviewing a Sperrvermerk whose expiry date has passed, or handing over positions of a terminated company. Registerwerk raises these as **entity tasks**. A task is opened once per entity, kind and reference while it is open, stays visible until an operator marks it done, and records who completed it and why.

## Where to find them

| Endpoint | Who | Purpose |
|---|---|---|
| `GET /api/v1/entity-tasks` | `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER`, `AUDIT` | All open tasks |
| `GET /api/v1/entities/{id}/tasks` | `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER`, `AUDIT` | Tasks of one entity |
| `POST /api/v1/entity-tasks/{taskId}/done` | `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER` | Mark a task done (optional note) |

The operator portal shows the tasks on the customer record.

## Kinds you will see

| Kind | Raised when | What to do |
|---|---|---|
| `KYC_REVIEW_REQUIRED` | Entity data changed in a way that needs a fresh KYC review | Review and approve (or reject) KYC |
| `REINSTATEMENT_KYC_REQUIRED`, `REINSTATEMENT_USERS_REVIEW` | A closed or dissolved entity was put into `PENDING_REACTIVATION` ([reinstatement](offboarding.md#reinstating-a-closed-or-dissolved-customer), step-up and a second approver) | Complete KYC again; review which users may sign in again (they stay disabled) |
| `CHAIN_REINSTATEMENT_REQUIRED` | The entity became active again after KYC | Re-establish on-chain identity claims and organisation binding as an explicit four-eyes step; nothing is re-issued automatically |
| `SPERRVERMERK_EXPIRY_REVIEW` | A block's expiry date passed; the block keeps blocking | Decide and lift through the normal lift (step-up and a second approver) or keep it |
| `SPERRVERMERK_FREEZE_NOT_PROPAGATED` | An on-chain freeze for a block failed or has no automated path | Freeze manually or fix the cause; the register-level block stays in force. See [Sperrvermerk](../../compliance/sperrvermerk.md) |
| `SPERRVERMERK_HOLDING` | A terminated or merged customer still holds blocked units | Resolve with the court or authority reference before the holding is moved |
| `REPO_PARTY_INELIGIBLE_ENFORCEMENT` | A lender used a protective repo action (margin call, default notice, default declaration) while its eligibility was lapsed | Review the party's KYC, screening and blocks; the trade is flagged `PARTY_FLAGGED` |

Termination follow-ups (issued securities, open trades and positions) are tasks of the same kind of record and stay open after the entity is `CLOSED`.

## Monitoring

Two gauges make open work alert-able: `registerwerk_offboarding_open_tasks` (termination and merge follow-ups) and `registerwerk_entity_review_open_tasks` (re-KYC and chain-reinstatement requests). Alert on values that stay above zero longer than your process allows.
