// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Script.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "../src/ecosystem/interfaces/IPermissionOracle.sol";
import "../src/examples/BoardroomGovernance.sol";
import "../src/examples/EwpgBondDesk.sol";
import "../src/examples/MockStablecoin.sol";
import "../src/settlement/DvpSettlement.sol";

/// @notice Deploy the two reference marketplace dApps — {BoardroomGovernance} and
///         {EwpgBondDesk} — plus the operator payment rails ({DvpSettlement} and, on
///         test networks, a {MockStablecoin} standing in for a MiCAR EMT such as AUEUR)
///         against an already-deployed ecosystem (see `script/DeployEcosystem.s.sol`)
///         and, for the bond desk, an already-deployed T-REX bond suite
///         (see `script/DeployEwpgTrexBond.s.sol`).
///
///         Reads REGISTRY_WALLET_PRIVATE_KEY and PERMISSION_ORACLE_ADDRESS (required).
///         Optional overrides:
///           GOVERNANCE_TOKEN_ADDRESS  (address, default 0 — informational pointer only)
///           EWPG_BOND_TOKEN_ADDRESS   (address; if unset, EwpgBondDesk is skipped)
///           BOND_PAYMENT_TOKEN        (address of the EMT stablecoin; if unset, a
///                                      MockStablecoin "AUEUR" (6 decimals) is deployed)
///           BOND_TREASURY             (address, default deployer — must approve the desk)
///           BOND_PRICE_PER_UNIT       (uint256 payment-token base units per bond unit,
///                                      default 100e6 = 100.00 in a 6-decimals EMT)
///           EWPG_BOND_COUPON_BPS      (uint16,  default 450 = 4.50% per period)
///           BOND_COUPON_INTERVAL_SECS (uint256, default 90 days)
///           EWPG_BOND_MATURITY        (uint256 unix timestamp, default now + 365 days)
///
///         Usage:
///           forge script script/DeployExampleDapps.s.sol --rpc-url <rpc> --broadcast
///
///         Remember to also add the T-REX agent role to EwpgBondDesk on the bond token
///         (`AgentRole(bondToken).addAgent(bondDeskAddress)`, token-owner only — see
///         `test/examples/EwpgBondDesk.t.sol` for the reference sequence), have the
///         treasury approve the desk on the payment token, register the deployed
///         DvpSettlement under `registerwerk.contracts.dvp-settlement.<chain>` /
///         the `erc7573-dvp` payment rail, and anchor both dApps in the marketplace via
///         the `marketplace` backend module before they are usable end to end.
///
///         For {EwpgRepoFacility} and {EwpgPaymaster} — the exit-liquidity and sponsored-
///         transaction dApps — see the separate `script/DeployLiquidityDapps.s.sol`: both
///         are pragma ^0.8.36 (untainted by the erc3643 lib's exact `=0.8.30` pin used
///         here), so they cannot share a compilation unit with this file — see that
///         script's own NatSpec for why.
contract DeployExampleDapps is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("REGISTRY_WALLET_PRIVATE_KEY");
        address oracleAddress = vm.envAddress("PERMISSION_ORACLE_ADDRESS");
        IPermissionOracle oracle = IPermissionOracle(oracleAddress);

        vm.startBroadcast(deployerKey);

        address governanceToken = vm.envOr("GOVERNANCE_TOKEN_ADDRESS", address(0));
        BoardroomGovernance boardroom = new BoardroomGovernance(oracle, governanceToken);

        // Operator payment rail: ERC-7573-style DvP settlement (asset- or payment-leg lock).
        DvpSettlement dvpSettlement = new DvpSettlement(vm.addr(deployerKey));

        address bondTokenAddress = vm.envOr("EWPG_BOND_TOKEN_ADDRESS", address(0));
        EwpgBondDesk bondDesk;
        address paymentToken = vm.envOr("BOND_PAYMENT_TOKEN", address(0));
        if (bondTokenAddress != address(0)) {
            if (paymentToken == address(0)) {
                // Test networks only: stand-in for a MiCAR EMT (e.g. AllUnity Euro).
                paymentToken = address(new MockStablecoin("AllUnity Euro (demo)", "AUEUR", 6));
            }
            address treasury = vm.envOr("BOND_TREASURY", vm.addr(deployerKey));
            uint256 pricePerUnit = vm.envOr("BOND_PRICE_PER_UNIT", uint256(100e6));
            uint16 couponBps = uint16(vm.envOr("EWPG_BOND_COUPON_BPS", uint256(450)));
            uint256 couponIntervalSecs = vm.envOr("BOND_COUPON_INTERVAL_SECS", uint256(90 days));
            uint256 maturity = vm.envOr("EWPG_BOND_MATURITY", block.timestamp + 365 days);
            bondDesk = new EwpgBondDesk(
                oracle,
                IERC3643(bondTokenAddress),
                IERC20(paymentToken),
                treasury,
                pricePerUnit,
                couponBps,
                couponIntervalSecs,
                maturity
            );
        }

        vm.stopBroadcast();

        console.log("PermissionOracle           :", oracleAddress);
        console.log("BoardroomGovernance        :", address(boardroom));
        console.log("DvpSettlement              :", address(dvpSettlement));
        if (bondTokenAddress != address(0)) {
            console.log("EwpgBondDesk               :", address(bondDesk));
            console.log("  -> bond token            :", bondTokenAddress);
            console.log("  -> payment token (EMT)   :", paymentToken);
        } else {
            console.log("EwpgBondDesk               : skipped (set EWPG_BOND_TOKEN_ADDRESS)");
        }
    }
}
