// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {IAssetTokenDeployer} from "./AssetTokenDeployers.sol";

/// @title AssetTokenFactory
/// @notice Small CREATE2 coordinator. Standard-specific creation bytecode lives in separately
///         deployable modules so this contract remains below EIP-170's runtime-size limit.
contract AssetTokenFactory {
    address public immutable registryWallet;
    mapping(uint8 tokenType => IAssetTokenDeployer deployer) public deployers;

    uint8 public constant TOKEN_TYPE_ERC20 = 0;
    uint8 public constant TOKEN_TYPE_ERC721 = 1;
    uint8 public constant TOKEN_TYPE_ERC1155 = 2;
    uint8 public constant TOKEN_TYPE_ERC3525 = 3;
    uint8 public constant TOKEN_TYPE_ERC4626 = 4;
    uint8 public constant TOKEN_TYPE_ERC7540 = 5;

    event DeployerConfigured(uint8 indexed tokenType, address indexed deployer);
    event TokenDeployed(bytes32 indexed assetId, uint8 indexed tokenType, address indexed tokenAddress);
    event VaultDeployed(
        bytes32 indexed assetId, uint8 indexed tokenType, address indexed vaultAddress, address underlyingAsset
    );

    constructor(address registryWallet_) {
        require(registryWallet_ != address(0), "AssetTokenFactory: zero registry address");
        registryWallet = registryWallet_;
    }

    /// @dev Deployment is restricted to the registry wallet (T1-17). The salt is
    ///      `keccak(assetId, tokenType)` and the initcode is fully determined by public
    ///      calldata, so an open `deployToken`/`deployVault` let anyone front-run the
    ///      registry's transaction with identical arguments and make its CREATE2 revert.
    ///      Factories deployed before this change remain permissionless; the backend
    ///      tolerates that by adopting a matching contract already at `predictAddress`.
    modifier onlyRegistry() {
        require(msg.sender == registryWallet, "AssetTokenFactory: only registry");
        _;
    }

    function configureDeployer(uint8 tokenType, address deployer) external onlyRegistry {
        require(tokenType <= TOKEN_TYPE_ERC7540, "AssetTokenFactory: unsupported token type");
        require(address(deployers[tokenType]) == address(0), "AssetTokenFactory: deployer already configured");
        require(deployer != address(0) && deployer.code.length > 0, "AssetTokenFactory: invalid deployer");
        deployers[tokenType] = IAssetTokenDeployer(deployer);
        emit DeployerConfigured(tokenType, deployer);
    }

    function deployToken(uint8 tokenType, string calldata name, string calldata symbol, bytes32 assetId)
        external
        onlyRegistry
        returns (address tokenAddress)
    {
        if (tokenType > TOKEN_TYPE_ERC3525) {
            revert(unicode"AssetTokenFactory: unsupported token type — for ERC-4626/7540 use deployVault");
        }
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));
        IAssetTokenDeployer module = _deployer(tokenType);
        tokenAddress = module.deploy(name, symbol, assetId, address(0), salt);
        emit TokenDeployed(assetId, tokenType, tokenAddress);
    }

    function deployVault(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset
    ) external onlyRegistry returns (address vaultAddress) {
        require(underlyingAsset != address(0), "AssetTokenFactory: zero underlying asset");
        if (tokenType < TOKEN_TYPE_ERC4626 || tokenType > TOKEN_TYPE_ERC7540) {
            revert(unicode"AssetTokenFactory: unsupported vault type — use 4 (ERC4626) or 5 (ERC7540)");
        }
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));
        IAssetTokenDeployer module = _deployer(tokenType);
        vaultAddress = module.deploy(name, symbol, assetId, underlyingAsset, salt);
        emit VaultDeployed(assetId, tokenType, vaultAddress, underlyingAsset);
    }

    function predictAddress(
        uint8 tokenType,
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset
    ) external view returns (address) {
        if (tokenType > TOKEN_TYPE_ERC7540) revert("AssetTokenFactory: unsupported token type");
        if (tokenType >= TOKEN_TYPE_ERC4626) {
            require(underlyingAsset != address(0), "AssetTokenFactory: zero underlying asset");
        }
        bytes32 salt = keccak256(abi.encode(assetId, tokenType));
        IAssetTokenDeployer module = _deployer(tokenType);
        return module.predict(name, symbol, assetId, underlyingAsset, salt);
    }

    function _deployer(uint8 tokenType) private view returns (IAssetTokenDeployer result) {
        result = deployers[tokenType];
        require(address(result) != address(0), "AssetTokenFactory: deployer not configured");
    }
}
