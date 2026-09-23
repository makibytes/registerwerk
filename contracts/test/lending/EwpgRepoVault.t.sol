// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/lending/EwpgRepoMarket.sol";
import "../../src/lending/EwpgRepoMarketFactory.sol";
import "../../src/lending/EwpgRepoVault.sol";
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Not a factory market: forwards every supplied unit to a thief and reports a zero
///         position (review PoC `test_curatorDrainsVault`, T2-13).
contract FakeMarket {
    IERC20 public loanToken;
    address thief;

    constructor(IERC20 t, address thief_) {
        loanToken = t;
        thief = thief_;
    }

    function supply(uint256 amount) external returns (uint256) {
        loanToken.transferFrom(msg.sender, thief, amount);
        return amount;
    }

    function balanceOf(address) external pure returns (uint256) {
        return 0;
    }

    function withdraw(uint256) external pure returns (uint256) {
        return 0;
    }
}

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
    EwpgRepoMarketFactory factory;
    EwpgRepoVault vault;
    MockOnchainId curatorOrgId;
    MockOnchainId operatorOrgId;
    MockOnchainId otherOrgId;

    address operator = address(0x1);
    address curator = address(0x2);
    address otherCurator = address(0x3);
    address depositor = address(0x11);

    uint256 constant TIMELOCK = 1 days;

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
        navOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(new MockOnchainId()), address(loanToken), 1500, 1 days, 0
        );

        factory = new EwpgRepoMarketFactory(ecosystemOracle);

        curatorOrgId = new MockOnchainId();
        operatorOrgId = new MockOnchainId();
        otherOrgId = new MockOnchainId();
        vault = new EwpgRepoVault(
            ecosystemOracle,
            loanToken,
            factory,
            address(curatorOrgId),
            address(operatorOrgId),
            TIMELOCK,
            "Registerwerk EMT Vault",
            "rwvAUEUR"
        );

        vm.startPrank(operator);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("CURATOR");
        orgRegistry.registerOrg(address(curatorOrgId), 276);
        orgRegistry.addMember(address(curatorOrgId), curator, roles, "");
        permissions.grantToOrg(address(curatorOrgId), vault.CURATE());
        // Same slug, different org: must not administer this instance (T2-06).
        orgRegistry.registerOrg(address(otherOrgId), 276);
        orgRegistry.addMember(address(otherOrgId), otherCurator, roles, "");
        permissions.grantToOrg(address(otherOrgId), vault.CURATE());
        orgRegistry.registerOrg(address(operatorOrgId), 276);
        orgRegistry.addMember(address(operatorOrgId), operator, roles, "");
        permissions.grantToOrg(address(operatorOrgId), factory.CREATE_MARKET());
        permissions.grantToOrg(address(operatorOrgId), vault.CONFIGURE());
        marketA = EwpgRepoMarket(factory.createMarket(_marketParams(loanToken, collateralTokenA, navOracle)));
        marketB = EwpgRepoMarket(factory.createMarket(_marketParams(loanToken, collateralTokenB, navOracle)));
        vm.stopPrank();

        loanToken.mint(depositor, 1_000_000e6);
        vm.prank(depositor);
        loanToken.approve(address(vault), type(uint256).max);
    }

    function _marketParams(IERC20 loan, IERC20 collateral, IRepoOracle priceOracle)
        private
        view
        returns (MarketParams memory)
    {
        return MarketParams(
            address(operatorOrgId), address(0x7EA5), loan, collateral, priceOracle, MAX_LTV_BPS, LLTV_BPS,
            LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD, 1 hours, 2 hours
        );
    }

    /// @dev submit → wait out the timelock → accept.
    function _addMarket(EwpgRepoMarket market, uint256 capWad) private {
        vm.prank(curator);
        vault.submitAddMarket(market, capWad);
        vm.warp(block.timestamp + TIMELOCK);
        vm.prank(curator);
        vault.acceptAddMarket(market);
    }

    function test_deposit_isPermissionlessAndMintsShares() public {
        vm.prank(depositor);
        uint256 shares = vault.deposit(100_000e6, depositor);

        assertGt(shares, 0);
        assertEq(vault.totalAssets(), 100_000e6);
        assertEq(vault.balanceOf(depositor), shares);
    }

    function test_addMarket_revertsForNonCuratorCaller() public {
        vm.prank(depositor);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, depositor, address(curatorOrgId))
        );
        vault.submitAddMarket(marketA, 500_000e6);
    }

    function test_addMarket_revertsOnLoanTokenMismatch() public {
        MockStablecoin otherLoanToken = new MockStablecoin("USD Coin", "USDC", 6);
        RegisterwerkNavOracle usdOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(new MockOnchainId()), address(otherLoanToken), 1500, 1 days, 0
        );
        vm.prank(operator);
        EwpgRepoMarket mismatchedMarket =
            EwpgRepoMarket(factory.createMarket(_marketParams(otherLoanToken, collateralTokenA, usdOracle)));

        vm.prank(curator);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoVault.LoanTokenMismatch.selector, address(mismatchedMarket))
        );
        vault.submitAddMarket(mismatchedMarket, 100_000e6);
    }

    function test_allocate_movesIdleCashIntoMarketAndCountsTowardTotalAssets() public {
        _addMarket(marketA, 500_000e6);

        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.prank(curator);
        vault.allocate(marketA, 400_000e6);

        assertEq(loanToken.balanceOf(address(vault)), 600_000e6, "remaining idle cash");
        assertEq(marketA.balanceOf(address(vault)), 400_000e6, "vault's claim in the market");
        assertEq(vault.totalAssets(), 1_000_000e6, "idle + allocated must equal total deposits");
    }

    function test_allocate_revertsBeyondMarketCap() public {
        _addMarket(marketA, 100_000e6);

        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.ExceedsMarketCap.selector, address(marketA)));
        vault.allocate(marketA, 100_001e6);
    }

    function test_removeAndReaddMarket_doesNotDuplicateValuation() public {
        _addMarket(marketA, 500_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);
        vm.prank(curator);
        vault.allocate(marketA, 400_000e6);

        uint256 assetsBefore = vault.totalAssets();
        vm.prank(curator);
        vault.removeMarket(marketA);
        _addMarket(marketA, 500_000e6);

        assertEq(vault.marketCount(), 1, "a market is listed at most once");
        assertEq(vault.totalAssets(), assetsBefore, "re-enabling cannot duplicate market value");
    }

    function test_deallocate_returnsCashToIdle() public {
        _addMarket(marketA, 500_000e6);
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
        _addMarket(marketA, 300_000e6);
        _addMarket(marketB, 300_000e6);

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
        _addMarket(marketA, 1_000_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);
        vm.prank(curator);
        vault.allocate(marketA, 900_000e6);

        // Only 100_000e6 remains idle — maxWithdraw must reflect that MVP boundary rather than
        // the depositor's full economic claim (see contract NatSpec).
        assertEq(vault.maxWithdraw(depositor), 100_000e6);
    }

    // ── T2-13: curator drain / timelock ──────────────────────────────────────

    /// @notice Port of review PoC `test_curatorDrainsVault`: a contract that merely reports the
    ///         right `loanToken()` can no longer be listed, so idle cash cannot be routed to it.
    function test_curatorCannotDrainVaultThroughNonFactoryMarket() public {
        vm.prank(depositor);
        vault.deposit(500_000e6, depositor);

        FakeMarket fake = new FakeMarket(loanToken, curator);
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.NotFactoryMarket.selector, address(fake)));
        vault.submitAddMarket(EwpgRepoMarket(address(fake)), type(uint256).max);

        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.MarketNotEnabled.selector, address(fake)));
        vault.allocate(EwpgRepoMarket(address(fake)), 500_000e6);

        assertEq(loanToken.balanceOf(curator), 0);
        assertEq(vault.totalAssets(), 500_000e6);
    }

    function test_addMarket_revertsForDirectlyDeployedMarket() public {
        EwpgRepoMarket direct =
            new EwpgRepoMarket(ecosystemOracle, _marketParams(loanToken, collateralTokenA, navOracle));
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.NotFactoryMarket.selector, address(direct)));
        vault.submitAddMarket(direct, 100_000e6);
    }

    function test_acceptAddMarket_revertsBeforeTimelock() public {
        vm.prank(curator);
        vault.submitAddMarket(marketA, 100_000e6);
        uint256 validAt = block.timestamp + TIMELOCK;

        vm.warp(validAt - 1);
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.TimelockNotElapsed.selector, address(marketA), validAt));
        vault.acceptAddMarket(marketA);

        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.MarketNotEnabled.selector, address(marketA)));
        vault.allocate(marketA, 1);

        vm.warp(validAt);
        vm.prank(curator);
        vault.acceptAddMarket(marketA);
        (bool enabled, uint256 cap) = vault.allocations(address(marketA));
        assertTrue(enabled);
        assertEq(cap, 100_000e6);
    }

    function test_capIncrease_revertsBeforeTimelock() public {
        _addMarket(marketA, 100_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        // An immediate raise is refused outright.
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.CapIncreaseRequiresTimelock.selector, address(marketA)));
        vault.setMarketCap(marketA, 1_000_000e6);

        vm.prank(curator);
        vault.submitCapIncrease(marketA, 1_000_000e6);
        uint256 validAt = block.timestamp + TIMELOCK;
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.TimelockNotElapsed.selector, address(marketA), validAt));
        vault.acceptCapIncrease(marketA);
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.ExceedsMarketCap.selector, address(marketA)));
        vault.allocate(marketA, 100_001e6);

        vm.warp(validAt);
        vm.prank(curator);
        vault.acceptCapIncrease(marketA);
        vm.prank(curator);
        vault.allocate(marketA, 900_000e6);
        assertEq(marketA.balanceOf(address(vault)), 900_000e6);
    }

    function test_capDecrease_isImmediateAndDropsPendingIncrease() public {
        _addMarket(marketA, 500_000e6);
        vm.startPrank(curator);
        vault.submitCapIncrease(marketA, 900_000e6);
        vault.setMarketCap(marketA, 200_000e6);
        vm.stopPrank();

        (, uint256 cap) = vault.allocations(address(marketA));
        assertEq(cap, 200_000e6);
        (, uint64 validAt) = vault.pendingCap(address(marketA));
        assertEq(validAt, 0, "a decrease drops the pending increase");
    }

    function test_revokePending_byCuratorOrOperatorOrg() public {
        vm.prank(curator);
        vault.submitAddMarket(marketA, 100_000e6);
        vm.prank(operator);
        vault.revokePending(marketA);

        vm.warp(block.timestamp + TIMELOCK);
        vm.prank(curator);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.NoPendingChange.selector, address(marketA)));
        vault.acceptAddMarket(marketA);

        vm.startPrank(curator);
        vault.submitAddMarket(marketA, 100_000e6);
        vault.revokePending(marketA);
        vm.stopPrank();

        vm.prank(depositor);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, depositor, address(operatorOrgId))
        );
        vault.revokePending(marketA);
    }

    function test_otherOrgWithCurateGrant_cannotCurateThisVault() public {
        _addMarket(marketA, 500_000e6);
        vm.prank(depositor);
        vault.deposit(1_000_000e6, depositor);

        vm.startPrank(otherCurator);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherCurator, address(curatorOrgId))
        );
        vault.submitAddMarket(marketB, 500_000e6);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, otherCurator, address(curatorOrgId))
        );
        vault.allocate(marketA, 100_000e6);
        vm.stopPrank();
    }

    function test_constructor_enforcesMinimumTimelockOffLocalChain() public {
        vm.chainId(1);
        vm.expectRevert(abi.encodeWithSelector(EwpgRepoVault.TimelockTooShort.selector, 1 hours));
        this.deployVault(1 hours);
        assertEq(this.deployVault(1 days).timelock(), 1 days);
    }

    function deployVault(uint256 timelock_) external returns (EwpgRepoVault) {
        return new EwpgRepoVault(
            ecosystemOracle, loanToken, factory, address(curatorOrgId), address(operatorOrgId), timelock_, "V", "V"
        );
    }

    function test_constructor_rejectsZeroCuratorOrg() public {
        vm.expectRevert(RegisterwerkGated.ZeroOperatingOrg.selector);
        new EwpgRepoVault(ecosystemOracle, loanToken, factory, address(0), address(operatorOrgId), TIMELOCK, "V", "V");
    }
}
