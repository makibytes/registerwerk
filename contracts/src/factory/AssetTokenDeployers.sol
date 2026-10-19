// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {EwpgERC20} from "../tokens/EwpgERC20.sol";
import {EwpgERC721} from "../tokens/EwpgERC721.sol";
import {EwpgERC1155} from "../tokens/EwpgERC1155.sol";
import {EwpgERC3525} from "../tokens/EwpgERC3525.sol";
import {EwpgERC4626} from "../tokens/EwpgERC4626.sol";
import {EwpgERC7540} from "../tokens/EwpgERC7540.sol";

interface IAssetTokenDeployer {
    function bindFactory(address factory) external;
    function deploy(
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset,
        bytes32 salt
    ) external returns (address);
    function predict(
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset,
        bytes32 salt
    ) external view returns (address);
}

/// @dev One module per token standard. A module only says how to build its token's creation code
///      ({_initCode}); {deploy} and {predict} both go through it, so the address {predict} returns is by
///      construction the address {deploy} creates.
abstract contract AssetTokenDeployerBase is IAssetTokenDeployer {
    address public immutable registryWallet;
    address public factory;

    error NotRegistry();
    error NotFactory();
    error FactoryAlreadyBound();

    /// @notice The one factory allowed to call {deploy}; emitted once, when the registry binds it.
    event FactoryBound(address indexed factory);

    constructor(address registryWallet_) {
        if (registryWallet_ == address(0)) revert NotRegistry();
        registryWallet = registryWallet_;
    }

    function bindFactory(address factory_) external {
        if (msg.sender != registryWallet) revert NotRegistry();
        if (factory != address(0)) revert FactoryAlreadyBound();
        if (factory_ == address(0) || factory_.code.length == 0) revert NotFactory();
        factory = factory_;
        emit FactoryBound(factory_);
    }

    modifier onlyFactory() {
        if (msg.sender != factory) revert NotFactory();
        _;
    }

    function deploy(
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset,
        bytes32 salt
    ) external onlyFactory returns (address deployed) {
        bytes memory initCode = _initCode(name, symbol, assetId, underlyingAsset);
        assembly ("memory-safe") {
            deployed := create2(0, add(initCode, 0x20), mload(initCode), salt)
        }
        require(deployed != address(0), "AssetTokenDeployer: CREATE2 failed");
    }

    function predict(
        string calldata name,
        string calldata symbol,
        bytes32 assetId,
        address underlyingAsset,
        bytes32 salt
    ) external view returns (address) {
        bytes32 initCodeHash = keccak256(_initCode(name, symbol, assetId, underlyingAsset));
        return address(uint160(uint256(keccak256(abi.encodePacked(bytes1(0xff), address(this), salt, initCodeHash)))));
    }

    /// @dev Creation code of the token followed by its ABI-encoded constructor arguments.
    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address underlyingAsset)
        internal
        view
        virtual
        returns (bytes memory);
}

contract Erc20TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(type(EwpgERC20).creationCode, abi.encode(name, symbol, registryWallet, assetId));
    }
}

contract Erc721TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(type(EwpgERC721).creationCode, abi.encode(name, symbol, registryWallet, assetId));
    }
}

/// @dev ERC-1155 has no name: the symbol is the only label the token takes.
contract Erc1155TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata, string calldata symbol, bytes32 assetId, address)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(type(EwpgERC1155).creationCode, abi.encode(symbol, registryWallet, assetId));
    }
}

contract Erc3525TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(type(EwpgERC3525).creationCode, abi.encode(name, symbol, registryWallet, assetId));
    }
}

contract Erc4626TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address underlyingAsset)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(
            type(EwpgERC4626).creationCode, abi.encode(IERC20(underlyingAsset), name, symbol, registryWallet, assetId)
        );
    }
}

contract Erc7540TokenDeployer is AssetTokenDeployerBase {
    constructor(address registry) AssetTokenDeployerBase(registry) {}

    function _initCode(string calldata name, string calldata symbol, bytes32 assetId, address underlyingAsset)
        internal
        view
        override
        returns (bytes memory)
    {
        return abi.encodePacked(
            type(EwpgERC7540).creationCode, abi.encode(IERC20(underlyingAsset), name, symbol, registryWallet, assetId)
        );
    }
}
