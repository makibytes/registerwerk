// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@erc3643/compliance/modular/IModularCompliance.sol";
import "../examples/EwpgBondDesk.t.sol";

/// @notice Regression tests for {EwpgComplianceModule} access control (T1-14), country-0
///         fail-closed behaviour (T1-13) and per-ONCHAINID limits (T1-12), run against the
///         full T-REX bond suite of {EwpgBondDeskTest} (whose own tests are inherited and
///         therefore re-run here too).
contract EwpgComplianceModuleTest is EwpgBondDeskTest {
    address internal anyone = address(0xBEEF);
    address internal wallet2 = makeAddr("investor1-wallet2"); // second wallet of investor1's ONCHAINID

    function _registerSecondWallet() internal {
        vm.prank(operator);
        identityRegistry.registerIdentity(wallet2, IIdentity(investor1Identity), COUNTRY_DE);
    }

    function _mint(address to, uint256 amount) internal {
        vm.prank(operator); // operator is a token agent
        bond.mint(to, amount);
    }

    // ── T1-14: config is owner-gated ────────────────────────────────────────

    /// @notice Ported panel PoC: an arbitrary EOA used to be able to unblock a blocked
    ///         country, DoS issuance, freeze secondary trading and self-exempt as a pool.
    function test_anyoneCannotRewriteComplianceConfig() public {
        vm.prank(operator);
        complianceModule.blockCountry(complianceAddr, COUNTRY_BLOCKED);

        bytes memory denied =
            abi.encodeWithSelector(EwpgComplianceModule.CallerNotComplianceAdmin.selector, anyone, complianceAddr);
        address[] memory wallets = new address[](1);
        wallets[0] = investor1;

        vm.startPrank(anyone);
        vm.expectRevert(denied);
        complianceModule.unblockCountry(complianceAddr, COUNTRY_BLOCKED);
        vm.expectRevert(denied);
        complianceModule.blockCountry(complianceAddr, COUNTRY_DE);
        vm.expectRevert(denied);
        complianceModule.setMaxInvestors(complianceAddr, 1);
        vm.expectRevert(denied);
        complianceModule.setMaxBalance(complianceAddr, 1);
        vm.expectRevert(denied);
        complianceModule.setTransferCooldown(complianceAddr, 365 days);
        vm.expectRevert(denied);
        complianceModule.setNomineePool(complianceAddr, address(desk), true);
        vm.expectRevert(denied);
        complianceModule.syncHolders(complianceAddr, wallets);
        vm.stopPrank();

        assertTrue(complianceModule.isCountryBlocked(complianceAddr, COUNTRY_BLOCKED));
        assertFalse(complianceModule.isNomineePool(complianceAddr, address(desk)));
        (uint256 maxInv, uint256 maxBal, uint256 cooldown,,) = complianceModule.getConfig(complianceAddr);
        assertEq(maxInv, 0);
        assertEq(maxBal, 0);
        assertEq(cooldown, 0);
    }

    /// @notice The compliance itself may configure the module — the path upstream T-REX
    ///         modules use (owner-only `callModuleFunction`) and `complianceSettings` at deploy.
    function test_complianceCanConfigureViaCallModuleFunction() public {
        vm.prank(operator);
        IModularCompliance(complianceAddr).callModuleFunction(
            abi.encodeCall(EwpgComplianceModule.setMaxInvestors, (complianceAddr, 7)), address(complianceModule)
        );
        (uint256 maxInv,,,,) = complianceModule.getConfig(complianceAddr);
        assertEq(maxInv, 7);
    }

    /// @notice An address that never bound the module has no config to write.
    function test_unboundComplianceCannotBeConfigured() public {
        vm.prank(anyone);
        vm.expectRevert(); // ComplianceNotBound
        complianceModule.setMaxInvestors(anyone, 1);
    }

    // ── T1-13: country 0 fails closed while any country is blocked ──────────

    /// @notice Ported panel PoC: a recipient with no country on file used to pass a
    ///         country block list that can only contain 1..999.
    function test_countryZeroFailsClosedWhileAnyCountryBlocked() public {
        vm.prank(operator);
        identityRegistry.updateCountry(investor2, 0);
        // No geographic restriction configured: country 0 is not a reason to reject.
        assertTrue(complianceModule.moduleCheck(address(0), investor2, 1, complianceAddr));

        vm.prank(operator);
        complianceModule.blockCountry(complianceAddr, 840);
        assertFalse(complianceModule.moduleCheck(address(0), investor2, 1, complianceAddr));
        // A recipient with a known, non-blocked country is unaffected.
        assertTrue(complianceModule.moduleCheck(address(0), investor3, 1, complianceAddr));

        vm.prank(alice);
        vm.expectRevert();
        desk.subscribe(investor2, 1);
    }

    function test_blockedCountryCountIsIdempotent() public {
        vm.startPrank(operator);
        complianceModule.blockCountry(complianceAddr, 840);
        complianceModule.blockCountry(complianceAddr, 840);
        complianceModule.unblockCountry(complianceAddr, 124); // never blocked: no-op
        (,,, uint256 count,) = complianceModule.getConfig(complianceAddr);
        assertEq(count, 1);

        complianceModule.unblockCountry(complianceAddr, 840);
        complianceModule.unblockCountry(complianceAddr, 840);
        vm.stopPrank();
        (,,, count,) = complianceModule.getConfig(complianceAddr);
        assertEq(count, 0);

        vm.prank(operator);
        identityRegistry.updateCountry(investor2, 0);
        assertTrue(complianceModule.moduleCheck(address(0), investor2, 1, complianceAddr));
    }

    // ── T1-12: limits apply per ONCHAINID, not per wallet ───────────────────

    /// @notice Ported panel PoC: a second wallet bound to the same ONCHAINID used to get a
    ///         fresh max-balance allowance.
    function test_maxBalanceIsPerIdentityNotPerWallet() public {
        _registerSecondWallet();
        vm.prank(operator);
        complianceModule.setMaxBalance(complianceAddr, 100);

        vm.prank(alice);
        desk.subscribe(investor1, 100);
        assertEq(complianceModule.getIdentityBalance(complianceAddr, investor1Identity), 100);

        assertFalse(complianceModule.moduleCheck(address(0), wallet2, 1, complianceAddr));
        vm.prank(alice);
        vm.expectRevert();
        desk.subscribe(wallet2, 1);
    }

    /// @notice Moving units between two wallets of the same identity at the cap is allowed
    ///         and changes neither the identity's balance nor the investor count.
    function test_transferBetweenWalletsOfSameIdentityAtCapIsAllowed() public {
        _registerSecondWallet();
        vm.prank(operator);
        complianceModule.setMaxBalance(complianceAddr, 100);
        _mint(investor1, 100);

        vm.prank(investor1);
        bond.transfer(wallet2, 60);

        assertEq(bond.balanceOf(wallet2), 60);
        assertEq(complianceModule.getIdentityBalance(complianceAddr, investor1Identity), 100);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);
    }

    /// @notice maxInvestors counts one identity holding through two wallets as one investor.
    function test_maxInvestorsCountsIdentityWithTwoWalletsOnce() public {
        _registerSecondWallet();
        vm.prank(operator);
        complianceModule.setMaxInvestors(complianceAddr, 2);

        _mint(investor1, 10);
        _mint(wallet2, 10); // same identity: not a new investor
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);

        _mint(investor3, 10);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 2);
        assertFalse(complianceModule.moduleCheck(address(0), investor4, 1, complianceAddr));

        // The identity only drops out once both of its wallets are empty.
        vm.startPrank(operator);
        bond.burn(investor1, 10);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 2);
        bond.burn(wallet2, 10);
        vm.stopPrank();
        assertEq(complianceModule.getInvestorCount(complianceAddr), 1);
        assertTrue(complianceModule.moduleCheck(address(0), investor4, 1, complianceAddr));
    }

    /// @notice A module bound to a suite that already has holders picks them up via
    ///         `syncHolders`, which is idempotent (duplicates and re-runs are no-ops).
    function test_syncHoldersBackfillsExistingHoldersIdempotently() public {
        _registerSecondWallet();
        _mint(investor1, 30);
        _mint(wallet2, 20);
        _mint(investor3, 5);

        EwpgComplianceModule fresh = new EwpgComplianceModule();
        vm.prank(operator);
        IModularCompliance(complianceAddr).addModule(address(fresh));
        assertEq(fresh.getInvestorCount(complianceAddr), 0);

        address[] memory wallets = new address[](4);
        wallets[0] = investor1;
        wallets[1] = wallet2;
        wallets[2] = investor3;
        wallets[3] = investor1; // duplicate
        vm.startPrank(operator);
        fresh.syncHolders(complianceAddr, wallets);
        fresh.syncHolders(complianceAddr, wallets);
        vm.stopPrank();

        assertEq(fresh.getInvestorCount(complianceAddr), 2);
        assertEq(fresh.getIdentityBalance(complianceAddr, investor1Identity), 50);
        assertEq(fresh.getIdentityBalance(complianceAddr, investor3Identity), 5);
    }

    /// @notice Re-binding a wallet to another identity (`updateIdentity`) moves its tracked
    ///         balance to the new identity on the next sync instead of drifting.
    function test_reboundWalletMovesBalanceToNewIdentity() public {
        _registerSecondWallet();
        _mint(investor1, 30);
        _mint(wallet2, 20);

        vm.prank(operator);
        identityRegistry.updateIdentity(wallet2, IIdentity(investor3Identity));

        address[] memory wallets = new address[](1);
        wallets[0] = wallet2;
        vm.prank(operator);
        complianceModule.syncHolders(complianceAddr, wallets);

        assertEq(complianceModule.getIdentityBalance(complianceAddr, investor1Identity), 30);
        assertEq(complianceModule.getIdentityBalance(complianceAddr, investor3Identity), 20);
        assertEq(complianceModule.getInvestorCount(complianceAddr), 2);
    }
}
