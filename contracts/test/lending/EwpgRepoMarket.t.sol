// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/lending/EwpgRepoMarket.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for the isolated-market mechanics of {EwpgRepoMarket}: single
///         collateral/loan pair, oracle-fed pricing, reserve factor, and partial (close-factor)
///         liquidation. Gating behavior mirrors `test/examples/EwpgRepoFacility.t.sol` — only
///         the mechanics that actually differ (isolation, reserves, partial liquidation,
///         staleness) get fresh coverage here to avoid duplicating that suite.
contract EwpgRepoMarketTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    MockOnchainId orgId;
    MockClaimIssuer kycIssuer;

    MockStablecoin loanToken; // 6-decimals EMT
    MockStablecoin collateralToken; // security-token leg stand-in, 0 decimals
    RegisterwerkNavOracle navOracle;
    EwpgRepoMarket market;

    address operator = address(0x1);
    address alice = address(0x3); // borrower: KYC'd org member
    address mallory = address(0x66); // unbound wallet
    address lender1 = address(0x11);
    address liquidator = address(0x44);
    address treasury = address(0x99);

    bytes32 borrowPermission;
    bytes32 configurePermission;
    bytes32 reconcilePermission;
    bytes32 pushPricePermission;
    bytes32 overridePricePermission;
    uint256 topicKyc;

    uint256 constant PRICE_PER_UNIT = 100e6; // 100.00 loan-token base units per collateral unit
    uint256 constant MAX_LTV_BPS = 7000; // 70% — origination cap, strictly below LLTV_BPS
    uint256 constant LLTV_BPS = 8000; // 80% — liquidation threshold
    uint256 constant LIQ_BONUS_BPS = 500; // 5%
    uint256 constant BASE_RATE_WAD = 0.02e18;
    uint256 constant SLOPE_WAD = 0.18e18;
    uint256 constant GRACE_PERIOD = 2 hours;
    // 15%: LLTV_BPS × (1 + LIQ_BONUS_BPS) = 0.84 must not exceed 1 − maxDeviation (T2-08).
    uint256 constant ORACLE_MAX_DEVIATION_BPS = 1500;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        orgId = new MockOnchainId();
        navOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(orgId), address(loanToken), ORACLE_MAX_DEVIATION_BPS, 1 days, 0
        );

        // no staleness check for these tests
        market = new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));

        borrowPermission = market.BORROW();
        configurePermission = market.CONFIGURE();
        reconcilePermission = market.RECONCILE();
        topicKyc = market.TOPIC_KYC();
        pushPricePermission = navOracle.PUSH_PRICE();
        overridePricePermission = navOracle.OVERRIDE_PRICE();

        kycIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), borrowPermission);
        permissions.grantToOrg(address(orgId), configurePermission);
        permissions.grantToOrg(address(orgId), reconcilePermission);
        permissions.grantToOrg(address(orgId), pushPricePermission);
        permissions.grantToOrg(address(orgId), overridePricePermission);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicKyc;
        tir.addTrustedIssuer(address(kycIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(topicKyc, address(kycIssuer), hex"01", hex"02");

        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), PRICE_PER_UNIT);

        loanToken.mint(lender1, 1_000_000e6);
        loanToken.mint(liquidator, 1_000_000e6);
        collateralToken.mint(alice, 1_000);

        vm.prank(lender1);
        loanToken.approve(address(market), type(uint256).max);
        vm.prank(liquidator);
        loanToken.approve(address(market), type(uint256).max);
        vm.prank(alice);
        loanToken.approve(address(market), type(uint256).max);
        vm.prank(alice);
        collateralToken.approve(address(market), type(uint256).max);
    }

    function _params(
        IRepoOracle oracle_,
        uint256 maxLtvBps,
        uint256 lltvBps,
        uint256 bonusBps,
        uint256 maxPriceAge,
        uint256 grace
    ) private view returns (MarketParams memory) {
        return _params(collateralToken, oracle_, maxLtvBps, lltvBps, bonusBps, maxPriceAge, grace);
    }

    function _params(
        IERC20 collateral,
        IRepoOracle oracle_,
        uint256 maxLtvBps,
        uint256 lltvBps,
        uint256 bonusBps,
        uint256 maxPriceAge,
        uint256 grace
    ) private view returns (MarketParams memory) {
        return MarketParams(
            address(orgId), treasury, loanToken, collateral, oracle_, maxLtvBps, lltvBps, bonusBps, BASE_RATE_WAD,
            SLOPE_WAD, maxPriceAge, grace
        );
    }

    /// @dev One unit's price at the liquidation discount (floor).
    function _discounted(uint256 price) private pure returns (uint256) {
        return price * 10_000 / (10_000 + LIQ_BONUS_BPS);
    }

    /// @dev What a liquidator pays for `units` whole units against `debt` outstanding, as the
    ///      market prices it: the value less the bonus (rounded up) while that stays below the
    ///      debt, otherwise the value less the bonus on the debt closed.
    function _paymentFor(uint256 units, uint256 price, uint256 debt) private pure returns (uint256) {
        uint256 value = units * price;
        uint256 discounted = (value * 10_000 + 10_000 + LIQ_BONUS_BPS - 1) / (10_000 + LIQ_BONUS_BPS);
        return discounted > debt ? value - debt * LIQ_BONUS_BPS / 10_000 : discounted;
    }

    /// @dev Simulates an issuer/agent forcedTransfer of `units` out of the market's custody.
    function _forceOut(EwpgRepoMarket m, uint256 units) private {
        vm.prank(address(m));
        collateralToken.transfer(address(0xF0), units);
    }

    // ── lender side ──────────────────────────────────────────────────────────

    function test_supply_isPermissionless() public {
        loanToken.mint(mallory, 1_000e6);
        vm.prank(mallory);
        loanToken.approve(address(market), type(uint256).max);

        vm.prank(mallory);
        market.supply(1_000e6);
        assertEq(market.balanceOf(mallory), 1_000e6);
    }

    function test_withdraw_returnsFundsAndBurnsShares() public {
        vm.prank(lender1);
        market.supply(100_000e6);

        uint256 before = loanToken.balanceOf(lender1);
        vm.prank(lender1);
        market.withdraw(40_000e6);

        assertEq(loanToken.balanceOf(lender1), before + 40_000e6);
        assertEq(market.balanceOf(lender1), 60_000e6);
    }

    function test_withdraw_roundsScaledSharesUpAfterInterestAccrues() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.warp(block.timestamp + 365 days);
        uint256 scaledBefore = market.scaledDepositOf(lender1);
        uint256 assetsBefore = loanToken.balanceOf(lender1);

        vm.prank(lender1);
        uint256 scaledBurned = market.withdraw(1);

        assertGt(market.liquidityIndex(), 1e18);
        assertEq(scaledBurned, 1, "non-zero withdrawal must burn a scaled share");
        assertEq(market.scaledDepositOf(lender1), scaledBefore - 1);
        assertEq(loanToken.balanceOf(lender1), assetsBefore + 1);
    }

    // ── borrower side: gating ────────────────────────────────────────────────

    function test_pledgeAndBorrow_revertsForUnboundWallet() public {
        vm.prank(lender1);
        market.supply(100_000e6);

        collateralToken.mint(mallory, 100);
        vm.prank(mallory);
        collateralToken.approve(address(market), type(uint256).max);

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, borrowPermission));
        market.pledgeAndBorrow(100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsWithoutKycClaim() public {
        kycIssuer.setValid(false);
        vm.prank(lender1);
        market.supply(100_000e6);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, topicKyc));
        market.pledgeAndBorrow(100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsWhenBorrowPaused() public {
        vm.prank(lender1);
        market.supply(100_000e6);

        vm.prank(alice);
        market.setBorrowPaused(true);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.BorrowIsPaused.selector);
        market.pledgeAndBorrow(100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsWhenUnpriced() public {
        RegisterwerkNavOracle freshOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(orgId), address(loanToken), ORACLE_MAX_DEVIATION_BPS, 1 days, 0
        );
        EwpgRepoMarket unpricedMarket = new EwpgRepoMarket(ecosystemOracle, _params(freshOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));
        vm.prank(lender1);
        loanToken.approve(address(unpricedMarket), type(uint256).max);
        vm.prank(lender1);
        unpricedMarket.supply(100_000e6);

        vm.prank(alice);
        collateralToken.approve(address(unpricedMarket), type(uint256).max);
        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.PriceNotSet.selector);
        unpricedMarket.pledgeAndBorrow(100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsOnStalePrice() public {
        EwpgRepoMarket staleMarket = new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 1 hours, GRACE_PERIOD));
        vm.prank(lender1);
        loanToken.approve(address(staleMarket), type(uint256).max);
        vm.prank(lender1);
        staleMarket.supply(100_000e6);
        vm.prank(alice);
        collateralToken.approve(address(staleMarket), type(uint256).max);

        vm.warp(block.timestamp + 2 hours);
        vm.prank(alice);
        vm.expectRevert(); // StalePrice(updatedAt, now) — exact timestamps not asserted here
        staleMarket.pledgeAndBorrow(100, 1_000e6);
    }

    // ── borrowing mechanics ──────────────────────────────────────────────────

    function test_pledgeAndBorrow_succeedsWithinLltvAndEscrowsCollateral() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);

        // 100 units * 100e6 price = 10_000e6 collateral value; 70% max-LTV (origination cap,
        // strictly below the 80% liquidation threshold) = 7_000e6 max borrow.
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        assertEq(collateralToken.balanceOf(address(market)), 100);
        assertEq(collateralToken.balanceOf(alice), 900);
        assertEq(market.debtOf(alice), 7_000e6);
        assertEq(loanToken.balanceOf(alice), 7_000e6);
    }

    function test_pledgeAndBorrow_revertsAboveLltv() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ExceedsLltv.selector);
        market.pledgeAndBorrow(100, 7_001e6);
    }

    function test_pledgeAndBorrow_revertsBeyondPoolLiquidity() public {
        vm.prank(lender1);
        market.supply(1_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.InsufficientPoolLiquidity.selector);
        market.pledgeAndBorrow(100, 5_000e6);
    }

    function test_addCollateral_improvesAnExistingPositionWithoutBorrowPermission() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), borrowPermission);

        vm.prank(alice);
        market.addCollateral(25);

        (uint256 collateralAmount,) = market.positions(alice);
        assertEq(collateralAmount, 125);
        assertEq(collateralToken.balanceOf(address(market)), 125);
    }

    function test_addCollateral_rejectsWalletWithoutAnOutstandingLoan() public {
        vm.prank(mallory);
        vm.expectRevert(EwpgRepoMarket.NoOutstandingDebt.selector);
        market.addCollateral(1);
    }

    function test_withdrawCollateral_releasesOnlyExcessAboveOriginationBuffer() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(125, 7_000e6);

        vm.prank(alice);
        market.withdrawCollateral(25);

        (uint256 collateralAmount,) = market.positions(alice);
        assertEq(collateralAmount, 100);
        assertEq(collateralToken.balanceOf(alice), 900);
    }

    function test_withdrawCollateral_revertsWhenItWouldExceedOriginationLtv() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ExceedsLltv.selector);
        market.withdrawCollateral(1);
    }

    // ── reserve factor ───────────────────────────────────────────────────────

    function test_reserveFactor_splitsInterestBetweenReservesAndDepositors() public {
        vm.prank(alice);
        market.setReserveFactor(2000); // 20%

        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.warp(block.timestamp + 365 days);

        uint256 depositorClaim = market.balanceOf(lender1);
        uint256 debtNow = market.debtOf(alice);
        uint256 interestAccrued = debtNow - 7_000e6;

        assertGt(interestAccrued, 0);
        assertGt(depositorClaim, 1_000_000e6, "depositor still earns most of the interest");
        // Depositor claim growth should be materially less than total interest since 20% is reserved.
        assertLt(depositorClaim - 1_000_000e6, interestAccrued);
    }

    function test_setReserveFactor_revertsAboveMax() public {
        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.InvalidReserveFactor.selector);
        market.setReserveFactor(2501);
    }

    function test_withdrawReserves_paysOutAccumulatedReserves() public {
        vm.prank(alice);
        market.setReserveFactor(2000);
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.warp(block.timestamp + 365 days);
        // Trigger accrual via a state-changing call. A 1-unit repay no longer works: once the
        // borrow index exceeds WAD, one debt share is worth more than one asset unit, so a
        // sub-share payment would move cash without burning a share and is rejected. Repay a
        // whole loan-token unit instead.
        vm.prank(alice);
        market.repay(1e6);

        uint256 reserves = market.totalReserves();
        assertGt(reserves, 0);

        vm.expectEmit(true, false, false, true);
        emit EwpgRepoMarket.ReservesWithdrawn(treasury, reserves);
        vm.prank(alice);
        market.withdrawReserves(reserves);
        assertEq(loanToken.balanceOf(treasury), reserves, "reserves only ever go to the immutable treasury");
    }

    // ── repay ────────────────────────────────────────────────────────────────

    function test_repay_fullyReturnsAllCollateral() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(alice);
        uint256 returned = market.repay(7_000e6);

        assertEq(returned, 100);
        assertEq(collateralToken.balanceOf(alice), 1_000);
        assertEq(market.debtOf(alice), 0);
    }

    function test_repay_isNotEcosystemGated() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), borrowPermission);

        vm.prank(alice);
        market.repay(7_000e6);
        assertEq(market.debtOf(alice), 0);
    }

    // ── repayDebtOnly / claimCollateral ──────────────────────────────────────

    function test_repayDebtOnly_partialKeepsAllCollateralPledged() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(alice);
        uint256 paid = market.repayDebtOnly(3_000e6);

        assertApproxEqAbs(paid, 3_000e6, 1);
        assertApproxEqAbs(market.debtOf(alice), 4_000e6, 1);
        (uint256 collateral,) = market.positions(alice);
        assertEq(collateral, 100);
        assertEq(collateralToken.balanceOf(alice), 900);
    }

    function test_repayDebtOnly_isNotEcosystemGated() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), borrowPermission);

        vm.prank(alice);
        market.repayDebtOnly(7_000e6);
        assertEq(market.debtOf(alice), 0);
    }

    function test_repayDebtOnly_revertsWithoutDebt() public {
        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.NoOutstandingDebt.selector);
        market.repayDebtOnly(1e6);
    }

    function test_claimCollateral_revertsWhileDebtOutstanding() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        vm.prank(alice);
        market.repayDebtOnly(1_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.OutstandingDebt.selector);
        market.claimCollateral();
    }

    function test_claimCollateral_revertsWithNothingToClaim() public {
        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ZeroAmount.selector);
        market.claimCollateral();
    }

    function test_repayDebtOnly_thenClaimCollateral_releasesEverything() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        vm.warp(block.timestamp + 30 days);
        loanToken.mint(alice, 1_000e6); // cover accrued interest

        vm.prank(alice);
        market.repayDebtOnly(type(uint256).max);
        assertEq(market.totalScaledDebt(), 0);

        vm.expectEmit(true, false, false, true);
        emit EwpgRepoMarket.CollateralWithdrawn(alice, 100, 0);
        vm.prank(alice);
        assertEq(market.claimCollateral(), 100);
        assertEq(collateralToken.balanceOf(alice), 1_000);
        assertEq(collateralToken.balanceOf(address(market)), 0);
    }

    // ── liquidation (partial / close-factor) ────────────────────────────────

    function test_liquidate_revertsForHealthyPosition() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(liquidator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoMarket.PositionHealthy.selector, alice));
        market.liquidate(alice, 7_000e6);
    }

    function test_liquidate_onlyClosesUpToCloseFactor() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // Drop price so the position is unhealthy but only mildly so — 100 * 85e6 * 0.80 /
        // 7_000e6 = 0.971 — below 1.0 (liquidatable) but at/above
        // FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD (0.95), so the ordinary CLOSE_FACTOR_BPS
        // applies rather than a full close ('s severity-scaled close factor).
        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), 85e6);

        uint256 debtBefore = market.debtOf(alice);
        vm.prank(liquidator);
        (uint256 debtRepaid,) = market.liquidate(alice, debtBefore); // requests full debt

        // Close factor caps a single call at 50% of outstanding debt, plus less than one whole
        // unit bought at the discounted mark (85e6 / 1.05) from rounding the units up.
        assertGe(debtRepaid, debtBefore / 2);
        assertLt(debtRepaid, debtBefore / 2 + _discounted(85e6) + 1);
        assertGt(market.debtOf(alice), 0, "position still open after partial liquidation");
    }

    function test_liquidate_canFullyUnwindOverRepeatedCalls() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // A 50% crash exceeds the oracle's ordinary deviation cap by design (see
        // RegisterwerkNavOracle.t.sol) — simulating one here requires the override path, the
        // same as a real deep NAV correction would. A crash this severe also lands well below
        // FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD, so MAX_CLOSE_FACTOR_BPS (100%) applies
        // rather than the ordinary 50% — in practice this now unwinds in a single
        // call, but the loop is kept as a bound so the position can never be *permanently*
        // stuck with unclosable dust regardless of tuning.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 50e6); // deeply unhealthy

        for (uint256 i = 0; i < 60 && market.debtOf(alice) > 0; i++) {
            uint256 debt = market.debtOf(alice);
            vm.prank(liquidator);
            market.liquidate(alice, debt);
        }

        assertEq(market.debtOf(alice), 0, "fully unwound after repeated partial liquidations");
    }

    function test_liquidate_appliesFullCloseFactorWhenSeverelyUnderwater() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // 100 * 75e6 * 0.80 / 7_000e6 = 0.857 — well below
        // FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD (0.95), so a single call may close the full
        // outstanding debt instead of being capped at CLOSE_FACTOR_BPS. 98 units at
        // 75e6 / 1.05 pay exactly the 7_000e6 debt.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 75e6);

        uint256 debtBefore = market.debtOf(alice);
        vm.prank(liquidator);
        (uint256 debtRepaid,) = market.liquidate(alice, debtBefore);

        assertEq(debtRepaid, debtBefore, "a severely underwater position closes fully in one call");
        assertEq(market.debtOf(alice), 0);
    }

    function test_liquidate_fullClose_creditsResidualCollateralForClaim() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // HF = 100*80*0.8/7000 = 0.914 < 0.95 -> full close; units = ceil(7000*1.05/80) = 92 (worth
        // 7360), paying 7360 − 5% of the 7000 closed = 7010: the 10 above the debt is the borrower's
        // surplus. The liquidator has to authorise the 7010 — a 7000 cap yields 91 units instead.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 80e6);

        uint256 payment = _paymentFor(92, 80e6, 7_000e6);
        assertEq(payment, 7_010e6);
        vm.prank(liquidator);
        (uint256 debtRepaid, uint256 seized) = market.liquidate(alice, payment);

        assertEq(seized, 92);
        assertEq(debtRepaid, 7_000e6);
        assertEq(market.surplusOf(alice), payment - 7_000e6);
        (uint256 collateral, uint256 scaledDebt) = market.positions(alice);
        assertEq(scaledDebt, 0);
        assertEq(collateral, 8, "residual stays credited, not pushed");
        assertEq(collateralToken.balanceOf(alice), 900);

        vm.prank(alice);
        assertEq(market.claimCollateral(), 8);
        assertEq(collateralToken.balanceOf(alice), 908);
    }

    function test_liquidate_isPermissionlessAtEcosystemLayer() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 80e6);

        vm.prank(liquidator);
        market.liquidate(alice, 7_000e6); // liquidator itself unbound — no revert
    }

    // ── configuration ────────────────────────────────────────────────────────

    function test_setBorrowPaused_revertsForNonOperatorCaller() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, mallory, address(orgId)));
        market.setBorrowPaused(true);
    }

    function test_constructor_revertsForInvalidLltv() public {
        vm.expectRevert(EwpgRepoMarket.InvalidLltv.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, 0, LIQ_BONUS_BPS, 0, 0));

        vm.expectRevert(EwpgRepoMarket.InvalidLltv.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, 10_001, LIQ_BONUS_BPS, 0, 0));
    }

    function test_constructor_revertsForInvalidMaxLtv() public {
        // maxLtvBps == 0
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, 0, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));

        // maxLtvBps == lltvBps (must be strictly below, not equal)
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, LLTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));

        // maxLtvBps > lltvBps
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, LLTV_BPS + 1, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));
    }

    function test_constructor_revertsForExcessiveLiquidationBonus() public {
        // MAX_LIQUIDATION_BONUS_BPS is 2000 (20%) — 2001 is one bps above the cap.
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationBonus.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, 2_001, 0, 0));
    }

    function test_constructor_revertsForNonZeroDecimalCollateral() public {
        MockStablecoin eighteenDecimalCollateral = new MockStablecoin("Wrong Decimals Token", "WDT", 18);
        vm.expectRevert(EwpgRepoMarket.InvalidCollateralDecimals.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(eighteenDecimalCollateral, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));
    }

    function test_constructor_revertsForHaircutThinnerThanOracleTolerance() public {
        // The oracle tolerates a 15% move per window; an LLTV of 9000 plus the 5% bonus
        // (0.945) leaves far less than that before a liquidation runs short.
        vm.expectRevert(EwpgRepoMarket.InsufficientLiquidationHaircut.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, 8000, 9000, LIQ_BONUS_BPS, 0, 0));
    }

    function test_constructor_revertsForGracePeriodShorterThanMaxPriceAge() public {
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationGracePeriod.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 1 hours, 30 minutes));
    }

    function test_constructor_allowsGracePeriodEqualToMaxPriceAge() public {
        // A grace period exactly equal to maxPriceAgeSeconds is a valid degenerate case — no
        // additional tolerance beyond the normal staleness bound, not an error.
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 1 hours, 1 hours));
    }

    // ── collateral reconciliation (eWpG §24 Berichtigung) ───────────────────

    function test_reconcileCollateral_reducesRecordedCollateral() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        // An agent forcedTransfer/forceBurn moved 60 units out of the pool independent of
        // {repay}/{liquidate} — the operator reconciles alice's position down by that amount.
        _forceOut(market, 60);
        vm.prank(alice); // alice's org holds RECONCILE in this suite's setUp
        market.reconcileCollateral(alice, 40, keccak256("forced-transfer-tx"));

        (uint256 collateralAmount,) = market.positions(alice);
        assertEq(collateralAmount, 40);
    }

    function test_reconcileCollateral_emitsEvent() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        _forceOut(market, 60);
        vm.expectEmit(true, false, false, true);
        emit EwpgRepoMarket.CollateralReconciled(alice, 100, 40, keccak256("forced-transfer-tx"));
        vm.prank(alice);
        market.reconcileCollateral(alice, 40, keccak256("forced-transfer-tx"));
    }

    function test_reconcileCollateral_revertsIfNotDecreasing() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ReconciliationWouldIncreaseCollateral.selector);
        market.reconcileCollateral(alice, 100, bytes32(0)); // equal — not a decrease

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ReconciliationWouldIncreaseCollateral.selector);
        market.reconcileCollateral(alice, 150, bytes32(0)); // above current — would increase
    }

    function test_reconcileCollateral_revertsForNonOperatorCaller() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, mallory, address(orgId)));
        market.reconcileCollateral(alice, 40, bytes32(0));
    }

    // ── bad-debt write-off  ────────────────────────────

    function test_liquidate_writesOffBadDebtWhenCollateralExhausted() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // A 90% crash: a liquidator repaying 2_000e6 (well within the now-100%-eligible close
        // factor, but a realistic size given only 100 units of crashed collateral back it)
        // demands a seize value far exceeding the 100 units actually available, so the seize is
        // capped to all 100 units while real debt remains outstanding in a single call.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 10e6);

        vm.prank(liquidator);
        (, uint256 collateralSeized) = market.liquidate(alice, 2_000e6);

        assertEq(collateralSeized, 100, "all collateral seized");
        (uint256 collateralAfter, uint256 scaledDebtAfter) = market.positions(alice);
        assertEq(collateralAfter, 0);
        assertEq(scaledDebtAfter, 0, "remaining debt written off, not left to compound phantom interest forever");
        assertEq(market.debtOf(alice), 0);
    }

    function test_liquidate_emitsBadDebtRecognizedEvent() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 10e6);

        vm.expectEmit(true, false, false, false);
        emit EwpgRepoMarket.BadDebtRecognized(alice, 0, 0); // only the indexed borrower is asserted
        vm.prank(liquidator);
        market.liquidate(alice, 2_000e6);
    }

    function test_liquidate_writeOffReducesDepositorClaims() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        uint256 claimBefore = market.balanceOf(lender1);

        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 10e6);
        vm.prank(liquidator);
        market.liquidate(alice, 2_000e6);

        uint256 claimAfter = market.balanceOf(lender1);
        assertLt(claimAfter, claimBefore, "depositor claim reduced to absorb the written-off loss");
    }

    function test_reconcileCollateral_writesOffBadDebtWhenReconciledToZero() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        _forceOut(market, 100);
        vm.prank(alice); // alice's org holds RECONCILE in this suite's setUp
        market.reconcileCollateral(alice, 0, bytes32(0));

        (uint256 collateralAfter, uint256 scaledDebtAfter) = market.positions(alice);
        assertEq(collateralAfter, 0);
        assertEq(scaledDebtAfter, 0, "debt written off once collateral is fully reconciled away");
        assertEq(market.debtOf(alice), 0);
    }

    function test_reconcileCollateral_toNonzeroDoesNotWriteOffDebt() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        _forceOut(market, 60);
        vm.prank(alice);
        market.reconcileCollateral(alice, 40, bytes32(0)); // still nonzero — no bad debt yet

        assertGt(market.debtOf(alice), 0, "debt untouched when some collateral remains");
    }

    // ── healthFactor reliability  ──────────────────────

    function test_healthFactor_unreliableWhenNeverPriced() public {
        RegisterwerkNavOracle freshOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(orgId), address(loanToken), ORACLE_MAX_DEVIATION_BPS, 1 days, 0
        );
        EwpgRepoMarket unpricedMarket = new EwpgRepoMarket(ecosystemOracle, _params(freshOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0));
        (uint256 factor, bool priceReliable) = unpricedMarket.healthFactor(alice);
        assertFalse(priceReliable, "never-priced collateral must not be reported reliable");
        assertEq(factor, type(uint256).max, "no debt yet -> infinite, regardless of pricing");
    }

    function test_healthFactor_reliableForFreshPrice() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        (uint256 factor, bool priceReliable) = market.healthFactor(alice);
        assertTrue(priceReliable);
        assertGe(factor, 1e18);
    }

    function test_healthFactor_unreliableWhenStale() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        vm.warp(block.timestamp + 2 hours); // past maxPriceAgeSeconds(1h), within grace(3h)

        (uint256 factor, bool priceReliable) = staleAwareMarket.healthFactor(alice);
        assertFalse(priceReliable, "a mark older than maxPriceAgeSeconds must not be reported reliable");
        assertGt(factor, 0, "still computed off the stale mark, not zeroed - callers gate on priceReliable");
    }

    // ── liquidate() stale-price grace period — per a joint Repo/Lending-
    //    desk and trading-desk business ruling) ──────────────────────────────

    function _newGraceAwareMarket() private returns (EwpgRepoMarket m) {
        m = new EwpgRepoMarket(ecosystemOracle, _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 1 hours, GRACE_PERIOD));
    }

    function _fundAndBorrow(EwpgRepoMarket m, uint256 borrowAmount) private {
        vm.prank(lender1);
        loanToken.approve(address(m), type(uint256).max);
        vm.prank(lender1);
        m.supply(1_000_000e6);
        vm.prank(alice);
        collateralToken.approve(address(m), type(uint256).max);
        vm.prank(liquidator);
        loanToken.approve(address(m), type(uint256).max);
        vm.prank(alice);
        m.pledgeAndBorrow(100, borrowAmount);
    }

    function test_liquidate_succeedsWithinGracePeriodWhenClearlyUnhealthy() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        // 100 * 80e6 * 0.80 / 7_000e6 = 0.914 — unhealthy even under the grace period's
        // stricter 0.95 bound.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 80e6);
        vm.warp(block.timestamp + 2 hours); // past maxPriceAgeSeconds(1h), within grace(3h)

        vm.prank(liquidator);
        (uint256 debtRepaid,) = staleAwareMarket.liquidate(alice, 7_000e6);
        assertGt(debtRepaid, 0);
    }

    function test_liquidate_revertsWithinGracePeriodWhenOnlyMarginallyUnhealthy() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        // 100 * 85e6 * 0.80 / 7_000e6 = 0.9714 — below the normal 1.0 threshold (would be
        // liquidatable with a fresh price) but still >= the grace period's stricter 0.95 bound,
        // so {liquidate} must still refuse: the borrower gets the benefit of the doubt while the
        // mark backing this decision is stale.
        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), 85e6);
        vm.warp(block.timestamp + 2 hours);

        vm.prank(liquidator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoMarket.PositionHealthy.selector, alice));
        staleAwareMarket.liquidate(alice, 7_000e6);
    }

    function test_liquidate_revertsBeyondGracePeriod() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 80e6); // clearly unhealthy at any threshold
        vm.warp(block.timestamp + GRACE_PERIOD + 1);

        vm.prank(liquidator);
        vm.expectRevert(); // StalePrice — a mark this old is not a legitimate valuation
        staleAwareMarket.liquidate(alice, 7_000e6);
    }

    function test_liquidate_pushPriceWithOverride_unblocksAfterGracePeriodExpires() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 80e6);
        vm.warp(block.timestamp + GRACE_PERIOD + 1);

        vm.prank(liquidator);
        vm.expectRevert();
        staleAwareMarket.liquidate(alice, 7_000e6);

        // The designated remediation path: an operator refreshes the mark past the ordinary
        // deviation cap via the override, which immediately unblocks liquidation again.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 75e6);

        vm.prank(liquidator);
        (uint256 debtRepaid,) = staleAwareMarket.liquidate(alice, 7_000e6);
        assertGt(debtRepaid, 0);
    }

    // ── Phase-2 review: instance binding, over-grant, risk invariant, grace close factor,
    //    whole-unit seizure (T2-06 / T2-07 / T2-08 / T2-09 / T2-10 / T2-12) ────────────────

    function _otherOrgWith(address wallet, bytes32 permission) private returns (MockOnchainId other) {
        other = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(other), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("OPERATOR");
        orgRegistry.addMember(address(other), wallet, roles, "");
        permissions.grantToOrg(address(other), permission);
        vm.stopPrank();
    }

    /// T2-06: a same-slug `repo-markets.configure` grant held by another org does not reach
    /// this instance.
    function test_adminFunctions_revertForOtherOrgHoldingSameCode() public {
        address otherAdmin = address(0x0B0B);
        _otherOrgWith(otherAdmin, configurePermission);

        vm.startPrank(otherAdmin);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherAdmin, address(orgId)));
        market.setBorrowPaused(true);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherAdmin, address(orgId)));
        market.withdrawReserves(0);
        vm.stopPrank();
    }

    /// T2-07: `repo-facility.configure` (routine facility price pushes) no longer administers
    /// markets — the market uses its own `repo-markets.*` codes.
    function test_adminFunctions_revertWithOnlyFacilityConfigureCode() public {
        bytes32 facilityConfigure = keccak256("repo-facility.configure");
        vm.startPrank(operator);
        permissions.revokeFromOrg(address(orgId), configurePermission);
        permissions.revokeFromOrg(address(orgId), reconcilePermission);
        permissions.grantToOrg(address(orgId), facilityConfigure);
        vm.stopPrank();

        vm.startPrank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, configurePermission));
        market.setReserveFactor(1000);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, configurePermission));
        market.withdrawReserves(0);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, alice, reconcilePermission));
        market.reconcileCollateral(alice, 0, bytes32(0));
        vm.stopPrank();
    }

    /// T2-07: without an observed outflow, reconciliation cannot write a position down (and
    /// therefore cannot trigger a bad-debt write-off against depositors).
    function test_reconcileCollateral_revertsWithoutObservedShortfall() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        uint256 lenderClaim = market.balanceOf(lender1);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.NoObservedShortfall.selector);
        market.reconcileCollateral(alice, 0, bytes32(0));
        assertEq(market.balanceOf(lender1), lenderClaim);
    }

    /// T2-07: after a forced transfer of k units, write-downs totalling ≤ k succeed and any
    /// further reduction reverts — the bound is cumulative.
    function test_reconcileCollateral_boundedByObservedShortfall() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);
        _forceOut(market, 30);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoMarket.ReconciliationExceedsShortfall.selector, 31, 30));
        market.reconcileCollateral(alice, 69, bytes32(0));

        vm.startPrank(alice);
        market.reconcileCollateral(alice, 80, bytes32(0)); // 20 of 30
        market.reconcileCollateral(alice, 70, bytes32(0)); // remaining 10
        vm.expectRevert(EwpgRepoMarket.NoObservedShortfall.selector);
        market.reconcileCollateral(alice, 69, bytes32(0));
        vm.stopPrank();

        assertEq(market.totalCollateral(), 70);
        assertEq(collateralToken.balanceOf(address(market)), 70);
    }

    /// T2-08 (poc2B): lltv × (1 + bonus) ≥ 1 lets a still over-collateralised position be
    /// liquidated into bad debt — rejected at construction even with an opted-out oracle.
    function test_constructor_revertsWhenLltvTimesBonusReachesOne() public {
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationIncentive.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(navOracle, 9000, 9500, 1000, 0, 0));

        OptOutRepoOracle optOut = new OptOutRepoOracle(address(loanToken));
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationIncentive.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(optOut, 9000, 9600, 500, 0, 0));
        new EwpgRepoMarket(ecosystemOracle, _params(optOut, 9000, 9500, 500, 0, 0)); // 0.9975 < 1
    }

    /// T2-08 (poc2D): 80% LLTV / 5% bonus against a 20% oracle tolerance — one in-tolerance
    /// push from HF 1.0 already left bad debt. The haircut check now includes the bonus.
    function test_constructor_revertsWhenBonusEatsTheDeviationHaircut() public {
        RegisterwerkNavOracle wideOracle =
            new RegisterwerkNavOracle(ecosystemOracle, address(orgId), address(loanToken), 2000, 1 days, 0);
        vm.expectRevert(EwpgRepoMarket.InsufficientLiquidationHaircut.selector);
        new EwpgRepoMarket(ecosystemOracle, _params(wideOracle, 7000, 8000, 500, 0, 0));
        // The demo retune (75% / 5%) fits exactly: 0.7875 ≤ 0.80.
        new EwpgRepoMarket(ecosystemOracle, _params(wideOracle, 7000, 7500, 500, 0, 0));
    }

    /// T2-12: a market must quote collateral in its own loan token.
    function test_constructor_revertsForOracleQuotedInAnotherToken() public {
        MockStablecoin usdc = new MockStablecoin("USD Coin", "USDC", 6);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoMarket.OracleQuoteMismatch.selector, address(loanToken), address(usdc))
        );
        new EwpgRepoMarket(
            ecosystemOracle,
            MarketParams(
                address(orgId), treasury, usdc, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
                BASE_RATE_WAD, SLOPE_WAD, 0, 0
            )
        );
    }

    function test_constructor_revertsForZeroOperatorOrgOrTreasury() public {
        MarketParams memory p = _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0);
        p.operatorOrg = address(0);
        vm.expectRevert(RegisterwerkGated.ZeroOperatingOrg.selector);
        new EwpgRepoMarket(ecosystemOracle, p);

        p = _params(navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, 0, 0);
        p.treasury = address(0);
        vm.expectRevert(EwpgRepoMarket.ZeroAddress.selector);
        new EwpgRepoMarket(ecosystemOracle, p);
    }

    /// T2-08: with the invariant in force, one liquidation from a health factor just below 1
    /// always leaves the position healthier (or closed) — never shorter of collateral.
    function testFuzz_liquidation_nearHealthFactorOne_raisesHealthFactor(uint256 priceSeed, uint256 repaySeed)
        public
    {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);

        // HF = 100 * p * 0.8 / 7000 ∈ [0.90, 1.0) ⇔ p ∈ [78.75e6, 87.5e6).
        uint256 price = bound(priceSeed, 78.75e6, 87.5e6 - 1);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), price);
        (uint256 hfBefore,) = market.healthFactor(alice);
        assertLt(hfBefore, 1e18);

        // `maxRepayAmount` caps the payment, so it must cover at least one whole unit (rounded
        // down by the market) — a smaller one is the dedicated revert tested separately.
        uint256 repay = bound(repaySeed, price, 7_000e6);
        vm.prank(liquidator);
        market.liquidate(alice, repay);

        if (market.debtOf(alice) == 0) return;
        (uint256 hfAfter,) = market.healthFactor(alice);
        assertGt(hfAfter, hfBefore, "a liquidation near HF 1 must raise the health factor");
    }

    /// T2-09: on a stale mark inside the grace window a single call closes at most 50% (plus
    /// less than one rounded-up unit), even when the health factor is below 0.95. A fresh mark
    /// at the same health factor restores the full close.
    function test_liquidate_withinGracePeriod_capsAtCloseFactor() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        // HF = 100 * 78.75e6 * 0.8 / 7_000e6 = 0.90.
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 78.75e6);
        vm.warp(block.timestamp + 2 hours); // stale, within grace

        uint256 debtBefore = staleAwareMarket.debtOf(alice);
        vm.prank(liquidator);
        (uint256 repaidStale,) = staleAwareMarket.liquidate(alice, type(uint256).max);
        assertLt(repaidStale, debtBefore / 2 + _discounted(78.75e6) + 1, "grace: 50% close factor");
        assertGt(staleAwareMarket.debtOf(alice), 0);

        // Same scenario on a fresh mark: full close.
        EwpgRepoMarket freshMarket = _newGraceAwareMarket();
        loanToken.mint(lender1, 1_000_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), PRICE_PER_UNIT);
        _fundAndBorrow(freshMarket, 7_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 78.75e6);
        vm.prank(liquidator);
        freshMarket.liquidate(alice, type(uint256).max);
        assertEq(freshMarket.debtOf(alice), 0, "fresh mark: full close below HF 0.95");
    }

    /// T2-10 (poc2B): a single-unit position at HF 0.92 used to seize zero units (no rational
    /// liquidator acts). Now the unit is sold whole and the payment above the debt is credited
    /// to the borrower as claimable cash.
    function test_liquidate_singleUnit_seizesWholeUnitAndCreditsSurplus() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 100_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(1, 70_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 81_000e6);
        vm.warp(block.timestamp + 60 days);
        (uint256 hf,) = market.healthFactor(alice);
        assertLt(hf, 0.95e18);

        uint256 debt = market.debtOf(alice);
        uint256 liquidatorCashBefore = loanToken.balanceOf(liquidator);
        vm.prank(liquidator);
        (uint256 repaid, uint256 seized) = market.liquidate(alice, type(uint256).max);

        // The unit (81_000e6) is worth more than the debt: the discount is the bonus on the debt only.
        uint256 payment = _paymentFor(1, 81_000e6, debt);
        assertEq(payment, 81_000e6 - debt * LIQ_BONUS_BPS / 10_000);
        assertEq(seized, 1);
        assertEq(repaid, debt);
        assertEq(liquidatorCashBefore - loanToken.balanceOf(liquidator), payment);
        assertEq(market.surplusOf(alice), payment - debt);
        assertEq(market.totalSurplus(), payment - debt);

        // Surplus cash is not pool liquidity.
        assertEq(market.availableLiquidity(), loanToken.balanceOf(address(market)) - (payment - debt));

        uint256 aliceBefore = loanToken.balanceOf(alice);
        vm.expectEmit(true, false, false, true);
        emit EwpgRepoMarket.SurplusClaimed(alice, payment - debt);
        vm.prank(alice);
        assertEq(market.claimLiquidationSurplus(), payment - debt);
        assertEq(loanToken.balanceOf(alice), aliceBefore + payment - debt);
        assertEq(market.totalSurplus(), 0);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ZeroAmount.selector);
        market.claimLiquidationSurplus();
    }

    // ── H2: indivisible-unit liquidation pricing (payment cap + bonus on the debt closed) ──────────

    /// @dev One collateral unit marked at 85_000e6 backing a 70_000e6 loan: HF 0.971, so the
    ///      ordinary 50% close factor applies — yet the single unit is worth more than the whole debt.
    function _indivisibleUnitScenario() private returns (uint256 debt) {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 100_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(1, 70_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 85_000e6);
        debt = market.debtOf(alice);
        assertEq(debt, 70_000e6);
    }

    /// @notice H2 (red-first): a liquidator asking to spend at most 35_000e6 used to be charged
    ///         ~80_952e6 — the whole unit rounded up, the excess credited to the borrower.
    function test_liquidate_neverChargesMoreThanMaxRepayAmount() public {
        uint256 debt = _indivisibleUnitScenario();
        uint256 maxRepay = debt / 2;

        uint256 cashBefore = loanToken.balanceOf(liquidator);
        vm.prank(liquidator);
        try market.liquidate(alice, maxRepay) {} catch {}

        assertLe(cashBefore - loanToken.balanceOf(liquidator), maxRepay, "liquidator charged more than maxRepayAmount");
    }

    /// @notice H2: when even one unit costs more than `maxRepayAmount` the call reverts and names the
    ///         amount to authorise — and authorising exactly that amount then succeeds.
    function test_liquidate_revertsWhenOneUnitCostsMoreThanMaxRepayAmount() public {
        uint256 debt = _indivisibleUnitScenario();
        uint256 required = 85_000e6 - debt * LIQ_BONUS_BPS / 10_000; // 81_500e6

        vm.prank(liquidator);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoMarket.LiquidationExceedsMaxRepay.selector, required, debt / 2)
        );
        market.liquidate(alice, debt / 2);

        vm.prank(liquidator);
        (uint256 repaid, uint256 seized) = market.liquidate(alice, required);
        assertEq(repaid, debt);
        assertEq(seized, 1);
    }

    /// @notice H2 (red-first): the bonus is `bonusBps × debt closed`, not `bonusBps × the value of
    ///         the whole unit`. A liquidator who permits the full unit price pays the unit's value
    ///         less 5% of the 70_000e6 it closes — not 85_000e6/1.05.
    function test_liquidate_bonusIsChargedOnTheDebtClosedNotOnTheWholeUnit() public {
        uint256 debt = _indivisibleUnitScenario();
        uint256 unitValue = 85_000e6;
        uint256 bonus = debt * LIQ_BONUS_BPS / 10_000; // 3_500e6
        uint256 expectedPayment = unitValue - bonus; // 81_500e6

        uint256 cashBefore = loanToken.balanceOf(liquidator);
        vm.prank(liquidator);
        (uint256 repaid, uint256 seized) = market.liquidate(alice, expectedPayment);

        uint256 paid = cashBefore - loanToken.balanceOf(liquidator);
        assertEq(seized, 1);
        assertEq(repaid, debt, "the unit's proceeds close the whole debt");
        assertEq(paid, expectedPayment, "payment = unit value - bonus x debt closed");
        assertEq(unitValue * seized - paid, bonus, "liquidator's discount equals the bonus on the debt closed");
        // The borrower's side balances: the proceeds beyond the debt are the borrower's surplus.
        assertEq(market.surplusOf(alice), expectedPayment - debt);
        assertEq(market.totalSurplus(), expectedPayment - debt);
        assertEq(market.debtOf(alice), 0);
    }

    /// @notice H2 (red-first): when rounding the units up would overshoot `maxRepayAmount` but one
    ///         unit fewer fits, the liquidator gets one unit fewer instead of an overcharge.
    function test_liquidate_dropsOneUnitInsteadOfExceedingMaxRepayAmount() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 75e6); // HF 0.857: full close factor

        // ⌈1_200 × 1.05 / 75⌉ = 17 units would cost ⌈1_275 / 1.05⌉ = 1_214.29e6 > 1_200e6.
        uint256 cashBefore = loanToken.balanceOf(liquidator);
        vm.prank(liquidator);
        (uint256 repaid, uint256 seized) = market.liquidate(alice, 1_200e6);

        uint256 paid = cashBefore - loanToken.balanceOf(liquidator);
        assertEq(seized, 16, "one unit fewer than the rounded-up 17");
        assertLe(paid, 1_200e6, "never charged more than maxRepayAmount");
        assertEq(repaid, paid, "no surplus when the units cover less than the debt");
        assertEq(market.surplusOf(alice), 0);
    }

    /// @notice H2 property: for any position, price and `maxRepayAmount` the liquidator never pays
    ///         more than `maxRepayAmount`, is discounted by exactly the bonus on the debt closed
    ///         (up to rounding), and the borrower's surplus is exactly what was paid beyond it.
    function testFuzz_liquidate_paymentNeverExceedsMaxRepayAndBonusIsOnDebtClosed(
        uint256 unitsSeed,
        uint256 priceSeed,
        uint256 crashSeed,
        uint256 repaySeed
    ) public {
        uint256 n = bound(unitsSeed, 1, 30);
        uint256 p0 = bound(priceSeed, 100e6, 100_000e6);
        loanToken.mint(lender1, 100_000_000e6);
        vm.prank(lender1);
        market.supply(100_000_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), p0);
        collateralToken.mint(alice, n);
        vm.prank(alice);
        market.pledgeAndBorrow(n, n * p0 * 7 / 10);

        // HF = p1 × 0.8 / (0.7 × p0) < 1 for p1 < 0.875 × p0 (includes underwater positions).
        uint256 p1 = p0 * bound(crashSeed, 3_000, 8_749) / 10_000;
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), p1);

        uint256 debt = market.debtOf(alice);
        uint256 maxRepay = bound(repaySeed, 1, 2 * debt);
        uint256 cashBefore = loanToken.balanceOf(liquidator);
        vm.prank(liquidator);
        try market.liquidate(alice, maxRepay) returns (uint256 repaid, uint256 seized) {
            uint256 paid = cashBefore - loanToken.balanceOf(liquidator);
            assertLe(paid, maxRepay, "liquidator charged more than maxRepayAmount");
            assertApproxEqAbs(seized * p1 - paid, repaid * LIQ_BONUS_BPS / 10_000, 2, "bonus is on the debt closed");
            assertEq(market.surplusOf(alice), paid - repaid, "surplus is what was paid beyond the debt closed");
            assertEq(market.totalSurplus(), paid - repaid);
        } catch {}
    }

    /// T2-10: surplus owed to a borrower is not pool liquidity — a lender with a larger claim
    /// cannot withdraw it while other loans keep the pool fully utilised.
    function test_withdraw_cannotConsumeLiquidationSurplus() public {
        address bob = address(0xB0B);
        vm.prank(operator);
        orgRegistry.addMember(address(orgId), bob, new bytes32[](0), "");
        collateralToken.mint(bob, 1);
        vm.prank(bob);
        collateralToken.approve(address(market), type(uint256).max);

        loanToken.mint(mallory, 140_000e6);
        vm.prank(mallory);
        loanToken.approve(address(market), type(uint256).max);
        vm.prank(mallory);
        market.supply(140_000e6);
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 100_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(1, 70_000e6);
        vm.prank(bob);
        market.pledgeAndBorrow(1, 70_000e6); // pool cash now 0
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 81_000e6);
        vm.prank(liquidator);
        market.liquidate(alice, type(uint256).max);

        uint256 surplus = market.surplusOf(alice);
        assertGt(surplus, 0);
        uint256 cash = loanToken.balanceOf(address(market));
        assertEq(market.availableLiquidity(), cash - surplus);
        vm.prank(mallory);
        vm.expectRevert(EwpgRepoMarket.InsufficientPoolLiquidity.selector);
        market.withdraw(cash);

        vm.prank(mallory);
        market.withdraw(cash - surplus);
        vm.prank(alice);
        assertEq(market.claimLiquidationSurplus(), surplus);
        assertEq(loanToken.balanceOf(address(market)), 0);
    }
}

/// @dev An `IRepoOracle` with no deviation concept (`maxDeviationBps() == type(uint256).max`).
contract OptOutRepoOracle is IRepoOracle {
    address public immutable quoteToken;

    constructor(address quoteToken_) {
        quoteToken = quoteToken_;
    }

    function price(address) external view returns (uint256, uint256) {
        return (100e6, block.timestamp);
    }

    function maxDeviationBps() external pure returns (uint256) {
        return type(uint256).max;
    }
}
