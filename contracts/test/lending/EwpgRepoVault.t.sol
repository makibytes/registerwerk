// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/lending/EwpgRepoMarket.sol";
import "../../src/lending/EwpgRepoVault.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for {EwpgRepoVault}: the MetaMorpho-style curator layer routing lender
///         deposits across {EwpgRepoMarket} instances.
contract EwpgRepoVaultTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    RegisterwerkNavOracle navOracle;
    MockStablecoin loanToken;
    MockStablecoin collateralTokenA;
    MockStablecoin collateralTokenB;
    EwpgRepoMarket marketA;
    EwpgRepoMarket marketB;
    EwpgRepoVault vault;
    MockOnchainId curatorOrgId;

    address operator = address(0x1);
    address curator = address(0x2);
    address depositor = address(0x11);

    uint256 constant MAX_LTV_BPS = 7000;
    uint256 constant LLTV_BPS = 8000;
    uint256 constant LIQ_BONUS_BPS = 500;
    uint256 constant BASE_RATE_WAD = 0.02e18;
    uint256 constant SLOPE_WAD = 0.18e18;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        collateralTokenA = new MockStablecoin("Bond A", "BONDA", 0);
        collateralTokenB = new MockStablecoin("Bond B", "BONDB", 0);
        navOracle = new RegisterwerkNavOracle(ecosystemOracle);

        marketA = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralTokenA, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );
        marketB = new EwpgRepoMarket(
            ecosystemOracle, loanToken, collateralTokenB, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );

        vault = new EwpgRepoVault(ecosystemOracle, loanToken, "Registerwerk EMT Vault", "rwvAUEUR");

        curatorOrgId = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(curatorOrgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("CURATOR");
        orgRegistry.addMember(address(curatorOrgId), curator, roles, "");
        permissions.grantToOrg(address(curatorOrgId), vault.CURATE());
        vm.stopPrank();

        loanToken.mint(depositor, 1_000_000e6);
        vm.prank(depositor);
        loanToken.approve(address(vault), type(uint256).max);
    }

    function test_deposit_isPermissionlessAndMintsShares() public {
        vm.prank(depositor);
        uint256 shares = vault.deposit(100_000e6, depositor);

        assertGt(shares, 0);
        assertEq(vault.totalAssets(), 100_000e6);
        assertEq(vault.balanceOf(depositor), shares);
    }

    function test_addMarket_revertsForNonCuratorCaller() public {
        // Compute CURATE() before pranking — see the equivalent comment in
        // EwpgRepoMarketFactory.t.sol for why calling it after vm.prank would consume the
        // prank on the wrong call.
        bytes32 curatePermission = vault.CURATE();
        vm.prank(depositor);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, depositor, curatePermission));
        vault.addMarket(marketA, 500_000e6);
    }

    function test_addMarket_revertsOnLoanTokenMismatch() public {
        MockStablecoin otherLoanToken = new MockStablecoin("USD Coin", "USDC", 6);
        EwpgRepoMarket mismatchedMarket = new EwpgRepoMarket(
            ecosystemOracle, otherLoanToken, collateralTokenA, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS,
            BASE_RATE_WAD, SLOPE_WAD, 0, 0
        );

        vm.prank(curator);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoVault.LoanTokenMismatch.selector, address(mismatchedMarket))
        );
        vault.addMarket(mismatchedMarket, 100_000e6);
    }

    function test_allocate_movesIdleCashIntoMarketAndCountsTowardTotalAssets() public {
        vm.prank(curator);
        vault.addMarket(marketA, 500_000e6);

        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.prank(curator);
        vault.allocate(marketA, 400_000e6);

        assertEq(loanToken.balanceOf(address(vault)), 600_000e6, "remaining idle cash");
        assertEq(marketA.balanceOf(address(vault)), 400_000e6, "vault's claim in the market");
        assertEq(vault.totalAssets(), 1_000_000e6, "idle + allocated must equal total deposits");
    }

    function test_allocate_revertsBeyondMarketCap() public {
        vm.prank(curator);
        vault.addMarket(marketA, 100_000e6);

        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.ExceedsMarketCap.selector, address(marketA)));
        vault.allocate(marketA, 100_001e6);
    }

    function test_removeAndReaddMarket_doesNotDuplicateValuation() public {
        vm.prank(curator);
        vault.addMarket(marketA, 500_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);
        vm.prank(curator);
        vault.allocate(marketA, 400_000e6);

        uint256 assetsBefore = vault.totalAssets();
        vm.startPrank(curator);
        vault.removeMarket(marketA);
        vault.addMarket(marketA, 500_000e6);
        vm.stopPrank();

        assertEq(vault.marketCount(), 1, "a market is listed at most once");
        assertEq(vault.totalAssets(), assetsBefore, "re-enabling cannot duplicate market value");
    }

    function test_deallocate_returnsCashToIdle() public {
        vm.prank(curator);
        vault.addMarket(marketA, 500_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);
        vm.prank(curator);
        vault.allocate(marketA, 400_000e6);

        vm.prank(curator);
        vault.deallocate(marketA, 150_000e6);

        assertEq(loanToken.balanceOf(address(vault)), 750_000e6);
        assertEq(marketA.balanceOf(address(vault)), 250_000e6);
    }

    function test_diversifiesAcrossMultipleMarkets() public {
        vm.startPrank(curator);
        vault.addMarket(marketA, 300_000e6);
        vault.addMarket(marketB, 300_000e6);
        vm.stopPrank();

        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.startPrank(curator);
        vault.allocate(marketA, 250_000e6);
        vault.allocate(marketB, 250_000e6);
        vm.stopPrank();

        assertEq(vault.totalAssets(), 1_000_000e6);
        assertEq(vault.marketCount(), 2);
        assertEq(marketA.balanceOf(address(vault)), 250_000e6);
        assertEq(marketB.balanceOf(address(vault)), 250_000e6);
    }

    function test_withdraw_servedFromIdleCashOnly() public {
        vm.prank(curator);
        vault.addMarket(marketA, 1_000_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);
        vm.prank(curator);
        vault.allocate(marketA, 900_000e6);

        // Only 100_000e6 remains idle — maxWithdraw must reflect that MVP boundary rather than
        // the depositor's full economic claim (see contract NatSpec).
        assertEq(vault.maxWithdraw(depositor), 100_000e6);
    }
}
