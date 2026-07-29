// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

interface IWhitelistRegistry {
    /// @notice Whitelist an account for a specific token contract.
    /// @param token The token contract address.
    /// @param account The account to whitelist.
    function whitelist(address token, address account) external;

    /// @notice Remove an account from the whitelist of a specific token contract.
    /// @param token The token contract address.
    /// @param account The account to remove.
    function removeFromWhitelist(address token, address account) external;

    /// @notice Check whether an account is whitelisted for a specific token contract.
    /// @param token The token contract address.
    /// @param account The account to check.
    /// @return True if the account is whitelisted, false otherwise.
    function isWhitelisted(address token, address account) external view returns (bool);

    event Whitelisted(address indexed token, address indexed account);
    event RemovedFromWhitelist(address indexed token, address indexed account);
}
