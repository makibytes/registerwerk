// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

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
 * @dev {fhevmInfra} holds the five FHEVM/Gateway host-contract addresses for
 * whichever network this factory instance is deployed on. These are
 * owner-settable, not hardcoded — see {ConfidentialERC20.FhevmInfra}'s
 * class-level note: Zama's own mainnet addresses are still being finalised
 * and are governance-upgradeable even once live, and T-REX Chain (which
 * announced in March 2026 that Zama is its confidentiality layer) will
 * publish its own addresses independently of Ethereum's. Deploy this factory
 * once per target network/chain and configure it with that network's real
 * addresses via {setFhevmInfra} before calling either deploy function.
 */
contract EwpgConfidentialFactory is Ownable {
    enum TokenType { CONFIDENTIAL_ERC20, CONFIDENTIAL_ERC3643 }

    ConfidentialERC20.FhevmInfra public fhevmInfra;

    event ConfidentialTokenDeployed(
        bytes32 indexed assetId,
        uint8   indexed tokenType,
        address indexed tokenAddress
    );

    event FhevmInfraUpdated(
        address aclAddress,
        address tfheExecutorAddress,
        address fhePaymentAddress,
        address kmsVerifierAddress,
        address gatewayAddress
    );

    error FhevmInfraNotConfigured();

    constructor(ConfidentialERC20.FhevmInfra memory _infra, address _owner) Ownable(_owner) {
        fhevmInfra = _infra;
    }

    function setFhevmInfra(ConfidentialERC20.FhevmInfra calldata _infra) external onlyOwner {
        fhevmInfra = _infra;
        emit FhevmInfraUpdated(
            _infra.aclAddress, _infra.tfheExecutorAddress, _infra.fhePaymentAddress,
            _infra.kmsVerifierAddress, _infra.gatewayAddress
        );
    }

    /**
     * @notice Deploys a confidential ERC-20 (ERC-7984) token for `assetId`.
     * @param assetId         Registerwerk asset UUID encoded as bytes32 (also CREATE2 salt).
     * @param name_           Token name.
     * @param symbol_         Token symbol.
     * @param initialViewers  Addresses granted decrypt rights on every holder's balance from
     *                        deployment — typically the registry operator's and an auditor's
     *                        viewer wallets (see {ConfidentialERC20}'s class-level ACL note).
     *                        Passed at construction so no separate post-deploy `addViewer`
     *                        transaction is needed before the first mint is reconcilable.
     */
    function deployConfidentialErc20(
        bytes32 assetId,
        string calldata name_,
        string calldata symbol_,
        address[] calldata initialViewers
    ) external onlyOwner returns (address token) {
        _requireFhevmInfraConfigured();
        bytes memory bytecode = abi.encodePacked(
            type(ConfidentialERC20).creationCode,
            abi.encode(assetId, name_, symbol_, fhevmInfra, initialViewers, msg.sender)
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
     * @param initialViewers   Addresses granted decrypt rights on every holder's balance from
     *                         deployment — the registry operator/auditor plus, typically, the
     *                         issuer's own bound wallet for issuer-facing visibility.
     * @param identityRegistry Address of the T-REX IdentityRegistry to use — must NOT be the
     *                         zero address; a confidential security token with no identity
     *                         registry enforces no KYC gate at all.
     * @param compliance       Address of the IConfidentialCompliance module (may be the zero
     *                         address if no additional transfer-rule module is needed yet).
     */
    function deployConfidentialErc3643(
        bytes32 assetId,
        string calldata name_,
        string calldata symbol_,
        address[] calldata initialViewers,
        address identityRegistry,
        address compliance
    ) external onlyOwner returns (address token) {
        _requireFhevmInfraConfigured();
        require(identityRegistry != address(0), "EwpgConfidentialFactory: identityRegistry required");
        bytes memory bytecode = abi.encodePacked(
            type(ConfidentialERC3643).creationCode,
            abi.encode(
                assetId, name_, symbol_, fhevmInfra, initialViewers,
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

    function _requireFhevmInfraConfigured() internal view {
        if (fhevmInfra.aclAddress == address(0) || fhevmInfra.gatewayAddress == address(0)) {
            revert FhevmInfraNotConfigured();
        }
    }
}
