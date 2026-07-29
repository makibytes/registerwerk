// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "./mocks/MockOnchainId.sol";

contract PermissionRegistryTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    MockOnchainId orgId;

    address operator = address(0x1);
    address orgAdmin = address(0x2);
    address stranger = address(0x5);

    bytes32 constant PERM_OPEN = keccak256("loandesk.open");
    bytes32 constant ROLE_TRADER = keccak256("TRADER");

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        orgId = new MockOnchainId();
        orgId.setKey(orgAdmin, orgRegistry.MANAGEMENT_KEY_PURPOSE(), true);

        vm.prank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
    }

    // -------------------------------------------------------------------------
    // Definitions
    // -------------------------------------------------------------------------

    function test_definePermission_requiresMatchingHash() public {
        vm.prank(operator);
        vm.expectRevert("PermissionRegistry: id is not keccak256(code)");
        permissions.definePermission(PERM_OPEN, "loandesk.close");
    }

    function test_definePermission_storesAndEmits() public {
        vm.prank(operator);
        vm.expectEmit(true, false, false, true);
        emit IPermissionRegistry.PermissionDefined(PERM_OPEN, "loandesk.open");
        permissions.definePermission(PERM_OPEN, "loandesk.open");
        assertTrue(permissions.isDefined(PERM_OPEN));
    }

    function test_definePermission_revertsForDuplicate() public {
        vm.startPrank(operator);
        permissions.definePermission(PERM_OPEN, "loandesk.open");
        vm.expectRevert("PermissionRegistry: already defined");
        permissions.definePermission(PERM_OPEN, "loandesk.open");
        vm.stopPrank();
    }

    // -------------------------------------------------------------------------
    // Operator tier
    // -------------------------------------------------------------------------

    function test_grantToOrg_andRevoke() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        assertTrue(permissions.orgGranted(address(orgId), PERM_OPEN));

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), PERM_OPEN);
        assertFalse(permissions.orgGranted(address(orgId), PERM_OPEN));
    }

    function test_grantToOrg_revertsForUnregisteredOrg() public {
        vm.prank(operator);
        vm.expectRevert("PermissionRegistry: org not registered");
        permissions.grantToOrg(address(0xdead), PERM_OPEN);
    }

    function test_grantToOrg_revertsForNonOperator() public {
        vm.prank(orgAdmin);
        vm.expectRevert();
        permissions.grantToOrg(address(orgId), PERM_OPEN);
    }

    // -------------------------------------------------------------------------
    // Org-admin tier
    // -------------------------------------------------------------------------

    function test_grantToRole_byOrgAdmin() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);

        vm.prank(orgAdmin);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        assertTrue(permissions.roleGranted(address(orgId), ROLE_TRADER, PERM_OPEN));
    }

    function test_grantToRole_requiresOrgGrant() public {
        vm.prank(orgAdmin);
        vm.expectRevert("PermissionRegistry: org does not hold permission");
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
    }

    function test_grantToRole_revertsForStranger() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);

        vm.prank(stranger);
        vm.expectRevert("PermissionRegistry: not operator or org admin");
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
    }

    function test_orgAdmin_cannotDelegateWhileSuspended() public {
        vm.startPrank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        orgRegistry.suspendOrg(address(orgId), "review");
        vm.stopPrank();

        vm.prank(orgAdmin);
        vm.expectRevert("PermissionRegistry: org not active");
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
    }

    function test_setRoleRestricted() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);

        vm.prank(orgAdmin);
        permissions.setRoleRestricted(address(orgId), PERM_OPEN, true);
        assertTrue(permissions.isRoleRestricted(address(orgId), PERM_OPEN));
    }

    function test_setRoleRestricted_requiresOrgGrant() public {
        vm.prank(orgAdmin);
        vm.expectRevert("PermissionRegistry: org does not hold permission");
        permissions.setRoleRestricted(address(orgId), PERM_OPEN, true);
    }

    // -------------------------------------------------------------------------
    // Org-level revoke must void delegations (no silent resurrection)
    // -------------------------------------------------------------------------

    function test_revokeFromOrg_voidsRoleGrantsAndRestriction() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        vm.startPrank(orgAdmin);
        permissions.setRoleRestricted(address(orgId), PERM_OPEN, true);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        vm.stopPrank();

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), PERM_OPEN);

        // Re-granting the org permission must NOT resurrect the old delegations.
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        assertFalse(
            permissions.roleGranted(address(orgId), ROLE_TRADER, PERM_OPEN),
            "role grant must not survive an org-level revoke"
        );
        assertFalse(
            permissions.isRoleRestricted(address(orgId), PERM_OPEN),
            "role restriction must not survive an org-level revoke"
        );

        // Fresh delegation after the re-grant works as usual.
        vm.startPrank(orgAdmin);
        permissions.setRoleRestricted(address(orgId), PERM_OPEN, true);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        vm.stopPrank();
        assertTrue(permissions.roleGranted(address(orgId), ROLE_TRADER, PERM_OPEN));
        assertTrue(permissions.isRoleRestricted(address(orgId), PERM_OPEN));
    }

    function test_revokeFromRole_stillWorksAfterRevokeRegrantCycle() public {
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        vm.prank(orgAdmin);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);

        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), PERM_OPEN);
        vm.prank(operator);
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        vm.prank(orgAdmin);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        assertTrue(permissions.roleGranted(address(orgId), ROLE_TRADER, PERM_OPEN));

        vm.prank(orgAdmin);
        permissions.revokeFromRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        assertFalse(permissions.roleGranted(address(orgId), ROLE_TRADER, PERM_OPEN));
    }
}
