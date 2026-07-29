# Building dApps for the Registerwerk Ecosystem

Registerwerk provides an **onchain identity and permission framework** that financial
institutions build tokenization dApps on, plus a **marketplace** where those dApps are
reviewed, anchored onchain and offered to other participants. This guide covers the
developer workflow end to end.

## The building blocks

| Contract | Purpose |
|---|---|
| `OrgRegistry` | Binds member wallets to organizations (an org = its ONCHAINID address). Every wallet belongs to at most one org per chain. |
| `PermissionRegistry` | Operator grants permissions to orgs; org admins delegate them to member roles and can mark them role-restricted. |
| `EcosystemTrustedIssuersRegistry` | Claim issuers trusted per ONCHAINID claim topic (1 = KYC, 2 = AML, 3 = Accreditation). |
| `DappRegistry` | Anchors approved marketplace manifests (keccak256) and optional instance attestations. |
| `PermissionOracle` | **The one address your dApp stores.** Composes all of the above behind a stable query facade. |

Your dApp never talks to the registries directly — only to the `PermissionOracle`
(`IPermissionOracle`), which the operator can repoint to upgraded registries without
breaking deployed dApps.

## Writing a gated contract

Inherit `RegisterwerkGated` (in `contracts/src/ecosystem/RegisterwerkGated.sol`) and pass
the oracle address in your constructor:

```solidity
import "@registerwerk/ecosystem/RegisterwerkGated.sol";

contract LoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
        // caller's wallet belongs to an active org holding "loandesk.open",
        // and the org's ONCHAINID carries a valid KYC claim
    }
}
```

Available modifiers:

- `requiresPermission(bytes32 permission)` — org-level grant (plus role delegation when
  the org marked the permission role-restricted).
- `requiresClaim(uint256 topic)` — a valid claim of the topic on the caller org's
  ONCHAINID, signed by an ecosystem-trusted issuer.
- `requiresActiveMember` — the caller's wallet is bound to a non-suspended org.

Permission ids are `keccak256("<your-slug>.<action>")`. Your marketplace slug is your
namespace — manifests declaring permissions outside `<slug>.*` are rejected unless the
code already exists as a platform permission.

