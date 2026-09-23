// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "../src/ecosystem/interfaces/IPermissionOracle.sol";
import "../src/lending/EwpgRepoMarketFactory.sol";
import "../src/lending/EwpgRepoVault.sol";
import "../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../src/examples/MockStablecoin.sol";

/// @notice Deploy the Morpho-Blue-style isolated-market stack — {EwpgRepoMarketFactory},
///         {RegisterwerkNavOracle}, and {EwpgRepoVault} — against an already-deployed
///         ecosystem (see `script/DeployEcosystem.s.sol`). This is additive to, not a
///         replacement for, `script/DeployLiquidityDapps.s.sol`'s pooled {EwpgRepoFacility}:
///         both can run against the same ecosystem and share the `repo-facility.borrow`
///         permission code; market administration uses its own `repo-markets.configure` /
///         `repo-markets.reconcile` codes (see `EwpgRepoMarket`'s NatSpec).
///
///         Reads REGISTRY_WALLET_PRIVATE_KEY and PERMISSION_ORACLE_ADDRESS (required).
///         Optional overrides:
///           REPO_MARKETS_PAYMENT_TOKEN (address of the lending-currency stablecoin; if
///                                       unset, a MockStablecoin "AUEUR" (6 decimals) is
///                                       deployed — pass the same address as
///                                       DeployLiquidityDapps.s.sol's REPO_FACILITY_PAYMENT_TOKEN
///                                       to share one lending currency across both dApps)
///           REPO_MARKETS_OPERATOR_ORG  (org operating the NAV oracles; defaults to the
///                                       deployer wallet's org in the PermissionOracle)
///           REPO_MARKETS_USD_TOKEN     (address of a second, USD-denominated rail token, e.g.
///                                       USDC; when set, a second NAV oracle quoting it is
///                                       deployed — one oracle per payment rail, since a
///                                       market only accepts an oracle quoted in its own
///                                       loan token)
///           REPO_MARKETS_CURATOR_ORG   (org curating the vault; defaults to the operator org)
///           REPO_MARKETS_VAULT_TIMELOCK (seconds between a vault market addition / cap
///                                       increase and its acceptance; default 1 day, which is
///                                       also the minimum except on the local chain 31337)
///
///         Usage:
///           forge script script/DeployRepoMarkets.s.sol --rpc-url <rpc> --broadcast
///
///         This script only deploys the factory/oracle(s)/vault — no markets exist yet. Remember
///         to also, per collateral security you want lendable:
///           1. Grant the operator org `repo-markets.create-market`, `repo-markets.configure`,
///              `repo-markets.reconcile`, `repo-markets.override-price` and
///              `repo-markets.curate-vault` (to the curator org instead, if it differs), and the
///              operator (or a delegated NAV
///              administrator's) org `repo-markets.push-price`, via `PermissionRegistry`. Grant
///              these codes to the operating org only (never to other orgs).
///           2. Optionally `RegisterwerkNavOracle.setAssetPusher(collateralToken, navAdminOrg)`
///              (override-price) to let a delegated NAV administrator push that asset only;
///              then `pushPrice(collateralToken, initialPrice)` on the oracle of the rail.
///           3. Call `EwpgRepoMarketFactory.createMarket(MarketParams(...))` from an operator
///              org wallet, with that org as `operatorOrg`, the operator treasury, the rail's
///              oracle, and LLTV × (1 + bonus) ≤ 1 − the oracle's `maxDeviationBps` (e.g. LLTV
///              75% / bonus 5% against 20%).
///           4. Flag the new market's address as a nominee pool on the collateral token's
///              `EwpgComplianceModule.setNomineePool` — identical requirement to
///              {EwpgRepoFacility}.
///           5. Optionally, from a curator-org wallet, `EwpgRepoVault.submitAddMarket(market, cap)`,
///              then `acceptAddMarket(market)` once the vault's timelock has elapsed, then
///              `allocate(...)` to route curated vault liquidity into it. Only markets deployed
///              by this factory are accepted; cap increases go through
///              `submitCapIncrease` / `acceptCapIncrease`.
///           6. Anchor this dApp in the marketplace via the `marketplace` backend module
///              (manifest: `backend/src/main/resources/demo/dapps/repo-markets.manifest.json`).
contract DeployRepoMarkets is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address oracleAddress = vm.envAddress("PERMISSION_ORACLE_ADDRESS");
        IPermissionOracle oracle = IPermissionOracle(oracleAddress);

        vm.startBroadcast(deployerKey);

        address paymentToken = vm.envOr("REPO_MARKETS_PAYMENT_TOKEN", address(0));
        if (paymentToken == address(0)) {
            // Test networks only: stand-in for a MiCAR EMT (e.g. AllUnity Euro).
            paymentToken = address(new MockStablecoin("AllUnity Euro (demo)", "AUEUR", 6));
        }

        address operatorOrg = vm.envOr("REPO_MARKETS_OPERATOR_ORG", oracle.orgOf(vm.addr(deployerKey)));
        RegisterwerkNavOracle navOracle = new RegisterwerkNavOracle(oracle, operatorOrg, paymentToken, 2000, 1 days, 0);
        // One oracle per payment rail (EwpgRepoMarket requires priceOracle.quoteToken() == loanToken).
        address usdToken = vm.envOr("REPO_MARKETS_USD_TOKEN", address(0));
        RegisterwerkNavOracle usdNavOracle;
        if (usdToken != address(0)) {
            usdNavOracle = new RegisterwerkNavOracle(oracle, operatorOrg, usdToken, 2000, 1 days, 0);
        }
        EwpgRepoMarketFactory factory = new EwpgRepoMarketFactory(oracle);
        address curatorOrg = vm.envOr("REPO_MARKETS_CURATOR_ORG", operatorOrg);
        uint256 vaultTimelock = vm.envOr("REPO_MARKETS_VAULT_TIMELOCK", uint256(1 days));
        EwpgRepoVault vault = new EwpgRepoVault(
            oracle,
            IERC20(paymentToken),
            factory,
            curatorOrg,
            operatorOrg,
            vaultTimelock,
            "Registerwerk EMT Vault",
            "rwvAUEUR"
        );

        vm.stopBroadcast();

        console.log("PermissionOracle           :", oracleAddress);
        console.log("RegisterwerkNavOracle       :", address(navOracle));
        console.log("  -> quote token            :", paymentToken);
        if (usdToken != address(0)) {
            console.log("RegisterwerkNavOracle (USD) :", address(usdNavOracle));
            console.log("  -> quote token            :", usdToken);
        }
        console.log("EwpgRepoMarketFactory       :", address(factory));
        console.log("EwpgRepoVault               :", address(vault));
        console.log("  -> payment token          :", paymentToken);
        console.log("  -> curator org            :", curatorOrg);
        console.log("  -> timelock (s)           :", vaultTimelock);
    }
}
