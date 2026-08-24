// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/examples/MockStablecoin.sol";
import "../../src/examples/StablecoinAmm.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice Constant-product AMM reserved for stablecoin-only pairs. Gated through
///         {RegisterwerkGated} like every other reference dApp  — org
///         membership and permission, not a claim topic, since neither pool leg is a
///         security (see the contract-level NatSpec on {StablecoinAmm}).
contract StablecoinAmmTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;

    MockStablecoin aueur; // 6-decimals EMT
    MockStablecoin usdc; // 6-decimals EMT
    StablecoinAmm amm;

    address operator = address(0x1);
    address lp = address(0x11);
    address trader = address(0x22);
    address mallory = address(0x33); // unbound wallet

    bytes32 provideLiquidity_;
    bytes32 swap_;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        aueur = new MockStablecoin("AllUnity Euro", "AUEUR", 6);
        usdc = new MockStablecoin("USD Coin", "USDC", 6);
        amm = new StablecoinAmm(oracle, aueur, usdc, "AUEUR-USDC LP", "AUEUR-USDC");

        provideLiquidity_ = amm.PROVIDE_LIQUIDITY();
        swap_ = amm.SWAP();

        orgId = new MockOnchainId();
        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("TRADER");
        orgRegistry.addMember(address(orgId), lp, roles, "");
        orgRegistry.addMember(address(orgId), trader, roles, "");
        permissions.grantToOrg(address(orgId), provideLiquidity_);
        permissions.grantToOrg(address(orgId), swap_);
        vm.stopPrank();

        aueur.mint(lp, 1_000_000e6);
        usdc.mint(lp, 1_000_000e6);
        aueur.mint(trader, 10_000e6);
        usdc.mint(trader, 10_000e6);

        vm.startPrank(lp);
        aueur.approve(address(amm), type(uint256).max);
        usdc.approve(address(amm), type(uint256).max);
        vm.stopPrank();

        vm.startPrank(trader);
        aueur.approve(address(amm), type(uint256).max);
        usdc.approve(address(amm), type(uint256).max);
        vm.stopPrank();
    }

    function test_addLiquidity_firstDeposit_locksMinimumLiquidityAndMintsShares() public {
        vm.prank(lp);
        uint256 shares = amm.addLiquidity(100_000e6, 100_000e6, 0);

        assertEq(amm.reserveA(), 100_000e6);
        assertEq(amm.reserveB(), 100_000e6);
        assertEq(amm.balanceOf(lp), shares);
        assertEq(amm.balanceOf(address(0xdead)), amm.MINIMUM_LIQUIDITY());
        assertEq(amm.totalSupply(), shares + amm.MINIMUM_LIQUIDITY());
    }

    function test_addLiquidity_subsequentDeposit_mintsProportionalShares() public {
        vm.prank(lp);
        amm.addLiquidity(100_000e6, 100_000e6, 0);
        uint256 supplyBefore = amm.totalSupply();

        vm.prank(trader);
        uint256 shares = amm.addLiquidity(10_000e6, 10_000e6, 0);

        // Depositing 10% of the existing pool mints ~10% of the pre-deposit supply.
        assertApproxEqRel(shares, supplyBefore / 10, 0.02e18);
    }

    function test_addLiquidity_revertsForUnboundWallet() public {
        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, provideLiquidity_));
        amm.addLiquidity(1_000e6, 1_000e6, 0);
    }

    function test_swapExactIn_movesReservesAndAppliesFee() public {
        vm.prank(lp);
        amm.addLiquidity(100_000e6, 100_000e6, 0);

        uint256 usdcBefore = usdc.balanceOf(trader);
        vm.prank(trader);
        uint256 amountOut = amm.swapExactIn(aueur, 1_000e6, 0);

        assertGt(amountOut, 0);
        assertLt(amountOut, 1_000e6, "fee + slippage means output is less than 1:1");
        assertEq(usdc.balanceOf(trader), usdcBefore + amountOut);
        assertEq(amm.reserveA(), 100_000e6 + 1_000e6);
        assertEq(amm.reserveB(), 100_000e6 - amountOut);
    }

    function test_swapExactIn_revertsBelowMinAmountOut() public {
        vm.prank(lp);
        amm.addLiquidity(100_000e6, 100_000e6, 0);

        vm.prank(trader);
        vm.expectRevert(StablecoinAmm.InsufficientOutputAmount.selector);
        amm.swapExactIn(aueur, 1_000e6, 1_000e6); // demands more than the fee/slippage allows
    }

    function test_swapExactIn_revertsForUnknownToken() public {
        MockStablecoin other = new MockStablecoin("Other", "OTH", 6);
        vm.prank(lp);
        amm.addLiquidity(100_000e6, 100_000e6, 0);

        vm.prank(trader);
        vm.expectRevert(StablecoinAmm.InvalidToken.selector);
        amm.swapExactIn(other, 1_000e6, 0);
    }

    function test_swapExactIn_revertsForUnboundWallet() public {
        vm.prank(lp);
        amm.addLiquidity(100_000e6, 100_000e6, 0);

        vm.prank(mallory);
        vm.expectRevert(abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, swap_));
        amm.swapExactIn(aueur, 1_000e6, 0);
    }

    function test_removeLiquidity_returnsProportionalReservesAndBurnsShares() public {
        vm.prank(lp);
        uint256 shares = amm.addLiquidity(100_000e6, 100_000e6, 0);

        uint256 aueurBefore = aueur.balanceOf(lp);
        uint256 usdcBefore = usdc.balanceOf(lp);

        vm.prank(lp);
        (uint256 amountA, uint256 amountB) = amm.removeLiquidity(shares, 0, 0);

        assertEq(aueur.balanceOf(lp), aueurBefore + amountA);
        assertEq(usdc.balanceOf(lp), usdcBefore + amountB);
        assertEq(amm.balanceOf(lp), 0);
        // Only the permanently-locked MINIMUM_LIQUIDITY shares remain outstanding.
        assertEq(amm.totalSupply(), amm.MINIMUM_LIQUIDITY());
    }

    function test_constructor_revertsForIdenticalTokens() public {
        vm.expectRevert(StablecoinAmm.IdenticalTokens.selector);
        new StablecoinAmm(oracle, aueur, aueur, "X", "X");
    }
}
