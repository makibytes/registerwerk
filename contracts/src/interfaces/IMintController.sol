// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

interface IMintController {
    /// @notice Set the mint allowance for a target token contract.
    /// @param target The token contract address.
    /// @param amount The maximum amount that can be minted.
    function setMintAllowance(address target, uint256 amount) external;

    /// @notice Return the current mint allowance for a target token contract.
    /// @param target The token contract address.
    /// @return The remaining mint allowance.
    function getMintAllowance(address target) external view returns (uint256);

    /// @notice Enable or disable automatic transfer approval for a target token contract.
    /// @param target The token contract address.
    /// @param approved True to auto-approve transfers, false to require manual approval.
    function setAutoApproveTransfer(address target, bool approved) external;

    /// @notice Enable or disable automatic burn approval for a target token contract.
    /// @param target The token contract address.
    /// @param approved True to auto-approve burns, false to require manual approval.
    function setAutoApproveBurn(address target, bool approved) external;

    event MintAllowanceSet(address indexed target, uint256 amount);
    event MintAllowanceConsumed(address indexed target, uint256 amount, uint256 remaining);
    event AutoApproveTransferSet(address indexed target, bool approved);
    event AutoApproveBurnSet(address indexed target, bool approved);
}
