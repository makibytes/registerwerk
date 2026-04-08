// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/token/Token.sol";
import "@erc3643/token/IToken.sol";

/**
 * @title EwpgERC3643
 * @notice eWpG-compliant security token implementing the full ERC-3643 (T-REX) standard.
 *         Deployed via the T-REX Factory — do not deploy directly.
 *         Wraps the T-REX Token contract with eWpG registry linkage.
 *
 * Key features inherited from T-REX Token:
 *  - Transfer compliance enforced via Identity Registry + Compliance contract
 *  - Freeze/pause per address or globally
 *  - Force transfer (agent-only, for regulatory recovery)
 *  - Batch mint/burn/transfer
 *
 * @dev The assetId links this contract back to the registry DB.
 *      The T-REX Token.sol is initializable (proxy pattern). The constructor only
 *      stores assetId; actual initialisation happens via init() called by the factory.
 */
contract EwpgERC3643 is Token {
    bytes32 public immutable assetId;

    event EwpgAssetLinked(bytes32 indexed assetId);

    constructor(bytes32 _assetId) {
        assetId = _assetId;
        emit EwpgAssetLinked(_assetId);
    }
}
