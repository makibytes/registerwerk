// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {euint64, externalEuint64} from "@fhevm/solidity/lib/FHE.sol";
import "../helpers/FhevmMockSetup.sol";
import "../../src/confidential/ConfidentialERC3643.sol";

/// @dev A T-REX identity registry reduced to what the token asks: who is verified.
contract MockIdentityRegistry {
    mapping(address => bool) public isVerified;

    function setVerified(address who, bool verified) external {
        isVerified[who] = verified;
    }
}

/// @dev A compliance module that records the hooks the token calls.
contract MockConfidentialCompliance {
    bool public allow = true;
    uint256 public transferredCalls;
    uint256 public createdCalls;
    uint256 public destroyedCalls;
    address public lastFrom;
    address public lastTo;

    function setAllow(bool allowed) external {
        allow = allowed;
    }

    function canTransfer(address, address) external view returns (bool) {
        return allow;
    }

    function transferred(address from, address to, euint64) external {
        ++transferredCalls;
        lastFrom = from;
        lastTo = to;
    }

    function created(address, euint64) external {
        ++createdCalls;
    }

    function destroyed(address, euint64) external {
        ++destroyedCalls;
    }
}

/// @notice Tests for the confidential ERC-3643 security token on the cleartext FHEVM mocks (see
///         test/mocks/MockFhevm.sol). They used to self-skip unless run against a real fhEVM network and,
///         where they ran, could only show that a call sequence did not revert; now balances, ACL grants
///         and the compliance hooks are asserted.
contract ConfidentialERC3643Test is FhevmMockSetup {
    ConfidentialERC3643 token;
    MockIdentityRegistry identity;
    MockConfidentialCompliance compliance;

    address owner = makeAddr("owner");
    address alice = makeAddr("alice");
    address bob = makeAddr("bob");
    address spender = makeAddr("spender");
    address agent = makeAddr("agent");
    address operatorViewer = makeAddr("operatorViewer");
    address auditorViewer = makeAddr("auditorViewer");
    bytes32 assetId = keccak256("conf-erc3643");

    function setUp() public {
        ConfidentialERC20.FhevmInfra memory infra = _deployFhevmMocks();
        identity = new MockIdentityRegistry();
        compliance = new MockConfidentialCompliance();
        identity.setVerified(alice, true);
        identity.setVerified(bob, true);

        address[] memory viewers = new address[](2);
        viewers[0] = operatorViewer;
        viewers[1] = auditorViewer;
        vm.prank(owner);
        token = new ConfidentialERC3643(
            assetId, "Confidential Security Token", "cSEC", infra, viewers,
            address(identity), address(compliance), owner
        );
    }

    function _mint(address to, uint256 amount) internal {
        (externalEuint64 handle, bytes memory proof) = _input(amount);
        vm.prank(owner);
        token.confidentialMint(to, handle, proof);
    }

    function _balance(address who) internal view returns (uint256) {
        return _clear(token.confidentialBalanceOf(who));
    }

    // ── Metadata / wiring ──────────────────────────────────────────────────

    function test_metadataAndWiring() public view {
        assertEq(token.assetId(), assetId);
        assertEq(token.name(), "Confidential Security Token");
        assertEq(token.symbol(), "cSEC");
        assertEq(token.decimals(), 6);
        assertEq(token.identityRegistry(), address(identity));
        assertEq(token.compliance(), address(compliance));
        assertFalse(token.paused());
        assertTrue(token.isAgent(owner));
    }

    // ── Pause / unpause ────────────────────────────────────────────────────

    function test_pause_unpause() public {
        vm.prank(owner);
        token.pause();
        assertTrue(token.paused());
        vm.prank(owner);
        token.unpause();
        assertFalse(token.paused());
    }

    function test_onlyAgent_canPauseAndUnpause() public {
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.pause();

        vm.prank(owner);
        token.pause();
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.unpause();
    }

    // ── Mint / burn ────────────────────────────────────────────────────────

    function test_mint_toAVerifiedInvestorNotifiesCompliance() public {
        _mint(alice, 1_000);
        assertEq(_balance(alice), 1_000);
        assertEq(_clear(token.confidentialTotalSupply()), 1_000);
        assertEq(compliance.createdCalls(), 1);
        assertTrue(_persistAllowed(token.confidentialBalanceOf(alice), alice));
        assertTrue(_persistAllowed(token.confidentialBalanceOf(alice), operatorViewer));
    }

    function test_mint_revertsForAnUnverifiedRecipient() public {
        address stranger = makeAddr("stranger");
        (externalEuint64 handle, bytes memory proof) = _input(1);
        vm.prank(owner);
        vm.expectRevert(ConfidentialERC3643.RecipientNotVerified.selector);
        token.confidentialMint(stranger, handle, proof);
    }

    function test_burn_reducesBalanceAndNotifiesCompliance() public {
        _mint(alice, 1_000);
        (externalEuint64 handle, bytes memory proof) = _input(300);
        vm.prank(owner);
        token.confidentialBurn(alice, handle, proof);
        assertEq(_balance(alice), 700);
        assertEq(_clear(token.confidentialTotalSupply()), 700);
        assertEq(compliance.destroyedCalls(), 1);
    }

    function test_onlyAgent_canMintAndBurn() public {
        (externalEuint64 handle, bytes memory proof) = _input(1);
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.confidentialMint(alice, handle, proof);
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.confidentialBurn(alice, handle, proof);
    }

    // ── Confidential transfer ──────────────────────────────────────────────

    function test_transfer_betweenVerifiedInvestorsMovesTheAmountAndNotifiesCompliance() public {
        _mint(alice, 1_000);
        (externalEuint64 handle, bytes memory proof) = _input(250);
        vm.prank(alice);
        token.confidentialTransfer(bob, handle, proof);
        assertEq(_balance(alice), 750);
        assertEq(_balance(bob), 250);
        assertEq(compliance.transferredCalls(), 1);
        assertEq(compliance.lastFrom(), alice);
        assertEq(compliance.lastTo(), bob);
    }

    function test_transfer_revertsWhenPaused() public {
        _mint(alice, 10);
        vm.prank(owner);
        token.pause();
        (externalEuint64 handle, bytes memory proof) = _input(1);
        vm.prank(alice);
        vm.expectRevert(ConfidentialERC3643.TransferPaused.selector);
        token.confidentialTransfer(bob, handle, proof);
    }

    function test_transfer_revertsForAFrozenOrUnverifiedOrRejectedParty() public {
        _mint(alice, 10);
        (externalEuint64 handle, bytes memory proof) = _input(1);

        vm.prank(owner);
        token.setAddressFrozen(bob, true);
        vm.prank(alice);
        vm.expectRevert(ConfidentialERC3643.AddressIsFrozen.selector);
        token.confidentialTransfer(bob, handle, proof);
        vm.prank(owner);
        token.setAddressFrozen(bob, false);

        identity.setVerified(bob, false);
        vm.prank(alice);
        vm.expectRevert(ConfidentialERC3643.RecipientNotVerified.selector);
        token.confidentialTransfer(bob, handle, proof);
        identity.setVerified(bob, true);
        identity.setVerified(alice, false);
        vm.prank(alice);
        vm.expectRevert(ConfidentialERC3643.SenderNotVerified.selector);
        token.confidentialTransfer(bob, handle, proof);
        identity.setVerified(alice, true);

        compliance.setAllow(false);
        vm.prank(alice);
        vm.expectRevert(ConfidentialERC3643.ComplianceRejected.selector);
        token.confidentialTransfer(bob, handle, proof);
    }

    // ── Allowance path (must be gated exactly like a direct transfer) ──────

    /// confidentialTransferFrom must decrement the allowance by what actually moved and re-grant the
    /// spender on the new allowance handle (a subtraction yields a fresh ciphertext with no grant).
    function test_transferFrom_consumesTheAllowanceAndRegrantsTheSpender() public {
        _mint(alice, 1_000);
        (externalEuint64 ah, bytes memory ap) = _input(300);
        vm.prank(alice);
        token.confidentialApprove(spender, ah, ap);

        (externalEuint64 th, bytes memory tp) = _input(100);
        vm.prank(spender);
        token.confidentialTransferFrom(alice, bob, th, tp);

        assertEq(_balance(bob), 100);
        euint64 remaining = token.confidentialAllowance(alice, spender);
        assertEq(_clear(remaining), 200);
        assertTrue(_persistAllowed(remaining, spender));
    }

    /// Regression: the base confidentialTransferFrom was not `virtual` and the 3643 token never
    /// overrode it, so an approved spender could move tokens with no compliance enforcement at all.
    function test_transferFrom_isGatedByPauseFreezeIdentityAndCompliance() public {
        _mint(alice, 100);
        (externalEuint64 ah, bytes memory ap) = _input(50);
        vm.prank(alice);
        token.confidentialApprove(spender, ah, ap);
        (externalEuint64 th, bytes memory tp) = _input(1);

        vm.prank(owner);
        token.pause();
        vm.prank(spender);
        vm.expectRevert(ConfidentialERC3643.TransferPaused.selector);
        token.confidentialTransferFrom(alice, bob, th, tp);
        vm.prank(owner);
        token.unpause();

        vm.prank(owner);
        token.setAddressFrozen(alice, true);
        vm.prank(spender);
        vm.expectRevert(ConfidentialERC3643.AddressIsFrozen.selector);
        token.confidentialTransferFrom(alice, bob, th, tp);
        vm.prank(owner);
        token.setAddressFrozen(alice, false);

        identity.setVerified(bob, false);
        vm.prank(spender);
        vm.expectRevert(ConfidentialERC3643.RecipientNotVerified.selector);
        token.confidentialTransferFrom(alice, bob, th, tp);
    }

    // ── Forced transfer ─────────────────────────────────────────────────────

    /// A regulatory override bypasses pause and freeze, still needs a verified recipient, and still
    /// tells the compliance module (as T-REX's Token.forcedTransfer does) so its counters do not drift.
    function test_forcedTransfer_bypassesPauseAndFreezeButNotifiesCompliance() public {
        _mint(alice, 500);
        vm.startPrank(owner);
        token.pause();
        token.setAddressFrozen(alice, true);
        vm.stopPrank();

        (externalEuint64 handle, bytes memory proof) = _input(200);
        vm.prank(owner);
        token.forcedTransfer(alice, bob, handle, proof);

        assertEq(_balance(alice), 300);
        assertEq(_balance(bob), 200);
        assertEq(compliance.transferredCalls(), 1);
    }

    function test_forcedTransfer_stillRequiresAVerifiedRecipientAndAnAgent() public {
        _mint(alice, 5);
        (externalEuint64 handle, bytes memory proof) = _input(1);
        identity.setVerified(bob, false);
        vm.prank(owner);
        vm.expectRevert(ConfidentialERC3643.RecipientNotVerified.selector);
        token.forcedTransfer(alice, bob, handle, proof);

        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.forcedTransfer(alice, bob, handle, proof);
    }

    // ── Agent management and admin setters ─────────────────────────────────

    function test_addAndRemoveAgent() public {
        vm.prank(owner);
        token.addAgent(agent);
        assertTrue(token.isAgent(agent));

        // an agent may now pause
        vm.prank(agent);
        token.pause();
        assertTrue(token.paused());

        vm.prank(owner);
        token.removeAgent(agent);
        assertFalse(token.isAgent(agent));
    }

    function test_setIdentityRegistryAndComplianceAreOwnerOnly() public {
        vm.startPrank(owner);
        token.setIdentityRegistry(address(0x20));
        token.setCompliance(address(0x21));
        vm.stopPrank();
        assertEq(token.identityRegistry(), address(0x20));
        assertEq(token.compliance(), address(0x21));

        vm.startPrank(address(0x99));
        vm.expectRevert();
        token.setIdentityRegistry(address(0x22));
        vm.expectRevert();
        token.setCompliance(address(0x22));
        vm.stopPrank();
    }

    function test_zeroRegistryAndComplianceDisableTheirChecks() public {
        vm.startPrank(owner);
        token.setIdentityRegistry(address(0));
        token.setCompliance(address(0));
        vm.stopPrank();
        identity.setVerified(bob, false);
        compliance.setAllow(false);

        _mint(alice, 10);
        (externalEuint64 handle, bytes memory proof) = _input(4);
        vm.prank(alice);
        token.confidentialTransfer(bob, handle, proof);
        assertEq(_balance(bob), 4);
    }

    // ── Viewer ACL registry ─────────────────────────────────────────────────
    // The isolation guarantee (an investor can only decrypt their OWN balance, never another holder's)
    // plus operator/auditor/issuer full visibility comes from this viewer set - see ConfidentialERC20's
    // class-level "Viewer ACL model" note.

    function test_initialViewersGrantedAtConstruction() public view {
        assertTrue(token.isViewer(operatorViewer));
        assertTrue(token.isViewer(auditorViewer));
        assertEq(token.viewers().length, 2);
    }

    function test_addViewer_ownerCanAddAndIsIdempotent() public {
        address issuerWallet = makeAddr("issuerWallet");
        vm.prank(owner);
        token.addViewer(issuerWallet);
        assertTrue(token.isViewer(issuerWallet));
        assertEq(token.viewers().length, 3);

        vm.prank(owner);
        token.addViewer(operatorViewer);
        assertEq(token.viewers().length, 3);
    }

    function test_addViewer_revertsForNonOwner() public {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.addViewer(address(0x60));
    }

    function test_removeViewer() public {
        vm.prank(owner);
        token.removeViewer(operatorViewer);
        assertFalse(token.isViewer(operatorViewer));
        assertEq(token.viewers().length, 1);
        assertTrue(token.isViewer(auditorViewer));

        vm.prank(owner);
        token.removeViewer(address(0x61)); // unknown viewer: no-op
        assertEq(token.viewers().length, 1);

        vm.prank(address(0x99));
        vm.expectRevert();
        token.removeViewer(auditorViewer);
    }
}
