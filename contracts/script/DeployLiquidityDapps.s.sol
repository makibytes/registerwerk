// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Script.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {IEntryPoint} from "@openzeppelin/contracts/interfaces/draft-IERC4337.sol";
import {ERC4337Utils} from "@openzeppelin/contracts/account/utils/draft-ERC4337Utils.sol";
import "../src/ecosystem/interfaces/IPermissionOracle.sol";
import "../src/ecosystem/EwpgPaymaster.sol";
import "../src/examples/EwpgRepoFacility.sol";
import "../src/examples/MockStablecoin.sol";

/// @notice Deploy {EwpgRepoFacility} (the exit-liquidity / collateralized-lending facility)
///         and {EwpgPaymaster} (sponsored ERC-4337 transactions) against an already-deployed
///         ecosystem (see `script/DeployEcosystem.s.sol`).
///
///         Kept in its own script, separate from `script/DeployExampleDapps.s.sol`: both
///         contracts here are pragma ^0.8.36 with no erc3643 dependency, while
///         `DeployExampleDapps.s.sol` imports `IERC3643`, which the vendored erc3643 lib
///         pins to an *exact* `=0.8.30` — incompatible with `^0.8.36` in a single
///         compilation unit. Sharing one script file across both taint classes fails to
///         compile; see `contracts/foundry.toml`/CI for the same split applied elsewhere.
///
///         Reads REGISTRY_WALLET_PRIVATE_KEY and PERMISSION_ORACLE_ADDRESS (required).
///         Optional overrides:
///           REPO_FACILITY_PAYMENT_TOKEN (address of the lending-currency stablecoin; if
///                                        unset, a MockStablecoin "AUEUR" (6 decimals) is
///                                        deployed — pass the same address used by
///                                        `DeployExampleDapps.s.sol`'s BOND_PAYMENT_TOKEN
///                                        to share one lending currency across dApps)
///           PAYMASTER_ENTRYPOINT        (address, default the canonical ERC-4337
///                                        EntryPoint v0.8 singleton,
///                                        `ERC4337Utils.ENTRYPOINT_V08`)
///
///         Usage:
///           forge script script/DeployLiquidityDapps.s.sol --rpc-url <rpc> --broadcast
///
///         Remember to also: flag {EwpgRepoFacility}'s address as a nominee pool on any
///         ERC-3643 collateral token via
///         `EwpgComplianceModule.setNomineePool(token, address(repoFacility), true)`
///         (token's compliance module, operator-only) and call its own
///         `setCollateralConfig` (operator-only) before any pledge can succeed; fund
///         {EwpgPaymaster}'s sponsorship policies via `fundSponsorship` before sponsored
///         transactions can be relayed; and anchor both dApps in the marketplace via the
///         `marketplace` backend module before they are usable end to end.
contract DeployLiquidityDapps is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address oracleAddress = vm.envAddress("PERMISSION_ORACLE_ADDRESS");
        IPermissionOracle oracle = IPermissionOracle(oracleAddress);

        vm.startBroadcast(deployerKey);

        address paymentToken = vm.envOr("REPO_FACILITY_PAYMENT_TOKEN", address(0));
        if (paymentToken == address(0)) {
            // Test networks only: stand-in for a MiCAR EMT (e.g. AllUnity Euro).
            paymentToken = address(new MockStablecoin("AllUnity Euro (demo)", "AUEUR", 6));
        }

        // The exit-liquidity facility — pledge a security-token holding, borrow the
        // stablecoin above, without selling the position. Collateral assets are enabled
        // after deployment via the operator-only setCollateralConfig (see NatSpec above).
        EwpgRepoFacility repoFacility = new EwpgRepoFacility(oracle, IERC20(paymentToken));

        // Sponsored (ERC-4337) transactions: operator- or issuer-funded gas policies.
        address entryPointAddress = vm.envOr("PAYMASTER_ENTRYPOINT", address(ERC4337Utils.ENTRYPOINT_V08));
        EwpgPaymaster paymaster = new EwpgPaymaster(oracle, IEntryPoint(entryPointAddress));

        vm.stopBroadcast();

        console.log("PermissionOracle           :", oracleAddress);
        console.log("EwpgRepoFacility           :", address(repoFacility));
        console.log("  -> payment token         :", paymentToken);
        console.log("EwpgPaymaster              :", address(paymaster));
        console.log("  -> EntryPoint            :", entryPointAddress);
    }
}
