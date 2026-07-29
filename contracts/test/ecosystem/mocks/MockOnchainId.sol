// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../../../src/ecosystem/interfaces/IIdentity.sol";

/// @notice Test double for an ONCHAINID identity contract: keys and claims are
///         freely settable so tests can model org admins and KYC claims.
contract MockOnchainId is IIdentity {
    struct Claim {
        uint256 topic;
        uint256 scheme;
        address issuer;
        bytes signature;
        bytes data;
        string uri;
    }

    mapping(bytes32 => mapping(uint256 => bool)) private _keys;
    mapping(uint256 => bytes32[]) private _claimIdsByTopic;
    mapping(bytes32 => Claim) private _claims;
    uint256 private _claimNonce;

    function setKey(address wallet, uint256 purpose, bool held) external {
        _keys[keccak256(abi.encode(wallet))][purpose] = held;
    }

    function addClaim(uint256 topic, address issuer, bytes calldata signature, bytes calldata data)
        external
        returns (bytes32 claimId)
    {
        claimId = keccak256(abi.encode(issuer, topic, _claimNonce++));
        _claims[claimId] = Claim(topic, 1, issuer, signature, data, "");
        _claimIdsByTopic[topic].push(claimId);
    }

    function keyHasPurpose(bytes32 key, uint256 purpose) external view override returns (bool) {
        return _keys[key][purpose];
    }

    function getClaimIdsByTopic(uint256 topic) external view override returns (bytes32[] memory) {
        return _claimIdsByTopic[topic];
    }

    function getClaim(bytes32 claimId)
        external
        view
        override
        returns (uint256, uint256, address, bytes memory, bytes memory, string memory)
    {
        Claim storage c = _claims[claimId];
        return (c.topic, c.scheme, c.issuer, c.signature, c.data, c.uri);
    }
}
