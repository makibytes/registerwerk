// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "./IIdentity.sol";

/// @title IClaimIssuer
/// @notice Minimal view surface of an ONCHAINID claim-issuer contract. Local interface so
///         the ecosystem suite compiles without the external ERC-3643 / ONCHAINID libraries.
interface IClaimIssuer {
    /// @notice Returns true if the claim (topic + signature + data) issued for `identity`
    ///         is currently valid (correctly signed and not revoked by the issuer).
    function isClaimValid(IIdentity identity, uint256 claimTopic, bytes calldata signature, bytes calldata data)
        external
        view
        returns (bool);
}
