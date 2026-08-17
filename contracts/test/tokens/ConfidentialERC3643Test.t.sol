// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/confidential/ConfidentialERC3643.sol";

/// @notice Tests for the fhEVM-backed confidential ERC-3643 token.
///
/// @dev Every state-changing operation (including the constructor) calls TFHE
///      precompiles that only exist on a real fhEVM coprocessor network (Zama's own
///      Sepolia/Ethereum deployments, or T-REX Chain once it publishes its FHEVM
///      infrastructure addresses — NOT Fhenix/Inco, which are separate, non-Zama FHE
///      stacks). On a vanilla EVM the deployment reverts, so all tests self-skip unless
///      run against a real fhEVM fork:
///        forge test --fork-url $SEPOLIA_RPC --match-contract ConfidentialERC3643Test
contract ConfidentialERC3643Test is Test {

    ConfidentialERC3643 token;
    bool fhevmAvailable;

    address owner = address(0x1);
    // Zama's real, documented Sepolia testnet addresses (lib/fhevm/config/ZamaFHEVMConfig.sol).
    ConfidentialERC20.FhevmInfra sepoliaInfra = ConfidentialERC20.FhevmInfra({
        aclAddress: 0xFee8407e2f5e3Ee68ad77cAE98c434e637f516e5,
        tfheExecutorAddress: 0x687408aB54661ba0b4aeF3a44156c616c6955E07,
        fhePaymentAddress: 0xFb03BE574d14C256D56F09a198B586bdfc0A9de2,
        kmsVerifierAddress: 0x9D6891A6240D6130c54ae243d8005063D05fE14b,
        gatewayAddress: 0x33347831500F1e73f0ccCBb95c9f86B94d7b1123
    });
    bytes32 assetId = keccak256("conf-erc3643");
    address operatorViewer = address(0x50);
    address auditorViewer = address(0x51);
    address[] initialViewers;

    function setUp() public {
        initialViewers.push(operatorViewer);
        initialViewers.push(auditorViewer);
        vm.prank(owner);
        try new ConfidentialERC3643(
            assetId,
            "Confidential Security Token",
            "cSEC",
            sepoliaInfra,
            initialViewers,
            address(0x10),  // identity registry (stub)
            address(0x11),  // compliance (stub)
            owner
        ) returns (ConfidentialERC3643 deployed) {
            token = deployed;
            fhevmAvailable = true;
        } catch {
            fhevmAvailable = false;
        }
    }

    modifier onFhevm() {
        vm.skip(!fhevmAvailable);
        _;
    }

    // ── Metadata ───────────────────────────────────────────────────────────

    function test_assetId_stored() public onFhevm {
        assertEq(token.assetId(), assetId);
    }

    function test_name_and_symbol() public onFhevm {
        assertEq(token.name(), "Confidential Security Token");
        assertEq(token.symbol(), "cSEC");
    }

    function test_decimals() public onFhevm {
        assertEq(token.decimals(), 6);
    }

    function test_initialIdentityRegistry() public onFhevm {
        assertEq(token.identityRegistry(), address(0x10));
    }

    function test_initialCompliance() public onFhevm {
        assertEq(token.compliance(), address(0x11));
    }

    // ── Pause / unpause ────────────────────────────────────────────────────

    function test_notPausedOnDeploy() public onFhevm {
        assertFalse(token.paused());
    }

    function test_pause_unpause() public onFhevm {
        vm.prank(owner);
        token.pause();
        assertTrue(token.paused());

        vm.prank(owner);
        token.unpause();
        assertFalse(token.paused());
    }

    function test_onlyAgent_canPause() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.pause();
    }

    function test_onlyAgent_canUnpause() public onFhevm {
        vm.prank(owner);
        token.pause();

        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.unpause();
    }

    // ── Confidential transfer ──────────────────────────────────────────────

    function test_confidentialTransfer_revertsWhenPaused() public onFhevm {
        vm.prank(owner);
        token.pause();

        vm.expectRevert(ConfidentialERC3643.TransferPaused.selector);
        token.confidentialTransfer(address(0x3), einput.wrap(bytes32(0)), hex"");
    }

    /// @notice confidentialTransferFrom must
    ///         (a) decrement the spender's allowance by what actually moved, not the requested
    ///         amount, so a transfer that moves zero tokens because the balance check
    ///         independently fails cannot still burn the allowance, and (b) re-grant the
    ///         spender's own ACL on the resulting allowance handle, since TFHE.sub produces a
    ///         fresh ciphertext with no carried-over grant. Neither can be asserted by decrypting
    ///         a value in a plain Foundry unit test (no off-chain Relayer/userDecrypt available
    ///         here) — this only proves the call sequence completes without reverting under a
    ///         real fhEVM; full verification requires `forge test --fork-url $SEPOLIA_RPC` plus
    ///         the Zama Relayer SDK to actually decrypt the post-call allowance both on-chain
    ///         (via requestOperatorDecrypt) and as the spender (via userDecrypt).
    ///         Identity/compliance are disabled here since this test's purpose is allowance/ACL
    ///         correctness, not compliance gating (covered by the tests below) — the configured
    ///         identityRegistry/compliance are unmocked stub addresses with no contract code, so
    ///         a bool-returning call against them (isVerified/canTransfer) would itself revert.
    function test_confidentialTransferFrom_doesNotRevert_afterApprove() public onFhevm {
        address spender = address(0x60);
        vm.startPrank(owner);
        token.setIdentityRegistry(address(0));
        token.setCompliance(address(0));
        token.confidentialApprove(spender, einput.wrap(bytes32(0)), hex"");
        vm.stopPrank();

        vm.prank(spender);
        token.confidentialTransferFrom(owner, address(0x61), einput.wrap(bytes32(0)), hex"");
    }

    /// @notice Regression test for: before the fix, the base
    ///         ConfidentialERC20.confidentialTransferFrom wasn't `virtual` and ConfidentialERC3643
    ///         never overrode it — so an approved spender could move encrypted tokens through the
    ///         allowance path with zero compliance enforcement, even while the token was paused.
    ///         Now confidentialTransferFrom must run the same _checkTransfer gate as
    ///         confidentialTransfer.
    function test_confidentialTransferFrom_revertsWhenPaused() public onFhevm {
        address spender = address(0x60);
        vm.prank(owner);
        token.confidentialApprove(spender, einput.wrap(bytes32(0)), hex"");

        vm.prank(owner);
        token.pause();

        vm.prank(spender);
        vm.expectRevert(ConfidentialERC3643.TransferPaused.selector);
        token.confidentialTransferFrom(owner, address(0x61), einput.wrap(bytes32(0)), hex"");
    }

    /// @notice Regression test for: a frozen sender must also block the
    ///         allowance-based path, not just direct confidentialTransfer.
    function test_confidentialTransferFrom_revertsWhenSenderFrozen() public onFhevm {
        address spender = address(0x60);
        vm.prank(owner);
        token.confidentialApprove(spender, einput.wrap(bytes32(0)), hex"");

        vm.prank(owner); // owner is an agent by default
        token.setAddressFrozen(owner, true);

        vm.prank(spender);
        vm.expectRevert(ConfidentialERC3643.AddressIsFrozen.selector);
        token.confidentialTransferFrom(owner, address(0x61), einput.wrap(bytes32(0)), hex"");
    }

    // ── Forced transfer ─────────────────────────────────────────────────────

    /// @notice Regression test for: forcedTransfer bypasses pause/freeze
    ///         intentionally (regulatory override) but must still notify the compliance module's
    ///         bookkeeping hook (transferred()), matching real T-REX's Token.forcedTransfer.
    ///         Compliance is left as the stub address here on purpose: transferred() has no
    ///         return value, so calling it against a stub with no contract code succeeds
    ///         trivially (no ABI-decode step) — this proves the added call doesn't break the
    ///         path, though a real IConfidentialCompliance mock would be needed to assert it was
    ///         actually invoked with the right arguments.
    function test_forcedTransfer_worksWhilePausedAndNotifiesCompliance() public onFhevm {
        vm.startPrank(owner);
        token.pause();
        token.setIdentityRegistry(address(0));
        vm.stopPrank();

        vm.prank(owner); // owner is an agent by default
        token.forcedTransfer(owner, address(0x62), einput.wrap(bytes32(0)), hex"");
    }

    function test_onlyAgent_canForceTransfer() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.forcedTransfer(owner, address(0x62), einput.wrap(bytes32(0)), hex"");
    }

    // ── Confidential mint / burn authorization ─────────────────────────────

    function test_onlyAgent_canMint() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.confidentialMint(address(0x2), einput.wrap(bytes32(0)), hex"");
    }

    function test_onlyAgent_canBurn() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert(ConfidentialERC3643.NotAgent.selector);
        token.confidentialBurn(address(0x2), einput.wrap(bytes32(0)), hex"");
    }

    // ── Agent management ───────────────────────────────────────────────────

    function test_addAndRemoveAgent() public onFhevm {
        address agent = address(0x42);
        vm.prank(owner);
        token.addAgent(agent);
        assertTrue(token.isAgent(agent));

        vm.prank(owner);
        token.removeAgent(agent);
        assertFalse(token.isAgent(agent));
    }

    // ── Admin setters ──────────────────────────────────────────────────────

    function test_setIdentityRegistry() public onFhevm {
        address newIR = address(0x20);
        vm.prank(owner);
        token.setIdentityRegistry(newIR);
        assertEq(token.identityRegistry(), newIR);
    }

    function test_setCompliance() public onFhevm {
        address newC = address(0x21);
        vm.prank(owner);
        token.setCompliance(newC);
        assertEq(token.compliance(), newC);
    }

    function test_onlyOwner_canSetIdentityRegistry() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.setIdentityRegistry(address(0x20));
    }

    // ── Viewer ACL registry ─────────────────────────────────────────────────
    // The isolation guarantee (an investor can only decrypt their OWN balance, never another
    // holder's) plus operator/auditor/issuer full visibility comes from this viewer set — see
    // ConfidentialERC20's class-level "Viewer ACL model" note. addViewer/removeViewer/viewers()
    // are plain storage operations (no TFHE precompile calls themselves) but the token can only be
    // constructed on a real fhEVM (see setUp), so these still self-skip per this file's convention.

    function test_initialViewersGrantedAtConstruction() public onFhevm {
        assertTrue(token.isViewer(operatorViewer));
        assertTrue(token.isViewer(auditorViewer));
        address[] memory current = token.viewers();
        assertEq(current.length, 2);
    }

    function test_addViewer_ownerCanAdd() public onFhevm {
        address issuerWallet = address(0x60);
        vm.prank(owner);
        token.addViewer(issuerWallet);
        assertTrue(token.isViewer(issuerWallet));
        assertEq(token.viewers().length, 3);
    }

    function test_addViewer_revertsForNonOwner() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.addViewer(address(0x60));
    }

    function test_addViewer_isIdempotent() public onFhevm {
        vm.startPrank(owner);
        token.addViewer(operatorViewer);
        vm.stopPrank();
        assertEq(token.viewers().length, 2);
    }

    function test_removeViewer_ownerCanRemove() public onFhevm {
        vm.prank(owner);
        token.removeViewer(operatorViewer);
        assertFalse(token.isViewer(operatorViewer));
        assertEq(token.viewers().length, 1);
        assertTrue(token.isViewer(auditorViewer));
    }

    function test_removeViewer_revertsForNonOwner() public onFhevm {
        vm.prank(address(0x99));
        vm.expectRevert();
        token.removeViewer(operatorViewer);
    }

    function test_removeViewer_noopForUnknownViewer() public onFhevm {
        vm.prank(owner);
        token.removeViewer(address(0x61));
        assertEq(token.viewers().length, 2);
    }
}
