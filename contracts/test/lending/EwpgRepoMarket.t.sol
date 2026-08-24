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

    bytes32 borrowPermission;
    bytes32 configurePermission;
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

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        navOracle = new RegisterwerkNavOracle(ecosystemOracle);

        market = new EwpgRepoMarket(
            ecosystemOracle,
            loanToken,
            collateralToken,
            navOracle,
            MAX_LTV_BPS,
            LLTV_BPS,
            LIQ_BONUS_BPS,
            BASE_RATE_WAD,
            SLOPE_WAD,
            0, // no staleness check for these tests
            0
        );

        borrowPermission = market.BORROW();
        configurePermission = market.CONFIGURE();
        topicKyc = market.TOPIC_KYC();
        pushPricePermission = navOracle.PUSH_PRICE();
        overridePricePermission = navOracle.OVERRIDE_PRICE();

        orgId = new MockOnchainId();
        kycIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), borrowPermission);
        permissions.grantToOrg(address(orgId), configurePermission);
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
        RegisterwerkNavOracle freshOracle = new RegisterwerkNavOracle(ecosystemOracle);
        EwpgRepoMarket unpricedMarket = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, freshOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
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
        EwpgRepoMarket staleMarket = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 1 hours, GRACE_PERIOD
        );
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

        address treasury = address(0x99);
        vm.prank(alice);
        market.withdrawReserves(treasury, reserves);
        assertEq(loanToken.balanceOf(treasury), reserves);
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

        // Close factor caps a single call at 50% of outstanding debt.
        assertApproxEqAbs(debtRepaid, debtBefore / 2, 1);
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

        // 100 * 50e6 * 0.80 / 7_000e6 = 0.571 — well below
        // FULL_CLOSE_HEALTH_FACTOR_THRESHOLD_WAD (0.95), so a single call may close the full
        // outstanding debt instead of being capped at CLOSE_FACTOR_BPS .
        vm.prank(alice);
        navOracle.pushPriceWithOverride(address(collateralToken), 50e6);

        uint256 debtBefore = market.debtOf(alice);
        vm.prank(liquidator);
        (uint256 debtRepaid,) = market.liquidate(alice, debtBefore);

        assertEq(debtRepaid, debtBefore, "a severely underwater position closes fully in one call");
        assertEq(market.debtOf(alice), 0);
    }

    function test_liquidate_isPermissionlessAtEcosystemLayer() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 7_000e6);
        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), 80e6);

        vm.prank(liquidator);
        market.liquidate(alice, 7_000e6); // liquidator itself unbound — no revert
    }

    // ── configuration ────────────────────────────────────────────────────────

    function test_setBorrowPaused_revertsForNonOperatorCaller() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, configurePermission));
        market.setBorrowPaused(true);
    }

    function test_constructor_revertsForInvalidLltv() public {
        vm.expectRevert(EwpgRepoMarket.InvalidLltv.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, 0, LIQ_BONUS_BPS, BASE_RATE_WAD,
            SLOPE_WAD, 0, 0
        );

        vm.expectRevert(EwpgRepoMarket.InvalidLltv.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, 10_001, LIQ_BONUS_BPS, BASE_RATE_WAD,
            SLOPE_WAD, 0, 0
        );
    }

    function test_constructor_revertsForInvalidMaxLtv() public {
        // maxLtvBps == 0
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, 0, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD,
            SLOPE_WAD, 0, 0
        );

        // maxLtvBps == lltvBps (must be strictly below, not equal)
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, LLTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD,
            SLOPE_WAD, 0, 0
        );

        // maxLtvBps > lltvBps
        vm.expectRevert(EwpgRepoMarket.InvalidMaxLtv.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, LLTV_BPS + 1, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
    }

    function test_constructor_revertsForExcessiveLiquidationBonus() public {
        // MAX_LIQUIDATION_BONUS_BPS is 2000 (20%) — 2001 is one bps above the cap.
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationBonus.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, 2_001,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
    }

    function test_constructor_revertsForNonZeroDecimalCollateral() public {
        MockStablecoin eighteenDecimalCollateral = new MockStablecoin("Wrong Decimals Token", "WDT", 18);
        vm.expectRevert(EwpgRepoMarket.InvalidCollateralDecimals.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, eighteenDecimalCollateral, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
    }

    function test_constructor_revertsForHaircutThinnerThanOracleTolerance() public {
        // Oracle's default maxDeviationBps is 2000 (20%); an LLTV of 9000 leaves only a 10%
        // haircut — thinner than the oracle's own routine per-push deviation tolerance.
        vm.expectRevert(EwpgRepoMarket.InsufficientLiquidationHaircut.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, 8000, 9000, LIQ_BONUS_BPS, BASE_RATE_WAD,
            SLOPE_WAD, 0, 0
        );
    }

    function test_constructor_revertsForGracePeriodShorterThanMaxPriceAge() public {
        vm.expectRevert(EwpgRepoMarket.InvalidLiquidationGracePeriod.selector);
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 1 hours, 30 minutes
        );
    }

    function test_constructor_allowsGracePeriodEqualToMaxPriceAge() public {
        // A grace period exactly equal to maxPriceAgeSeconds is a valid degenerate case — no
        // additional tolerance beyond the normal staleness bound, not an error.
        new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 1 hours, 1 hours
        );
    }

    // ── collateral reconciliation (eWpG §24 Berichtigung) ───────────────────

    function test_reconcileCollateral_reducesRecordedCollateral() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        // Simulates the token layer's own `positions` view desyncing from the market's
        // internal ledger after an agent forcedTransfer/forceBurn moved collateral out of the
        // pool independent of {repay}/{liquidate} — the operator reconciles down to what was
        // actually attributable to alice's position following that forced move.
        vm.prank(alice); // alice's org holds CONFIGURE in this suite's setUp
        market.reconcileCollateral(alice, 40);

        (uint256 collateralAmount,) = market.positions(alice);
        assertEq(collateralAmount, 40);
    }

    function test_reconcileCollateral_emitsEvent() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        vm.expectEmit(true, false, false, true);
        emit EwpgRepoMarket.CollateralReconciled(alice, 100, 40);
        vm.prank(alice);
        market.reconcileCollateral(alice, 40);
    }

    function test_reconcileCollateral_revertsIfNotDecreasing() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ReconciliationWouldIncreaseCollateral.selector);
        market.reconcileCollateral(alice, 100); // equal — not a decrease

        vm.prank(alice);
        vm.expectRevert(EwpgRepoMarket.ReconciliationWouldIncreaseCollateral.selector);
        market.reconcileCollateral(alice, 150); // above current — would increase
    }

    function test_reconcileCollateral_revertsForNonOperatorCaller() public {
        vm.prank(lender1);
        market.supply(1_000_000e6);
        vm.prank(alice);
        market.pledgeAndBorrow(100, 1_000e6);

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, configurePermission));
        market.reconcileCollateral(alice, 40);
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

        vm.prank(alice); // alice's org holds CONFIGURE in this suite's setUp
        market.reconcileCollateral(alice, 0);

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

        vm.prank(alice);
        market.reconcileCollateral(alice, 40); // still nonzero — no bad debt yet

        assertGt(market.debtOf(alice), 0, "debt untouched when some collateral remains");
    }

    // ── healthFactor reliability  ──────────────────────

    function test_healthFactor_unreliableWhenNeverPriced() public {
        RegisterwerkNavOracle freshOracle = new RegisterwerkNavOracle(ecosystemOracle);
        EwpgRepoMarket unpricedMarket = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, freshOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
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
        m = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 1 hours, GRACE_PERIOD
        );
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

        // 100 * 75e6 * 0.80 / 7_000e6 = 0.857 — unhealthy even under the grace period's
        // stricter 0.95 bound.
        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), 80e6);
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
        navOracle.pushPrice(address(collateralToken), 80e6); // clearly unhealthy at any threshold
        vm.warp(block.timestamp + GRACE_PERIOD + 1);

        vm.prank(liquidator);
        vm.expectRevert(); // StalePrice — a mark this old is not a legitimate valuation
        staleAwareMarket.liquidate(alice, 7_000e6);
    }

    function test_liquidate_pushPriceWithOverride_unblocksAfterGracePeriodExpires() public {
        EwpgRepoMarket staleAwareMarket = _newGraceAwareMarket();
        _fundAndBorrow(staleAwareMarket, 7_000e6);

        vm.prank(alice);
        navOracle.pushPrice(address(collateralToken), 80e6);
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
}
