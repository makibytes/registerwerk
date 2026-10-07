// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC7540.sol";
import {MockUSDC7540} from "./EwpgERC7540Test.t.sol";

/// @dev Forward pricing with a dealing cut-off (decision T1-07).
///      A request deals at the first cut-off boundary strictly after it was placed; it may only be
///      settled at a NAV that was struck at or after that dealing point, so a request placed after
///      the cut-off can never be settled at a price that was already known (late trading).
contract EwpgERC7540DealingCutoffTest is Test {
    EwpgERC7540 vault;
    MockUSDC7540 usdc;

    address registry = makeAddr("registry");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");

    /// @dev 00:00 UTC of an arbitrary day.
    uint256 constant DAY0 = 20833 * 86400;
    uint256 constant CUTOFF = 12 hours; // 12:00 UTC

    event DealingCutoffUpdated(
        uint256 oldCutoffSecondsOfDay, uint256 oldPeriodSecs, uint256 newCutoffSecondsOfDay, uint256 newPeriodSecs
    );

    function setUp() public {
        assertEq(DAY0 % 1 days, 0);
        vm.warp(DAY0);
        usdc = new MockUSDC7540();
        usdc.transfer(alice, 1_000_000e6);
        usdc.transfer(bob, 1_000_000e6);

        vm.startPrank(registry);
        vault = new EwpgERC7540(usdc, "Registerwerk Async Fund", "RWAF", registry, keccak256("dealing"));
        vault.whitelist(alice);
        vault.whitelist(bob);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.stopPrank();

        // Alice already holds shares (settled before any cut-off was configured).
        uint256 id = _requestDeposit(alice, 100_000e6);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    function _configure() internal {
        vm.prank(registry);
        vault.setDealingCutoff(CUTOFF, 1 days);
    }

    function _requestDeposit(address who, uint256 assets) internal returns (uint256 id) {
        vm.startPrank(who);
        usdc.approve(address(vault), assets);
        id = vault.requestDeposit(assets, who, who);
        vm.stopPrank();
    }

    function _requestRedeem(address who, uint256 shares) internal returns (uint256 id) {
        vm.prank(who);
        id = vault.requestRedeem(shares, who, who);
    }

    function _strike(uint256 nav) internal {
        vm.prank(registry);
        vault.setNavPerShare(nav, block.timestamp, bytes32(0));
    }

    function _expectNotAfterDealingPoint(uint256 id, uint256 dealingPoint, uint256 struckAt) internal {
        vm.expectRevert(
            abi.encodeWithSelector(EwpgERC7540.NavNotStruckAfterDealingPoint.selector, id, dealingPoint, struckAt)
        );
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    function test_defaults_unconfigured() public view {
        assertFalse(vault.dealingCutoffConfigured());
        assertEq(vault.dealingCutoffSecondsOfDay(), 0);
        assertEq(vault.dealingPeriodSecs(), 1 days);
        assertEq(vault.nextDealingPoint(), 0);
    }

    function test_setDealingCutoff_onlyRegistry() public {
        vm.prank(alice);
        vm.expectRevert("EwpgCompliance: caller is not registry");
        vault.setDealingCutoff(CUTOFF, 1 days);
    }

    function test_setDealingCutoff_validatesRange() public {
        vm.startPrank(registry);
        vm.expectRevert("EwpgERC7540: cut-off out of range");
        vault.setDealingCutoff(1 days, 1 days);
        vm.expectRevert("EwpgERC7540: zero dealing period");
        vault.setDealingCutoff(CUTOFF, 0);
        vault.setDealingCutoff(1 days - 1, 1 days); // upper bound is accepted
        vm.stopPrank();
    }

    function test_setDealingCutoff_emitsAndStores() public {
        vm.expectEmit(address(vault));
        emit DealingCutoffUpdated(0, 1 days, CUTOFF, 2 days);
        vm.prank(registry);
        vault.setDealingCutoff(CUTOFF, 2 days);

        assertTrue(vault.dealingCutoffConfigured());
        assertEq(vault.dealingCutoffSecondsOfDay(), CUTOFF);
        assertEq(vault.dealingPeriodSecs(), 2 days);
    }

    function test_navStruckAt_recordsStrikeTimestamp() public {
        assertEq(vault.navStruckAt(), DAY0);
        vm.warp(DAY0 + 5 hours);
        _strike(1.1e18);
        assertEq(vault.navStruckAt(), DAY0 + 5 hours);
    }

    // ── Unconfigured vault keeps the legacy behaviour (no error, no dealing point) ──

    function test_unconfigured_fulfilsAtAlreadyStruckNav() public {
        uint256 id = _requestDeposit(bob, 10_000e6);
        assertEq(vault.dealingPointOf(id), 0);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
        assertEq(vault.balanceOf(bob), 10_000e6);
    }

    // ── Forward pricing ───────────────────────────────────────────────────────

    function test_requestBeforeCutoff_dealsAtTodaysCutoff() public {
        _configure();
        vm.warp(DAY0 + 10 hours);
        uint256 id = _requestDeposit(bob, 10_000e6);
        assertEq(vault.dealingPointOf(id), DAY0 + CUTOFF);

        // A NAV struck before the cut-off is not the dealing price.
        vm.warp(DAY0 + 11 hours);
        _strike(1.05e18);
        _expectNotAfterDealingPoint(id, DAY0 + CUTOFF, DAY0 + 11 hours);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);

        // Struck after the cut-off: accepted at that NAV.
        vm.warp(DAY0 + CUTOFF + 1);
        _strike(1.06e18);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
        assertEq(vault.balanceOf(bob), uint256(10_000e6) * 1e18 / 1.06e18);
    }

    function test_requestAfterCutoff_needsNextDaysNav() public {
        _configure();
        vm.warp(DAY0 + 13 hours);
        uint256 id = _requestDeposit(bob, 10_000e6);
        uint256 dealingPoint = DAY0 + 1 days + CUTOFF;
        assertEq(vault.dealingPointOf(id), dealingPoint);

        // Today's post-cut-off NAV (the one that closed today's dealing) is not available to it.
        vm.warp(DAY0 + 14 hours);
        _strike(1.07e18);
        _expectNotAfterDealingPoint(id, dealingPoint, DAY0 + 14 hours);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);

        // Even a strike a second before tomorrow's cut-off is too early.
        vm.warp(dealingPoint - 1);
        _strike(1.08e18);
        _expectNotAfterDealingPoint(id, dealingPoint, dealingPoint - 1);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);

        // Tomorrow's post-cut-off strike settles it.
        vm.warp(dealingPoint + 1 hours);
        _strike(1.09e18);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
        assertEq(vault.balanceOf(bob), uint256(10_000e6) * 1e18 / 1.09e18);
    }

    function test_lateRequest_cannotDealAtAlreadyStruckNav() public {
        _configure();
        // Today's NAV is struck right after the cut-off; the operator now knows it.
        vm.warp(DAY0 + CUTOFF + 10 minutes);
        _strike(1.2e18);

        // A request placed afterwards (the late trade) must not settle at that known price.
        vm.warp(DAY0 + CUTOFF + 20 minutes);
        uint256 id = _requestDeposit(bob, 10_000e6);
        uint256 dealingPoint = DAY0 + 1 days + CUTOFF;
        _expectNotAfterDealingPoint(id, dealingPoint, DAY0 + CUTOFF + 10 minutes);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);

        // Same for a late redemption.
        uint256 redeemId = _requestRedeem(alice, 1_000e6);
        _expectNotAfterDealingPoint(redeemId, dealingPoint, DAY0 + CUTOFF + 10 minutes);
        vm.prank(registry);
        vault.fulfillRedeemRequest(redeemId);
    }

    function test_redeemRequest_dealsAtNavStruckAfterDealingPoint() public {
        _configure();
        vm.warp(DAY0 + 9 hours);
        uint256 id = _requestRedeem(alice, 10_000e6);
        assertEq(vault.dealingPointOf(id), DAY0 + CUTOFF);

        vm.warp(DAY0 + 10 hours);
        _strike(1.5e18);
        _expectNotAfterDealingPoint(id, DAY0 + CUTOFF, DAY0 + 10 hours);
        vm.prank(registry);
        vault.fulfillRedeemRequest(id);

        vm.warp(DAY0 + CUTOFF);
        _strike(1.1e18);
        uint256 before = usdc.balanceOf(alice);
        vm.prank(registry);
        vault.fulfillRedeemRequest(id);
        assertEq(usdc.balanceOf(alice) - before, 11_000e6);
    }

    function test_minSettlementDelay_stillEnforced() public {
        _configure();
        vm.prank(registry);
        vault.setMinSettlementDelay(2 days);

        vm.warp(DAY0 + 10 hours);
        uint256 id = _requestDeposit(bob, 10_000e6);
        vm.warp(DAY0 + CUTOFF + 1);
        _strike(1e18);
        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: settlement delay not elapsed");
        vault.fulfillDepositRequest(id);

        vm.warp(DAY0 + 10 hours + 2 days);
        _strike(1e18);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
    }

    function test_nextDealingPoint_tracksTheClock() public {
        _configure();
        vm.warp(DAY0 + 1 hours);
        assertEq(vault.nextDealingPoint(), DAY0 + CUTOFF);
        vm.warp(DAY0 + CUTOFF);
        assertEq(vault.nextDealingPoint(), DAY0 + 1 days + CUTOFF); // strictly after
        vm.warp(DAY0 + CUTOFF + 1);
        assertEq(vault.nextDealingPoint(), DAY0 + 1 days + CUTOFF);
    }

    // ── Boundary equality ─────────────────────────────────────────────────────

    function test_boundary_requestAtCutoffDealsTomorrow_requestJustBeforeDealsToday() public {
        _configure();
        vm.warp(DAY0 + CUTOFF - 1);
        uint256 justBefore = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(justBefore), DAY0 + CUTOFF);

        vm.warp(DAY0 + CUTOFF);
        uint256 atCutoff = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(atCutoff), DAY0 + 1 days + CUTOFF);

        // 00:00 UTC is before that day's cut-off, so it deals at that day's cut-off.
        vm.warp(DAY0 + 1 days);
        uint256 midnight = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(midnight), DAY0 + 1 days + CUTOFF);
    }

    function test_boundary_strikeExactlyAtDealingPointIsAccepted() public {
        _configure();
        vm.warp(DAY0 + 10 hours);
        uint256 id = _requestDeposit(bob, 1_000e6);

        vm.warp(DAY0 + CUTOFF - 1);
        _strike(1e18);
        _expectNotAfterDealingPoint(id, DAY0 + CUTOFF, DAY0 + CUTOFF - 1);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);

        vm.warp(DAY0 + CUTOFF);
        _strike(1e18);
        vm.prank(registry);
        vault.fulfillDepositRequest(id);
    }

    function test_boundary_cutoffAtMidnightAndEndOfDay() public {
        vm.prank(registry);
        vault.setDealingCutoff(0, 1 days);
        vm.warp(DAY0 + 1 hours);
        uint256 a = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(a), DAY0 + 1 days);

        vm.prank(registry);
        vault.setDealingCutoff(1 days - 1, 1 days);
        uint256 b = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(b), DAY0 + 1 days - 1);
    }

    // ── Cut-off changes are not retroactive ───────────────────────────────────

    function test_cutoffChange_doesNotMoveExistingDealingPoints() public {
        _configure();
        vm.warp(DAY0 + 10 hours);
        uint256 first = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(first), DAY0 + CUTOFF);

        vm.prank(registry);
        vault.setDealingCutoff(18 hours, 1 days);
        assertEq(vault.dealingPointOf(first), DAY0 + CUTOFF); // unchanged

        uint256 second = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(second), DAY0 + 18 hours);

        // The first request settles at a NAV struck after ITS dealing point.
        vm.warp(DAY0 + 13 hours);
        _strike(1e18);
        vm.prank(registry);
        vault.fulfillDepositRequest(first);
        _expectNotAfterDealingPoint(second, DAY0 + 18 hours, DAY0 + 13 hours);
        vm.prank(registry);
        vault.fulfillDepositRequest(second);
    }

    function test_requestsBeforeConfiguration_keepLegacyDealingPoint() public {
        uint256 legacy = _requestDeposit(bob, 1_000e6);
        _configure();
        assertEq(vault.dealingPointOf(legacy), 0);
        vm.prank(registry);
        vault.fulfillDepositRequest(legacy); // legacy request: struck NAV is accepted
    }

    // ── Fuzz ──────────────────────────────────────────────────────────────────

    /// @dev For any request time and strike time, fulfilment is accepted iff the strike is at or
    ///      after the first daily cut-off boundary strictly after the request.
    function testFuzz_fulfilAcceptedIffStrikeAtOrAfterDealingPoint(uint256 requestTime, uint256 cutoff, uint256 strikeDelta)
        public
    {
        cutoff = bound(cutoff, 0, 1 days - 1);
        requestTime = bound(requestTime, DAY0, DAY0 + 3650 days);
        strikeDelta = bound(strikeDelta, 0, 3 days);

        vm.prank(registry);
        vault.setDealingCutoff(cutoff, 1 days);

        // Independent oracle for the daily case.
        uint256 expected = (requestTime / 1 days) * 1 days + cutoff;
        if (expected <= requestTime) expected += 1 days;

        vm.warp(requestTime);
        uint256 id = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(id), expected);
        assertGt(expected, requestTime);
        assertLe(expected - requestTime, 1 days);

        uint256 strikeTime = requestTime + strikeDelta;
        vm.warp(strikeTime);
        _strike(1e18);

        vm.prank(registry);
        if (strikeTime >= expected) {
            vault.fulfillDepositRequest(id);
            (,,, bool pending) = vault.depositRequest(id);
            assertFalse(pending);
        } else {
            _expectNotAfterDealingPoint(id, expected, strikeTime);
            vault.fulfillDepositRequest(id);
        }
    }

    /// @dev Structural properties of the dealing point for any period: it is on the boundary grid,
    ///      strictly after the request, and no earlier boundary was skipped.
    function testFuzz_dealingPointProperties(uint256 requestTime, uint256 cutoff, uint256 period) public {
        cutoff = bound(cutoff, 0, 1 days - 1);
        period = bound(period, 1, 30 days);
        requestTime = bound(requestTime, DAY0, DAY0 + 3650 days);

        vm.prank(registry);
        vault.setDealingCutoff(cutoff, period);
        vm.warp(requestTime);
        uint256 dp = vault.nextDealingPoint();

        assertGt(dp, requestTime);
        assertGe(dp, cutoff);
        assertEq((dp - cutoff) % period, 0);
        assertTrue(dp - cutoff < period || dp - period <= requestTime); // previous boundary not strictly after
        assertLe(dp - requestTime, period);

        uint256 id = _requestDeposit(bob, 1_000e6);
        assertEq(vault.dealingPointOf(id), dp);
    }
}
