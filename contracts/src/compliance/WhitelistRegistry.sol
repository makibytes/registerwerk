// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/access/Ownable.sol";
import "../interfaces/IWhitelistRegistry.sol";

/// @title WhitelistRegistry
/// @notice Standalone registry that token contracts can delegate whitelist checks to.
///         The owner (the deployer / backend registry wallet) controls all entries.
///         Each token address has its own independent whitelist mapping.
contract WhitelistRegistry is Ownable, IWhitelistRegistry {
    // token -> account -> whitelisted
    mapping(address => mapping(address => bool)) private _whitelists;

    constructor() Ownable(msg.sender) {}

    /// @inheritdoc IWhitelistRegistry
    function whitelist(address token, address account) external override onlyOwner {
        require(token != address(0), "WhitelistRegistry: zero token address");
        require(account != address(0), "WhitelistRegistry: zero account address");
        _whitelists[token][account] = true;
        emit Whitelisted(token, account);
    }

    /// @inheritdoc IWhitelistRegistry
    function removeFromWhitelist(address token, address account) external override onlyOwner {
        _whitelists[token][account] = false;
        emit RemovedFromWhitelist(token, account);
    }

    /// @inheritdoc IWhitelistRegistry
    function isWhitelisted(address token, address account) external view override returns (bool) {
        return _whitelists[token][account];
    }
}
