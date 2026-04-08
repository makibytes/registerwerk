// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/Ownable.sol";
import "../confidential/ConfidentialERC20.sol";
import "../confidential/ConfidentialERC3643.sol";

/**
 * @title EwpgConfidentialFactory
 * @notice CREATE2 factory for Zama fhEVM confidential tokens used by Registerwerk.
 *
 * Deploys either a plain {ConfidentialERC20} (ERC-7984) or a
 * {ConfidentialERC3643} regulated security token. The factory mirrors the
 * {AssetTokenFactory} contract used for the non-confidential variants so that
 * the backend {ConfidentialErc20Service} / {ConfidentialErc3643Service} can
 * reuse the same event-parsing logic.
 *
 * The `kmsGateway` parameter is the Zama KMS Gateway address for the target
 * fhEVM network (configured per-chain in the registry backend).
 */
contract EwpgConfidentialFactory is Ownable {
    enum TokenType { CONFIDENTIAL_ERC20, CONFIDENTIAL_ERC3643 }

    address public kmsGateway;

    event ConfidentialTokenDeployed(
        bytes32 indexed assetId,
        uint8   indexed tokenType,
        address indexed tokenAddress
    );

    event KmsGatewayUpdated(address indexed gateway);

    constructor(address _kmsGateway, address _owner) Ownable(_owner) {
        kmsGateway = _kmsGateway;
    }

    function setKmsGateway(address _kmsGateway) external onlyOwner {
        kmsGateway = _kmsGateway;
        emit KmsGatewayUpdated(_kmsGateway);
    }

    /**
     * @notice Deploys a confidential ERC-20 (ERC-7984) token for `assetId`.
     * @param assetId  Registerwerk asset UUID encoded as bytes32 (also CREATE2 salt).
     * @param name_    Token name.
     * @param symbol_  Token symbol.
     */
    function deployConfidentialErc20(
        bytes32 assetId,
        string calldata name_,
        string calldata symbol_
    ) external onlyOwner returns (address token) {
        bytes memory bytecode = abi.encodePacked(
            type(ConfidentialERC20).creationCode,
            abi.encode(assetId, name_, symbol_, kmsGateway, msg.sender)
        );
        bytes32 salt = assetId;
        assembly {
            token := create2(0, add(bytecode, 0x20), mload(bytecode), salt)
            if iszero(token) { revert(0, 0) }
        }
        emit ConfidentialTokenDeployed(assetId, uint8(TokenType.CONFIDENTIAL_ERC20), token);
    }

    /**
     * @notice Deploys a confidential ERC-3643 security token for `assetId`.
     * @param assetId          Registerwerk asset UUID (CREATE2 salt).
     * @param name_            Token name.
     * @param symbol_          Token symbol.
     * @param identityRegistry Address of the T-REX IdentityRegistry to use.
     * @param compliance       Address of the IConfidentialCompliance module.
     */
    function deployConfidentialErc3643(
        bytes32 assetId,
        string calldata name_,
        string calldata symbol_,
        address identityRegistry,
        address compliance
    ) external onlyOwner returns (address token) {
        bytes memory bytecode = abi.encodePacked(
            type(ConfidentialERC3643).creationCode,
            abi.encode(
                assetId, name_, symbol_, kmsGateway,
                identityRegistry, compliance, msg.sender
            )
        );
        bytes32 salt = assetId;
        assembly {
            token := create2(0, add(bytecode, 0x20), mload(bytecode), salt)
            if iszero(token) { revert(0, 0) }
        }
        emit ConfidentialTokenDeployed(assetId, uint8(TokenType.CONFIDENTIAL_ERC3643), token);
    }
}
