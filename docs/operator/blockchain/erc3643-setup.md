---
title: ERC-3643 Setup
---

# ERC-3643 (T-REX) Setup

This guide walks through the complete setup of the ERC-3643 T-REX infrastructure — from contract deployment to issuing KYC claims to investors.

## What gets deployed

For each ERC-3643 issuance, the factory deploys six contracts:

| Contract | Role |
|----------|------|
| `Token` | The ERC-3643 token (main contract, ERC-20 compatible interface) |
| `IdentityRegistry` | Maps investor wallets to their ONCHAINID |
| `IdentityRegistryStorage` | Upgradeable storage for the identity registry |
| `ClaimTopicsRegistry` | Defines required claim topic IDs (e.g., KYC=1, AML=2) |
| `TrustedIssuersRegistry` | Defines which identity issuers can sign claims |
| `ModularCompliance` | Container for pluggable compliance rule modules |

All six are deployed atomically by the `EwpgTREXFactory` via `AssetTokenFactory`.

## Step 1 — Deploy the factory suite

Ensure the `AssetTokenFactory` and `EwpgTREXFactory` are deployed per [Deploying Contracts](./deploying-contracts.md). Confirm the factory address is set in `.env` and the backend has loaded it:

```bash
curl http://localhost:48080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Step 2 — Set up the registry as Trusted Issuer

The registry backend operator wallet must be registered in the `TrustedIssuersRegistry` so it can issue KYC/AML claims. This is done once per factory deployment.

```bash
cast send $TRUSTED_ISSUERS_REGISTRY \
  "addTrustedIssuer(address,uint256[])" \
  $REGISTRY_OPERATOR_ADDRESS "[1,2]" \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Parameters:
- First arg: address of the registry operator (deployer wallet)
- Second arg: array of claim topic IDs this issuer is trusted to sign (1=KYC, 2=AML)

Verify:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" \
  $REGISTRY_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

## Step 3 — Configure claim topics

The `ClaimTopicsRegistry` lists all claim topics required for transfer eligibility:

```bash
cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 1 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 2 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

| Topic ID | Meaning |
|----------|---------|
| 1 | KYC — identity verification |
| 2 | AML — anti-money laundering screening |

The backend automatically provisions these topics when creating a new T-REX issuance.

## Step 4 — Register investor ONCHAINID contracts

When an investor is onboarded, the backend deploys an ONCHAINID contract for them and registers it in the Identity Registry. This happens automatically when you whitelist an investor via the operator frontend.

Every registration needs an ISO 3166-1 numeric country. The operator dialog prefills it from the
legal entity's KYC registration country, and the API rejects a missing country or country `0`.
When any country is blocked for a token, `EwpgComplianceModule` rejects transfers to a wallet
whose registered country is `0`. The identity-registry table flags such wallets as **Country
missing**. Fix them with `updateCountry(address,uint16)` on the Identity Registry.

To verify an investor's ONCHAINID is registered:

```bash
cast call $IDENTITY_REGISTRY \
  "contains(address)(bool)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

To look up the ONCHAINID address for a wallet:

```bash
cast call $IDENTITY_REGISTRY \
  "identity(address)(address)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
```

## Step 5 — Issuing KYC/AML claims

After KYC approval in the operator frontend, the backend automatically issues claims on the investor's ONCHAINID:

1. Constructs a claim with topic ID, issuer address, and a hash of the KYC verification record
2. Signs the claim with the operator's private key
3. Calls `addClaim` on the investor's ONCHAINID contract

Claims include an expiry date (default: 365 days). The backend schedules expiry reminder emails and can re-issue claims on renewal.

To manually verify claims on an ONCHAINID:

```bash
cast call $INVESTOR_ONCHAINID \
  "getClaimIdsByTopic(uint256)(bytes32[])" 1 \
  --rpc-url $RPC_URL
# Returns array of claim IDs for topic 1 (KYC)
```

## Step 6 — Compliance modules

Configure compliance modules per issuance from the operator frontend under **Issuances → [issuance] → Compliance Modules**.

### MaxBalance module

Limits the maximum token balance any single investor may hold.

Configure via operator frontend, or directly:

