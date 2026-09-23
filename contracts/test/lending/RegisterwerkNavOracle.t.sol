// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/examples/MockStablecoin.sol";
import "../../src/lending/EwpgRepoMarket.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for {RegisterwerkNavOracle}'s price-deviation circuit breaker: a
///         compromised or fat-fingered `PUSH_PRICE` key must not be able to mark collateral
///         arbitrarily high (enabling over-borrowing) or arbitrarily low (triggering mass
///         unnecessary liquidations) — not in one push, and not in many pushes within one
///         deviation window. Also covers per-asset pusher scoping, operator-org binding of the
///         override path, the decrease-only cap and the quote denomination.
contract RegisterwerkNavOracleTest is Test {
    uint256 constant WINDOW = 1 days;

    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    RegisterwerkNavOracle navOracle;
    MockStablecoin quote;
    MockOnchainId operatorOrgId; // operates the oracle: default pusher + override
    MockOnchainId feedOrgId; // delegated NAV administrator for one asset
    MockOnchainId otherOrgId; // another org holding the same slug grants

    address operatorKey = address(0x22); // operator org: PUSH_PRICE + OVERRIDE_PRICE
    address feedKey = address(0x21); // feed org: PUSH_PRICE only
    address otherKey = address(0x23); // other org: PUSH_PRICE + OVERRIDE_PRICE
    address operator = address(0x1);
    address collateralAsset = address(0x99);
    address otherAsset = address(0x98);

    bytes32 pushPricePermission;
    bytes32 overridePricePermission;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        quote = new MockStablecoin("AllUnity Euro", "AUEUR", 6);

        operatorOrgId = new MockOnchainId();
        feedOrgId = new MockOnchainId();
        otherOrgId = new MockOnchainId();
        navOracle = new RegisterwerkNavOracle(ecosystemOracle, address(operatorOrgId), address(quote), 2000, WINDOW, 0);

        pushPricePermission = navOracle.PUSH_PRICE();
        overridePricePermission = navOracle.OVERRIDE_PRICE();

        _member(operatorOrgId, operatorKey);
        _member(feedOrgId, feedKey);
        _member(otherOrgId, otherKey);
        vm.startPrank(operator);
        permissions.grantToOrg(address(operatorOrgId), pushPricePermission);
        permissions.grantToOrg(address(operatorOrgId), overridePricePermission);
        permissions.grantToOrg(address(feedOrgId), pushPricePermission);
        permissions.grantToOrg(address(otherOrgId), pushPricePermission);
        permissions.grantToOrg(address(otherOrgId), overridePricePermission);
        vm.stopPrank();

        // The feed org is the delegated pusher of `collateralAsset` only.
        vm.prank(operatorKey);
        navOracle.setAssetPusher(collateralAsset, address(feedOrgId));
    }

    function _member(MockOnchainId org, address wallet) internal {
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(org), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("NAV_FEED");
        orgRegistry.addMember(address(org), wallet, roles, "");
        vm.stopPrank();
    }

    function _price(address asset) internal view returns (uint256 pricePerUnit) {
        (pricePerUnit,) = navOracle.price(asset);
    }

    // ── single-push deviation cap (unchanged semantics) ─────────────────────────

    function test_pushPrice_firstPushIsUnbounded() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 1_000_000e6);

        assertEq(_price(collateralAsset), 1_000_000e6);
    }

    function test_pushPrice_withinDeviationCapSucceeds() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        // +15% — within the 20% (2000 bps) cap.
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 115e6);

        assertEq(_price(collateralAsset), 115e6);
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
        assertEq(_price(collateralAsset), 100e6);
    }

    function test_pushPrice_revertsOnZeroPrice() public {
        vm.prank(feedKey);
        vm.expectRevert(RegisterwerkNavOracle.ZeroAmount.selector);
        navOracle.pushPrice(collateralAsset, 0);
    }

    // ── D2-02 / T2-11: cumulative per-window bound (no ratchet) ─────────────────

    /// @notice Ported from `LendingPoc.test_navRatchet_*`: on HEAD twenty in-tolerance −20%
    ///         pushes in one tx moved the mark to ~1% of NAV. Now the second push already
    ///         breaches the window's band.
    function test_pushPrice_downwardRatchetInOneWindowReverts() public {
        vm.startPrank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);
        navOracle.pushPrice(collateralAsset, 80e6); // −20% vs the window anchor: at the cap
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 64e6, 2000));
        navOracle.pushPrice(collateralAsset, 64e6);
        vm.stopPrank();

        assertEq(_price(collateralAsset), 80e6);
    }

    function test_pushPrice_upwardRatchetInOneWindowReverts() public {
        vm.startPrank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);
        navOracle.pushPrice(collateralAsset, 120e6);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 144e6, 2000));
        navOracle.pushPrice(collateralAsset, 144e6);
        vm.stopPrank();

        assertEq(_price(collateralAsset), 120e6);
    }

    /// @notice The band is peak-to-trough: after marking up inside a window, a drop is
    ///         measured from the window's high, not from its anchor — so a later drop never
    ///         exceeds the cap relative to any mark a position was opened or checked at.
    function test_pushPrice_dropIsMeasuredFromWindowHigh() public {
        vm.startPrank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);
        navOracle.pushPrice(collateralAsset, 110e6);
        // 110 → 85 is −22.7% from the window high (though only −15% from the anchor).
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 110e6, 85e6, 2000));
        navOracle.pushPrice(collateralAsset, 85e6);
        navOracle.pushPrice(collateralAsset, 88e6); // exactly −20% from the high
        vm.stopPrank();

        (uint256 since, uint256 low, uint256 high) = navOracle.deviationWindowOf(collateralAsset);
        assertEq(since, block.timestamp);
        assertEq(low, 88e6);
        assertEq(high, 110e6);
    }

    function test_pushPrice_windowReanchorsOnCurrentMarkAfterItElapses() public {
        vm.startPrank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);
        navOracle.pushPrice(collateralAsset, 80e6);

        vm.warp(block.timestamp + WINDOW - 1);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 79e6, 2000));
        navOracle.pushPrice(collateralAsset, 79e6);

        // A new window anchors on the current mark (80), so another cap-sized move is allowed.
        vm.warp(block.timestamp + 1);
        navOracle.pushPrice(collateralAsset, 64e6);
        vm.stopPrank();

        assertEq(_price(collateralAsset), 64e6);
        (, uint256 low, uint256 high) = navOracle.deviationWindowOf(collateralAsset);
        assertEq(low, 64e6);
        assertEq(high, 80e6);
    }

    function test_pushPrice_minPushIntervalBlocksSameBlockRestore() public {
        RegisterwerkNavOracle spaced =
            new RegisterwerkNavOracle(ecosystemOracle, address(operatorOrgId), address(quote), 2000, WINDOW, 1 hours);

        vm.startPrank(operatorKey);
        spaced.pushPrice(collateralAsset, 100e6);
        vm.expectRevert(
            abi.encodeWithSelector(
                RegisterwerkNavOracle.PushTooSoon.selector, collateralAsset, block.timestamp + 1 hours
            )
        );
        spaced.pushPrice(collateralAsset, 90e6);

        vm.warp(block.timestamp + 1 hours);
        spaced.pushPrice(collateralAsset, 90e6);
        vm.stopPrank();

        (uint256 pricePerUnit,) = spaced.price(collateralAsset);
        assertEq(pricePerUnit, 90e6);
    }

    // ── D2-02 / T2-11: per-asset pusher scoping ─────────────────────────────────

    function test_pushPrice_pusherOfOneAssetCannotPushAnother() public {
        assertEq(navOracle.pusherOf(collateralAsset), address(feedOrgId));
        assertEq(navOracle.pusherOf(otherAsset), address(operatorOrgId));

        vm.prank(feedKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, feedKey, address(operatorOrgId))
        );
        navOracle.pushPrice(otherAsset, 100e6);
    }

    function test_pushPrice_sameSlugGrantInAnotherOrgReverts() public {
        vm.prank(otherKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherKey, address(feedOrgId))
        );
        navOracle.pushPrice(collateralAsset, 100e6);

        // Also the operator org cannot use the ordinary path once a delegate is assigned —
        // its remedy is the override path (or re-assigning the pusher).
        vm.prank(operatorKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, operatorKey, address(feedOrgId))
        );
        navOracle.pushPrice(collateralAsset, 100e6);
    }

    function test_setAssetPusher_zeroRestoresOperatorDefault() public {
        vm.prank(operatorKey);
        navOracle.setAssetPusher(collateralAsset, address(0));
        assertEq(navOracle.pusherOf(collateralAsset), address(operatorOrgId));

        vm.prank(operatorKey);
        navOracle.pushPrice(collateralAsset, 100e6);
        assertEq(_price(collateralAsset), 100e6);
    }

    function test_pushPrice_pusherOrgStillNeedsPushPermission() public {
        MockOnchainId bareOrgId = new MockOnchainId();
        address bareKey = address(0x24);
        _member(bareOrgId, bareKey);
        vm.prank(operatorKey);
        navOracle.setAssetPusher(otherAsset, address(bareOrgId));

        vm.prank(bareKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, bareKey, pushPricePermission)
        );
        navOracle.pushPrice(otherAsset, 100e6);
    }

    // ── override path: bound to the operator org ────────────────────────────────

    function test_pushPriceWithOverride_bypassesCapAndRequiresSeparatePermission() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        // The ordinary feed key cannot self-authorize an override.
        vm.prank(feedKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, feedKey, address(operatorOrgId))
        );
        navOracle.pushPriceWithOverride(collateralAsset, 500e6);

        // The operator's override key can push a legitimate large repricing past the cap.
        vm.prank(operatorKey);
        navOracle.pushPriceWithOverride(collateralAsset, 500e6);

        assertEq(_price(collateralAsset), 500e6);
        // …and the window re-anchors on it, so ordinary pushes continue from the new level.
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 450e6);
        assertEq(_price(collateralAsset), 450e6);
    }

    function test_overridePath_otherOrgWithOverrideGrantReverts() public {
        vm.startPrank(otherKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherKey, address(operatorOrgId))
        );
        navOracle.pushPriceWithOverride(collateralAsset, 1e6);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherKey, address(operatorOrgId))
        );
        navOracle.setAssetPusher(collateralAsset, address(otherOrgId));
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherKey, address(operatorOrgId))
        );
        navOracle.setMaxDeviationBps(1000);
        vm.stopPrank();
    }

    // ── T2-08b: the cap may only be lowered ─────────────────────────────────────

    function test_setMaxDeviationBps_revertsForNonOverrideCaller() public {
        vm.prank(feedKey);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, feedKey, address(operatorOrgId))
        );
        navOracle.setMaxDeviationBps(1000);
    }

    function test_setMaxDeviationBps_raisingReverts() public {
        vm.prank(operatorKey);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.DeviationCapIncrease.selector, 2000, 6000));
        navOracle.setMaxDeviationBps(6000);
        assertEq(navOracle.maxDeviationBps(), 2000);
    }

    function test_setMaxDeviationBps_loweringTightensPushes() public {
        vm.prank(feedKey);
        navOracle.pushPrice(collateralAsset, 100e6);

        vm.startPrank(operatorKey);
        navOracle.setMaxDeviationBps(1000);
        navOracle.setMaxDeviationBps(1000); // equal is a no-op, not an increase
        vm.stopPrank();
        assertEq(navOracle.maxDeviationBps(), 1000);

        vm.prank(feedKey);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 85e6, 1000));
        navOracle.pushPrice(collateralAsset, 85e6);
    }

    // ── B2-05 / T2-12: quote denomination + constructor validation ──────────────

    function test_quoteTokenAndDecimals() public view {
        assertEq(navOracle.quoteToken(), address(quote));
        assertEq(navOracle.quoteDecimals(), 6);
        assertEq(IRepoOracle(address(navOracle)).quoteToken(), address(quote));
        assertEq(navOracle.operatorOrg(), address(operatorOrgId));
        assertEq(navOracle.deviationWindow(), WINDOW);
    }

    function test_constructor_rejectsInvalidParameters() public {
        vm.expectRevert(RegisterwerkGated.ZeroOperatingOrg.selector);
        new RegisterwerkNavOracle(ecosystemOracle, address(0), address(quote), 2000, WINDOW, 0);

        vm.expectRevert(RegisterwerkNavOracle.ZeroQuoteToken.selector);
        new RegisterwerkNavOracle(ecosystemOracle, address(operatorOrgId), address(0), 2000, WINDOW, 0);

        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.InvalidDeviationWindow.selector, 0));
        new RegisterwerkNavOracle(ecosystemOracle, address(operatorOrgId), address(quote), 2000, 0, 0);

        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.InvalidMaxDeviation.selector, 10_001));
        new RegisterwerkNavOracle(ecosystemOracle, address(operatorOrgId), address(quote), 10_001, WINDOW, 0);
    }
}

