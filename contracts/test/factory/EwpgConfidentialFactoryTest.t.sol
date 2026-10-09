// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "../helpers/FhevmMockSetup.sol";
import "../../src/factory/EwpgConfidentialFactory.sol";
import "../../src/confidential/ConfidentialERC20.sol";

/// @notice Tests for the CREATE2 factory the backend's ConfidentialErc20Service /
///         ConfidentialErc3643Service call via Web3j (registerwerk.contracts.confidential-factory).
///
/// @dev The tokens it deploys call into the FHEVM host contracts, so the factory is configured with
///      the cleartext mocks of test/mocks/MockFhevm.sol (the infra addresses are injected, not
///      hardcoded) and the deployment paths run like any other instead of self-skipping.
contract EwpgConfidentialFactoryTest is FhevmMockSetup {
    EwpgConfidentialFactory factory;
    ConfidentialERC20.FhevmInfra infra;

    address owner = makeAddr("registryWallet");
    bytes32 constant ASSET_ID = keccak256("conf-factory-asset-1");

    address operatorViewer = makeAddr("operatorViewer");
    address auditorViewer = makeAddr("auditorViewer");
    address[] initialViewers;

    function setUp() public {
        infra = _deployFhevmMocks();
        factory = new EwpgConfidentialFactory(infra, owner);
        initialViewers.push(operatorViewer);
        initialViewers.push(auditorViewer);
    }

    // ── Ownership / configuration (no fhEVM required) ───────────────────────

    function test_fhevmInfraIsSet() public view {
        (address aclAddress, address coprocessor, address kmsVerifier) = factory.fhevmInfra();
        assertEq(aclAddress, infra.aclAddress);
        assertEq(coprocessor, infra.coprocessorAddress);
        assertEq(kmsVerifier, infra.kmsVerifierAddress);
    }

    function test_setFhevmInfra_ownerCanUpdate() public {
        ConfidentialERC20.FhevmInfra memory newInfra = ConfidentialERC20.FhevmInfra({
            aclAddress: makeAddr("newAcl"),
            coprocessorAddress: makeAddr("newCoprocessor"),
            kmsVerifierAddress: makeAddr("newKmsVerifier")
        });
        vm.prank(owner);
        vm.expectEmit(false, false, false, true, address(factory));
        emit EwpgConfidentialFactory.FhevmInfraUpdated(
            newInfra.aclAddress, newInfra.coprocessorAddress, newInfra.kmsVerifierAddress
        );
        factory.setFhevmInfra(newInfra);
        (address aclAddress, address coprocessor, address kmsVerifier) = factory.fhevmInfra();
        assertEq(aclAddress, newInfra.aclAddress);
        assertEq(coprocessor, newInfra.coprocessorAddress);
        assertEq(kmsVerifier, newInfra.kmsVerifierAddress);
    }

    function test_setFhevmInfra_revertsForNonOwner() public {
        vm.expectRevert(
            abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", address(this))
        );
        factory.setFhevmInfra(infra);
    }

    function test_deployConfidentialErc20_revertsForNonOwner() public {
        vm.expectRevert(
            abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", address(this))
        );
        factory.deployConfidentialErc20(keccak256("other-asset"), "X", "X", initialViewers);
    }

    function test_deployConfidentialErc20_revertsWhenInfraNotConfigured() public {
        EwpgConfidentialFactory unconfigured = new EwpgConfidentialFactory(
            ConfidentialERC20.FhevmInfra(address(0), address(0), address(0)), owner
        );
        vm.prank(owner);
        vm.expectRevert(EwpgConfidentialFactory.FhevmInfraNotConfigured.selector);
        unconfigured.deployConfidentialErc20(keccak256("unconfigured-asset"), "X", "X", initialViewers);
    }

    /// Any of the three host addresses missing is "not configured" (the Gateway used to be the odd one out).
    function test_deploy_revertsWhenOnlyOneInfraAddressIsMissing() public {
        ConfidentialERC20.FhevmInfra[3] memory partial_ = [
            ConfidentialERC20.FhevmInfra(address(0), infra.coprocessorAddress, infra.kmsVerifierAddress),
            ConfidentialERC20.FhevmInfra(infra.aclAddress, address(0), infra.kmsVerifierAddress),
            ConfidentialERC20.FhevmInfra(infra.aclAddress, infra.coprocessorAddress, address(0))
        ];
        for (uint256 i; i < partial_.length; ++i) {
            EwpgConfidentialFactory f = new EwpgConfidentialFactory(partial_[i], owner);
            vm.prank(owner);
            vm.expectRevert(EwpgConfidentialFactory.FhevmInfraNotConfigured.selector);
            f.deployConfidentialErc20(keccak256(abi.encode("partial", i)), "X", "X", initialViewers);
        }
    }

    function test_deployConfidentialErc3643_revertsForZeroIdentityRegistry() public {
        vm.prank(owner);
        vm.expectRevert(bytes("EwpgConfidentialFactory: identityRegistry required"));
        factory.deployConfidentialErc3643(
            keccak256("conf-3643-no-registry"), "Confidential Security Token", "cSEC",
            initialViewers, address(0), makeAddr("compliance")
        );
    }

    // ── CREATE2 deployment ───────────────────────────────────────────────────

    function test_deployConfidentialErc20_deterministicAddressPerAssetId() public {
        bytes32 assetId = keccak256("determinism-asset");

        vm.prank(owner);
        address deployed = factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
        assertTrue(deployed != address(0));

        // Same assetId (CREATE2 salt) must not be deployable twice.
        vm.prank(owner);
        vm.expectRevert();
        factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
    }

    function test_deployConfidentialErc20_grantsInitialViewers() public {
        bytes32 assetId = keccak256("viewer-grant-asset");
        vm.prank(owner);
        address deployed = factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
        ConfidentialERC20 token = ConfidentialERC20(deployed);
        assertEq(token.owner(), owner, "the registry wallet that called the factory owns the token");
        assertTrue(token.isViewer(operatorViewer));
        assertTrue(token.isViewer(auditorViewer));
        assertFalse(token.isViewer(makeAddr("someRandomAddress")));
    }

    function test_deployConfidentialErc3643_succeeds() public {
        vm.prank(owner);
        address deployed = factory.deployConfidentialErc3643(
            keccak256("conf-3643-asset-1"),
            "Confidential Security Token",
            "cSEC",
            initialViewers,
            makeAddr("identityRegistry"),
            makeAddr("compliance")
        );
        assertTrue(deployed != address(0));
    }
}
