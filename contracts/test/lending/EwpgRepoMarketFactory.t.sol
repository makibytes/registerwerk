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
        collateralToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        navOracle = new RegisterwerkNavOracle(ecosystemOracle);

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

    function test_createMarket_revertsForUnauthorizedCaller() public {
        // Compute the permission constant BEFORE pranking — calling factory.CREATE_MARKET()
        // after vm.prank(mallory) would itself consume the prank (it's still a call to
        // `factory`), leaving the actual createMarket() call executing as the test contract
        // rather than mallory.
        bytes32 createMarketPermission = factory.CREATE_MARKET();
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, createMarketPermission));
        factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 1 hours
        );
    }

    function test_createMarket_deploysWithCorrectImmutables() public {
        vm.prank(operator);
        address marketAddress = factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 2 hours
        );

        EwpgRepoMarket market = EwpgRepoMarket(marketAddress);
        assertEq(address(market.loanToken()), address(loanToken));
        assertEq(address(market.collateralToken()), address(collateralToken));
        assertEq(address(market.priceOracle()), address(navOracle));
        assertEq(market.maxLtvBps(), MAX_LTV_BPS);
        assertEq(market.lltvBps(), LLTV_BPS);
        assertEq(market.liquidationBonusBps(), LIQ_BONUS_BPS);
        assertEq(market.maxPriceAgeSeconds(), 1 hours);
        assertEq(market.liquidationGracePeriodSeconds(), 2 hours);
        assertEq(factory.marketCount(), 1);
    }

    function test_predictMarketAddress_matchesActualDeployment() public {
        address predicted = factory.predictMarketAddress(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 2 hours
        );

        vm.prank(operator);
        address actual = factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 2 hours
        );

        assertEq(actual, predicted);
    }

    function test_createMarket_sameParamsTwiceReverts() public {
        vm.startPrank(operator);
        factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 2 hours
        );
        vm.expectRevert(); // CREATE2 collision — same salt, same init code
        factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            1 hours, 2 hours
        );
        vm.stopPrank();
    }

    function test_createMarket_revertsWithZeroMaxPriceAge() public {
        // A factory-deployed market is a real, operator-approved listing — unlike direct
        // `EwpgRepoMarket` construction in unit tests, `maxPriceAgeSeconds == 0` (staleness
        // check disabled) must never be allowed to slip through here.
        vm.prank(operator);
        vm.expectRevert(EwpgRepoMarketFactory.InvalidMaxPriceAge.selector);
        factory.createMarket(
            loanToken, collateralToken, navOracle, MAX_LTV_BPS, LLTV_BPS, LIQ_BONUS_BPS, BASE_RATE_WAD, SLOPE_WAD,
            0, 0
        );
    }
}
