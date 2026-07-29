// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

/// @title IIdentity
/// @notice Minimal view surface of an ONCHAINID identity contract (ERC-734 key holder +
///         ERC-735 claim holder). Local interface so the ecosystem suite compiles without
///         the external ERC-3643 / ONCHAINID libraries.
interface IIdentity {
    /// @notice Returns true if `key` (keccak256(abi.encode(address))) holds `purpose`.
    ///         Purposes per ERC-734: 1 = MANAGEMENT, 2 = ACTION, 3 = CLAIM.
    function keyHasPurpose(bytes32 key, uint256 purpose) external view returns (bool);

    /// @notice Returns the ids of all claims with the given ERC-735 topic.
    function getClaimIdsByTopic(uint256 topic) external view returns (bytes32[] memory);

    /// @notice Returns an ERC-735 claim by id.
    function getClaim(bytes32 claimId)
        external
        view
        returns (
            uint256 topic,
            uint256 scheme,
            address issuer,
            bytes memory signature,
            bytes memory data,
            string memory uri
        );
}
