// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "forge-std/Test.sol";
import "../../src/factory/EwpgConfidentialFactory.sol";
import "../../src/confidential/ConfidentialERC20.sol";

/// @notice Tests for the CREATE2 factory the backend's ConfidentialErc20Service /
///         ConfidentialErc3643Service call via Web3j (registerwerk.contracts.confidential-factory).
///
/// @dev deployConfidentialErc20/deployConfidentialErc3643 construct fhEVM tokens whose
///      constructors call TFHE precompiles unavailable on vanilla EVM — those two paths
///      self-skip unless run against an fhEVM fork, same convention as
///      test/tokens/ConfidentialERC3643Test.t.sol. Owner/access-control behavior needs no
///      fhEVM and is tested unconditionally.
///
///      The FhevmInfra addresses below are Zama's REAL, documented Sepolia testnet addresses
///      (see lib/fhevm/config/ZamaFHEVMConfig.sol's getSepoliaConfig()/getGetwayConfig()) —
///      not placeholders — since this factory's whole purpose after the confidential-token
///      review is to reject an unconfigured/zero-address deploy rather than silently accept one.
contract EwpgConfidentialFactoryTest is Test {
    EwpgConfidentialFactory factory;
    bool fhevmAvailable;

    address owner = makeAddr("registryWallet");
    ConfidentialERC20.FhevmInfra sepoliaInfra = ConfidentialERC20.FhevmInfra({
        aclAddress: 0xFee8407e2f5e3Ee68ad77cAE98c434e637f516e5,
        tfheExecutorAddress: 0x687408aB54661ba0b4aeF3a44156c616c6955E07,
        fhePaymentAddress: 0xFb03BE574d14C256D56F09a198B586bdfc0A9de2,
        kmsVerifierAddress: 0x9D6891A6240D6130c54ae243d8005063D05fE14b,
        gatewayAddress: 0x33347831500F1e73f0ccCBb95c9f86B94d7b1123
    });
    bytes32 constant ASSET_ID = keccak256("conf-factory-asset-1");

    address operatorViewer = makeAddr("operatorViewer");
    address auditorViewer = makeAddr("auditorViewer");
    address[] initialViewers;

    function setUp() public {
        factory = new EwpgConfidentialFactory(sepoliaInfra, owner);
        initialViewers.push(operatorViewer);
        initialViewers.push(auditorViewer);

        vm.prank(owner);
        try factory.deployConfidentialErc20(ASSET_ID, "Confidential Token", "cTKN", initialViewers)
            returns (address) {
            fhevmAvailable = true;
        } catch {
            fhevmAvailable = false;
        }
    }

    modifier onFhevm() {
        vm.skip(!fhevmAvailable);
        _;
    }

    // ── Ownership / configuration (no fhEVM required) ───────────────────────

    function test_fhevmInfraIsSet() public view {
        (address acl,,,, address gateway) = factory.fhevmInfra();
        assertEq(acl, sepoliaInfra.aclAddress);
        assertEq(gateway, sepoliaInfra.gatewayAddress);
    }

    function test_setFhevmInfra_ownerCanUpdate() public {
        ConfidentialERC20.FhevmInfra memory newInfra = ConfidentialERC20.FhevmInfra({
            aclAddress: makeAddr("newAcl"),
            tfheExecutorAddress: makeAddr("newExecutor"),
            fhePaymentAddress: makeAddr("newPayment"),
            kmsVerifierAddress: makeAddr("newKmsVerifier"),
            gatewayAddress: makeAddr("newGateway")
        });
        vm.prank(owner);
        factory.setFhevmInfra(newInfra);
        (address acl,,,, address gateway) = factory.fhevmInfra();
        assertEq(acl, newInfra.aclAddress);
        assertEq(gateway, newInfra.gatewayAddress);
    }

    function test_setFhevmInfra_revertsForNonOwner() public {
        vm.expectRevert(
            abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", address(this))
        );
        factory.setFhevmInfra(sepoliaInfra);
    }

    function test_deployConfidentialErc20_revertsForNonOwner() public {
        vm.expectRevert(
            abi.encodeWithSignature("OwnableUnauthorizedAccount(address)", address(this))
        );
        factory.deployConfidentialErc20(keccak256("other-asset"), "X", "X", initialViewers);
    }

    function test_deployConfidentialErc20_revertsWhenInfraNotConfigured() public {
        EwpgConfidentialFactory unconfigured = new EwpgConfidentialFactory(
            ConfidentialERC20.FhevmInfra(address(0), address(0), address(0), address(0), address(0)),
            owner
        );
        vm.prank(owner);
        vm.expectRevert(EwpgConfidentialFactory.FhevmInfraNotConfigured.selector);
        unconfigured.deployConfidentialErc20(keccak256("unconfigured-asset"), "X", "X", initialViewers);
    }

    function test_deployConfidentialErc3643_revertsForZeroIdentityRegistry() public {
        vm.prank(owner);
        vm.expectRevert(bytes("EwpgConfidentialFactory: identityRegistry required"));
        factory.deployConfidentialErc3643(
            keccak256("conf-3643-no-registry"), "Confidential Security Token", "cSEC",
            initialViewers, address(0), makeAddr("compliance")
        );
    }

    // ── CREATE2 deployment (fhEVM only) ──────────────────────────────────────

    function test_deployConfidentialErc20_deterministicAddressPerAssetId() public onFhevm {
        // Fresh salt — setUp already consumed ASSET_ID when probing fhEVM availability.
        bytes32 assetId = keccak256("determinism-asset");

        vm.prank(owner);
        address deployed = factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
        assertTrue(deployed != address(0));

        // Same assetId (CREATE2 salt) must not be deployable twice.
        vm.prank(owner);
        vm.expectRevert();
        factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
    }

    function test_deployConfidentialErc20_grantsInitialViewers() public onFhevm {
        bytes32 assetId = keccak256("viewer-grant-asset");
        vm.prank(owner);
        address deployed = factory.deployConfidentialErc20(assetId, "Confidential Token", "cTKN", initialViewers);
        ConfidentialERC20 token = ConfidentialERC20(deployed);
        assertTrue(token.isViewer(operatorViewer));
        assertTrue(token.isViewer(auditorViewer));
        assertFalse(token.isViewer(makeAddr("someRandomAddress")));
    }

    function test_deployConfidentialErc3643_succeeds() public onFhevm {
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