```bash
cast send $MAX_BALANCE_MODULE \
  "setMaxBalance(address,uint256)" $TOKEN_ADDRESS 100000 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### MaxInvestors module

Caps the total number of distinct token holders (useful for Regulation D exemption limits):

```bash
cast send $MAX_INVESTORS_MODULE \
  "setMaxInvestors(address,uint256)" $TOKEN_ADDRESS 499 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### CountryRestrict module

Blocks investors from specified ISO 3166-1 numeric country codes:

```bash
# Block US (840) and CN (156)
cast send $COUNTRY_RESTRICT_MODULE \
  "batchRestrictCountries(address,uint16[])" \
  $TOKEN_ADDRESS "[840,156]" \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### EwpgComplianceModule (Registerwerk's own module)

`EwpgComplianceModule` combines max investors, max balance per investor, blocked countries,
transfer cooldown and the nominee-pool exemption. All of its settings are stored per
`ModularCompliance` contract. Its setters take that compliance address as the first argument,
for example `setMaxInvestors(address compliance, uint256)`.

- **Who may configure it.** Only the owner of the compliance contract may call a setter, or the
  compliance itself through `callModuleFunction`. Any other caller reverts with
  `CallerNotComplianceAdmin`. T-REX transfers ownership in two steps, so after a suite is
  deployed the registry wallet is only the *pending* owner. The backend calls
  `acceptOwnership()` on the compliance at deploy time. If that step failed, it calls it again
  before the next compliance-module change.
- **Adding it from the backend.** The backend binds the module, sends the setters, and reads the
  result back with `getConfig(address)` and `isCountryBlocked(address,uint16)`. It writes the
  database row only when every value is on-chain. If configuration fails, it unbinds the module
  again and reports the error.
- **"Investor" means ONCHAINID.** Balances and the investor count are added up per identity, so
  several wallets bound to one ONCHAINID share one balance cap and count as one investor.
  Transfers between two wallets of the same identity are always allowed.
- **Unknown country.** While at least one country is blocked, a recipient with no country on file
  (`0`) is rejected.

#### Migrating a live suite to the fixed module

Contracts deployed before this fix run the old module. In that module **anyone** can change the
settings, and limits apply per wallet instead of per identity. The module is not upgradeable,
so each live suite must switch to a newly deployed module.

1. Deploy the new `EwpgComplianceModule`.
2. As the compliance owner (call `acceptOwnership()` first if you are still only the pending
   owner), run `addModule(newModule)` on the suite's `ModularCompliance`.
3. Configure the new module from the values recorded in the database (`erc3643_compliance_module`):
   `setMaxInvestors`, `setMaxBalance`, `setTransferCooldown`, `blockCountry` and `setNomineePool`.
   Do not copy values from the old module's on-chain state, because anyone may have changed it.
4. Backfill existing holders:
   `syncHolders(compliance, wallets)` with every wallet in `erc3643_identity_registry` for the
   suite (plus any other holder wallet the indexer knows). The call is idempotent, so it can be
   run in batches and re-run safely.
5. Check `getConfig(compliance)` against the database, including the investor count against the
   number of distinct ONCHAINIDs with a positive balance.
6. Run `removeModule(oldModule)` on the compliance.

!!! warning "A legacy module cannot be bound from the backend"
    The backend reads the configuration back with `getConfig(address)` after binding a module.
    A module deployed before this fix has no `getConfig`, so adding it from the backend always
    rolls back. Do not retry: redeploy `EwpgComplianceModule` and bind the new deployment.

Until a suite is migrated, alert on any difference between the old module's `isCountryBlocked` /
limits and the database. Also report every identity-registry entry with country `0`, checked
against `investorCountry(wallet)` on-chain, to operators as an `updateCountry` task.

## Step 7 — Agent roles

The registry backend wallet must hold agent roles on each deployed token to perform management operations. The deployment script grants these automatically.

| Role | Allows |
|------|--------|
| Identity Registry Agent | `registerIdentity`, `updateIdentity`, `deleteIdentity` |
| Token Agent | `mint`, `burn`, `freezePartialTokens`, `forcedTransfer` |
| Compliance Agent | `addModule`, `removeModule`, `callModuleFunction` |

To grant agent roles manually (if needed):

```bash
cast send $IDENTITY_REGISTRY \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $TOKEN \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```
