// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "./mocks/MockClaimIssuer.sol";
import "./mocks/MockOnchainId.sol";

contract PermissionOracleTest is Test {
    OrgRegistry orgRegistry;
    PermissionRegistry permissions;
    EcosystemTrustedIssuersRegistry tir;
    PermissionOracle oracle;
    MockOnchainId orgId;
    MockClaimIssuer issuer;

    address operator = address(0x1);
    address alice = address(0x3);

    bytes32 constant PERM_OPEN = keccak256("loandesk.open");
    bytes32 constant ROLE_TRADER = keccak256("TRADER");
    uint256 constant TOPIC_KYC = 1;

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        permissions = new PermissionRegistry(operator, orgRegistry);
        tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        orgId = new MockOnchainId();
        issuer = new MockClaimIssuer();

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(orgId), 276);
        orgRegistry.addMember(address(orgId), alice, _roles(ROLE_TRADER), "");
        permissions.grantToOrg(address(orgId), PERM_OPEN);
        vm.stopPrank();
    }

    function _roles(bytes32 role) internal pure returns (bytes32[] memory r) {
        r = new bytes32[](1);
        r[0] = role;
    }

    function _trustIssuer() internal {
        uint256[] memory topics = new uint256[](1);
        topics[0] = TOPIC_KYC;
        vm.prank(operator);
        tir.addTrustedIssuer(address(issuer), topics);
    }

    // -------------------------------------------------------------------------
    // hasPermission
    // -------------------------------------------------------------------------

    function test_hasPermission_forGrantedMember() public view {
        assertTrue(oracle.hasPermission(alice, PERM_OPEN));
    }

    function test_hasPermission_falseForUnboundWallet() public view {
        assertFalse(oracle.hasPermission(address(0x99), PERM_OPEN));
    }

    function test_hasPermission_falseAfterOrgSuspension() public {
        vm.prank(operator);
        orgRegistry.suspendOrg(address(orgId), "review");
        assertFalse(oracle.hasPermission(alice, PERM_OPEN));
    }

    function test_hasPermission_falseAfterRevocation() public {
        vm.prank(operator);
        permissions.revokeFromOrg(address(orgId), PERM_OPEN);
        assertFalse(oracle.hasPermission(alice, PERM_OPEN));
    }

    function test_hasPermission_roleRestriction() public {
        vm.prank(operator);
        permissions.setRoleRestricted(address(orgId), PERM_OPEN, true);
        // restricted and TRADER carries no role grant yet
        assertFalse(oracle.hasPermission(alice, PERM_OPEN));

        vm.prank(operator);
        permissions.grantToRole(address(orgId), ROLE_TRADER, PERM_OPEN);
        assertTrue(oracle.hasPermission(alice, PERM_OPEN));
    }

    // -------------------------------------------------------------------------
    // isActiveMember / hasRole / orgOf
    // -------------------------------------------------------------------------

    function test_membershipViews() public view {
        assertEq(oracle.orgOf(alice), address(orgId));
        assertTrue(oracle.isActiveMember(alice));
        assertTrue(oracle.hasRole(alice, ROLE_TRADER));
        assertFalse(oracle.hasRole(alice, keccak256("SIGNER")));
    }

    // -------------------------------------------------------------------------
    // hasClaimTopic
    // -------------------------------------------------------------------------

    function test_hasClaimTopic_withTrustedIssuerClaim() public {
        _trustIssuer();
        orgId.addClaim(TOPIC_KYC, address(issuer), hex"01", hex"02");
        assertTrue(oracle.hasClaimTopic(alice, TOPIC_KYC));
    }

    function test_hasClaimTopic_falseWithoutClaim() public {
        _trustIssuer();
        assertFalse(oracle.hasClaimTopic(alice, TOPIC_KYC));
    }

    function test_hasClaimTopic_falseForUntrustedIssuer() public {
        // issuer never added to the TIR
        orgId.addClaim(TOPIC_KYC, address(issuer), hex"01", hex"02");
        assertFalse(oracle.hasClaimTopic(alice, TOPIC_KYC));
    }

    function test_hasClaimTopic_falseWhenIssuerRevokes() public {
        _trustIssuer();
        orgId.addClaim(TOPIC_KYC, address(issuer), hex"01", hex"02");
        issuer.setValid(false);
        assertFalse(oracle.hasClaimTopic(alice, TOPIC_KYC));
    }

    // -------------------------------------------------------------------------
    // check / component management
    // -------------------------------------------------------------------------

    function test_check_combinesPermissionAndClaims() public {
        _trustIssuer();
        orgId.addClaim(TOPIC_KYC, address(issuer), hex"01", hex"02");

        uint256[] memory topics = new uint256[](1);
        topics[0] = TOPIC_KYC;
        assertTrue(oracle.check(alice, PERM_OPEN, topics));

        issuer.setValid(false);
        assertFalse(oracle.check(alice, PERM_OPEN, topics));
    }

    function test_isApprovedInstance_falseWithoutDappRegistry() public view {
        assertFalse(oracle.isApprovedInstance(address(0x42)));
    }

    function test_setComponent_requiresOperator() public {
        vm.prank(alice);
        vm.expectRevert();
        oracle.setOrgRegistry(orgRegistry);
    }
}
