---
id: lifecycle
title: Token Lifecycle
sidebar_label: Token Lifecycle
---

# Token Lifecycle

Every issuance in the eWpG Registry moves through a defined set of states from creation to final settlement. Understanding the lifecycle helps you plan your issuance workflow.

## State diagram

```
DRAFT
  |
  | (submit for approval)
  v
PENDING_APPROVAL
  |           \
  | (approved) \ (rejected)
  v             v
APPROVED      REJECTED
  |
  | (deploy to chain)
  v
ISSUED
  |         \         \
  | (suspend) \ (redeem) \ (operator: freeze)
  v            v          v
SUSPENDED   REDEEMED   FROZEN
  |
  | (unsuspend)
  v
ISSUED
```

## State descriptions

### DRAFT

The issuance has been created but not yet submitted for review. You can freely edit all fields. No on-chain action has occurred.

**Available actions**: Edit all fields, Submit for approval, Delete

### PENDING_APPROVAL

The issuance has been submitted and is under review by the registry operator. You cannot edit the issuance at this stage.

**Typical duration**: 1–3 business days

**Available actions**: Withdraw submission (returns to DRAFT)

### APPROVED

The registry operator has reviewed and approved the issuance. All configured parameters are locked. You can now deploy the token to the blockchain.

**Available actions**: Deploy to blockchain

### REJECTED

The registry operator has rejected the submission, with a reason. You can review the rejection reason and either correct the issues and resubmit, or delete the issuance.

**Available actions**: View rejection reason, Edit and resubmit, Delete

### ISSUED

The token contract has been successfully deployed to the blockchain. This is the active operational state. Investors can hold and transfer tokens (subject to compliance rules).

**Available actions**: Manage investors, View transfers, Suspend, Redeem

### SUSPENDED

The issuer has temporarily suspended the token. All transfers are blocked on-chain. This may be used during corporate actions (e.g., a record date for coupon payments) or in response to a regulatory hold.

:::warning
Suspension triggers an on-chain `pause()` call on the token contract. All transfers — including those that would otherwise pass compliance — are blocked until the token is unsuspended.
:::

**Available actions**: Unsuspend (returns to ISSUED)

### REDEEMED

The security has reached maturity or has been fully repurchased. All tokens have been burned and the contract is permanently closed. This state is irreversible.

**Available actions**: View historical data, Export final report

### FROZEN (operator-initiated)

The registry operator can freeze a token independently of the issuer, for example following a regulatory order or AML concern. A frozen token behaves like SUSPENDED but can only be unfrozen by the operator.

**Available actions (issuer)**: Contact registry operator

## Transition permissions

| Transition | Who can trigger |
|------------|----------------|
| DRAFT → PENDING_APPROVAL | Issuer |
| PENDING_APPROVAL → APPROVED | Registry operator |
| PENDING_APPROVAL → REJECTED | Registry operator |
| APPROVED → ISSUED | Issuer (initiates deployment) |
| ISSUED → SUSPENDED | Issuer |
| SUSPENDED → ISSUED | Issuer |
| ISSUED → REDEEMED | Issuer (irreversible) |
| Any → FROZEN | Registry operator only |
| FROZEN → previous state | Registry operator only |

## Notifications

You receive email notifications on every state transition. The activity feed on the [Dashboard](../dashboard) also shows all lifecycle events for your issuances.
