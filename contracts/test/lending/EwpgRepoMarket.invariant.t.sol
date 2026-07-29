// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/lending/EwpgRepoMarket.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";
import "./handlers/EwpgRepoMarketHandler.sol";

/// @notice Fuzzed invariant suite for {EwpgRepoMarket} (finding #12, Phase 7) — a bounded-random
///         handler drives supply/withdraw/pledgeAndBorrow/repay/liquidate/price-move sequences
///         and every run checks the pool's core accounting identities still hold, including
///         through the bad-debt write-off path added by finding #5. Complements (does not
///         replace) `EwpgRepoMarket.t.sol`'s scenario-specific unit tests.
contract EwpgRepoMarketInvariantTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    MockStablecoin loanToken;
    MockStablecoin collateralToken;
    RegisterwerkNavOracle navOracle;
    EwpgRepoMarket market;
    EwpgRepoMarketHandler handler;

    address operator = address(0x1);
    address pricePusher = address(0x2);

    uint256 constant MAX_LTV_BPS = 7000;
    uint256 constant LLTV_BPS = 8000;
    uint256 constant LIQ_BONUS_BPS = 500;
    uint256 constant BASE_RATE_WAD = 0.02e18;
    uint256 constant SLOPE_WAD = 0.18e18;
    uint256 constant NUM_BORROWERS = 3;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        navOracle = new RegisterwerkNavOracle(ecosystemOracle);

        market = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );

        MockClaimIssuer kycIssuer = new MockClaimIssuer();
        uint256 topicKyc = market.TOPIC_KYC();

        vm.startPrank(operator);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicKyc;
        tir.addTrustedIssuer(address(kycIssuer), topics);

        // A small fixed set of pre-authorized (KYC'd, BORROW-permissioned) borrowers — see
        // EwpgRepoMarketHandler's NatSpec for why these aren't arbitrarily fuzzed addresses.
        address[] memory borrowers = new address[](NUM_BORROWERS);
        for (uint256 i = 0; i < NUM_BORROWERS; i++) {
            address borrower = address(uint160(uint256(keccak256(abi.encode("invariant-borrower", i)))));
            borrowers[i] = borrower;
            MockOnchainId orgId = new MockOnchainId();
            orgRegistry.registerOrg(address(orgId), 276);
            bytes32[] memory roles = new bytes32[](1);
            roles[0] = keccak256("TRADER");
            orgRegistry.addMember(address(orgId), borrower, roles, "");
            permissions.grantToOrg(address(orgId), market.BORROW());
            orgId.addClaim(topicKyc, address(kycIssuer), hex"01", hex"02");
        }

        // pricePusher's org holds PUSH_PRICE only — never OVERRIDE_PRICE, so the handler's price
        // moves are always bounded by the oracle's ordinary deviation cap, matching real
        // day-to-day operation rather than emergency repricing.
        MockOnchainId pusherOrgId = new MockOnchainId();
        orgRegistry.registerOrg(address(pusherOrgId), 276);
        bytes32[] memory pusherRoles = new bytes32[](1);
        pusherRoles[0] = keccak256("NAV_ADMIN");
        orgRegistry.addMember(address(pusherOrgId), pricePusher, pusherRoles, "");
        permissions.grantToOrg(address(pusherOrgId), navOracle.PUSH_PRICE());
        vm.stopPrank();

        vm.prank(pricePusher);
        navOracle.pushPrice(address(collateralToken), 100e6);

        handler = new EwpgRepoMarketHandler(market, navOracle, loanToken, collateralToken, pricePusher, borrowers);
        targetContract(address(handler));
    }

    /// @notice Every unit of collateral the market custodies is attributable to a tracked
    ///         borrower's position — nothing is ever lost or double-counted.
    function invariant_collateralCustodySolvency() public view {
        uint256 sumPositions = 0;
        for (uint256 i = 0; i < NUM_BORROWERS; i++) {
            (uint256 collateralAmount,) = market.positions(handler.borrowers(i));
            sumPositions += collateralAmount;
        }
        assertEq(collateralToken.balanceOf(address(market)), sumPositions);
    }

    /// @notice The pool's core accounting identity: idle cash plus outstanding debt must always
    ///         equal what depositors (plus the protocol's own reserves) are owed. This must
    ///         continue to hold through the bad-debt write-off path (finding #5) — a write-off
    ///         removes debt and depositor claims by the same amount, by construction, so this
    ///         identity is exactly what proves that fix keeps the books balanced rather than
    ///         quietly creating or destroying value.
    function invariant_poolAccountingBalances() public view {
        uint256 cash = loanToken.balanceOf(address(market));
        uint256 totalDebt = (market.totalScaledDebt() * market.borrowIndex()) / 1e18;
        uint256 totalDepositorClaims = (market.totalScaledDeposits() * market.liquidityIndex()) / 1e18;
        uint256 lhs = cash + totalDebt;
        uint256 rhs = market.totalReserves() + totalDepositorClaims;
        assertApproxEqAbs(lhs, rhs, 100, "cash + debt must equal reserves + depositor claims");
    }

    /// @notice Read paths must never revert for any tracked actor — an accounting invariant
    ///         violation upstream would otherwise first surface as a confusing panic here rather
    ///         than a clear assertion failure above.
    function invariant_readsNeverRevert() public view {
        for (uint256 i = 0; i < NUM_BORROWERS; i++) {
            market.debtOf(handler.borrowers(i));
            market.healthFactor(handler.borrowers(i));
        }
        for (uint256 i = 0; i < handler.lenderCount(); i++) {
            market.balanceOf(handler.lendersSeen(i));
        }
    }
}
