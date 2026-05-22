// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "../tokens/EwpgERC20.sol";
import "../tokens/EwpgERC721.sol";
import "../tokens/EwpgERC1155.sol";
import "../tokens/EwpgERC3525.sol";
import "../tokens/EwpgERC4626.sol";
import "../tokens/EwpgERC7540.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";

/// @title AssetTokenFactory
/// @notice CREATE2 factory for deploying eWpG token contracts with deterministic addresses.
///         Supports ERC-20 (0), ERC-721 (1), ERC-1155 (2), ERC-3525 (3), ERC-4626 (4), ERC-7540 (5).
///         The registry wallet is embedded at construction time and forwarded to every
///         deployed token so ownership and compliance authority are consistent.
contract AssetTokenFactory {
    /// @notice The registry wallet forwarded to every deployed token contract.
    address public immutable registryWallet;

    /// @dev Token type constants for readability.
    uint8 public constant TOKEN_TYPE_ERC20 = 0;
    uint8 public constant TOKEN_TYPE_ERC721 = 1;
    uint8 public constant TOKEN_TYPE_ERC1155 = 2;
    uint8 public constant TOKEN_TYPE_ERC3525 = 3;
    uint8 public constant TOKEN_TYPE_ERC4626 = 4;
    uint8 public constant TOKEN_TYPE_ERC7540 = 5;

    event TokenDeployed(
        bytes32 indexed assetId,
        uint8 indexed tokenType,
        address indexed tokenAddress
    );

    event VaultDeployed(
        bytes32 indexed assetId,
        uint8 indexed tokenType,
        address indexed vaultAddress,
        address underlyingAsset
    );

    constructor(address _registryWallet) {
        require(_registryWallet != address(0), "AssetTokenFactory: zero registry address");
        registryWallet = _registryWallet;
    }

    /// @notice Deploy a token contract for the given asset using CREATE2.
    ///         Supports fungible (ERC-20), NFT (ERC-721), multi-token (ERC-1155), and SFT (ERC-3525).
    ///         For vaults (ERC-4626/7540) use deployVault instead.
    /// @param tokenType Token standard: 0=ERC20, 1=ERC721, 2=ERC1155, 3=ERC3525.
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
        } else if (tokenType == TOKEN_TYPE_ERC3525) {
            tokenAddress = address(
                new EwpgERC3525{salt: salt}(name, symbol, registryWallet, assetId)
            );
        } else {
            revert("AssetTokenFactory: unsupported token type — for ERC-4626/7540 use deployVault");
        }

        emit TokenDeployed(assetId, tokenType, tokenAddress);
    }

    /// @notice Deploy an ERC-4626 or ERC-7540 vault contract for the given asset using CREATE2.
    ///         Vault types need an underlying ERC-20 token address, which simple token types do not.
    /// @param tokenType Vault standard: 4=ERC4626 (sync), 5=ERC7540 (async).
    /// @param name Vault share token name.
    /// @param symbol Vault share token symbol.
    /// @param assetId Unique identifier linking this contract to an off-chain asset record.
    /// @param underlyingAsset The ERC-20 token that investors deposit (e.g. USDC).
    /// @return vaultAddress The address of the newly deployed vault contract.
    function deployVault(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset
    ) external returns (address vaultAddress) {
        require(underlyingAsset != address(0), "AssetTokenFactory: zero underlying asset");
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));

        if (tokenType == TOKEN_TYPE_ERC4626) {
            vaultAddress = address(
                new EwpgERC4626{salt: salt}(IERC20(underlyingAsset), name, symbol, registryWallet, assetId)
            );
        } else if (tokenType == TOKEN_TYPE_ERC7540) {
            vaultAddress = address(
                new EwpgERC7540{salt: salt}(IERC20(underlyingAsset), name, symbol, registryWallet, assetId)
            );
        } else {
            revert("AssetTokenFactory: unsupported vault type — use 4 (ERC4626) or 5 (ERC7540)");
        }

        emit VaultDeployed(assetId, tokenType, vaultAddress, underlyingAsset);
    }

    /// @notice Predict the CREATE2 address for a token without deploying it.
    function predictAddress(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId
    ) external view returns (address) {
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));
        bytes32 initCodeHash;

        if (tokenType == TOKEN_TYPE_ERC20) {
            initCodeHash = keccak256(abi.encodePacked(
                type(EwpgERC20).creationCode, abi.encode(name, symbol, registryWallet, assetId)));
        } else if (tokenType == TOKEN_TYPE_ERC721) {
            initCodeHash = keccak256(abi.encodePacked(
                type(EwpgERC721).creationCode, abi.encode(name, symbol, registryWallet, assetId)));
        } else if (tokenType == TOKEN_TYPE_ERC1155) {
            initCodeHash = keccak256(abi.encodePacked(
                type(EwpgERC1155).creationCode, abi.encode(symbol, registryWallet, assetId)));
        } else if (tokenType == TOKEN_TYPE_ERC3525) {
            initCodeHash = keccak256(abi.encodePacked(
                type(EwpgERC3525).creationCode, abi.encode(name, symbol, registryWallet, assetId)));
        } else {
            revert("AssetTokenFactory: unsupported token type");
        }

        return address(uint160(uint256(keccak256(abi.encodePacked(bytes1(0xff), address(this), salt, initCodeHash)))));
    }
}