A minimal runnable example lives in `contracts/test/ecosystem/SampleGatedDapp.t.sol`. For
two fully packaged, marketplace-ready reference dApps — including a real ERC-3643 (T-REX)
integration — see [Reference example dApps](#reference-example-dapps) below.

## The manifest

The marketplace stores **metadata only**: your containers stay in your own OCI registry,
pinned by digest. The manifest (JSON, schema:
`backend/src/main/resources/schemas/dapp-manifest.schema.json`) describes:

```json
{
  "slug": "loandesk",
  "name": "Loan Desk",
  "version": "1.0.0",
  "description": "Institutional loan origination on Registerwerk rails.",
  "category": "lending",
  "contracts": [
    { "name": "LoanDesk", "abiSha256": "<sha256 of the ABI json>" }
  ],
  "requiredPermissions": [
    { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
  ],
  "requiredClaimTopics": [1],
  "images": [
    { "name": "backend",  "role": "backend",
      "ref": "registry.bank.example/loandesk/backend@sha256:…" },
    { "name": "frontend", "role": "frontend",
      "ref": "registry.bank.example/loandesk/frontend@sha256:…" }
  ],
  "deployment": { "composeUrl": "https://…/docker-compose.yml", "composeSha256": "…" },
  "docsUrl": "https://docs.bank.example/loandesk",
  "contact": "dapps@bank.example",
  "license": "commercial",
  "pricingNote": "Contact publisher"
}
```

Rules enforced at validation:

- **Digest pinning is mandatory** — `images[].ref` must match `…@sha256:<64 hex>`;
  tag-only references are rejected.
- The manifest `slug` must equal the listing slug.
- `requiredPermissions[].code` must be in your namespace or an existing platform
  permission.

## Declaring payment methods

Issuing an asset token is only half the story — most dApps also need a cash leg
(subscription payments, coupon/dividend payouts, redemptions). Rather than every
publisher building and auditing their own payment rails, the registry operator curates a
catalog of ready-made ones — stablecoins with operator-entered MiCAR-related disclosure and
attestation fields, the Pontes instant-payment API, ERC-7573-style
delivery-versus-payment settlement, and classic off-chain SEPA — and your manifest can
simply reference them by code:

```json
"paymentMethods": [
  { "rail": "aueur", "note": "Primary-market subscription plus coupon and redemption payouts" },
  { "rail": "usdc" },
  { "rail": "erc7573-dvp", "note": "Same-transaction DvP; exact-leg, finality, and legal-register checks remain external" }
]
```

Fetch the current catalog of enabled rails at `GET /api/v1/payment-rails/catalog` (also
surfaced in the publish wizard's "Payment methods" step) and copy a `code`. Each rail
entry is validated at submission **and again at approval** — a rail the operator disabled
in between blocks the version from being approved until the manifest is updated.

This is advisory, not a whitelist: your dApp can always implement its own payment logic.
Declare it as a `custom` entry instead of a `rail` reference:

```json
"paymentMethods": [
  { "custom": { "name": "Own SEPA collection account", "description": "Publisher-run SEPA rail, settled off-chain", "currency": "EUR" } }
]
```

Custom entries pass validation unconditionally but are flagged prominently to the
operator during review (and to investors in the catalog detail page) — the market can see
exactly what left the "registry-provided rails" convenience path.

For dApps that themselves want to offer atomic delivery-versus-payment (e.g. a
secondary-market desk), the operator's `DvpSettlement` contract
(`contracts/src/settlement/DvpSettlement.sol`) implements ERC-7573-style same-chain DvP:
one party locks the asset or payment leg in escrow, the counterparty settles both legs
atomically, or the trade expires and the locker reclaims it. See its NatSpec for the
ERC-3643-escrow caveat (T-REX tokens require the settlement contract's ONCHAINID to be
verified in the identity registry before they can be escrowed — locking the payment leg
instead sidesteps this for security tokens).

## Publication workflow

1. **Prerequisite:** your company is registered as an onchain organization
   (operator-side) and your publishing wallet is bound to it
   (Customer Portal → Company Admin → Organization).
2. Customer Portal → **My dApps** → *New dApp* (slug + anchor chain).
3. Paste the manifest into the publish wizard → server-side validation returns errors
   and `manifestHash = keccak256(manifest_raw_bytes)` as a 0x-prefixed hex string.
4. **Sign** with a bound org wallet: `personal_sign` (EIP-191) is called with the
   **0x-hex hash *string*** as the message — not the raw 32 hash bytes. This is a
   deliberate choice so any wallet UI shows the human-readable hex string being signed;
   verifiers must recover against the same string (see [Integrity
   verification](#integrity-verification-consumers) below).
5. **Submit** — the registry operator reviews with step-up + 4-eyes.
6. On approval the backend calls `DappRegistry.registerDapp(keccak256(slug), publisherOrg,
   manifestHash, …)`; once the transaction confirms, the listing is live in the catalog.

Version updates repeat steps 3–6; the new hash is anchored with
`DappRegistry.updateManifest` and the previous version is marked superseded.

## Integrity verification (consumers)

Everything needed to verify a listing independently is in the catalog detail:

```bash
# 1. The manifest hash must match the onchain anchor
MANIFEST_HASH=$(cast keccak "$(cat manifest.json)")
cast call $DAPP_REGISTRY "getDapp(bytes32)" $(cast keccak "loandesk") --rpc-url $RPC

# 2. The signature must recover to the declared publisher wallet, which must be a bound
#    member wallet of the publisher org. Recovery is over the hex *string* $MANIFEST_HASH
#    (EIP-191 personal_sign), not the raw 32 hash bytes:
cast wallet verify --address $PUBLISHER_WALLET "$MANIFEST_HASH" $SIGNATURE

# 3. Pull images only by the digests listed in the manifest
```

## Instance attestation (optional)

Deployed contract instances of your dApp can be attested in the `DappRegistry`
(`attestInstance`) by your org admin. Other contracts may then require
`oracle.isApprovedInstance(caller)` — an opt-in composition layer; it is deliberately
not folded into `hasPermission`, since self-hosted deployments control their own callers.

## External DeFi composability

`PermissionOracle` and `DvpSettlement` are both freely, permissionlessly callable by **any**
external contract — not just Registerwerk-marketplace dApps. There is no `onlyRole` or
allowlist on either:

- **`PermissionOracle`** — an external DeFi protocol (its own pool, vault, or lending market)
  can call `hasPermission`/`hasClaimTopic`/`isActiveMember` on any wallet address to gate its
  *own* logic to Registerwerk-verified investors, without ever touching a Registerwerk security
  token or holding any fund the oracle is aware of. This is the `ORACLE_ONLY` interoperability
  model (see `DefiInteropModel` in the backend `kyc` module) — zero custody risk, since the
  oracle never custodies anything; it only answers "is this wallet KYC'd for topic X."
  One constraint worth internalizing: the oracle checks the **queried wallet's own** org
  membership, not the caller's. If your contract itself needs to *be* the checked identity
  (e.g. to call a gated Registerwerk dApp function as `msg.sender`), your contract's address
  must itself be onboarded via `OrgRegistry` like any other member wallet — there is no
  generic "any smart contract passes" shortcut.
- **`DvpSettlement`** — a generic, ungated ERC-7573-style escrow usable by any external
  protocol for atomic asset↔stablecoin swaps, independent of the ecosystem permission
  framework entirely. Read its NatSpec caveat carefully before integrating: escrowing an
  ERC-3643 asset via `lockAsset` requires `DvpSettlement` itself to pass `isVerified()` in that
  token's identity registry (a one-time onboarding step by the token's registry agent); calling
  `lockPayment` instead avoids this entirely, since the security-token leg then moves directly
  seller→buyer as a direct contract-level transfer rather than sitting in escrow. Passing the
  token's technical checks does not establish legal or regulatory compliance or settlement.

For the harder question — can an external protocol *hold* a Registerwerk security token as a
pooled balance (an AMM pool, a lending market) — see
[`docs/platform/defi-interoperability.md`](./defi-interoperability.md), which covers why that
requires a licensed nominee/custodian structure (the `NOMINEE_POOL` model) rather than an
anonymous permissionless pool, and how the `EwpgComplianceModule` nominee exemption works.

## Reference example dApps

Three technical reference examples ship in this repository with manifests, Solidity source,
tests, and a `README`. They are examples rather than approved product templates, and are seeded as
`PUBLISHED` demo marketplace listings by
`EcosystemDemoDataSeeder` when `registerwerk.seed-demo-data=true`:

| dApp | Slug | Showcases |
|---|---|---|
| **Boardroom Governance** | `boardroom` | The permission-management framework in full: propose/vote/tally gated by permissions + ONCHAINID claims (KYC, Accreditation), and the **role-restriction / org-admin delegation** flow on `boardroom.tally`. |
| **eWpG Bond Desk** | `bond-desk` | An ERC-3643/T-REX technical example with a configured token payment leg. `subscribe` performs payment transfer and minting in one transaction; `payCoupon`/`redeem` exercise time/idempotency controls. This is not a legally classified bond, verified payment arrangement, or proof of legal settlement. |
| **eWpG Repo & Lending Facility** | `repo-facility` | A collateralized-lending technical example with an open stablecoin-lender side and contract-gated borrower side. Production use is blocked pending legal characterization, custody/control, liquidation, oracle, insolvency, eligibility, and security approval. Token identity checks alone do not make liquidation compliant. See [DeFi Interoperability](./defi-interoperability.md#ewpgrepofacility--the-primary-exit-liquidity-mechanism). |

| | Path |
|---|---|
| Contracts | `contracts/src/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility,MockStablecoin}.sol`, `contracts/src/settlement/DvpSettlement.sol` |
| Tests | `contracts/test/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility}.t.sol`, `contracts/test/settlement/DvpSettlementTest.t.sol` |
| T-REX bootstrap helper | `contracts/test/helpers/TrexSuiteDeployer.sol` — the full T-REX + ONCHAINID bring-up (implementation authority, identity factory, compliance module) reused by the bond desk's test and deploy script |
| Deploy scripts | `contracts/script/DeployEwpgTrexBond.s.sol`, `contracts/script/DeployExampleDapps.s.sol` (boardroom, bond desk), `contracts/script/DeployLiquidityDapps.s.sol` (repo facility, plus `EwpgPaymaster` — kept in a separate script since both are pragma `^0.8.36` and can't share a compilation unit with the erc3643-dependent contracts above; see that script's own NatSpec) |
| Manifests | `backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility}.manifest.json` — also read directly by the demo data seeder (`registerwerk.seed-demo-data=true`), which publishes all three as live marketplace listings with real, independently-verifiable signatures |
| Guides | `examples/dapps/{boardroom,bond-desk,repo-facility}/README.md` |

Run `forge test --match-path 'test/examples/*'` to see all three exercised end to end,
including — for the bond desk — real ONCHAINID identities and ECDSA-signed KYC/AML
claims via an onchain-id `ClaimIssuer`.

Two further contracts demonstrate the `NOMINEE_POOL` bridge and AMM-for-stablecoins pattern
from [DeFi Interoperability](./defi-interoperability.md) — unlike the three dApps above, they
ship as tested Solidity only (no manifest, not seeded as live marketplace listings):

- `contracts/src/examples/CompliantSecondaryMarket.sol` — a nominee/omnibus secondary-market
  desk gated by `secondary-market.trade` + the `NOMINEE` claim topic (4); settles every trade
  through the unmodified, ungated `DvpSettlement` above, and its fills double as the price feed
  for `EwpgRepoFacility.updatePrice`. Tests:
  `contracts/test/examples/CompliantSecondaryMarket.t.sol`.
- `contracts/src/examples/StablecoinAmm.sol` — a minimal constant-product AMM restricted to
  stablecoin-only pairs, deliberately **not** `RegisterwerkGated` (see its NatSpec for why).
  Tests: `contracts/test/examples/StablecoinAmm.t.sol`.
