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
import "../../src/lending/oracle/RegisterwerkNavOracle.sol";
import "../../src/examples/MockStablecoin.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Unit tests for {EwpgRepoMarketFactory}: deterministic CREATE2 deployment and
///         operator-only market creation.
contract EwpgRepoMarketFactoryTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle ecosystemOracle;
    EwpgRepoMarketFactory factory;
    RegisterwerkNavOracle navOracle;
    MockStablecoin loanToken;
    MockStablecoin collateralToken;
    MockOnchainId operatorOrgId;

    address operator = address(0x1);
    address mallory = address(0x66);
    address treasury = address(0x99);

    uint256 constant MAX_LTV_BPS = 7000;
    uint256 constant LLTV_BPS = 7500; // 0.75 × 1.05 ≤ 1 − 0.20 oracle tolerance (T2-08)
    uint256 constant LIQ_BONUS_BPS = 500;
    uint256 constant BASE_RATE_WAD = 0.02e18;
    uint256 constant SLOPE_WAD = 0.18e18;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        ecosystemOracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        loanToken = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        navOracle = new RegisterwerkNavOracle(
            ecosystemOracle, address(new MockOnchainId()), address(loanToken), 2000, 1 days, 0
        );

        factory = new EwpgRepoMarketFactory(ecosystemOracle);

        // The operator's own org holds CREATE_MARKET — mirrors how every other Ewpg*Factory
        // in this codebase is called by an operator-controlled org member wallet.
        operatorOrgId = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(operatorOrgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("OPERATOR");
        orgRegistry.addMember(address(operatorOrgId), operator, roles, "");
        permissions.grantToOrg(address(operatorOrgId), factory.CREATE_MARKET());
        vm.stopPrank();
    }

    function _params(uint256 maxPriceAge, uint256 grace) private view returns (MarketParams memory) {
        return MarketParams(
            address(operatorOrgId), treasury, loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS,
            LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD, maxPriceAge, grace
        );
    }

    function test_createMarket_revertsForUnauthorizedCaller() public {
        // Compute the permission constant BEFORE pranking — calling factory.CREATE_MARKET()
        // after vm.prank(mallory) would itself consume the prank (it's still a call to
        // `factory`), leaving the actual createMarket() call executing as the test contract
        // rather than mallory.
        bytes32 createMarketPermission = factory.CREATE_MARKET();
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, createMarketPermission));
        factory.createMarket(_params(1 hours, 1 hours));
    }

    function test_createMarket_deploysWithCorrectImmutables() public {
        vm.prank(operator);
        address marketAddress = factory.createMarket(_params(1 hours, 2 hours));

        EwpgRepoMarket market = EwpgRepoMarket(marketAddress);
        assertEq(address(market.loanToken()), address(loanToken));
        assertEq(address(market.collateralToken()), address(collateralToken));
        assertEq(address(market.priceOracle()), address(navOracle));
        assertEq(market.maxLtvBps(), MAX_LTV_BPS);
        assertEq(market.lltvBps(), LLTV_BPS);
        assertEq(market.liquidationBonusBps(), LIQ_BONUS_BPS);
        assertEq(market.maxPriceAgeSeconds(), 1 hours);
        assertEq(market.liquidationGracePeriodSeconds(), 2 hours);
        assertEq(market.operatorOrg(), address(operatorOrgId), "operated by the creator's org");
        assertEq(market.treasury(), treasury);
        assertEq(factory.marketCount(), 1);
        assertTrue(factory.isMarket(marketAddress));
        assertFalse(factory.isMarket(address(navOracle)));
    }

    /// T2-06: the creator cannot bind a market to another org.
    function test_createMarket_revertsForForeignOperatorOrg() public {
        MarketParams memory p = _params(1 hours, 2 hours);
        p.operatorOrg = address(0xBEEF);
        vm.prank(operator);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.WrongOperatingOrg.selector, operator, address(0xBEEF)));
        factory.createMarket(p);
    }

    /// T2-12: a USDC market on the AUEUR NAV oracle is refused.
    function test_createMarket_revertsForUsdcMarketOnEuroOracle() public {
        MockStablecoin usdc = new MockStablecoin("USD Coin", "USDC", 6);
        MarketParams memory p = _params(1 hours, 2 hours);
        p.loanToken = usdc;
        vm.prank(operator);
        vm.expectRevert(
            abi.encodeWithSelector(EwpgRepoMarket.OracleQuoteMismatch.selector, address(loanToken), address(usdc))
        );
        factory.createMarket(p);
    }

    /// T2-08: the demo's former 80% LLTV / 5% bonus against a 20% oracle tolerance is refused.
    function test_createMarket_revertsWhenBonusEatsTheDeviationHaircut() public {
        MarketParams memory p = _params(1 hours, 2 hours);
        p.lltvBps = 8000;
        vm.prank(operator);
        vm.expectRevert(EwpgRepoMarket.InsufficientLiquidationHaircut.selector);
        factory.createMarket(p);
    }

    function test_predictMarketAddress_matchesActualDeployment() public {
        address predicted = factory.predictMarketAddress(_params(1 hours, 2 hours));

        vm.prank(operator);
        address actual = factory.createMarket(_params(1 hours, 2 hours));

        assertEq(actual, predicted);
    }

    function test_createMarket_sameParamsTwiceReverts() public {
        vm.startPrank(operator);
        factory.createMarket(_params(1 hours, 2 hours));
        vm.expectRevert(); // CREATE2 collision — same salt, same init code
        factory.createMarket(_params(1 hours, 2 hours));
        vm.stopPrank();
    }

    function test_createMarket_revertsWithZeroMaxPriceAge() public {
        // A factory-deployed market is a real, operator-approved listing — unlike direct
        // `EwpgRepoMarket` construction in unit tests, `maxPriceAgeSeconds == 0` (staleness
        // check disabled) must never be allowed to slip through here.
        vm.prank(operator);
        vm.expectRevert(EwpgRepoMarketFactory.InvalidMaxPriceAge.selector);
        factory.createMarket(_params(0, 0));
    }
}
