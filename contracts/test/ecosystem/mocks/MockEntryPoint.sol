// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

/// @dev Minimal ERC-4337 EntryPoint stand-in for unit-testing {EwpgPaymaster} in isolation.
///      Only `depositTo` needs to actually exist, so `fundSponsorship` has somewhere to send
///      its deposit; `validatePaymasterUserOp`/`postOp` are exercised directly via
///      `vm.prank(address(mockEntryPoint))` in tests rather than through a real `handleOps`
///      simulation — the paymaster's own accounting logic is what's under test here, not
///      EntryPoint's (extensively tested upstream).
contract MockEntryPoint {
    mapping(address => uint256) public deposits;

    function depositTo(address account) external payable {
        deposits[account] += msg.value;
    }

    function balanceOf(address account) external view returns (uint256) {
        return deposits[account];
    }
}
