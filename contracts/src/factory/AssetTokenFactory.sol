// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../tokens/EwpgERC20.sol";
import "../tokens/EwpgERC721.sol";
import "../tokens/EwpgERC1155.sol";

/// @title AssetTokenFactory
/// @notice CREATE2 factory for deploying eWpG token contracts with deterministic addresses.
///         Supports ERC-20 (type 0), ERC-721 (type 1), and ERC-1155 (type 2).
///         The registry wallet is embedded at construction time and forwarded to every
///         deployed token so ownership and compliance authority are consistent.
contract AssetTokenFactory {
    /// @notice The registry wallet forwarded to every deployed token contract.
    address public immutable registryWallet;

    /// @dev Token type constants for readability.
    uint8 public constant TOKEN_TYPE_ERC20 = 0;
    uint8 public constant TOKEN_TYPE_ERC721 = 1;
    uint8 public constant TOKEN_TYPE_ERC1155 = 2;

    event TokenDeployed(
        bytes32 indexed assetId,
        uint8 indexed tokenType, // 0=ERC20, 1=ERC721, 2=ERC1155
        address indexed tokenAddress
    );

    constructor(address _registryWallet) {
        require(_registryWallet != address(0), "AssetTokenFactory: zero registry address");
        registryWallet = _registryWallet;
    }

    /// @notice Deploy a token contract for the given asset using CREATE2.
    /// @param tokenType Token standard: 0=ERC20, 1=ERC721, 2=ERC1155.
    /// @param name Human-readable token name (ignored for ERC-1155).
    /// @param symbol Token symbol / ticker.
    /// @param assetId Unique identifier linking this contract to an off-chain asset record.
    /// @return tokenAddress The address of the newly deployed token contract.
    function deployToken(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId
    ) external returns (address tokenAddress) {
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));

        if (tokenType == TOKEN_TYPE_ERC20) {
            tokenAddress = address(
                new EwpgERC20{salt: salt}(name, symbol, registryWallet, assetId)
            );
        } else if (tokenType == TOKEN_TYPE_ERC721) {
            tokenAddress = address(
                new EwpgERC721{salt: salt}(name, symbol, registryWallet, assetId)
            );
        } else if (tokenType == TOKEN_TYPE_ERC1155) {
            tokenAddress = address(
                new EwpgERC1155{salt: salt}(symbol, registryWallet, assetId)
            );
        } else {
            revert("AssetTokenFactory: unsupported token type");
        }

        emit TokenDeployed(assetId, tokenType, tokenAddress);
    }

    /// @notice Predict the CREATE2 address for a token without deploying it.
    /// @param tokenType Token standard: 0=ERC20, 1=ERC721, 2=ERC1155.
    /// @param name Human-readable token name (ignored for ERC-1155).
    /// @param symbol Token symbol / ticker.
    /// @param assetId Unique identifier linking this contract to an off-chain asset record.
    /// @return The deterministic address the token would be deployed to.
    function predictAddress(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId
    ) external view returns (address) {
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));
        bytes32 initCodeHash;

        if (tokenType == TOKEN_TYPE_ERC20) {
            initCodeHash = keccak256(
                abi.encodePacked(
                    type(EwpgERC20).creationCode,
                    abi.encode(name, symbol, registryWallet, assetId)
                )
            );
        } else if (tokenType == TOKEN_TYPE_ERC721) {
            initCodeHash = keccak256(
                abi.encodePacked(
                    type(EwpgERC721).creationCode,
                    abi.encode(name, symbol, registryWallet, assetId)
                )
            );
        } else if (tokenType == TOKEN_TYPE_ERC1155) {
            initCodeHash = keccak256(
                abi.encodePacked(
                    type(EwpgERC1155).creationCode,
                    abi.encode(symbol, registryWallet, assetId)
                )
            );
        } else {
            revert("AssetTokenFactory: unsupported token type");
        }

        return address(
            uint160(
                uint256(
                    keccak256(
                        abi.encodePacked(
                            bytes1(0xff),
                            address(this),
                            salt,
                            initCodeHash
                        )
                    )
                )
            )
        );
    }
}
