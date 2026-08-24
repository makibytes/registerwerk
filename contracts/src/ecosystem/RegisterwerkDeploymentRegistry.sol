// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {Initializable} from "@openzeppelin/contracts/proxy/utils/Initializable.sol";
import {OwnableUpgradeable} from "@openzeppelin/contracts-upgradeable/access/OwnableUpgradeable.sol";
import {UUPSUpgradeable} from "@openzeppelin/contracts-upgradeable/proxy/utils/UUPSUpgradeable.sol";

/// @notice Canonical address catalogue for a Registerwerk deployment.
/// @dev This coordination layer is upgradeable; issued asset contracts remain immutable unless
///      their own standard (such as the T-REX suite) explicitly uses audited proxies.
contract RegisterwerkDeploymentRegistry is Initializable, OwnableUpgradeable, UUPSUpgradeable {
    struct Deployment {
        address contractAddress;
        uint64 revision;
        uint64 updatedAt;
        bytes32 metadataHash;
    }

    mapping(bytes32 standardId => Deployment deployment) private _deployments;

    error InvalidContractAddress();

    event DeploymentUpdated(
        bytes32 indexed standardId,
        address indexed previousAddress,
        address indexed contractAddress,
        uint64 revision,
        bytes32 metadataHash
    );

    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() {
        _disableInitializers();
    }

    function initialize(address initialOwner) external initializer {
        __Ownable_init(initialOwner);
    }

    function setDeployment(bytes32 standardId, address contractAddress, bytes32 metadataHash) external onlyOwner {
        if (contractAddress == address(0) || contractAddress.code.length == 0) {
            revert InvalidContractAddress();
        }
        Deployment storage current = _deployments[standardId];
        address previous = current.contractAddress;
        uint64 revision = current.revision + 1;
        _deployments[standardId] = Deployment({
            contractAddress: contractAddress,
            revision: revision,
            updatedAt: uint64(block.timestamp),
            metadataHash: metadataHash
        });
        emit DeploymentUpdated(standardId, previous, contractAddress, revision, metadataHash);
    }

    function deployment(bytes32 standardId) external view returns (Deployment memory) {
        return _deployments[standardId];
    }

    function contractVersion() external pure virtual returns (uint64) {
        return 1;
    }

    function _authorizeUpgrade(address) internal override onlyOwner {}
}
