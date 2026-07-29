// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../../../src/ecosystem/interfaces/IClaimIssuer.sol";

/// @notice Test double for an ONCHAINID claim issuer with a switchable validity flag.
contract MockClaimIssuer is IClaimIssuer {
    bool public valid = true;

    function setValid(bool valid_) external {
        valid = valid_;
    }

    function isClaimValid(IIdentity, uint256, bytes calldata, bytes calldata)
        external
        view
        override
        returns (bool)
    {
        return valid;
    }
}
