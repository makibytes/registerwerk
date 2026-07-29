# Boardroom Governance

Reference marketplace dApp for the **Registerwerk ONCHAINID permission-management
framework**. Board-level governance and voting for a tokenised entity — the dApp
proposes, votes on, and tallies board resolutions, with every action gated entirely
through org identity, operator permission grants, org-admin role delegation, and
ONCHAINID claim checks.

Nothing here is chain-specific about the *governed* entity: `governanceToken` is stored
purely as an informational pointer back to whatever cap-table token the board oversees
(e.g. an ERC-3643 equity token). Governance itself is one-member-one-vote, not
balance-weighted, so the contract never reads that token.

| | |
|---|---|
| Slug | `boardroom` |
| Category | `governance` |
| Contract | [`contracts/src/examples/BoardroomGovernance.sol`](../../../contracts/src/examples/BoardroomGovernance.sol) |
| Tests | [`contracts/test/examples/BoardroomGovernance.t.sol`](../../../contracts/test/examples/BoardroomGovernance.t.sol) |
| Manifest | [`backend/src/main/resources/demo/dapps/boardroom.manifest.json`](../../../backend/src/main/resources/demo/dapps/boardroom.manifest.json) |
| Deploy script | [`contracts/script/DeployExampleDapps.s.sol`](../../../contracts/script/DeployExampleDapps.s.sol) |

## What it showcases

The contract inherits [`RegisterwerkGated`](../../../contracts/src/ecosystem/RegisterwerkGated.sol)
and gates every action with a different combination of the three ecosystem modifiers, so
it reads as living documentation of the whole permission framework:

| Function | `requiresPermission` | `requiresClaim` | Notes |
|---|---|---|---|
| `propose(title, descriptionHash)` | `boardroom.propose` | Accreditation (topic 3) | Opening a resolution is restricted to accredited proposers |
| `vote(proposalId, support)` | `boardroom.vote` | KYC (topic 1) | Any KYC'd member holding the permission may vote once per proposal |
| `tally(proposalId)` | `boardroom.tally` | — | **The role-restriction showcase** — see below |
| `checkIn()` | — | — | `requiresActiveMember` in isolation, e.g. for a quorum roll call |

### The role-restriction / delegation story (`tally`)

`tally` is deliberately gated by nothing but a permission, because the point of this
function is to demonstrate **org-admin delegation**:

1. The operator grants the org `boardroom.tally` (`PermissionRegistry.grantToOrg`) —
   at this point *any* member of the org holding the permission may call `tally`.
2. The org admin (an ERC-734 MANAGEMENT key holder on the org's ONCHAINID) calls
   `PermissionRegistry.setRoleRestricted(org, TALLY, true)`. From this point on, org
   membership alone is no longer enough.
3. The org admin delegates the permission to a specific member role, e.g.
   `PermissionRegistry.grantToRole(org, keccak256("BOARD_SECRETARY"), TALLY)`.
4. Only wallets carrying the `BOARD_SECRETARY` role may now call `tally` — even though
   the org itself still holds the permission.

This demonstrates that **org admins, not just the Registerwerk operator, control who
inside their organization may exercise a permission the operator granted to the org as
a whole.** See `test_tally_respectsRoleRestriction` in the test file for the full
grant → restrict → deny → delegate → succeed sequence.

## Permissions declared in the manifest

| Code | Rationale |
|---|---|
| `boardroom.propose` | Open a new board resolution for a vote |
| `boardroom.vote` | Cast a vote on an open board resolution |
| `boardroom.tally` | Close voting and record the resolution outcome |

Claim topics: `1` (KYC), `3` (Accreditation).

## Publishing this dApp

This repository ships the manifest and Solidity source only — no container images are
published (the `images[]` digests in the manifest are illustrative placeholders). To
publish a real instance:

1. Build your own backend/frontend images and pin them by OCI digest.
2. Update `images[].ref` in the manifest with your real digests.
3. Follow the publication workflow in
   [`docs/platform/dapp-development.md`](../../../docs/platform/dapp-development.md):
   Customer Portal → My dApps → paste the manifest → sign the hash with a bound org
   wallet → submit → operator review (step-up + 4-eyes) → onchain anchoring.

The demo environment (`SEED_DEMO_DATA=true`) seeds this dApp as an already-`PUBLISHED`
marketplace listing so it's visible end to end without going through the publish wizard.
