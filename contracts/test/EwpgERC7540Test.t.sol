// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../src/tokens/EwpgERC7540.sol";
import "../src/factory/AssetTokenFactory.sol";
import "../src/factory/AssetTokenFactoryBootstrap.sol";
import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @dev Minimal USDC mock.
contract MockUSDC7540 is ERC20 {
    constructor() ERC20("USD Coin", "USDC") {
        _mint(msg.sender, 10_000_000e6);
    }

    function decimals() public pure override returns (uint8) {
        return 6;
    }
}

/// @dev USDC-like token that burns 1% of every transfer (the "fee-on-transfer" class).
contract FeeOnTransferToken7540 is ERC20 {
    constructor() ERC20("Fee Token", "FEE") {
        _mint(msg.sender, 10_000_000e6);
    }

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function _update(address from, address to, uint256 value) internal override {
        if (from != address(0) && to != address(0)) {
            uint256 fee = value / 100;
            super._update(from, address(0), fee);
            value -= fee;
        }
        super._update(from, to, value);
    }
}

contract EwpgERC7540Test is Test {
    EwpgERC7540 vault;
    MockUSDC7540 usdc;
    AssetTokenFactory factory;

    address registry = makeAddr("registry");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");

    bytes32 constant ASSET_ID = keccak256("async-vault-uuid");

    function setUp() public {
        usdc = new MockUSDC7540();
        usdc.transfer(alice, 200_000e6);
        usdc.transfer(bob, 200_000e6);

        vm.startPrank(registry);
        vault = new EwpgERC7540(usdc, "Registerwerk Async Fund", "RWAF", registry, ASSET_ID);
        vault.whitelist(alice);
        vault.whitelist(bob);
        vm.stopPrank();
    }

    // ── Deposit request / fulfill ─────────────────────────────────────────────

    function test_requestDeposit_createsRequest() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        (uint256 assets, address controller, address owner, bool pending) = vault.depositRequest(requestId);
        assertEq(assets, 10_000e6);
        assertEq(controller, alice);
        assertEq(owner, alice);
        assertTrue(pending);
    }

    function test_fulfillDepositRequest_mintsShares() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        // Strike NAV first
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);

        assertGt(vault.balanceOf(alice), 0);
        (,,, bool pending) = vault.depositRequest(requestId);
        assertFalse(pending);
    }

    function test_fulfillDepositRequest_revertsIfAlreadyFulfilled() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 5_000e6);
        uint256 requestId = vault.requestDeposit(5_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: not pending");
        vault.fulfillDepositRequest(requestId);
    }

    // ── Settlement delay ──────────────────────────────────────────────────────

    function test_settlementDelay_preventsEarlyFulfillment() public {
        vm.prank(registry);
        vault.setMinSettlementDelay(3600); // 1 hour

        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 requestId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: settlement delay not elapsed");
        vault.fulfillDepositRequest(requestId);

        // Advance time
        vm.warp(block.timestamp + 3601);
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);
        assertGt(vault.balanceOf(alice), 0);
    }

    // ── Redeem request / fulfill ──────────────────────────────────────────────

    function test_requestRedeem_thenFulfill_returnsAssets() public {
        // First deposit to get shares
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));

        vm.startPrank(alice);
        usdc.approve(address(vault), 10_000e6);
        uint256 depReqId = vault.requestDeposit(10_000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vault.fulfillDepositRequest(depReqId);

        uint256 shares = vault.balanceOf(alice);
        assertGt(shares, 0);

        // Now redeem
        vm.startPrank(alice);
        vault.approve(address(vault), shares);
        uint256 redeemReqId = vault.requestRedeem(shares, alice, alice);
        vm.stopPrank();

        uint256 usdcBefore = usdc.balanceOf(alice);
        vm.prank(registry);
        vault.fulfillRedeemRequest(redeemReqId);
        uint256 usdcAfter = usdc.balanceOf(alice);
        assertGt(usdcAfter, usdcBefore);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    function test_cancelDepositRequest_returnsAssets() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 5_000e6);
        uint256 requestId = vault.requestDeposit(5_000e6, alice, alice);
        vm.stopPrank();

        uint256 usdcBefore = usdc.balanceOf(alice);
        vm.prank(alice);
        vault.cancelDepositRequest(requestId);
        assertEq(usdc.balanceOf(alice), usdcBefore + 5_000e6);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    function test_factory_deploysErc7540ViaDeployVault() public {
        factory = new AssetTokenFactory(registry);
        vm.startPrank(registry);
        AssetTokenFactoryBootstrap.configure(factory, registry);
        vm.stopPrank();
        vm.prank(registry);
        address vaultAddr = factory.deployVault(5, "Async Fund", "AF", ASSET_ID, address(usdc));
        EwpgERC7540 deployed = EwpgERC7540(vaultAddr);
        assertEq(deployed.assetId(), ASSET_ID);
    }

    // ── Regression: redemption authorization, NAV requirement, self-custody ───

    function test_strangerCannotRequestRedeemForOthers() public {
        _depositFor(alice, 1000e6);

        address mallory = makeAddr("mallory");
        vm.prank(mallory);
        vm.expectRevert(); // ERC20InsufficientAllowance
        vault.requestRedeem(100e6, mallory, alice);
    }

    function test_approvedOperatorCanRequestRedeem() public {
        _depositFor(alice, 1000e6);
        uint256 aliceShares = vault.balanceOf(alice);

        address operator = makeAddr("operator");
        vm.prank(alice);
        vault.approve(operator, aliceShares);

        vm.prank(operator);
        uint256 requestId = vault.requestRedeem(aliceShares, alice, alice);

        // Shares moved into vault self-custody (exempt from whitelist)
        assertEq(vault.balanceOf(alice), 0);
        assertEq(vault.balanceOf(address(vault)), aliceShares);
        (,, address owner, bool pending) = vault.redeemRequest(requestId);
        assertEq(owner, alice);
        assertTrue(pending);
    }

    function test_fulfillWithoutNavStrikeReverts() public {
        // No NAV ever struck — fulfilling a deposit at the implicit 1:1
        // pre-strike rate must be impossible for a regulated fund.
        vm.startPrank(alice);
        usdc.approve(address(vault), 1000e6);
        uint256 requestId = vault.requestDeposit(1000e6, alice, alice);
        vm.stopPrank();

        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: NAV not struck");
        vault.fulfillDepositRequest(requestId);
    }

    function test_synchronousErc4626EntryPointsRevert() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), type(uint256).max);

        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.deposit(1000e6, alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.mint(1000e6, alice);
        vm.stopPrank();

        _depositFor(alice, 1000e6);

        vm.startPrank(alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.withdraw(1, alice, alice);
        vm.expectRevert(EwpgERC7540.AsyncOnly.selector);
        vault.redeem(1, alice, alice);
        vm.stopPrank();
    }

    function test_synchronousErc4626MaximumsAreZero() public view {
        assertEq(vault.maxDeposit(alice), 0);
        assertEq(vault.maxMint(alice), 0);
        assertEq(vault.maxWithdraw(alice), 0);
        assertEq(vault.maxRedeem(alice), 0);
    }

    // ── Regression T1-04: pending subscriptions are escrow, not fund assets ───

    function test_redemptionCannotBePaidFromPendingDeposits() public {
        _depositFor(alice, 100e6);
        vm.prank(registry);
        vault.setNavPerShare(1.5e18, block.timestamp, bytes32(0));

        vm.startPrank(bob);
        usdc.approve(address(vault), 100e6);
        uint256 bobDeposit = vault.requestDeposit(100e6, bob, bob);
        vm.stopPrank();

        assertEq(vault.pendingDepositAssets(), 100e6);
        assertEq(vault.totalAssets(), 100e6, "pending subscription must not count as AUM");

        vm.prank(alice);
        uint256 aliceRedeem = vault.requestRedeem(100e6, alice, alice);
        // 150 owed at NAV 1.5, only 100 settled — the extra 50 would be bob's money
        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: insufficient settled liquidity");
        vault.fulfillRedeemRequest(aliceRedeem);

        uint256 bobBefore = usdc.balanceOf(bob);
        vm.prank(bob);
        vault.cancelDepositRequest(bobDeposit);
        assertEq(usdc.balanceOf(bob), bobBefore + 100e6);
        assertEq(vault.pendingDepositAssets(), 0);
        assertEq(vault.totalAssets(), 100e6);
    }

    function test_pendingDepositAssetsTrackedThroughFulfil() public {
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.startPrank(alice);
        usdc.approve(address(vault), 300e6);
        uint256 a = vault.requestDeposit(100e6, alice, alice);
        vault.requestDeposit(200e6, alice, alice);
        vm.stopPrank();
        assertEq(vault.pendingDepositAssets(), 300e6);
        assertEq(vault.totalAssets(), 0);

        vm.prank(registry);
        vault.fulfillDepositRequest(a);
        assertEq(vault.pendingDepositAssets(), 200e6);
        assertEq(vault.totalAssets(), 100e6);
    }

    // ── Regression T1-05: frozen owner / payer — freeze-in-place ──────────────

    function test_frozenOwnerCannotCancelOwnDeposit() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 100e6);
        uint256 id = vault.requestDeposit(100e6, alice, alice);
        vm.stopPrank();
        vm.startPrank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vault.freezeAddress(alice, "sanctions");
        vm.expectRevert(); // mint to frozen owner blocked by the share hook
        vault.fulfillDepositRequest(id);
        vm.expectRevert("EwpgERC7540: refund recipient is frozen");
        vault.cancelDepositRequest(id);
        vm.stopPrank();
        vm.prank(alice);
        vm.expectRevert("EwpgERC7540: refund recipient is frozen");
        vault.cancelDepositRequest(id);
        assertEq(usdc.balanceOf(address(vault)), 100e6, "subscription stays escrowed");
    }

    function test_fulfilRedeemRefusesFrozenOwner() public {
        _depositFor(alice, 1000e6);
        vm.prank(alice);
        uint256 r = vault.requestRedeem(1000e6, alice, alice);
        vm.prank(registry);
        vault.freezeAddress(alice, "sanctions");

        uint256 before = usdc.balanceOf(alice);
        vm.prank(registry);
        vm.expectRevert("EwpgERC7540: owner is frozen");
        vault.fulfillRedeemRequest(r);
        // cancel returns shares to the frozen owner -> blocked by the share hook
        vm.prank(registry);
        vm.expectRevert("EwpgCompliance: recipient is frozen");
        vault.cancelRedeemRequest(r);
        assertEq(usdc.balanceOf(alice), before, "sanctioned owner must not be paid");
        assertEq(vault.balanceOf(address(vault)), 1000e6, "shares stay escrowed");
    }

    function test_forcedOpsOnVaultCustodyRevert() public {
        _depositFor(alice, 1000e6);
        _depositFor(bob, 1000e6);
        vm.prank(alice);
        vault.requestRedeem(1000e6, alice, alice);
        vm.prank(bob);
        uint256 bobRedeem = vault.requestRedeem(1000e6, bob, bob);
        address victim = makeAddr("victim");

        vm.startPrank(registry);
        vault.whitelist(victim);
        vault.freezeAddress(alice, "sanctions");
        vm.expectRevert("EwpgERC7540: vault custody is not force-movable");
        vault.forcedTransfer(address(vault), victim, 1000e6, "confiscate");
        vm.expectRevert("EwpgERC7540: vault custody is not force-movable");
        vault.forceBurn(address(vault), 1000e6, "confiscate");
        vm.expectRevert("EwpgERC7540: vault custody is not force-movable");
        vault.forcedApprove(address(vault), victim, 1000e6, "confiscate");
        vault.fulfillRedeemRequest(bobRedeem); // bob's redemption stays fundable
        vm.stopPrank();
        assertEq(usdc.balanceOf(bob), 200_000e6);
    }

    function test_forceCancelRedeemMovesEscrowAndLeavesOthersFundable() public {
        _depositFor(alice, 1000e6);
        _depositFor(bob, 1000e6);
        vm.prank(alice);
        uint256 aliceRedeem = vault.requestRedeem(1000e6, alice, alice);
        vm.prank(bob);
        uint256 bobRedeem = vault.requestRedeem(1000e6, bob, bob);
        address blocked = makeAddr("blockedAssetsAccount"); // not whitelisted: forced leg bypasses it

        vm.startPrank(registry);
        vault.freezeAddress(alice, "sanctions");
        vm.expectEmit(true, true, false, true, address(vault));
        emit EwpgERC7540.ForcedRequestCancelled(aliceRedeem, blocked, "court order 1");
        vault.forceCancelRedeemRequest(aliceRedeem, blocked, "court order 1");
        assertEq(vault.balanceOf(blocked), 1000e6);
        (,,, bool pending) = vault.redeemRequest(aliceRedeem);
        assertFalse(pending);
        vm.expectRevert("EwpgERC7540: not pending");
        vault.fulfillRedeemRequest(aliceRedeem);
        vault.fulfillRedeemRequest(bobRedeem);
        vm.stopPrank();
        assertEq(vault.balanceOf(address(vault)), 0);
        assertEq(usdc.balanceOf(bob), 200_000e6);
    }

    function test_forceCancelDepositMovesEscrowAndReleasesPending() public {
        vm.startPrank(alice);
        usdc.approve(address(vault), 100e6);
        uint256 id = vault.requestDeposit(100e6, alice, alice);
        vm.stopPrank();
        address blocked = makeAddr("blockedAssetsAccount");

        vm.prank(makeAddr("mallory"));
        vm.expectRevert("EwpgCompliance: caller is not registry");
        vault.forceCancelDepositRequest(id, blocked, "x");

        vm.startPrank(registry);
        vault.freezeAddress(alice, "sanctions");
        vm.expectRevert("EwpgERC7540: invalid destination");
        vault.forceCancelDepositRequest(id, address(vault), "x");
        vault.forceCancelDepositRequest(id, blocked, "court order 2");
        vm.stopPrank();
        assertEq(usdc.balanceOf(blocked), 100e6);
        assertEq(vault.pendingDepositAssets(), 0);
        (,,, bool pending) = vault.depositRequest(id);
        assertFalse(pending);
    }

    // ── Regression T1-06: payer checks and refund-to-payer ────────────────────

    function test_frozenPayerCannotRequestDeposit() public {
        address frozen = makeAddr("frozenPayer");
        usdc.transfer(frozen, 500e6);
        vm.prank(registry);
        vault.freezeAddress(frozen, "sanctions");
        vm.startPrank(frozen);
        usdc.approve(address(vault), 500e6);
        vm.expectRevert("EwpgERC7540: payer is frozen");
        vault.requestDeposit(500e6, frozen, bob);
        vm.stopPrank();
    }

    function test_frozenOwnerCannotBeSubscribedFor() public {
        vm.prank(registry);
        vault.freezeAddress(bob, "sanctions");
        vm.startPrank(alice);
        usdc.approve(address(vault), 500e6);
        vm.expectRevert("EwpgERC7540: owner is frozen");
        vault.requestDeposit(500e6, alice, bob);
        vm.stopPrank();
    }

    function test_cancelRefundsPayerNotOwner() public {
        address payer = makeAddr("payer");
        usdc.transfer(payer, 500e6);
        vm.startPrank(payer);
        usdc.approve(address(vault), 500e6);
        uint256 id = vault.requestDeposit(500e6, payer, bob);
        assertEq(vault.depositRequestPayer(id), payer);
        uint256 bobBefore = usdc.balanceOf(bob);
        vault.cancelDepositRequest(id);
        vm.stopPrank();
        assertEq(usdc.balanceOf(payer), 500e6, "refund goes back to the payer");
        assertEq(usdc.balanceOf(bob), bobBefore, "no relay to the owner");
    }

    // ── H15: deposit cap, received-amount accounting, frozen operator ────────────────────

    /// @notice H15 (red-first): `setDepositCap` used to be dead on the async vault — `maxDeposit` is 0
    ///         for the (disabled) synchronous path and {requestDeposit} never looked at the cap.
    function test_requestDeposit_enforcesTheDepositCap() public {
        vm.prank(registry);
        vault.setDepositCap(1_000e6);

        vm.startPrank(alice);
        usdc.approve(address(vault), type(uint256).max);
        vm.expectRevert("EwpgERC7540: deposit cap exceeded");
        vault.requestDeposit(1_001e6, alice, alice);
        vault.requestDeposit(1_000e6, alice, alice); // exactly the cap fits
        vm.stopPrank();
        assertEq(vault.maxRequestDeposit(alice), 0);
    }

    /// @notice The cap counts pending requests, not just settled assets — otherwise N investors could
    ///         each request the whole headroom before the operator fulfils any of them.
    function test_depositCap_countsPendingRequestsAndFrees_onCancelAndFulfilment() public {
        vm.prank(registry);
        vault.setDepositCap(1_000e6);
        vm.startPrank(alice);
        usdc.approve(address(vault), type(uint256).max);
        uint256 first = vault.requestDeposit(600e6, alice, alice);
        vm.stopPrank();
        assertEq(vault.maxRequestDeposit(bob), 400e6);

        vm.startPrank(bob);
        usdc.approve(address(vault), type(uint256).max);
        vm.expectRevert("EwpgERC7540: deposit cap exceeded");
        vault.requestDeposit(401e6, bob, bob);
        vm.stopPrank();

        vm.prank(alice);
        vault.cancelDepositRequest(first);
        assertEq(vault.maxRequestDeposit(bob), 1_000e6, "cancelled escrow frees the headroom");

        // A fulfilled request moves from pending to settled assets: the headroom stays consumed.
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.prank(bob);
        uint256 second = vault.requestDeposit(700e6, bob, bob);
        vm.prank(registry);
        vault.fulfillDepositRequest(second);
        assertEq(vault.maxRequestDeposit(alice), 300e6);
    }

    function test_depositCap_zeroMeansUnlimited() public view {
        assertEq(vault.maxRequestDeposit(alice), type(uint256).max);
    }

    /// @notice H15 (red-first): the request, the pending escrow and the shares minted at fulfilment
    ///         were booked from the *requested* amount. With a fee-on-transfer underlying that
    ///         mints shares for cash the vault never received.
    function test_requestDeposit_booksTheReceivedAmountForAFeeOnTransferUnderlying() public {
        FeeOnTransferToken7540 feeToken = new FeeOnTransferToken7540();
        vm.startPrank(registry);
        EwpgERC7540 feeVault = new EwpgERC7540(feeToken, "Fee Fund", "FF", registry, ASSET_ID);
        feeVault.whitelist(alice);
        feeVault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.stopPrank();
        feeToken.transfer(alice, 100_000e6);

        vm.startPrank(alice);
        feeToken.approve(address(feeVault), type(uint256).max);
        uint256 id = feeVault.requestDeposit(1_000e6, alice, alice);
        vm.stopPrank();

        (uint256 booked,,,) = feeVault.depositRequest(id);
        assertEq(feeToken.balanceOf(address(feeVault)), 990e6, "the vault received the amount net of the fee");
        assertEq(booked, 990e6, "request booked at the requested, not the received, amount");
        assertEq(feeVault.pendingDepositAssets(), 990e6);

        vm.prank(registry);
        feeVault.fulfillDepositRequest(id);
        assertEq(feeVault.balanceOf(alice), 990e6, "no shares for cash that never arrived");
        assertEq(feeVault.totalAssets(), feeToken.balanceOf(address(feeVault)));
    }

    /// @notice H15 (red-first): a delegate that was frozen after receiving a share allowance could still
    ///         lock the owner's shares into a redemption request.
    function test_requestRedeem_revertsForAFrozenOperator() public {
        _depositFor(alice, 1000e6);
        uint256 aliceShares = vault.balanceOf(alice);
        address delegate = makeAddr("delegate");
        vm.prank(alice);
        vault.approve(delegate, aliceShares);
        vm.prank(registry);
        vault.freezeAddress(delegate, "sanctions");

        vm.prank(delegate);
        vm.expectRevert("EwpgERC7540: caller is frozen");
        vault.requestRedeem(aliceShares, alice, alice);
        assertEq(vault.balanceOf(alice), aliceShares, "owner's shares untouched");
    }

    // ── Helpers for regression tests ──────────────────────────────────────────

    function _depositFor(address investor, uint256 assets) internal {
        vm.prank(registry);
        vault.setNavPerShare(1e18, block.timestamp, bytes32(0));
        vm.startPrank(investor);
        usdc.approve(address(vault), assets);
        uint256 requestId = vault.requestDeposit(assets, investor, investor);
        vm.stopPrank();
        vm.prank(registry);
        vault.fulfillDepositRequest(requestId);
    }
}
