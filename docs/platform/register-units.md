---
title: Register units
description: The register counts whole units - how bond and fund tokens are deployed (decimals = 0), what the registry refuses on any other token, and what to do about it.
---

# Register units { #register-units }

**The register counts whole units. One token is one unit of the security.**

The register's amounts - a holder's nominal amount and every indexed transfer - are the token's **raw base
units**; the indexer writes them without scaling. Coupon and redemption maths (`amountPerUnit x nominal`), the
primary-market mint (the allocated amount is sent to the token as it is) and secondary trading (quantity and price
per unit) all read those amounts as whole units. On a token with 18 decimals every one of them would be wrong by a
factor of 10^18, so the registry does not scale: it deploys bond and fund tokens with `decimals = 0` and refuses to
run any of those flows on a token that does not count in whole units.

## What is deployed { #what-is-deployed }

| Standard | Decimals of a new deployment |
|---|---|
| ERC-20 (`EwpgERC20`), ERC-3643 (T-REX), ERC-721, ERC-1155, ERC-3525 (EVM and Starknet), SPL / Token-2022, Daml bonds | **0** |
| Starknet ERC-20 (Cairo, fixed at 18), Stellar assets (fixed at 7) | as the contract defines - **refused** by the flows below |
| ERC-4626 / ERC-7540 vault shares (follow the underlying asset), confidential tokens, Canton tokens | not under the registry's control - recorded as *unknown*, **refused** |

The decimals are recorded on the deployment (`asset_deployment.token_decimals`). Deployments created before this
rule keep what the earlier code deployed (for example 18 for ERC-20 and ERC-3643), so they are refused too.

## What is refused { #what-is-refused }

Any asset with a live (pending or confirmed) deployment whose decimals are not exactly 0 - including unknown - is
refused, fail closed, with a `409` that names the deployment and its decimals:

- **Corporate actions** (coupons, redemption, dividends, splits, calls): the record-date snapshot is not taken; the
  action is parked as `SNAPSHOT_BLOCKED` with the reason, audited and alerted, and retried every day.
- **Subscriptions**: allocation and settlement (the mint).
- **Trading**: creating a listing, buying and settling a trade.
- **Redemption**: starting the redemption of the asset (the burn amounts are the register's raw base units).

An asset without any deployment (an off-chain register) has nothing to scale and is not affected.

!!! warning "How to fix a refused asset"
    The token cannot be changed in place. Deploy the asset again with a whole-unit token (`decimals = 0`) and move
    the register to it. Nothing is paid, minted or traded in the meantime.
