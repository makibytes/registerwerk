// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "./mocks/MockOnchainId.sol";

contract OrgRegistryTest is Test {
    OrgRegistry registry;
    MockOnchainId orgId;

    address operator = address(0x1);
    address orgAdmin = address(0x2);
    address alice = address(0x3);
    uint256 constant BOB_PK = 0xB0B;
    address bob = vm.addr(BOB_PK);
    address stranger = address(0x5);

    bytes32 constant ROLE_TRADER = keccak256("TRADER");
    bytes32 constant ROLE_SIGNER = keccak256("SIGNER");
    uint16 constant COUNTRY_DE = 276;

    function setUp() public {
        registry = new OrgRegistry(operator);
        orgId = new MockOnchainId();
        orgId.setKey(orgAdmin, registry.MANAGEMENT_KEY_PURPOSE(), true);
    }

    function _registerOrg() internal {
        vm.prank(operator);
        registry.registerOrg(address(orgId), COUNTRY_DE);
    }

    function _roles(bytes32 a) internal pure returns (bytes32[] memory r) {
        r = new bytes32[](1);
        r[0] = a;
    }

    // -------------------------------------------------------------------------
    // Org lifecycle
    // -------------------------------------------------------------------------

    function test_registerOrg_activatesOrg() public {
        _registerOrg();
        assertTrue(registry.isOrgActive(address(orgId)));
        assertEq(registry.orgCountry(address(orgId)), COUNTRY_DE);
        assertEq(uint8(registry.orgStatus(address(orgId))), uint8(IOrgRegistry.OrgStatus.Active));
    }

    function test_registerOrg_emitsEvent() public {
        vm.prank(operator);
        vm.expectEmit(true, false, false, true);
        emit IOrgRegistry.OrgRegistered(address(orgId), COUNTRY_DE);
        registry.registerOrg(address(orgId), COUNTRY_DE);
    }

    function test_registerOrg_revertsForNonOperator() public {
        vm.prank(stranger);
        vm.expectRevert();
        registry.registerOrg(address(orgId), COUNTRY_DE);
    }

    function test_registerOrg_revertsForZeroAddress() public {
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: zero org address");
        registry.registerOrg(address(0), COUNTRY_DE);
    }

    function test_registerOrg_revertsIfAlreadyRegistered() public {
        _registerOrg();
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: org already registered");
        registry.registerOrg(address(orgId), COUNTRY_DE);
    }

    function test_suspendAndReinstateOrg() public {
        _registerOrg();
        vm.prank(operator);
        registry.suspendOrg(address(orgId), "sanctions hit");
        assertFalse(registry.isOrgActive(address(orgId)));

        vm.prank(operator);
        registry.reinstateOrg(address(orgId));
        assertTrue(registry.isOrgActive(address(orgId)));
    }

    function test_suspendOrg_revertsForNonOperator() public {
        _registerOrg();
        vm.prank(orgAdmin);
        vm.expectRevert();
        registry.suspendOrg(address(orgId), "nope");
    }

    function test_suspendOrg_revertsIfNotActive() public {
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: org not active");
        registry.suspendOrg(address(orgId), "unregistered");
    }

    // -------------------------------------------------------------------------
    // Membership — operator path
    // -------------------------------------------------------------------------

    function test_addMember_byOperator() public {
        _registerOrg();
        vm.prank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");

        assertEq(registry.orgOf(alice), address(orgId));
        assertTrue(registry.isActiveMember(alice));
        assertTrue(registry.memberHasRole(alice, ROLE_TRADER));
        assertFalse(registry.memberHasRole(alice, ROLE_SIGNER));
    }

    function test_addMember_emitsEvent() public {
        _registerOrg();
        vm.prank(operator);
        vm.expectEmit(true, true, false, true);
        emit IOrgRegistry.MemberAdded(address(orgId), alice, _roles(ROLE_TRADER));
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
    }

    function test_addMember_revertsIfWalletAlreadyBound() public {
        _registerOrg();
        vm.startPrank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
        vm.expectRevert("OrgRegistry: wallet already bound");
        registry.addMember(address(orgId), alice, _roles(ROLE_SIGNER), "");
        vm.stopPrank();
    }

    function test_addMember_revertsIfOrgNotRegistered() public {
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: org not registered");
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
    }

    function test_addMember_revertsForTooManyRoles() public {
        _registerOrg();
        bytes32[] memory roles = new bytes32[](17);
        for (uint256 i = 0; i < roles.length; i++) {
            roles[i] = keccak256(abi.encode(i));
        }
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: too many roles");
        registry.addMember(address(orgId), alice, roles, "");
    }

    function test_removeMember_unbindsWalletAndAllowsRebinding() public {
        _registerOrg();
        vm.startPrank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
        registry.removeMember(address(orgId), alice);
        vm.stopPrank();

        assertEq(registry.orgOf(alice), address(0));
        assertFalse(registry.isActiveMember(alice));
        assertFalse(registry.memberHasRole(alice, ROLE_TRADER));
        assertEq(registry.memberRoles(alice).length, 0);

        // wallet is free to bind again
        vm.prank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_SIGNER), "");
        assertTrue(registry.memberHasRole(alice, ROLE_SIGNER));
    }

    function test_removeMember_revertsIfNotBoundToOrg() public {
        _registerOrg();
        vm.prank(operator);
        vm.expectRevert("OrgRegistry: wallet not bound to org");
        registry.removeMember(address(orgId), alice);
    }

    function test_setMemberRoles_replacesRoles() public {
        _registerOrg();
        vm.startPrank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
        registry.setMemberRoles(address(orgId), alice, _roles(ROLE_SIGNER));
        vm.stopPrank();

        assertFalse(registry.memberHasRole(alice, ROLE_TRADER));
        assertTrue(registry.memberHasRole(alice, ROLE_SIGNER));
    }

    // -------------------------------------------------------------------------
    // Membership — org-admin path (ONCHAINID MANAGEMENT key)
    // -------------------------------------------------------------------------

    /// @dev Builds and signs the EIP-712 MemberConsent digest for `wallet` at its current
    /// on-chain nonce — mirrors exactly what `OrgRegistry._hashTypedDataV4` computes.
    function _signConsent(uint256 signerPk, address org, address wallet) internal view returns (bytes memory) {
        uint256 nonce = registry.walletConsentNonce(wallet);
        bytes32 structHash = keccak256(abi.encode(registry.MEMBER_CONSENT_TYPEHASH(), org, wallet, nonce));
        bytes32 domainSeparator = keccak256(
            abi.encode(
                keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"),
                keccak256(bytes("Registerwerk OrgRegistry")),
                keccak256(bytes("1")),
                block.chainid,
                address(registry)
            )
        );
        bytes32 digest = keccak256(abi.encodePacked("\x19\x01", domainSeparator, structHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(signerPk, digest);
        return abi.encodePacked(r, s, v);
    }

    function test_addMember_byOrgAdmin() public {
        _registerOrg();
        bytes memory consent = _signConsent(BOB_PK, address(orgId), bob);
        vm.prank(orgAdmin);
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), consent);
        assertEq(registry.orgOf(bob), address(orgId));
    }

    function test_addMember_byOrgAdmin_revertsWithoutWalletConsent() public {
        _registerOrg();
        vm.prank(orgAdmin);
        vm.expectRevert("OrgRegistry: invalid wallet consent signature");
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), "");
    }

    function test_addMember_byOrgAdmin_revertsForConsentSignedByWrongWallet() public {
        _registerOrg();
        // Signed by "alice's" key (address(0x3) has no known private key, so use a fresh one)
        // rather than bob's — proves the org admin can't substitute any signature at all.
        uint256 impostorPk = 0xBAD;
        bytes memory consent = _signConsent(impostorPk, address(orgId), bob);
        vm.prank(orgAdmin);
        vm.expectRevert("OrgRegistry: invalid wallet consent signature");
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), consent);
    }

    function test_addMember_byOrgAdmin_revertsOnReplayedConsentAfterRemoval() public {
        _registerOrg();
        bytes memory consent = _signConsent(BOB_PK, address(orgId), bob);
        vm.startPrank(orgAdmin);
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), consent);
        registry.removeMember(address(orgId), bob);

        // Same signature, same nonce — must not be replayable to re-bind after removal.
        vm.expectRevert("OrgRegistry: invalid wallet consent signature");
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), consent);
        vm.stopPrank();
    }

    function test_addMember_byOperator_neverNeedsWalletConsent() public {
        // The operator-relayed path is already gated off-chain (MemberWalletService's
        // nonce-challenge flow) before the backend ever broadcasts — empty consent is
        // correct and sufficient here, not a bypass.
        _registerOrg();
        vm.prank(operator);
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), "");
        assertEq(registry.orgOf(bob), address(orgId));
    }

    function test_addMember_revertsForStranger() public {
        _registerOrg();
        vm.prank(stranger);
        vm.expectRevert("OrgRegistry: not operator or org admin");
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), "");
    }

    function test_orgAdmin_cannotAdministerSuspendedOrg() public {
        _registerOrg();
        vm.prank(operator);
        registry.suspendOrg(address(orgId), "review");

        vm.prank(orgAdmin);
        vm.expectRevert("OrgRegistry: org not active");
        registry.addMember(address(orgId), bob, _roles(ROLE_TRADER), "");
    }

    function test_operator_canRemoveMemberFromSuspendedOrg() public {
        _registerOrg();
        vm.startPrank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
        registry.suspendOrg(address(orgId), "review");
        registry.removeMember(address(orgId), alice);
        vm.stopPrank();
        assertEq(registry.orgOf(alice), address(0));
    }

    function test_isOrgAdmin_falseForEoaOrg() public {
        // org address without code must not qualify anyone as admin
        assertFalse(registry.isOrgAdmin(address(0xdead), orgAdmin));
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    function test_isActiveMember_falseWhileOrgSuspended() public {
        _registerOrg();
        vm.prank(operator);
        registry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");

        vm.prank(operator);
        registry.suspendOrg(address(orgId), "review");
        assertFalse(registry.isActiveMember(alice));

        vm.prank(operator);
        registry.reinstateOrg(address(orgId));
        assertTrue(registry.isActiveMember(alice));
    }

    function test_memberRoles_returnsAllRoles() public {
        _registerOrg();
        bytes32[] memory roles = new bytes32[](2);
        roles[0] = ROLE_TRADER;
        roles[1] = ROLE_SIGNER;
        vm.prank(operator);
        registry.addMember(address(orgId), alice, roles, "");

        bytes32[] memory got = registry.memberRoles(alice);
        assertEq(got.length, 2);
        assertEq(got[0], ROLE_TRADER);
        assertEq(got[1], ROLE_SIGNER);
    }
}
