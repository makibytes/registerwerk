// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "../../src/ecosystem/RegisterwerkGated.sol";
import "../../src/examples/CompliantSecondaryMarket.sol";
import "../../src/examples/MockStablecoin.sol";
import "../../src/settlement/DvpSettlement.sol";
import "../ecosystem/mocks/MockClaimIssuer.sol";
import "../ecosystem/mocks/MockOnchainId.sol";

/// @notice {CompliantSecondaryMarket} is the pool contract's own address — its inventory
///         legs settle through the ungated, generic {DvpSettlement} escrow exactly like any
///         other DvP counterparty would. These tests exercise both trade directions plus
///         the ecosystem permission/claim gating on the operator-facing functions; the
///         nominee exemption in {EwpgComplianceModule} itself is tested end-to-end against
///         a real T-REX suite in test/examples/EwpgBondDesk.t.sol.
contract CompliantSecondaryMarketTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockClaimIssuer nomineeIssuer;

    DvpSettlement settlement;
    MockStablecoin securityToken; // security-token leg stand-in
    MockStablecoin paymentToken; // MiCAR EMT payment leg (6 decimals)
    CompliantSecondaryMarket market;

    address operator = address(0x1);
    address alice = address(0x3); // nominee-operator wallet
    address mallory = address(0x66); // unbound wallet
    address investor = address(0x7);

    bytes32 tradeBuy = keccak256("trade-buy");
    bytes32 tradeSell = keccak256("trade-sell");
    uint256 constant ASSET_AMOUNT = 1_000;
    uint256 constant PAYMENT_AMOUNT = 100_000e6;
    uint64 expiry;

    bytes32 tradePermission;
    uint256 topicNominee;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);

        settlement = new DvpSettlement(operator);
        securityToken = new MockStablecoin("Demo Bond Units", "BOND", 0);
        paymentToken = new MockStablecoin("USD Coin", "USDC", 6);
        market = new CompliantSecondaryMarket(oracle, paymentToken, settlement);

        tradePermission = market.TRADE();
        topicNominee = market.TOPIC_NOMINEE();
        expiry = uint64(block.timestamp + 1 days);

        orgId = new MockOnchainId();
        nomineeIssuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        bytes32[] memory roles = new bytes32[](1);
        roles[0] = keccak256("CUSTODY_OPS");
        orgRegistry.addMember(address(orgId), alice, roles, "");
        permissions.grantToOrg(address(orgId), tradePermission);
        uint256[] memory topics = new uint256[](1);
        topics[0] = topicNominee;
        tir.addTrustedIssuer(address(nomineeIssuer), topics);
        vm.stopPrank();
        orgId.addClaim(topicNominee, address(nomineeIssuer), hex"01", hex"02");

        // Pool inventory: pre-funded security tokens (for selling) and stablecoin trading
        // capital (for buying) — both held directly at the market contract's own address.
        securityToken.mint(address(market), ASSET_AMOUNT);
        paymentToken.mint(address(market), PAYMENT_AMOUNT);
        // Investor-side balances/approvals for the counterparty leg of each trade.
        paymentToken.mint(investor, PAYMENT_AMOUNT);
        securityToken.mint(investor, ASSET_AMOUNT);
        vm.prank(investor);
        paymentToken.approve(address(settlement), type(uint256).max);
        vm.prank(investor);
        securityToken.approve(address(settlement), type(uint256).max);
    }

    // ── sellFromInventory ────────────────────────────────────────────────────

    function test_sellFromInventory_escrowsPoolInventoryAndBuyerCanSettle() public {
        vm.prank(alice);
        market.sellFromInventory(tradeSell, investor, securityToken, ASSET_AMOUNT, PAYMENT_AMOUNT, expiry);

        assertEq(securityToken.balanceOf(address(market)), 0, "inventory escrowed out");
        assertEq(securityToken.balanceOf(address(settlement)), ASSET_AMOUNT);

        vm.prank(investor);
        settlement.settle(tradeSell);

        assertEq(securityToken.balanceOf(investor), 2 * ASSET_AMOUNT, "investor received the escrowed leg");
        assertEq(paymentToken.balanceOf(address(market)), 2 * PAYMENT_AMOUNT, "sale proceeds landed on the pool");
    }

    function test_sellFromInventory_revertsForNonNomineeCaller() public {
        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, tradePermission)
        );
        market.sellFromInventory(tradeSell, investor, securityToken, ASSET_AMOUNT, PAYMENT_AMOUNT, expiry);
    }

    function test_sellFromInventory_revertsWhenNomineeClaimRevoked() public {
        nomineeIssuer.setValid(false);

        vm.prank(alice);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.ClaimMissing.selector, alice, topicNominee)
        );
        market.sellFromInventory(tradeSell, investor, securityToken, ASSET_AMOUNT, PAYMENT_AMOUNT, expiry);
    }

    // ── buyIntoInventory ─────────────────────────────────────────────────────

    function test_buyIntoInventory_escrowsPaymentAndSellerCanSettle() public {
        vm.prank(alice);
        market.buyIntoInventory(tradeBuy, investor, securityToken, ASSET_AMOUNT, PAYMENT_AMOUNT, expiry);

        assertEq(paymentToken.balanceOf(address(market)), 0, "trading capital escrowed out");
        assertEq(paymentToken.balanceOf(address(settlement)), PAYMENT_AMOUNT);

        vm.prank(investor);
        settlement.settle(tradeBuy);

        // This is the leg that grows the pool's own onchain security-token balance —
        // exactly where EwpgComplianceModule's nominee exemption applies.
        assertEq(securityToken.balanceOf(address(market)), 2 * ASSET_AMOUNT, "bought into pool inventory");
        assertEq(paymentToken.balanceOf(investor), 2 * PAYMENT_AMOUNT, "seller received the escrowed payment");
    }

    function test_buyIntoInventory_revertsForNonNomineeCaller() public {
        vm.prank(mallory);
        vm.expectRevert(
            abi.encodeWithSelector(RegisterwerkGated.PermissionDenied.selector, mallory, tradePermission)
        );
        market.buyIntoInventory(tradeBuy, investor, securityToken, ASSET_AMOUNT, PAYMENT_AMOUNT, expiry);
    }
}
