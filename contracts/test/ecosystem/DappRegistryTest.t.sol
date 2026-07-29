// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/ecosystem/DappRegistry.sol";
import "../../src/ecosystem/EcosystemTrustedIssuersRegistry.sol";
import "../../src/ecosystem/OrgRegistry.sol";
import "../../src/ecosystem/PermissionOracle.sol";
import "../../src/ecosystem/PermissionRegistry.sol";
import "./mocks/MockOnchainId.sol";

contract DappRegistryTest is Test {
    OrgRegistry orgRegistry;
    DappRegistry dappRegistry;
    PermissionOracle oracle;
    MockOnchainId publisherOrgId;

    address operator = address(0x1);
    address publisherAdmin = address(0x2);
    address stranger = address(0x5);
    address instance = address(0x42);

    bytes32 dappId = keccak256("loandesk");
    bytes32 manifestHash = keccak256("manifest-v1");

    function setUp() public {
        orgRegistry = new OrgRegistry(operator);
        dappRegistry = new DappRegistry(operator, orgRegistry);
        PermissionRegistry permissions = new PermissionRegistry(operator, orgRegistry);
        EcosystemTrustedIssuersRegistry tir = new EcosystemTrustedIssuersRegistry(operator);
        oracle = new PermissionOracle(operator, orgRegistry, permissions, tir);
        publisherOrgId = new MockOnchainId();
        publisherOrgId.setKey(publisherAdmin, orgRegistry.MANAGEMENT_KEY_PURPOSE(), true);

        vm.startPrank(operator);
        orgRegistry.registerOrg(address(publisherOrgId), 276);
        oracle.setDappRegistry(IDappRegistryView(address(dappRegistry)));
        vm.stopPrank();
    }

    function _register() internal {
        vm.prank(operator);
        dappRegistry.registerDapp(dappId, address(publisherOrgId), manifestHash, "1.0.0",
                new bytes32[](0), new uint256[](0));
    }

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    function test_registerDapp_storesManifestAnchor() public {
        _register();
        assertTrue(dappRegistry.isApproved(dappId));

        IDappRegistry.Dapp memory dapp = dappRegistry.getDapp(dappId);
        assertEq(dapp.publisherOrg, address(publisherOrgId));
        assertEq(dapp.manifestHash, manifestHash);
        assertEq(dapp.version, "1.0.0");
    }

    function test_registerDapp_revertsForNonOperator() public {
        vm.prank(stranger);
        vm.expectRevert();
        dappRegistry.registerDapp(dappId, address(publisherOrgId), manifestHash, "1.0.0",
                new bytes32[](0), new uint256[](0));
    }

    function test_registerDapp_revertsForUnregisteredPublisher() public {
        vm.prank(operator);
        vm.expectRevert("DappRegistry: publisher org not registered");
        dappRegistry.registerDapp(dappId, address(0xdead), manifestHash, "1.0.0",
                new bytes32[](0), new uint256[](0));
    }

    function test_registerDapp_revertsForDuplicate() public {
        _register();
        vm.prank(operator);
        vm.expectRevert("DappRegistry: dapp already registered");
        dappRegistry.registerDapp(dappId, address(publisherOrgId), manifestHash, "1.0.0",
                new bytes32[](0), new uint256[](0));
    }

    function test_updateManifest_anchorsNewVersion() public {
        _register();
        bytes32 newHash = keccak256("manifest-v2");
        vm.prank(operator);
        dappRegistry.updateManifest(dappId, newHash, "2.0.0");

        IDappRegistry.Dapp memory dapp = dappRegistry.getDapp(dappId);
        assertEq(dapp.manifestHash, newHash);
        assertEq(dapp.version, "2.0.0");
    }

    function test_setStatus_lifecycle() public {
        _register();
        vm.prank(operator);
        dappRegistry.setStatus(dappId, IDappRegistry.DappStatus.Deprecated);
        assertFalse(dappRegistry.isApproved(dappId));
    }

    // -------------------------------------------------------------------------
    // Instance attestation
    // -------------------------------------------------------------------------

    function test_attestInstance_byPublisherAdmin() public {
        _register();
        vm.prank(publisherAdmin);
        dappRegistry.attestInstance(dappId, instance);

        assertEq(dappRegistry.dappOf(instance), dappId);
        assertTrue(dappRegistry.isApprovedInstance(instance));
        // composed through the oracle as well
        assertTrue(oracle.isApprovedInstance(instance));
    }

    function test_attestInstance_revertsForStranger() public {
        _register();
        vm.prank(stranger);
        vm.expectRevert("DappRegistry: not operator or publisher admin");
        dappRegistry.attestInstance(dappId, instance);
    }

    function test_isApprovedInstance_falseWhileDappSuspended() public {
        _register();
        vm.prank(publisherAdmin);
        dappRegistry.attestInstance(dappId, instance);

        vm.prank(operator);
        dappRegistry.setStatus(dappId, IDappRegistry.DappStatus.Suspended);
        assertFalse(dappRegistry.isApprovedInstance(instance));
        assertFalse(oracle.isApprovedInstance(instance));
    }

    function test_revokeInstance() public {
        _register();
        vm.prank(publisherAdmin);
        dappRegistry.attestInstance(dappId, instance);

        vm.prank(operator);
        dappRegistry.revokeInstance(instance);
        assertFalse(dappRegistry.isApprovedInstance(instance));
        assertEq(dappRegistry.dappOf(instance), bytes32(0));
    }
}
