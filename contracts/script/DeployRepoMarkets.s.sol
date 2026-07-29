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
///         both can run against the same ecosystem, and both share the `repo-facility.borrow`/
///         `repo-facility.configure` permission codes (see `EwpgRepoMarket`'s NatSpec).
///
///         Reads REGISTRY_WALLET_PRIVATE_KEY and PERMISSION_ORACLE_ADDRESS (required).
///         Optional overrides:
///           REPO_MARKETS_PAYMENT_TOKEN (address of the lending-currency stablecoin; if
///                                       unset, a MockStablecoin "AUEUR" (6 decimals) is
///                                       deployed — pass the same address as
///                                       DeployLiquidityDapps.s.sol's REPO_FACILITY_PAYMENT_TOKEN
///                                       to share one lending currency across both dApps)
///
///         Usage:
///           forge script script/DeployRepoMarkets.s.sol --rpc-url <rpc> --broadcast
///
///         This script only deploys the factory/oracle/vault — no markets exist yet. Remember
///         to also, per collateral security you want lendable:
///           1. Grant the operator (or delegated) org `repo-markets.create-market`,
///              `repo-markets.curate-vault`, and `repo-markets.push-price` via
///              `PermissionRegistry`.
///           2. Call `RegisterwerkNavOracle.pushPrice(collateralToken, initialPrice)`.
///           3. Call `EwpgRepoMarketFactory.createMarket(...)` for the collateral/loan pair.
///           4. Flag the new market's address as a nominee pool on the collateral token's
///              `EwpgComplianceModule.setNomineePool` — identical requirement to
///              {EwpgRepoFacility}.
///           5. Optionally `EwpgRepoVault.addMarket(market, cap)` then `allocate(...)` to route
///              curated vault liquidity into it.
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

        RegisterwerkNavOracle navOracle = new RegisterwerkNavOracle(oracle);
        EwpgRepoMarketFactory factory = new EwpgRepoMarketFactory(oracle);
        EwpgRepoVault vault = new EwpgRepoVault(oracle, IERC20(paymentToken), "Registerwerk EMT Vault", "rwvAUEUR");

        vm.stopBroadcast();

        console.log("PermissionOracle           :", oracleAddress);
        console.log("RegisterwerkNavOracle       :", address(navOracle));
        console.log("EwpgRepoMarketFactory       :", address(factory));
        console.log("EwpgRepoVault               :", address(vault));
        console.log("  -> payment token          :", paymentToken);
    }
}
