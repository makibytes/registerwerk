// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {FHE, euint64, externalEuint64} from "@fhevm/solidity/lib/FHE.sol";
import "../helpers/FhevmMockSetup.sol";

/// @notice Behaviour of {ConfidentialERC20} on the cleartext FHEVM mocks (see test/mocks/MockFhevm.sol):
///         balances, the silent-failure semantics of a transfer, allowances, the viewer ACL and the
///         public-decryption supply disclosure. These replace the old tests that self-skipped unless run
///         against a real fhEVM network.
contract ConfidentialERC20Test is FhevmMockSetup {
    ConfidentialERC20 token;

    address owner = makeAddr("owner");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");
    address spender = makeAddr("spender");
    address operatorViewer = makeAddr("operatorViewer");
    address auditorViewer = makeAddr("auditorViewer");
    bytes32 assetId = keccak256("conf-erc20");

    function setUp() public {
        ConfidentialERC20.FhevmInfra memory infra = _deployFhevmMocks();
        address[] memory viewers = new address[](2);
        viewers[0] = operatorViewer;
        viewers[1] = auditorViewer;
        vm.prank(owner);
        token = new ConfidentialERC20(assetId, "Confidential Token", "cTKN", infra, viewers, owner);
    }

    function _mint(address to, uint256 amount) internal returns (euint64 minted) {
        (externalEuint64 handle, bytes memory proof) = _input(amount);
        vm.recordLogs();
        vm.prank(owner);
        token.confidentialMint(to, handle, proof);
        Vm.Log[] memory logs = vm.getRecordedLogs();
        // ConfidentialMint(address indexed to, euint64 handle) is the token's last log of the call
        minted = euint64.wrap(bytes32(logs[logs.length - 1].data));
    }

    function _balance(address who) internal view returns (uint256) {
        return _clear(token.confidentialBalanceOf(who));
    }

    // ── construction ─────────────────────────────────────────────────────────

    function test_construction_metadataAndEmptySupply() public view {
        assertEq(token.name(), "Confidential Token");
        assertEq(token.symbol(), "cTKN");
        assertEq(token.decimals(), 6);
        assertEq(token.assetId(), assetId);
        assertEq(token.owner(), owner);
        assertTrue(FHE.isInitialized(token.confidentialTotalSupply()));
        assertEq(_clear(token.confidentialTotalSupply()), 0);
    }

    function test_construction_viewersCanDecryptTheSupplyFromBlockOne() public view {
        euint64 supply = token.confidentialTotalSupply();
        assertTrue(_persistAllowed(supply, address(token)));
        assertTrue(_persistAllowed(supply, operatorViewer));
        assertTrue(_persistAllowed(supply, auditorViewer));
        assertFalse(_persistAllowed(supply, alice));
    }

    // ── mint ─────────────────────────────────────────────────────────────────

    function test_mint_creditsBalanceAndSupply() public {
        _mint(alice, 1_000);
        assertEq(_balance(alice), 1_000);
        assertEq(_clear(token.confidentialTotalSupply()), 1_000);
        _mint(alice, 250);
        _mint(bob, 5);
        assertEq(_balance(alice), 1_250);
        assertEq(_balance(bob), 5);
        assertEq(_clear(token.confidentialTotalSupply()), 1_255);
    }

    function test_mint_revertsForNonOwner() public {
        (externalEuint64 handle, bytes memory proof) = _input(1);
        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", alice));
        token.confidentialMint(alice, handle, proof);
    }

    /// The per-investor isolation guarantee: a holder is allowed on their OWN balance handle and the
    /// registered viewers on every handle - but nobody else's.
    function test_mint_grantsHolderAndViewersOnlyTheirOwnHandles() public {
        _mint(alice, 100);
        _mint(bob, 200);
        euint64 aliceBal = token.confidentialBalanceOf(alice);
        euint64 bobBal = token.confidentialBalanceOf(bob);

        assertTrue(_persistAllowed(aliceBal, alice));
        assertTrue(_persistAllowed(aliceBal, operatorViewer));
        assertTrue(_persistAllowed(aliceBal, auditorViewer));
        assertTrue(_persistAllowed(aliceBal, address(token)));
        assertFalse(_persistAllowed(aliceBal, bob), "bob must not read alice's balance");
        assertFalse(_persistAllowed(bobBal, alice), "alice must not read bob's balance");
        assertTrue(_persistAllowed(token.confidentialTotalSupply(), operatorViewer));
    }

    // ── transfer ─────────────────────────────────────────────────────────────

    function test_transfer_movesTheAmount() public {
        _mint(alice, 1_000);
        (externalEuint64 handle, bytes memory proof) = _input(400);
        vm.prank(alice);
        euint64 transferred = token.confidentialTransfer(bob, handle, proof);

        assertEq(_clear(transferred), 400);
        assertEq(_balance(alice), 600);
        assertEq(_balance(bob), 400);
        assertEq(_clear(token.confidentialTotalSupply()), 1_000, "a transfer does not change the supply");
        // the receiver's new balance handle carries the receiver's own grant (a fresh ciphertext has none)
        assertTrue(_persistAllowed(token.confidentialBalanceOf(bob), bob));
        assertTrue(_persistAllowed(token.confidentialBalanceOf(alice), alice));
        assertTrue(_persistAllowed(token.confidentialBalanceOf(bob), operatorViewer));
    }

    /// ERC-7984 "silent failure": with too little balance the transfer moves zero instead of reverting
    /// (a revert would leak that the balance was too low).
    function test_transfer_withInsufficientBalanceMovesNothing() public {
        _mint(alice, 100);
        (externalEuint64 handle, bytes memory proof) = _input(101);
        vm.prank(alice);
        euint64 transferred = token.confidentialTransfer(bob, handle, proof);

        assertEq(_clear(transferred), 0);
        assertEq(_balance(alice), 100);
        assertEq(_balance(bob), 0);
    }

    function test_transfer_exactBalanceIsAllowed() public {
        _mint(alice, 100);
        (externalEuint64 handle, bytes memory proof) = _input(100);
        vm.prank(alice);
        token.confidentialTransfer(bob, handle, proof);
        assertEq(_balance(alice), 0);
        assertEq(_balance(bob), 100);
    }

    // ── approve / transferFrom ───────────────────────────────────────────────

    function test_approve_storesTheAllowanceForOwnerAndSpender() public {
        (externalEuint64 handle, bytes memory proof) = _input(300);
        vm.prank(alice);
        token.confidentialApprove(spender, handle, proof);

        euint64 allowance = token.confidentialAllowance(alice, spender);
        assertEq(_clear(allowance), 300);
        assertTrue(_persistAllowed(allowance, spender));
        assertTrue(_persistAllowed(allowance, address(token)));
    }

    function test_transferFrom_consumesWhatActuallyMovedAndRegrantsTheSpender() public {
        _mint(alice, 1_000);
        (externalEuint64 ah, bytes memory ap) = _input(300);
        vm.prank(alice);
        token.confidentialApprove(spender, ah, ap);

        (externalEuint64 th, bytes memory tp) = _input(120);
        vm.prank(spender);
        euint64 transferred = token.confidentialTransferFrom(alice, bob, th, tp);

        assertEq(_clear(transferred), 120);
        assertEq(_balance(alice), 880);
        assertEq(_balance(bob), 120);
        euint64 remaining = token.confidentialAllowance(alice, spender);
        assertEq(_clear(remaining), 180);
        // sub() produced a fresh handle: the spender must have been re-granted on it
        assertTrue(_persistAllowed(remaining, spender), "spender locked out of its own remaining allowance");
    }

    function test_transferFrom_aboveTheAllowanceMovesNothing() public {
        _mint(alice, 1_000);
        (externalEuint64 ah, bytes memory ap) = _input(50);
        vm.prank(alice);
        token.confidentialApprove(spender, ah, ap);

        (externalEuint64 th, bytes memory tp) = _input(51);
        vm.prank(spender);
        euint64 transferred = token.confidentialTransferFrom(alice, bob, th, tp);

        assertEq(_clear(transferred), 0);
        assertEq(_balance(alice), 1_000);
        assertEq(_clear(token.confidentialAllowance(alice, spender)), 50, "a failed move must not burn the allowance");
    }

    /// If the allowance would cover it but the balance does not, the allowance is not consumed either.
    function test_transferFrom_withInsufficientBalanceKeepsTheAllowance() public {
        _mint(alice, 10);
        (externalEuint64 ah, bytes memory ap) = _input(500);
        vm.prank(alice);
        token.confidentialApprove(spender, ah, ap);

        (externalEuint64 th, bytes memory tp) = _input(100);
        vm.prank(spender);
        euint64 transferred = token.confidentialTransferFrom(alice, bob, th, tp);

        assertEq(_clear(transferred), 0);
        assertEq(_clear(token.confidentialAllowance(alice, spender)), 500);
    }

    function test_transferFrom_withoutAnyApprovalMovesNothing() public {
        _mint(alice, 100);
        (externalEuint64 th, bytes memory tp) = _input(1);
        vm.prank(spender);
        euint64 transferred = token.confidentialTransferFrom(alice, bob, th, tp);
        assertEq(_clear(transferred), 0);
        assertEq(_balance(alice), 100);
    }

    // ── burn ─────────────────────────────────────────────────────────────────

    function test_burn_reducesBalanceAndSupplyAndRegrantsTheHolder() public {
        _mint(alice, 1_000);
        (externalEuint64 handle, bytes memory proof) = _input(400);
        vm.prank(owner);
        token.confidentialBurn(alice, handle, proof);

        assertEq(_balance(alice), 600);
        assertEq(_clear(token.confidentialTotalSupply()), 600);
        // the post-burn handle is brand new: holder and viewers must have been re-granted on it
        assertTrue(_persistAllowed(token.confidentialBalanceOf(alice), alice), "holder locked out after a burn");
        assertTrue(_persistAllowed(token.confidentialBalanceOf(alice), operatorViewer));
        assertTrue(_persistAllowed(token.confidentialTotalSupply(), auditorViewer));
    }

    function test_burn_aboveTheBalanceBurnsNothing() public {
        _mint(alice, 100);
        (externalEuint64 handle, bytes memory proof) = _input(101);
        vm.prank(owner);
        token.confidentialBurn(alice, handle, proof);
        assertEq(_balance(alice), 100);
        assertEq(_clear(token.confidentialTotalSupply()), 100);
    }

    function test_burn_revertsForNonOwner() public {
        (externalEuint64 handle, bytes memory proof) = _input(1);
        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", alice));
        token.confidentialBurn(alice, handle, proof);
    }

    // ── viewers ──────────────────────────────────────────────────────────────

    /// ACL grants are additive and per handle: a viewer added later sees the NEXT handles, and one
    /// removed keeps what it was already granted.
    function test_viewerChangesApplyToFutureHandlesOnly() public {
        address issuerWallet = makeAddr("issuerWallet");
        _mint(alice, 100);
        euint64 before = token.confidentialBalanceOf(alice);

        vm.prank(owner);
        token.addViewer(issuerWallet);
        assertFalse(_persistAllowed(before, issuerWallet), "no retroactive grant on an untouched handle");

        _mint(alice, 1);
        euint64 afterMint = token.confidentialBalanceOf(alice);
        assertTrue(_persistAllowed(afterMint, issuerWallet));

        vm.prank(owner);
        token.removeViewer(operatorViewer);
        assertTrue(_persistAllowed(afterMint, operatorViewer), "ACL has no revoke: the old grant stays");
        _mint(alice, 1);
        assertFalse(_persistAllowed(token.confidentialBalanceOf(alice), operatorViewer), "but new handles exclude it");
    }

    // ── supply disclosure (public decryption) ────────────────────────────────

    function test_requestSupplyDisclosure_marksTheHandlePubliclyDecryptable() public {
        _mint(alice, 700);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();

        euint64 handle = token.supplyDisclosureHandle(requestId);
        assertEq(euint64.unwrap(handle), euint64.unwrap(token.confidentialTotalSupply()));
        assertTrue(acl.isAllowedForDecryption(euint64.unwrap(handle)));
        assertFalse(token.supplyDisclosureFulfilled(requestId));
    }

    function test_requestSupplyDisclosure_isOwnerOnly() public {
        vm.prank(alice);
        vm.expectRevert(abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", alice));
        token.requestSupplyDisclosure();
    }

    function test_fulfillSupplyDisclosure_recordsAKmsSignedValue() public {
        _mint(alice, 700);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();
        (bytes memory cleartexts, bytes memory proof) = _decryptionProof(token.supplyDisclosureHandle(requestId), 700);

        vm.expectEmit(true, false, false, true, address(token));
        emit ConfidentialERC20.SupplyDisclosureFulfilled(requestId, 700);
        vm.prank(makeAddr("anyRelayer")); // permissionless: the proof is the authority
        token.fulfillSupplyDisclosure(requestId, cleartexts, proof);

        assertEq(token.lastDisclosedSupply(), 700);
        assertEq(token.lastDisclosureRequestId(), requestId);
        assertTrue(token.supplyDisclosureFulfilled(requestId));
    }

    function test_fulfillSupplyDisclosure_rejectsAValueTheKmsDidNotSign() public {
        _mint(alice, 700);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();
        (, bytes memory proof) = _decryptionProof(token.supplyDisclosureHandle(requestId), 700);

        vm.expectRevert(FHE.InvalidKMSSignatures.selector);
        token.fulfillSupplyDisclosure(requestId, abi.encode(uint64(1)), proof); // a different cleartext
        assertFalse(token.supplyDisclosureFulfilled(requestId));
    }

    function test_fulfillSupplyDisclosure_rejectsAForeignSigner() public {
        _mint(alice, 700);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();
        euint64 handle = token.supplyDisclosureHandle(requestId);
        bytes32[] memory handles = new bytes32[](1);
        handles[0] = euint64.unwrap(handle);
        bytes memory cleartexts = abi.encode(uint64(700));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(0xBAD, kms.digestOf(handles, cleartexts));

        vm.expectRevert(FHE.InvalidKMSSignatures.selector);
        token.fulfillSupplyDisclosure(requestId, cleartexts, abi.encodePacked(uint8(1), r, s, v));
    }

    function test_fulfillSupplyDisclosure_unknownAndRepeatedRequests() public {
        vm.expectRevert(abi.encodeWithSelector(ConfidentialERC20.UnknownDisclosureRequest.selector, 42));
        token.fulfillSupplyDisclosure(42, "", "");

        _mint(alice, 5);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();
        (bytes memory cleartexts, bytes memory proof) = _decryptionProof(token.supplyDisclosureHandle(requestId), 5);
        token.fulfillSupplyDisclosure(requestId, cleartexts, proof);

        vm.expectRevert(abi.encodeWithSelector(ConfidentialERC20.DisclosureAlreadyFulfilled.selector, requestId));
        token.fulfillSupplyDisclosure(requestId, cleartexts, proof);
    }

    /// A request is bound to the handle it was made for: a later mint changes the live supply handle
    /// and neither invalidates the open request nor leaks the new supply through it.
    function test_supplyDisclosure_staysBoundToTheRequestedHandle() public {
        _mint(alice, 100);
        vm.prank(owner);
        uint256 requestId = token.requestSupplyDisclosure();
        euint64 requested = token.supplyDisclosureHandle(requestId);

        _mint(alice, 50); // supply is now 150, behind a new handle
        assertTrue(euint64.unwrap(requested) != euint64.unwrap(token.confidentialTotalSupply()));
        assertFalse(acl.isAllowedForDecryption(euint64.unwrap(token.confidentialTotalSupply())));

        (bytes memory cleartexts, bytes memory proof) = _decryptionProof(requested, 100);
        token.fulfillSupplyDisclosure(requestId, cleartexts, proof);
        assertEq(token.lastDisclosedSupply(), 100, "the disclosed value is the supply at request time");
    }

    /// Fulfilment is permissionless, and the KMS proof of an older request stays valid. Relaying it after a
    /// newer request was fulfilled must not roll "the last disclosed supply" back to the older figure.
    function test_supplyDisclosure_olderRequestFulfilledLateDoesNotOverwriteTheNewerFigure() public {
        _mint(alice, 100);
        vm.prank(owner);
        uint256 first = token.requestSupplyDisclosure();
        euint64 firstHandle = token.supplyDisclosureHandle(first);

        _mint(alice, 50);
        vm.prank(owner);
        uint256 second = token.requestSupplyDisclosure();
        euint64 secondHandle = token.supplyDisclosureHandle(second);

        (bytes memory newerClear, bytes memory newerProof) = _decryptionProof(secondHandle, 150);
        token.fulfillSupplyDisclosure(second, newerClear, newerProof);
        assertEq(token.lastDisclosedSupply(), 150);

        (bytes memory olderClear, bytes memory olderProof) = _decryptionProof(firstHandle, 100);
        vm.expectEmit(true, false, false, true);
        emit ConfidentialERC20.SupplyDisclosureFulfilled(first, 100);
        token.fulfillSupplyDisclosure(first, olderClear, olderProof);

        assertTrue(token.supplyDisclosureFulfilled(first), "the older request is still recorded as fulfilled");
        assertEq(token.lastDisclosedSupply(), 150, "but the newest figure stays");
        assertEq(token.lastDisclosureRequestId(), second);
    }
}
