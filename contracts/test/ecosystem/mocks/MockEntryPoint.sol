// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

/// @dev Minimal ERC-4337 EntryPoint stand-in: an address that {EwpgPasskeyAccount} tests can
///      `vm.prank` as when calling `validateUserOp`/`execute` directly. {EwpgPaymaster} is NOT
///      tested against this mock — its gas accounting only means something against the real
///      EntryPoint's `handleOps` (see `lib/account-abstraction` and `EwpgPaymaster.t.sol`).
contract MockEntryPoint {
    mapping(address => uint256) public deposits;

    function depositTo(address account) external payable {
        deposits[account] += msg.value;
    }

    function balanceOf(address account) external view returns (uint256) {
        return deposits[account];
    }
}
