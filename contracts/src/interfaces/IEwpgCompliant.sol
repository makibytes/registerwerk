// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

interface IEwpgCompliant {
    function isWhitelisted(address account) external view returns (bool);
    function whitelist(address account) external;
    function removeFromWhitelist(address account) external;
    event Whitelisted(address indexed account);
    event RemovedFromWhitelist(address indexed account);
}
