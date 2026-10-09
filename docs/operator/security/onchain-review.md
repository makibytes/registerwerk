---
title: On-chain security review
---

# On-chain security review

The Anvil and wallet-management work received an internal, code-assisted security review before
integration. This is an engineering review, not an independent audit or a substitute for one.

## Review scope and resolved findings

| Area | Finding | Resolution |
| --- | --- | --- |
| Factory deployment | The previous monolithic factory runtime exceeded the EIP-170 size limit (24,576 bytes). | Split deployment into a 3 KB coordinator and per-standard deployer modules. CI runs `forge build --sizes` against the Glamsterdam limits (EIP-7954: 65,536 / 131,072 bytes), and the demo deploys on `anvil --hardfork amsterdam`. See [Glamsterdam](../../platform/glamsterdam.md). |
| Upgradeability | A single upgradeable implementation would couple unrelated token standards and issued products. | Issued products stay immutable; a small UUPS deployment registry coordinates versioned addresses. ERC-3643 retains the T-REX proxy model. |
| Upgrade authorization | Registry upgrades and mutations must not be publicly reachable. | Both are owner-gated and tested for unauthorized callers, non-contract implementations, and storage preservation. |
| Smart accounts | A passkey/EntryPoint path could otherwise bypass recovery and administration policy. | Routine, administrative, and recovery selectors are separated. EntryPoint execution is limited by target-and-selector policy; guardian-only operations cannot pass through it. |
| Key custody | Passing Web3j credentials through services exposed private-key material broadly. | All EVM transaction paths use the `EvmSigner` boundary. PKCS#11 wallets persist only address and key label, and cannot be exported through wallet APIs. |
| Demo drift | Independently configured addresses could point frontends and backends at different deployments. | The deployment scripts write one persisted manifest. Backend reconciliation and the customer UI consume that artifact after bytecode validation. |

The regression suite covers factory access control and all standards, upgrade authorization and
storage preservation, passkey execution policy, manifest reconciliation, and wallet custody
boundaries. Slither emits SARIF for repository-wide review and fails CI on high-severity findings.

## Interim runbook: org-wide dApp permissions

