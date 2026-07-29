// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import "@openzeppelin/contracts/access/Ownable.sol";
import "../interfaces/IMintController.sol";

/// @title MintController
/// @notice Controls minting permissions and transfer/burn auto-approval flags for
///         token contracts operating at OnchainLevel.CONTROL. Token contracts call
///         consumeMintAllowance during mint to deduct from the pre-approved budget.
contract MintController is Ownable, IMintController {
    /// @notice Remaining mint budget per token contract address.
    mapping(address => uint256) public mintAllowances;

    /// @notice Whether transfers are automatically approved for a token contract.
    mapping(address => bool) public autoApproveTransfer;

    /// @notice Whether burns are automatically approved for a token contract.
    mapping(address => bool) public autoApproveBurn;

    constructor() Ownable(msg.sender) {}

    /// @inheritdoc IMintController
    function setMintAllowance(address target, uint256 amount) external override onlyOwner {
        require(target != address(0), "MintController: zero address");
        mintAllowances[target] = amount;
        emit MintAllowanceSet(target, amount);
    }

    /// @inheritdoc IMintController
    function getMintAllowance(address target) external view override returns (uint256) {
        return mintAllowances[target];
    }

    /// @notice Called by token contracts during mint to deduct from the pre-approved budget.
    /// @param target The token contract address (msg.sender is the token contract).
    /// @param amount The amount being minted.
    function consumeMintAllowance(address target, uint256 amount) external {
        require(target == msg.sender, "MintController: target must be caller");
        uint256 current = mintAllowances[target];
        require(current >= amount, "MintController: allowance exceeded");
        unchecked {
            mintAllowances[target] = current - amount;
        }
        emit MintAllowanceConsumed(target, amount, mintAllowances[target]);
    }

    /// @inheritdoc IMintController
    function setAutoApproveTransfer(address target, bool approved) external override onlyOwner {
        require(target != address(0), "MintController: zero address");
        autoApproveTransfer[target] = approved;
        emit AutoApproveTransferSet(target, approved);
    }

    /// @inheritdoc IMintController
    function setAutoApproveBurn(address target, bool approved) external override onlyOwner {
        require(target != address(0), "MintController: zero address");
        autoApproveBurn[target] = approved;
        emit AutoApproveBurnSet(target, approved);
    }
}
