// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "@openzeppelin/contracts/access/IAccessControl.sol";
import "../../src/settlement/DvpSettlement.sol";
import "../../src/examples/MockStablecoin.sol";

/// @dev Asset-leg stand-in answering T-REX's `isFrozen(address)`.
contract FreezableMockToken is MockStablecoin {
    mapping(address => bool) public isFrozen;

    constructor() MockStablecoin("Frozen-capable Bond", "FBOND", 0) {}

    function setFrozen(address account, bool frozen) external {
        isFrozen[account] = frozen;
    }
}

/// @notice Tests for the ERC-7573-style same-chain DvP rail: one leg is escrowed, the
///         counterparty settles both legs atomically, expiry refunds the locker. A plain
///         ERC-20 stands in for the asset leg; see the DvpSettlement NatSpec for how
///         ERC-3643 assets are handled (lock the payment leg instead).
contract DvpSettlementTest is Test {
    DvpSettlement dvp;
    MockStablecoin asset; // security-token leg stand-in
    MockStablecoin cash; // MiCAR EMT payment leg (6 decimals)

    address operator = address(0x10);
    address seller = address(0x11);
    address buyer = address(0x22);
    address stranger = address(0x33);

    bytes32 constant REF = keccak256("trade-1"); // locker's clientRef
    bytes32 TRADE; // derived id of the trade the current test locked
    uint256 constant ASSET_AMOUNT = 1_000;
    uint256 constant PAYMENT_AMOUNT = 100_000e6;
    uint64 expiry;

    function setUp() public {
        dvp = new DvpSettlement(operator);
        asset = new MockStablecoin("Demo Bond Units", "BOND", 0);
        cash = new MockStablecoin("USD Coin", "USDC", 6);
        expiry = uint64(block.timestamp + 1 days);

        asset.mint(seller, ASSET_AMOUNT);
        cash.mint(buyer, PAYMENT_AMOUNT);

        vm.prank(seller);
        asset.approve(address(dvp), type(uint256).max);
        vm.prank(buyer);
        cash.approve(address(dvp), type(uint256).max);
    }

    function _lockAsset() private {
        vm.prank(seller);
        TRADE = dvp.lockAsset(REF, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function _lockPayment() private {
        vm.prank(buyer);
        TRADE = dvp.lockPayment(REF, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    /// @dev What the counterparty computes from its own record of the agreed deal.
    ///      Computed locally (not via the contract) so it also pins the documented encoding,
    ///      and so it does not consume a pending `vm.prank`.
    function _terms(DvpSettlement.LockedLeg leg) private view returns (bytes32) {
        return keccak256(abi.encode(seller, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, leg, expiry));
    }

    function _assetTerms() private view returns (bytes32) {
        return _terms(DvpSettlement.LockedLeg.Asset);
    }

    function _paymentTerms() private view returns (bytes32) {
        return _terms(DvpSettlement.LockedLeg.Payment);
    }

    // ── asset-leg lock ────────────────────────────────────────────────────────

    function test_lockAsset_escrowsAssetLeg() public {
        _lockAsset();
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
        assertEq(asset.balanceOf(seller), 0);
    }

    function test_settle_afterLockAsset_swapsBothLegsAtomically() public {
        _lockAsset();

        vm.prank(buyer);
        dvp.settle(TRADE, _assetTerms());

        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(cash.balanceOf(seller), PAYMENT_AMOUNT);
        assertEq(asset.balanceOf(address(dvp)), 0, "no residue in escrow");
        assertEq(cash.balanceOf(address(dvp)), 0);
    }

    function test_settle_revertsForNonBuyer() public {
        _lockAsset();
        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotCounterparty.selector, TRADE, stranger));
        dvp.settle(TRADE, _assetTerms());
    }

    function test_settle_revertsWithoutBuyerFunds() public {
        _lockAsset();
        vm.prank(buyer);
        cash.transfer(stranger, PAYMENT_AMOUNT); // buyer can no longer pay

        vm.prank(buyer);
        vm.expectRevert();
        dvp.settle(TRADE, _assetTerms());

        // Escrow stays intact — the seller has not lost the asset leg.
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
    }

    function test_settle_revertsOnDoubleSettle() public {
        _lockAsset();
        vm.startPrank(buyer);
        dvp.settle(TRADE, _assetTerms());
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.settle(TRADE, _assetTerms());
        vm.stopPrank();
    }

    function test_settle_revertsAfterExpiry() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeExpired.selector, TRADE, expiry));
        dvp.settle(TRADE, _assetTerms());
    }

    // ── payment-leg lock (the ERC-3643-friendly direction) ───────────────────

    function test_lockPayment_thenSellerSettles() public {
        _lockPayment();
        assertEq(cash.balanceOf(address(dvp)), PAYMENT_AMOUNT);

        vm.prank(seller);
        dvp.settle(TRADE, _paymentTerms());

        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(cash.balanceOf(seller), PAYMENT_AMOUNT);
        assertEq(cash.balanceOf(address(dvp)), 0);
    }

    function test_settle_afterLockPayment_revertsForBuyer() public {
        _lockPayment();
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotCounterparty.selector, TRADE, buyer));
        dvp.settle(TRADE, _paymentTerms());
    }

    // ── cancellation / expiry refunds ─────────────────────────────────────────

    function test_cancel_byLockerAfterExpiry_refunds() public {
        _lockAsset();
        vm.warp(expiry);

        vm.prank(seller);
        dvp.cancel(TRADE);

        assertEq(asset.balanceOf(seller), ASSET_AMOUNT);
        assertEq(asset.balanceOf(address(dvp)), 0);
    }

    function test_cancel_byLockerBeforeExpiry_reverts() public {
        _lockAsset();
        vm.prank(seller);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotExpired.selector, TRADE, expiry));
        dvp.cancel(TRADE);
    }

    function test_cancel_byCounterpartyAnyTime_refundsLocker() public {
        _lockPayment();

        vm.prank(seller); // counterparty of the locked payment leg renounces early
        dvp.cancel(TRADE);

        assertEq(cash.balanceOf(buyer), PAYMENT_AMOUNT);
    }

    function test_cancel_byStranger_reverts() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(stranger);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.NotTradeParty.selector, TRADE, stranger));
        dvp.cancel(TRADE);
    }

    function test_settle_revertsAfterCancel() public {
        _lockAsset();
        vm.warp(expiry);
        vm.prank(seller);
        dvp.cancel(TRADE);

        vm.warp(expiry - 1); // even if time could rewind, state is terminal
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.settle(TRADE, _assetTerms());
    }

    // ── validation ────────────────────────────────────────────────────────────

    function test_lock_rejectsReusedClientRefBySameLocker() public {
        asset.mint(seller, ASSET_AMOUNT);
        _lockAsset();
        vm.prank(seller);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeAlreadyExists.selector, TRADE));
        dvp.lockAsset(REF, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function test_lock_rejectsInvalidParameters() public {
        vm.startPrank(seller);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(REF, address(0), asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(REF, buyer, asset, 0, cash, PAYMENT_AMOUNT, expiry);
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(REF, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, uint64(block.timestamp));
        vm.expectRevert(DvpSettlement.InvalidTrade.selector);
        dvp.lockAsset(REF, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry); // self-trade
        vm.stopPrank();
    }

    // ── pause ────────────────────────────────────────────────────

    function test_pause_blocksNewLocksAndSettlement() public {
        vm.prank(operator);
        dvp.pause();
        assertTrue(dvp.paused());

        vm.prank(seller);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.lockAsset(REF, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);

        vm.prank(buyer);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.lockPayment(REF, seller, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
    }

    function test_pause_stillAllowsSettleAndCancelOfAlreadyLockedTrades() public {
        _lockAsset(); // locked before the pause

        vm.prank(operator);
        dvp.pause();

        // settle() IS gated — no new fund movement while paused...
        vm.prank(buyer);
        vm.expectRevert(DvpSettlement.ContractPaused.selector);
        dvp.settle(TRADE, _assetTerms());

        // ...but cancel() must never trap an already-escrowed leg.
        vm.warp(expiry);
        vm.prank(seller);
        dvp.cancel(TRADE);
        assertEq(asset.balanceOf(seller), ASSET_AMOUNT, "locker must be able to reclaim escrow even while paused");
    }

    function test_pause_revertsForNonOperator() public {
        vm.prank(stranger);
        vm.expectRevert();
        dvp.pause();
    }

    function test_unpause_resumesNormalOperation() public {
        vm.startPrank(operator);
        dvp.pause();
        dvp.unpause();
        vm.stopPrank();

        assertFalse(dvp.paused());
        _lockAsset(); // succeeds — no revert
        assertEq(asset.balanceOf(address(dvp)), ASSET_AMOUNT);
    }

    // ── derived trade ids (T2-03: no squatting) ──────────────────────────────

    function test_tradeId_isDerivedFromLockerAndClientRef() public {
        _lockAsset();
        assertEq(TRADE, dvp.tradeIdFor(seller, REF));
        assertEq(TRADE, keccak256(abi.encode(block.chainid, address(dvp), seller, REF)));
        assertTrue(TRADE != dvp.tradeIdFor(buyer, REF), "ids are namespaced per locker");
    }

    /// @dev Port of the phase-2 PoC `Poc2BDvp.test_tradeIdSquat_buyerPaysForJunk`: on the old
    ///      contract Mallory front-ran the seller's lock with the agreed global id, the seller's
    ///      lock reverted, and the buyer's blind `settle(tradeId)` paid for Mallory's junk.
    function test_squatAttempt_lockAsset_cannotBlockOrHijackTheAgreedTrade() public {
        address mallory = address(0x66);
        MockStablecoin junk = new MockStablecoin("Junk", "JUNK", 0);
        junk.mint(mallory, ASSET_AMOUNT);
        vm.startPrank(mallory);
        junk.approve(address(dvp), type(uint256).max);
        bytes32 malloryId = dvp.lockAsset(REF, buyer, junk, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
        vm.stopPrank();

        _lockAsset(); // the seller's lock with the same clientRef still succeeds
        assertTrue(malloryId != TRADE, "squatter gets its own id");

        // Even if the buyer is handed Mallory's id, its agreed terms do not match.
        bytes32 agreed = _assetTerms();
        vm.prank(buyer);
        vm.expectRevert(
            abi.encodeWithSelector(DvpSettlement.TermsMismatch.selector, malloryId, agreed, dvp.termsHashOf(malloryId))
        );
        dvp.settle(malloryId, agreed);

        vm.prank(buyer);
        dvp.settle(TRADE, agreed);
        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(cash.balanceOf(seller), PAYMENT_AMOUNT);
        assertEq(cash.balanceOf(mallory), 0, "squatter received nothing");
        assertEq(junk.balanceOf(buyer), 0);
    }

    /// @dev Mirror of the squat via `lockPayment` against a seller with a standing asset approval.
    function test_squatAttempt_lockPayment_cannotPullSellersAsset() public {
        address mallory = address(0x66);
        MockStablecoin junkCash = new MockStablecoin("Junk", "JUNK", 6);
        junkCash.mint(mallory, PAYMENT_AMOUNT);
        vm.startPrank(mallory);
        junkCash.approve(address(dvp), type(uint256).max);
        bytes32 malloryId = dvp.lockPayment(REF, seller, asset, ASSET_AMOUNT, junkCash, PAYMENT_AMOUNT, expiry);
        vm.stopPrank();

        _lockPayment();
        assertTrue(malloryId != TRADE);

        bytes32 agreed = _paymentTerms();
        vm.prank(seller);
        vm.expectRevert(
            abi.encodeWithSelector(DvpSettlement.TermsMismatch.selector, malloryId, agreed, dvp.termsHashOf(malloryId))
        );
        dvp.settle(malloryId, agreed);
        assertEq(asset.balanceOf(seller), ASSET_AMOUNT, "seller's standing approval not drained");

        vm.prank(seller);
        dvp.settle(TRADE, agreed);
        assertEq(asset.balanceOf(buyer), ASSET_AMOUNT);
    }

    function test_settle_revertsOnWrongTermsHash() public {
        _lockAsset();
        // Buyer agreed to pay less than the locker stored.
        bytes32 agreed = dvp.hashTerms(
            seller, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT - 1, DvpSettlement.LockedLeg.Asset, expiry
        );
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TermsMismatch.selector, TRADE, agreed, _assetTerms()));
        dvp.settle(TRADE, agreed);
        assertEq(cash.balanceOf(buyer), PAYMENT_AMOUNT, "buyer funds untouched");
    }

    function test_termsHashOf_matchesHashTermsAndIsZeroForUnknownId() public {
        _lockAsset();
        assertEq(dvp.termsHashOf(TRADE), _assetTerms());
        assertEq(
            dvp.hashTerms(
                seller, buyer, asset, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, DvpSettlement.LockedLeg.Asset, expiry
            ),
            _assetTerms()
        );
        assertEq(dvp.termsHashOf(keccak256("unknown")), bytes32(0));
    }

    // ── frozen parties / legal-order release (T2-04) ─────────────────────────

    function _lockFreezable() private returns (FreezableMockToken fbond, bytes32 id, bytes32 terms) {
        fbond = new FreezableMockToken();
        fbond.mint(seller, ASSET_AMOUNT);
        vm.startPrank(seller);
        fbond.approve(address(dvp), type(uint256).max);
        id = dvp.lockAsset(REF, buyer, fbond, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, expiry);
        vm.stopPrank();
        terms = dvp.hashTerms(
            seller, buyer, fbond, ASSET_AMOUNT, cash, PAYMENT_AMOUNT, DvpSettlement.LockedLeg.Asset, expiry
        );
    }

    function test_settle_revertsWhenSellerFrozenAfterLock() public {
        (FreezableMockToken fbond, bytes32 id, bytes32 terms) = _lockFreezable();
        fbond.setFrozen(seller, true);

        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.PartyFrozen.selector, id, seller));
        dvp.settle(id, terms);
        assertEq(cash.balanceOf(seller), 0, "frozen seller not paid");
        assertEq(fbond.balanceOf(address(dvp)), ASSET_AMOUNT, "escrow held in place");

        fbond.setFrozen(seller, false); // freeze lifted -> settles normally
        vm.prank(buyer);
        dvp.settle(id, terms);
        assertEq(fbond.balanceOf(buyer), ASSET_AMOUNT);
    }

    function test_settle_revertsWhenBuyerFrozen() public {
        (FreezableMockToken fbond, bytes32 id, bytes32 terms) = _lockFreezable();
        fbond.setFrozen(buyer, true);
        vm.prank(buyer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.PartyFrozen.selector, id, buyer));
        dvp.settle(id, terms);
    }

    function test_forceCancel_revertsForNonOperator() public {
        _lockAsset();
        vm.prank(stranger);
        vm.expectRevert();
        dvp.forceCancel(TRADE, stranger, "no order");
    }

    /// @notice The operator's legal-order release stays inside the trade: with the locker (seller)
    ///         frozen, the escrow goes to the counterparty, regardless of the circuit breaker.
    function test_forceCancel_releasesEscrowToATradeParty() public {
        (FreezableMockToken fbond, bytes32 id,) = _lockFreezable();
        fbond.setFrozen(seller, true);

        vm.prank(operator);
        dvp.pause(); // a legal-order release must work regardless of the circuit breaker
        vm.expectEmit(true, true, false, true, address(dvp));
        emit DvpSettlement.TradeForceCancelled(id, buyer, "BaFin Az. 2026-001");
        vm.prank(operator);
        dvp.forceCancel(id, buyer, "BaFin Az. 2026-001");

        assertEq(fbond.balanceOf(buyer), ASSET_AMOUNT);
        assertEq(fbond.balanceOf(address(dvp)), 0);
        (,,,,,,,, DvpSettlement.TradeState state) = dvp.trades(id);
        assertEq(uint8(state), uint8(DvpSettlement.TradeState.Cancelled));

        vm.prank(operator);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, id));
        dvp.forceCancel(id, buyer, "BaFin Az. 2026-001");
    }

    function test_forceCancel_canReturnEscrowToTheLocker() public {
        _lockPayment(); // buyer is the locker
        vm.prank(operator);
        dvp.forceCancel(TRADE, buyer, "order");
        assertEq(cash.balanceOf(buyer), PAYMENT_AMOUNT);
    }

    function test_forceCancel_revertsForADestinationOutsideTheTrade() public {
        _lockPayment();
        vm.prank(operator);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.DestinationNotTradeParty.selector, TRADE, custodian));
        dvp.forceCancel(TRADE, custodian, "BaFin Az. 2026-001");
        assertEq(cash.balanceOf(address(dvp)), PAYMENT_AMOUNT, "escrow untouched");
    }

    // ── legal-order release to a destination outside the trade: role + timelock ──────

    address legalOfficer = address(0x55);
    address custodian = address(0x44);

    function _grantLegalOrderRole() private {
        bytes32 role = dvp.LEGAL_ORDER_ROLE();
        vm.prank(operator);
        dvp.grantRole(role, legalOfficer);
    }

    function test_proposeForceCancel_operatorKeyAloneCannotPropose() public {
        _lockPayment();
        bytes32 role = dvp.LEGAL_ORDER_ROLE();
        vm.prank(operator);
        vm.expectRevert(
            abi.encodeWithSelector(IAccessControl.AccessControlUnauthorizedAccount.selector, operator, role)
        );
        dvp.proposeForceCancel(TRADE, custodian, "order");
    }

    function test_forceCancelToThirdParty_requiresTheTimelock() public {
        _lockPayment();
        _grantLegalOrderRole();

        vm.expectEmit(true, true, false, true, address(dvp));
        emit DvpSettlement.ForceCancelProposed(
            TRADE, custodian, uint64(block.timestamp + 2 days), "BaFin Az. 2026-001"
        );
        vm.prank(legalOfficer);
        dvp.proposeForceCancel(TRADE, custodian, "BaFin Az. 2026-001");

        uint64 executableAt = uint64(block.timestamp + 2 days);
        vm.prank(legalOfficer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.ForceCancelNotReady.selector, TRADE, executableAt));
        dvp.executeForceCancel(TRADE);

        vm.warp(executableAt);
        vm.prank(operator); // the operator key cannot execute it either
        vm.expectRevert();
        dvp.executeForceCancel(TRADE);

        vm.prank(operator);
        dvp.pause(); // must work regardless of the circuit breaker
        vm.expectEmit(true, true, false, true, address(dvp));
        emit DvpSettlement.TradeForceCancelled(TRADE, custodian, "BaFin Az. 2026-001");
        vm.prank(legalOfficer);
        dvp.executeForceCancel(TRADE);

        assertEq(cash.balanceOf(custodian), PAYMENT_AMOUNT);
        assertEq(cash.balanceOf(address(dvp)), 0);
        (,,,,,,,, DvpSettlement.TradeState state) = dvp.trades(TRADE);
        assertEq(uint8(state), uint8(DvpSettlement.TradeState.Cancelled));
        (, uint64 pendingAt,) = dvp.pendingForceCancels(TRADE);
        assertEq(pendingAt, 0, "proposal cleared");

        vm.prank(legalOfficer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.executeForceCancel(TRADE);
    }

    function test_executeForceCancel_revertsWithoutProposal() public {
        _lockPayment();
        _grantLegalOrderRole();
        vm.prank(legalOfficer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.ForceCancelNotProposed.selector, TRADE));
        dvp.executeForceCancel(TRADE);
    }

    function test_proposeForceCancel_cannotBeReplacedWithoutWithdrawing() public {
        _lockPayment();
        _grantLegalOrderRole();
        vm.startPrank(legalOfficer);
        dvp.proposeForceCancel(TRADE, custodian, "order");
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.ForceCancelAlreadyProposed.selector, TRADE));
        dvp.proposeForceCancel(TRADE, address(0x66), "other order");

        dvp.withdrawForceCancel(TRADE);
        dvp.proposeForceCancel(TRADE, address(0x66), "other order");
        vm.stopPrank();
        (address to, uint64 executableAt,) = dvp.pendingForceCancels(TRADE);
        assertEq(to, address(0x66));
        assertEq(executableAt, block.timestamp + 2 days, "the delay restarts");
    }

    function test_withdrawForceCancel_byAdminBlocksExecution() public {
        _lockPayment();
        _grantLegalOrderRole();
        vm.prank(legalOfficer);
        dvp.proposeForceCancel(TRADE, custodian, "order");

        vm.prank(stranger);
        vm.expectRevert();
        dvp.withdrawForceCancel(TRADE);

        vm.prank(operator); // the admin
        dvp.withdrawForceCancel(TRADE);

        vm.warp(block.timestamp + 3 days);
        vm.prank(legalOfficer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.ForceCancelNotProposed.selector, TRADE));
        dvp.executeForceCancel(TRADE);
        assertEq(cash.balanceOf(address(dvp)), PAYMENT_AMOUNT);
    }

    function test_forceCancelToThirdParty_isMootedByTheTradeSettlingFirst() public {
        _lockPayment();
        _grantLegalOrderRole();
        vm.prank(legalOfficer);
        dvp.proposeForceCancel(TRADE, custodian, "order");

        vm.prank(seller);
        dvp.settle(TRADE, _paymentTerms());

        vm.warp(block.timestamp + 3 days);
        vm.prank(legalOfficer);
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, TRADE));
        dvp.executeForceCancel(TRADE);
        assertEq(cash.balanceOf(custodian), 0);
    }

    function test_proposeForceCancel_rejectsInvalidDestinationAndUnlockedTrade() public {
        _lockPayment();
        _grantLegalOrderRole();
        vm.startPrank(legalOfficer);
        vm.expectRevert(DvpSettlement.InvalidDestination.selector);
        dvp.proposeForceCancel(TRADE, address(0), "order");
        vm.expectRevert(DvpSettlement.InvalidDestination.selector);
        dvp.proposeForceCancel(TRADE, address(dvp), "order");
        vm.expectRevert(abi.encodeWithSelector(DvpSettlement.TradeNotLocked.selector, bytes32(uint256(1))));
        dvp.proposeForceCancel(bytes32(uint256(1)), custodian, "order");
        vm.stopPrank();
    }

    function test_forceCancel_rejectsInvalidDestination() public {
        _lockPayment();
        vm.startPrank(operator);
        vm.expectRevert(DvpSettlement.InvalidDestination.selector);
        dvp.forceCancel(TRADE, address(0), "order");
        vm.expectRevert(DvpSettlement.InvalidDestination.selector);
        dvp.forceCancel(TRADE, address(dvp), "order");
        vm.stopPrank();
    }
}
