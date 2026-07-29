// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/examples/EwpgRepoFacility.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for the lending/repo mechanics using a plain ERC-20 as the collateral
///         stand-in (a nominee-pool-flagged real ERC-3643 collateral scenario is covered
///         end to end in test/examples/EwpgBondDesk.t.sol, alongside the other
///         EwpgComplianceModule nominee tests).
contract EwpgRepoFacilityTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockClaimIssuer kycIssuer;

    MockStablecoin paymentToken; // 6-decimals EMT
    MockStablecoin collateralToken; // security-token leg stand-in, 0 decimals
    EwpgRepoFacility facility;

    address operator = address(0x1);
    address alice = address(0x3); // borrower: KYC'd org member
    address mallory = address(0x66); // unbound wallet
    address lender1 = address(0x11);
    address lender2 = address(0x12);
    address liquidator = address(0x44);

    bytes32 borrowPermission;
    bytes32 configurePermission;
    uint256 topicKyc;

    uint256 constant PRICE_PER_UNIT = 100e6; // 100.00 in payment-token base units per collateral unit
    uint256 constant MAX_LTV_BPS = 7000; // 70%
    uint256 constant LIQ_THRESHOLD_BPS = 8000; // 80%
    uint256 constant LIQ_BONUS_BPS = 500; // 5%

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        paymentToken = new MockStablecoin("USD Coin", "USDC", 6);
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        facility = new EwpgRepoFacility(oracle, paymentToken);

        borrowPermission = facility.BORROW();
        configurePermission = facility.CONFIGURE();
        topicKyc = facility.TOPIC_KYC();

        orgId = new MockOnchainId();
        kycIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), borrowPermission);
        permissions.grantToOrg(address(orgId), configurePermission);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicKyc;
        tir.addTrustedIssuer(address(kycIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(topicKyc, address(kycIssuer), hex"01", hex"02");

        // alice is the org's bound member wallet — the org holds `configurePermission`, so
        // alice (not the raw OPERATOR_ROLE address) is who actually calls gated functions.
        vm.prank(alice);
        facility.setCollateralConfig(
            address(collateralToken), PRICE_PER_UNIT, MAX_LTV_BPS, LIQ_THRESHOLD_BPS, LIQ_BONUS_BPS, true
        );

        paymentToken.mint(lender1, 1_000_000e6);
        paymentToken.mint(lender2, 1_000_000e6);
        paymentToken.mint(liquidator, 1_000_000e6);
        collateralToken.mint(alice, 1_000);

        vm.prank(lender1);
        paymentToken.approve(address(facility), type(uint256).max);
        vm.prank(lender2);
        paymentToken.approve(address(facility), type(uint256).max);
        vm.prank(liquidator);
        paymentToken.approve(address(facility), type(uint256).max);
        vm.prank(alice);
        paymentToken.approve(address(facility), type(uint256).max);
        vm.prank(alice);
        collateralToken.approve(address(facility), type(uint256).max);
    }

    // ── lender side ──────────────────────────────────────────────────────────

    function test_deposit_mintsSharesAtCurrentIndex() public {
        vm.prank(lender1);
        uint256 shares = facility.deposit(100_000e6);

        assertEq(shares, 100_000e6, "1:1 at index = WAD");
        assertEq(facility.balanceOf(lender1), 100_000e6);
        assertEq(paymentToken.balanceOf(address(facility)), 100_000e6);
    }

    function test_withdraw_returnsFundsAndBurnsShares() public {
        vm.prank(lender1);
        facility.deposit(100_000e6);

        uint256 before = paymentToken.balanceOf(lender1);
        vm.prank(lender1);
        facility.withdraw(40_000e6);

        assertEq(paymentToken.balanceOf(lender1), before + 40_000e6);
        assertEq(facility.balanceOf(lender1), 60_000e6);
    }

    function test_withdraw_revertsBeyondAvailableLiquidity() public {
        vm.prank(lender1);
        facility.deposit(100_000e6);

        vm.prank(lender1);
        vm.expectRevert(EwpgRepoFacility.InsufficientShares.selector);
        facility.withdraw(100_001e6);
    }

    function test_deposit_isPermissionless() public {
        // mallory is not bound to any org at all — the lender side must not care.
        paymentToken.mint(mallory, 1_000e6);
        vm.prank(mallory);
        paymentToken.approve(address(facility), type(uint256).max);

        vm.prank(mallory);
        facility.deposit(1_000e6);
        assertEq(facility.balanceOf(mallory), 1_000e6);
    }

    // ── borrower side: gating ────────────────────────────────────────────────

    function test_pledgeAndBorrow_revertsForUnboundWallet() public {
        vm.prank(lender1);
        facility.deposit(100_000e6);

        collateralToken.mint(mallory, 100);
        vm.prank(mallory);
        collateralToken.approve(address(facility), type(uint256).max);

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, borrowPermission));
        facility.pledgeAndBorrow(address(collateralToken), 100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsWithoutKycClaim() public {
        kycIssuer.setValid(false);
        vm.prank(lender1);
        facility.deposit(100_000e6);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, topicKyc));
        facility.pledgeAndBorrow(address(collateralToken), 100, 1_000e6);
    }

    function test_pledgeAndBorrow_revertsForDisabledCollateral() public {
        MockStablecoin other = new MockStablecoin("Other", "OTH", 0);
        vm.prank(lender1);
        facility.deposit(100_000e6);

        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoFacility.CollateralNotEnabled.selector, address(other)));
        facility.pledgeAndBorrow(address(other), 100, 1_000e6);
    }

    // ── borrowing mechanics ──────────────────────────────────────────────────

    function test_pledgeAndBorrow_succeedsWithinLtvAndEscrowsCollateral() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);

        // 100 units * 100e6 price = 10_000e6 collateral value; 70% LTV = 7_000e6 max borrow.
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        assertEq(collateralToken.balanceOf(address(facility)), 100);
        assertEq(collateralToken.balanceOf(alice), 900);
        assertEq(facility.debtOf(alice, address(collateralToken)), 7_000e6);
        assertEq(paymentToken.balanceOf(alice), 7_000e6);
    }

    function test_pledgeAndBorrow_revertsAboveMaxLtv() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);

        vm.prank(alice);
        vm.expectRevert(EwpgRepoFacility.ExceedsMaxLtv.selector);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_001e6);
    }

    function test_pledgeAndBorrow_revertsBeyondPoolLiquidity() public {
        vm.prank(lender1);
        facility.deposit(1_000e6); // far less than the 7_000e6 the collateral would allow

        vm.prank(alice);
        vm.expectRevert(EwpgRepoFacility.InsufficientPoolLiquidity.selector);
        facility.pledgeAndBorrow(address(collateralToken), 100, 5_000e6);
    }

    function test_debtAccruesOverTimeAtUtilizationDependentRate() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        uint256 debtBefore = facility.debtOf(alice, address(collateralToken));
        assertEq(debtBefore, 7_000e6);

        vm.warp(block.timestamp + 365 days);
        uint256 debtAfterOneYear = facility.debtOf(alice, address(collateralToken));

        // utilization = 7_000e6 / 1_000_000e6 = 0.7%; rate = 2% + 18%*0.007 ~= 2.126%/yr
        assertGt(debtAfterOneYear, debtBefore);
        assertApproxEqRel(debtAfterOneYear, (debtBefore * 10213) / 10000, 0.01e18);
    }

    function test_lenderClaimGrowsWithAccruedInterest() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        vm.warp(block.timestamp + 365 days);
        uint256 claim = facility.balanceOf(lender1);
        assertGt(claim, 1_000_000e6, "depositor claim grows from borrower interest");
    }

    // ── repay ────────────────────────────────────────────────────────────────

    function test_repay_fullyReturnsAllCollateral() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        vm.prank(alice);
        uint256 returned = facility.repay(address(collateralToken), 7_000e6);

        assertEq(returned, 100);
        assertEq(collateralToken.balanceOf(alice), 1_000);
        assertEq(facility.debtOf(alice, address(collateralToken)), 0);
    }

    function test_repay_partialReturnsProportionalCollateral() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        vm.prank(alice);
        uint256 returned = facility.repay(address(collateralToken), 3_500e6); // half the debt

        assertEq(returned, 50);
        assertEq(facility.debtOf(alice, address(collateralToken)), 3_500e6);
    }

    function test_repay_revertsWithoutOutstandingDebt() public {
        vm.prank(alice);
        vm.expectRevert(EwpgRepoFacility.NoOutstandingDebt.selector);
        facility.repay(address(collateralToken), 1);
    }

    function test_repay_isNotEcosystemGated() public {
        // Even an unbound wallet may repay its own debt — repayment only ever reduces risk
        // and returns collateral the wallet already held (see contract NatSpec).
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), borrowPermission); // alice can no longer *borrow*

        vm.prank(alice);
        facility.repay(address(collateralToken), 7_000e6); // but can still repay
        assertEq(facility.debtOf(alice, address(collateralToken)), 0);
    }

    // ── liquidation ──────────────────────────────────────────────────────────

    function test_liquidate_revertsForHealthyPosition() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        vm.prank(liquidator);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoFacility.PositionHealthy.selector, alice, address(collateralToken))
        );
        facility.liquidate(alice, address(collateralToken));
    }

    function test_liquidate_succeedsAfterPriceDropBelowThreshold() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        // Collateral value drops from 10_000e6 to 8_000e6: debt/collateral*threshold =
        // 7_000e6 / (8_000e6 * 0.80) = 1.09 > 1 → unhealthy.
        vm.prank(alice);
        facility.updatePrice(address(collateralToken), 80e6);

        uint256 hf = facility.healthFactor(alice, address(collateralToken));
        assertLt(hf, 1e18);

        uint256 liquidatorCollateralBefore = collateralToken.balanceOf(liquidator);
        vm.prank(liquidator);
        (uint256 debtRepaid, uint256 collateralSeized) = facility.liquidate(alice, address(collateralToken));

        assertEq(debtRepaid, 7_000e6);
        // seizeValue = 7_000e6 * 1.05 = 7_350e6; at 80e6/unit that's ~91 units, capped at 100.
        assertEq(collateralSeized, 91);
        assertEq(collateralToken.balanceOf(liquidator), liquidatorCollateralBefore + 91);
        assertEq(facility.debtOf(alice, address(collateralToken)), 0);
        assertEq(collateralToken.balanceOf(alice), 900, "alice's own wallet balance is unaffected");
    }

    function test_liquidate_capsSeizedCollateralAtPositionBalance() public {
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);

        // Crash the price hard enough that the bonus-adjusted seize value would exceed the
        // entire pledged position.
        vm.prank(alice);
        facility.updatePrice(address(collateralToken), 1e6);

        vm.prank(liquidator);
        (, uint256 collateralSeized) = facility.liquidate(alice, address(collateralToken));
        assertEq(collateralSeized, 100, "capped at the full pledged position");
    }

    function test_liquidate_isPermissionlessAtEcosystemLayer() public {
        // liquidator is not bound to any org — the ecosystem layer places no restriction on
        // who may liquidate (see contract NatSpec on why the token layer is the real gate).
        vm.prank(lender1);
        facility.deposit(1_000_000e6);
        vm.prank(alice);
        facility.pledgeAndBorrow(address(collateralToken), 100, 7_000e6);
        vm.prank(alice);
        facility.updatePrice(address(collateralToken), 80e6);

        vm.prank(liquidator);
        facility.liquidate(alice, address(collateralToken)); // no revert despite being unbound
    }

    // ── configuration ────────────────────────────────────────────────────────

    function test_setCollateralConfig_revertsForNonOperatorCaller() public {
        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, configurePermission)
        );
        facility.setCollateralConfig(address(collateralToken), 1, 1, 2, 0, true);
    }

    function test_setCollateralConfig_revertsForInvalidThresholds() public {
        vm.prank(alice);
        vm.expectRevert(EwpgRepoFacility.InvalidThresholds.selector);
        facility.setCollateralConfig(address(collateralToken), 1e6, 8000, 7000, 0, true); // maxLtv >= liqThreshold
    }
}
