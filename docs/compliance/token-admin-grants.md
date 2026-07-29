---
title: Token Admin Grants — Delegatable Forced-Action Authority
description: ASSET_TOKEN_ADMIN — the delegatable permission gating forcedTransfer/forcedApprove/forceBurn beyond REGISTRY_ADMIN.
---

# Token Admin Grants — Delegatable Forced-Action Authority

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page records intended control mappings. It is not evidence that a delegation is legally
    valid, within an operator's authorisation, or sufficient for a correction, cancellation,
    freeze, burn, or forced transfer. Capacity, instruction evidence, segregation of duties, and
    instrument/jurisdiction rules require external review.

Registerwerk's registry-compulsory token operations — **forcedTransfer**, **forcedApprove**,
and **forceBurn** — let the registry move, re-approve, or destroy a holder's tokens without
their consent. These are the sharpest tools in the platform: a wrongful call moves real
value to an attacker-chosen address or destroys it outright. Until now they were reachable
only by `REGISTRY_ADMIN` (plus, for `forcedTransfer`/`forcedApprove`, any issuer acting on
their own asset purely by virtue of owning it).

**`ASSET_TOKEN_ADMIN`** replaces the issuer-ownership shortcut with an explicit, operator-granted
permission. By default **nobody has it — not even an asset's own issuer.** An operator must
deliberately delegate it to a named customer entity (issuer or investor), and only after
validating that entity's wallet is a genuine, whitelisted (and, for ERC-3643 assets,
ONCHAINID-verified) participant.

Note what does **not** change: the actual on-chain transaction is still signed by the
registry's own operator wallet, exactly as before. `ASSET_TOKEN_ADMIN` is purely an
**API-level authorization gate** — it decides who may *ask* the registry to perform a forced
action, not who *executes* it on-chain.

---

## What it gates

| Action | Operator path | Customer path |
|---|---|---|
| `forcedTransfer` / `forcedTransferSingle` | `TokenAdminController` | `IssuerTokenController` |
| `forcedApprove` | `TokenAdminController` | `IssuerTokenController` |
| `forceBurn` / `forceBurnSingle` | `TokenAdminController` | — (operator only) |
| ERC-3643 equivalents (incl. batch) | `Erc3643Controller` | `Erc3643Controller` |
| Canton `force-transfer-canton` / `burn-holding` | `TokenAdminController` | — (operator only) |

Every endpoint above now requires `hasRole('REGISTRY_ADMIN')` **or** an active
`ASSET_TOKEN_ADMIN` grant for the caller's entity on that specific asset (see
`AssetAccessChecker.canForceAdmin`). Everything else — pause, freeze, whitelist,
mint, burn (the non-forced kind) — is unaffected.

---

## eWpG §24 / §26 as the delegation basis (Germany)

The forced actions map to concrete eWpG provisions: `forcedTransfer` to **§24 Berichtigung**
(registry correction under a BaFin/court order), `forceBurn` to **§26 Einziehung** (compulsory
cancellation). Both provisions describe the *registry keeper's* authority to correct or cancel
an entry — they do not themselves contemplate delegating that authority to a customer. The
position this feature takes is that the registry keeper (the operator) remains legally
responsible for every forced action regardless of who initiated the API call; `ASSET_TOKEN_ADMIN`
is an **operational delegation of initiation**, not a delegation of legal authority — the
operator's own dual-control step-up (see below) is what actually authorizes execution, on
every single call, whether the initiator is REGISTRY_ADMIN or a granted customer.

**Other jurisdictions:** FR, LU, and LI have no directly analogous "delegate the initiation of
a compulsory register correction" concept documented in this codebase yet. Treat delegation to
a customer entity under those jurisdictions' local securities/DLT regimes as **unreviewed** —
get local-counsel confirmation before granting `ASSET_TOKEN_ADMIN` to a non-DE entity in
production, consistent with the disclaimer convention used elsewhere in this directory
(e.g. [Sperrvermerk](sperrvermerk.md)) and the [jurisdiction overview](../legal/index.md).

---

## Grant model

Two variants, both created/revoked exclusively by `REGISTRY_ADMIN` with
`@RequiresStepUp(requireSecondApprover = true)` (the same TOTP + 4-eyes flow used for the
forced actions themselves):

- **Asset-scoped** (`POST /api/v1/assets/{assetId}/token-admin-grants`) — the common case:
  one asset, one grantee entity.
- **Entity-wide** (`POST /api/v1/entities/{entityId}/token-admin-grants`) — applies across
  every asset that entity is issuer/holder on, present and future. A materially larger trust
  delegation; reserve for a trusted repeat issuer, not the default.

### Eligibility, validated once at grant time

| Grantee | Wallet check |
|---|---|
| Asset's own issuer (asset-scoped) | Wallet bound to the entity's org identity (`orgidentity.PermissionGate.isWalletBoundToEntity`) |
| A holder/investor of the asset (asset-scoped) | `AssetHolder.whitelisted = true` for that wallet on that asset, **plus** T-REX `IdentityRegistry.isVerified` if the asset is ERC-3643/CONF_ERC3643 |
| Entity-wide | Wallet bound to the entity's org identity (no single asset to check whitelisting against) |

The check that passed is recorded on the grant (`eligibilityBasis`) for audit — it is
**not** re-verified live on every subsequent forced-action call; only the grant's own
`ACTIVE`/non-expired status is. If a wallet is later un-whitelisted or blocked, the operator
must separately revoke the grant.

### Lifecycle

Mirrors [Sperrvermerk](sperrvermerk.md)'s `HolderBlock`: `ACTIVE → REVOKED` (manual, step-up +
4-eyes) or `ACTIVE → EXPIRED` (nightly `@Scheduled` job past `expiresAt`, if one was set).

---

## Operator UI

- **Asset-scoped** — "Token Admin Grants" tab on the asset detail page: list active grants,
  grant new (entity, wallet, optional chain config, legal basis, optional expiry), revoke.
- **Entity-wide** — `/compliance/token-admin-grants`: look up an entity, manage its
  entity-wide grants. Deliberately a separate page from the unrelated orgidentity ecosystem
  Permissions screen (`/permissions`) — that one governs dApp-marketplace org permissions and
  has no asset dimension at all.

---

## Audit trail

Every grant and revocation publishes `ASSET_TOKEN_ADMIN_GRANTED` / `ASSET_TOKEN_ADMIN_REVOKED`
audit events (`asset.events.AssetTokenAdminGrantedEvent` / `...RevokedEvent`), captured
automatically via the [audit hash chain](../platform/audit-log.md) — actor, entity, asset
(or "entity-wide"), wallet, legal basis, and eligibility basis are all recorded.