/// @notice Ports of `LendingPoc.test_navRatchet_sameTxLiquidation` / `test_navRatchet_overBorrow`
///         (D2-02) against a real {EwpgRepoMarket}: a delegated `PUSH_PRICE`-only key can no
///         longer ratchet the mark in one tx to liquidate at a fake price or to over-borrow.
contract RegisterwerkNavOracleRatchetTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle eco;
    MockClaimIssuer kycIssuer;
    MockStablecoin loanToken;
    MockStablecoin bond;
    RegisterwerkNavOracle nav;
    EwpgRepoMarket market;

    address operator = address(0x1);
    address alice = address(0xA11CE); // honest borrower (bank org)
    address desk = address(0xDE5C); // delegated NAV admin for `bond`: PUSH_PRICE only
    address lender = address(0x11);

    function _org(address wallet) internal returns (MockOnchainId id) {
        id = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(id), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("MEMBER");
        orgRegistry.addMember(address(id), wallet, roles, "");
        vm.stopPrank();
    }

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        eco = new PermissionOracle(operator, orgRegistry, permissions, tir);
        kycIssuer = new MockClaimIssuer();
        uint256[] memory topics = new uint256[](1);
        topics[0] = 1;
        vm.prank(operator);
        tir.addTrustedIssuer(address(kycIssuer), topics);

        loanToken = new MockStablecoin("AUEUR", "AUEUR", 6);
        bond = new MockStablecoin("Bond", "BOND", 0);
        MockOnchainId operatorOrg = new MockOnchainId();
        nav = new RegisterwerkNavOracle(eco, address(operatorOrg), address(loanToken), 2000, 1 days, 0);
        // lltv 75% keeps lltv×(1+bonus) ≤ 1 − maxDeviation (T2-08), unlike the PoC's 80%.
        market = new EwpgRepoMarket(
            eco,
            MarketParams(
                address(operatorOrg), operator, loanToken, bond, nav, 7000, 7500, 500, 0.02e18, 0.18e18, 1 days, 2 days
            )
        );

        MockOnchainId bank = _org(alice);
        bytes32 b = market.BORROW();
        vm.prank(operator);
        permissions.grantToOrg(address(bank), b);
        bank.addClaim(1, address(kycIssuer), hex"01", hex"02");

        MockOnchainId deskOrg = _org(desk);
        bytes32 pp = nav.PUSH_PRICE();
        vm.prank(operator);
        permissions.grantToOrg(address(deskOrg), pp); // NOT override

        address operatorKey = address(0x0B);
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(operatorOrg), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("OPERATOR");
        orgRegistry.addMember(address(operatorOrg), operatorKey, roles, "");
        permissions.grantToOrg(address(operatorOrg), nav.OVERRIDE_PRICE());
        vm.stopPrank();
        vm.prank(operatorKey);
        nav.setAssetPusher(address(bond), address(deskOrg));

        vm.prank(desk);
        nav.pushPrice(address(bond), 100e6);

        loanToken.mint(lender, 1_000_000e6);
        vm.startPrank(lender);
        loanToken.approve(address(market), type(uint256).max);
        market.supply(1_000_000e6);
        vm.stopPrank();

        bond.mint(alice, 1_000);
        vm.startPrank(alice);
        bond.approve(address(market), type(uint256).max);
        market.pledgeAndBorrow(1_000, 70_000e6); // 100k collateral, 70k debt (70% LTV, healthy)
        vm.stopPrank();
    }

    function test_navRatchet_sameTxLiquidation_blocked() public {
        loanToken.mint(desk, 10_000e6);
        vm.startPrank(desk);
        loanToken.approve(address(market), type(uint256).max);
        nav.pushPrice(address(bond), 80e6); // one cap-sized mark: HF = 80k·0.75/70k = 0.857
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 64e6, 2000));
        nav.pushPrice(address(bond), 64e6);
        vm.stopPrank();

        // The deepest mark reachable in this window is the cap: a liquidation there seizes at
        // most repaid×(1+bonus)/80 per unit — never the whole book for ~1.2k as on HEAD.
        (uint256 px,) = nav.price(address(bond));
        assertEq(px, 80e6);
        uint256 lenderClaimBefore = market.balanceOf(lender);
        vm.prank(desk);
        (uint256 repaid, uint256 seized) = market.liquidate(alice, 1_200e6);
        // Whole units, rounded up: ⌈1.2k × 1.05 / 80⌉ = 16 units out of 1,000, paid at 80/1.05 each.
        assertEq(seized, 16);
        assertEq(repaid, (16 * uint256(80e6) * 10_000 + 10_499) / 10_500);
        assertGe(market.balanceOf(lender), lenderClaimBefore); // no bad debt socialized
    }

    function test_navRatchet_overBorrow_blocked() public {
        vm.startPrank(desk);
        nav.pushPrice(address(bond), 120e6);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkNavOracle.ExcessiveDeviation.selector, 100e6, 144e6, 2000));
        nav.pushPrice(address(bond), 144e6);
        vm.stopPrank();

        bond.mint(alice, 1_000);
        vm.prank(alice);
        vm.expectRevert(); // 2000 units at the capped 120 mark support 168k, not the pool's 930k
        market.pledgeAndBorrow(1_000, 930_000e6 - 1);
    }
}
