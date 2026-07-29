// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for {RegisterwerkNavOracle}'s price-deviation circuit breaker: a single
///         compromised or fat-fingered `PUSH_PRICE` key must not be able to mark collateral
///         arbitrarily high (enabling over-borrowing) or arbitrarily low (triggering mass
///         unnecessary liquidations) in one push.
contract RegisterwerkNavOracleTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    RegisterwerkNavOracle navOracle;
    MockOnchainId feedOrgId;
    MockOnchainId overrideOrgId;

    address operator = address(0x1);
    address feedKey = address(0x21); // ordinary PUSH_PRICE-only automation key
    address overrideKey = address(0x22); // OVERRIDE_PRICE-holding admin key
    address collateralAsset = address(0x99);

    bytes32 pushPricePermission;
    bytes32 overridePricePermission;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        navOracle = new RegisterwerkNavOracle(ecosystemOracle);

        pushPricePermission = navOracle.PUSH_PRICE();
        overridePricePermission = navOracle.OVERRIDE_PRICE();

        feedOrgId = new MockOnchainId();
        overrideOrgId = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(feedOrgId), 276);
        orgRegistry.registerOrg(address(overrideOrgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("NAV_FEED");
        orgRegistry.addMember(address(feedOrgId), feedKey, roles, "");
        orgRegistry.addMember(address(overrideOrgId), overrideKey, roles, "");
        permissions.grantToOrg(address(feedOrgId), pushPricePermission);
        permissions.grantToOrg(address(overrideOrgId), pushPricePermission);
        permissions.grantToOrg(address(overrideOrgId), overridePricePermission);
        vm.stopPrank();
    }

    function test_pushPrice_firstPushIsUnbounded() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 1_000_000e6);

        (uint256 pricePerUnit,) = navOracle.price(collateralAsset);
        assertEq(pricePerUnit, 1_000_000e6);
    }

    function test_pushPrice_withinDeviationCapSucceeds() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        // +15% — within the default 20% (2000 bps) cap.
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 115e6);

        (uint256 pricePerUnit,) = navOracle.price(collateralAsset);
        assertEq(pricePerUnit, 115e6);
    }

    function test_pushPrice_revertsAboveDeviationCap() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        // +50% in one push — a compromised/fat-fingered key must not be able to do this.
        vm.prank(feedKey);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 150e6, 2000));
        navOracle.pushPrice(collateralAsset, 150e6);

        // A downward crash push is bounded the same way.
        vm.prank(feedKey);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 40e6, 2000));
        navOracle.pushPrice(collateralAsset, 40e6);

        // The mark is unchanged after both reverted attempts.
        (uint256 pricePerUnit,) = navOracle.price(collateralAsset);
        assertEq(pricePerUnit, 100e6);
    }

    function test_pushPriceWithOverride_bypassesCapAndRequiresSeparatePermission() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        // The ordinary feed key cannot self-authorize an override.
        vm.prank(feedKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, feedKey, overridePricePermission)
        );
        navOracle.pushPriceWithOverride(collateralAsset, 500e6);

        // The override key can push a legitimate large repricing past the cap.
        vm.prank(overrideKey);
        navOracle.pushPriceWithOverride(collateralAsset, 500e6);

        (uint256 pricePerUnit,) = navOracle.price(collateralAsset);
        assertEq(pricePerUnit, 500e6);
    }

    function test_setMaxDeviationBps_revertsForNonOverrideCaller() public {
        vm.prank(feedKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, feedKey, overridePricePermission)
        );
        navOracle.setMaxDeviationBps(5000);
    }

    function test_setMaxDeviationBps_wideningAllowsPreviouslyRejectedPush() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        vm.prank(overrideKey);
        navOracle.setMaxDeviationBps(6000); // widen to 60%

        // +50% now succeeds through the ordinary (non-override) path.
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 150e6);

        (uint256 pricePerUnit,) = navOracle.price(collateralAsset);
        assertEq(pricePerUnit, 150e6);
    }

    function test_pushPrice_revertsOnZeroPrice() public {
        vm.prank(feedKey);
        vm.expectRevert(RegisterwerkNavOracle.ZeroAmount.selector);
        navOracle.pushPrice(collateralAsset, 0);
    }
}