Ecosystem permissions are granted per slug and apply to **every** instance of a dApp. Until the
affected contracts are redeployed with an instance binding (`operatorOrg`, see
[Binding an instance to its operating org](../../platform/dapp-development.md#binding-an-instance-to-its-operating-org)),
any org holding one of the codes below can act on an instance that another org operates, for
example empty a secondary desk's inventory or push prices for every asset. The deployed contracts
are immutable, so the only mitigation available today is to hold these codes exclusively in the
operator org.

| Permission code | Contract and privileged action |
| --- | --- |
| `repo-markets.push-price` | `RegisterwerkNavOracle.pushPrice` (any asset on pre-review oracles; reviewed oracles only accept the asset's assigned pusher org, see `setAssetPusher`) |
| `repo-facility.configure` | Pre-review `EwpgRepoMarket` configuration, `withdrawReserves` (to any address) and `reconcileCollateral`; pre-review `EwpgRepoFacility` configuration and `updatePrice`. Reviewed markets use `repo-markets.configure` / `repo-markets.reconcile` bound to their `operatorOrg` instead; reviewed facilities bind `repo-facility.configure` and the new `repo-facility.price` (for `updatePrice`) to their `operatorOrg` |
| `repo-markets.curate-vault` | Pre-review `EwpgRepoVault` market list (any contract), caps, `allocate` / `deallocate`. Reviewed vaults bind it to their `curatorOrg`, accept only factory markets and apply additions and cap increases after a timelock. Retire a pre-review vault: the curator deallocates every market and calls `removeMarket`; depositors redeem from idle cash |
| `secondary-market.trade` | `CompliantSecondaryMarket.sellFromInventory` / `buyIntoInventory` |
| `bond-desk.issue`, `bond-desk.pay-coupon`, `bond-desk.redeem` | Pre-review `EwpgBondDesk` issuance and servicing. Reviewed desks bind these and `bond-desk.pause` / `bond-desk.legal-order` to their `operatorOrg` |

Steps:

1. In the operator portal, open **Permissions** and select each code above. The grant list shows
   every org holding it.
2. Revoke each grant held by an org other than the operator org. Revocation needs step-up and a
   second approver; the backend then broadcasts `PermissionRegistry.revokeFromOrg(org, code)` and
   records an audit event. Org-admin role delegations need no separate action, because
   `hasPermission` requires the org-level grant first.
3. Wait until each revoked grant shows as revoked onchain, then confirm on the chain itself, for a
   member wallet of each affected org:
   `cast call $PERMISSION_ORACLE "hasPermission(address,bytes32)(bool)" <wallet> $(cast keccak "<code>")`
   must return `false`.
4. Refuse new grants of these codes to non-operator orgs until the bound redeployment is live.
   Record the revocations and the affected orgs in the change log.

This withdraws the codes from every non-operator org, including for instances that org legitimately
operates. After the redeployment, grant the codes back only to orgs that operate a bound instance,
and retire the old instances (pause, then inventory any stranded balances) as described in the
release notes of each contract.

### Retiring pre-review repo markets

Reviewed `EwpgRepoMarket`s check `lltvBps × (1 + bonus) ≤ 1 − oracle.maxDeviationBps()` and
`oracle.quoteToken() == loanToken` at construction, cap grace-window liquidations at 50%, sell
whole collateral units with a claimable borrower surplus, bind administration to `operatorOrg`
(`repo-markets.configure` / `repo-markets.reconcile`), pay reserves only to a fixed `treasury`, and
bound `reconcileCollateral` by the observed collateral outflow. `EwpgRepoMarketFactory.createMarket`
now takes a `MarketParams` struct whose `operatorOrg` must be the caller's org. Nothing here can be
patched into deployed markets.

1. Deploy the reviewed factory and one reviewed `RegisterwerkNavOracle` per loan token
   (`script/DeployRepoMarkets.s.sol`; set `REPO_MARKETS_USD_TOKEN` for a second rail).
2. Grant `repo-markets.configure`, `repo-markets.reconcile` and `repo-markets.override-price` to the
   operator org only. Assign delegated NAV administrators per asset with `setAssetPusher`.
3. Create the replacement markets with parameters that pass the check (for a 20% oracle tolerance
   and a 5% bonus, LLTV at most 76.19%), flag each as a nominee pool on its collateral token, and
   register it in the backend. Registration refuses markets that fail the check.
4. For every old market: `setBorrowPaused(true)`, and revoke `repo-facility.configure` from every
   non-operator org (see above). The backend flags old markets `riskParametersLegacy`; the customer
   portal keeps repay, add-collateral, claim and withdraw available there and offers no new
   borrowing or supply.
5. Inventory each old market: open positions (`debtOf`), residual collateral of closed positions
   (`positions`), and lender claims. Follow up with borrowers until debt is repaid and collateral
   claimed; lenders withdraw as cash frees up. Record the old addresses in the change log.
6. Update `indexer/evm/subgraph/subgraph.yaml`: `CollateralReconciled` now carries a fourth
   `bytes32 forcedTransferRef` argument, and markets emit `LiquidationSurplusCredited` /
   `SurplusClaimed`; the factory emits `MarketOperatorSet`.

### Retiring the pre-review `DvpSettlement`

The reviewed `DvpSettlement` derives trade ids from the locker (`lockAsset`/`lockPayment` take a
`clientRef` and return the id), requires the counterparty's expected terms hash on
`settle(tradeId, expectedTermsHash)`, refuses to settle with a frozen party, and adds
`forceCancel(tradeId, to, legalBasis)` for releases to the trade's own parties, plus a separate
`LEGAL_ORDER_ROLE` path (`proposeForceCancel`, two-day timelock, `executeForceCancel`) for any other
destination named in a legal order; grant that role to a different key or multisig than the operator
wallet. The ABI is not compatible with the old deployment, so integrators must switch clients at the
same time.

1. Deploy the new contract (`script/DeployExampleDapps.s.sol`) and announce its address.
2. Update `registerwerk.contracts.dvp-settlement.<chain>`, the `erc7573-dvp` payment rail's chain
   address, and the indexer's `DVP_SETTLEMENT_ADDRESS_<CHAIN>` / start block.
3. `pause()` the old deployment. `cancel` stays available there, so every open escrow remains
   recoverable by its counterparty at any time and by its locker after expiry.
4. List the old deployment's `LegLocked` trades without a matching `TradeSettled` or
   `TradeCancelled` and follow up with the parties until each is cancelled. The old deployment has
   no `forceCancel`; an escrow stuck because its locker is frozen needs a token-agent
   `forcedTransfer` out of the old contract under a legal order.
5. Redeploy each `CompliantSecondaryMarket` with its `operatorOrg` against the new settlement, move
   and move its inventory. The old desk has no reclaim function, so escrows it locked on the old
   deployment can only be returned by the counterparty's `cancel` or a token-agent
   `forcedTransfer`; inventory them before retiring the old desk.

## Production acceptance criteria

Before deploying value-bearing contracts or attaching a production HSM:

1. commission an independent audit of the exact tagged source, compiler configuration and
   deployment scripts;
2. resolve or formally accept all static-analysis findings and publish the resulting report;
3. move registry ownership to a separately reviewed multisig and timelock policy;
4. perform vendor-specific Thales or Utimaco integration, key ceremony, backup and disaster
   recovery tests;
5. verify the deployed bytecode, proxy implementation slots, owner and manifest hashes against
   the approved release artifact;
6. repeat end-to-end tests against the production RPC, bundler and monitoring stack.

Confidential-token tests that need a dedicated fhEVM runtime are intentionally skipped in the
ordinary Foundry environment and must pass in that environment before confidential issuance is
enabled.
