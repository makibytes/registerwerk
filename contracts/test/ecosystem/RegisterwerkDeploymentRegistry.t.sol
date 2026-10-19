// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {Test} from "forge-std/Test.sol";
import {ERC1967Proxy} from "@openzeppelin/contracts/proxy/ERC1967/ERC1967Proxy.sol";
import {OwnableUpgradeable} from "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import {RegisterwerkDeploymentRegistry} from "../../src/ecosystem/RegisterwerkDeploymentRegistry.sol";

contract RegisterwerkDeploymentRegistryV2 is RegisterwerkDeploymentRegistry {
    function contractVersion() external pure override returns (uint64) {
        return 2;
    }
}

contract DemoProduct {
    uint256 public constant DEMO = 1;
}

contract RegisterwerkDeploymentRegistryTest is Test {
    RegisterwerkDeploymentRegistry private registry;

    function setUp() public {
        RegisterwerkDeploymentRegistry implementation = new RegisterwerkDeploymentRegistry();
        registry = RegisterwerkDeploymentRegistry(
            address(
                new ERC1967Proxy(
                    address(implementation), abi.encodeCall(RegisterwerkDeploymentRegistry.initialize, (address(this)))
                )
            )
        );
    }

    function test_recordsVersionedDeployment() public {
        DemoProduct product = new DemoProduct();
        bytes32 id = keccak256("ERC-20");
        registry.setDeployment(id, address(product), keccak256("manifest-v1"));

        RegisterwerkDeploymentRegistry.Deployment memory result = registry.deployment(id);
        assertEq(result.contractAddress, address(product));
        assertEq(result.revision, 1);
        assertEq(result.metadataHash, keccak256("manifest-v1"));
    }

    function test_upgradePreservesStorageAndRequiresOwner() public {
        DemoProduct product = new DemoProduct();
        bytes32 id = keccak256("ERC-721");
        registry.setDeployment(id, address(product), keccak256("before-upgrade"));

        RegisterwerkDeploymentRegistryV2 next = new RegisterwerkDeploymentRegistryV2();
        vm.prank(address(0xBAD));
        vm.expectRevert(abi.encodeWithSelector(OwnableUpgradeable.OwnableUnauthorizedAccount.selector, address(0xBAD)));
        registry.upgradeToAndCall(address(next), "");

        registry.upgradeToAndCall(address(next), "");
        assertEq(RegisterwerkDeploymentRegistryV2(address(registry)).contractVersion(), 2);
        assertEq(registry.deployment(id).contractAddress, address(product));
        assertEq(registry.deployment(id).revision, 1);
    }

    function test_ownershipMovesInTwoSteps() public {
        address next = address(0xA11CE);
        registry.transferOwnership(next);
        // Nominated, not yet in charge: the upgrade key has not moved.
        assertEq(registry.owner(), address(this));
        assertEq(registry.pendingOwner(), next);

        vm.prank(address(0xBAD));
        vm.expectRevert(abi.encodeWithSelector(OwnableUpgradeable.OwnableUnauthorizedAccount.selector, address(0xBAD)));
        registry.acceptOwnership();

        vm.prank(next);
        registry.acceptOwnership();
        assertEq(registry.owner(), next);
        assertEq(registry.pendingOwner(), address(0));
    }

    function test_aMistypedNewOwnerCannotTakeTheUpgradeKey() public {
        registry.transferOwnership(address(0xDEAD)); // nobody holds this key and nobody will accept
        // The current owner is still in charge and can still upgrade.
        RegisterwerkDeploymentRegistryV2 next = new RegisterwerkDeploymentRegistryV2();
        registry.upgradeToAndCall(address(next), "");
        assertEq(RegisterwerkDeploymentRegistryV2(address(registry)).contractVersion(), 2);
    }

    function test_rejectsEOAAsDeployment() public {
        vm.expectRevert(RegisterwerkDeploymentRegistry.InvalidContractAddress.selector);
        registry.setDeployment(keccak256("ERC-20"), address(0x1234), bytes32(0));
    }
}
