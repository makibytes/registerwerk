// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@erc3643/factory/TREXFactory.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "../interfaces/IERC3643Suite.sol";

/**
 * @title EwpgTREXFactory
 * @notice Wraps the T-REX TREXFactory to add eWpG registry linkage.
 *         The backend calls deployEwpgSuite() instead of deployTREXSuite()
 *         so every token deployment is automatically indexed by its assetId.
 *
 * Usage:
 *   EwpgTREXFactory factory = new EwpgTREXFactory(implementationAuthority, idFactory);
 *   factory.deployEwpgSuite(assetId, salt, tokenDetails, claimDetails);
 *
 * The salt is a human-readable string (e.g. the assetId hex string) used for
 * CREATE2 — it must be unique across all deployments on a given chain.
 */
contract EwpgTREXFactory is TREXFactory {
    // ── Events ─────────────────────────────────────────────────────────────

    event EwpgSuiteDeployed(
        bytes32 indexed assetId,
        address indexed tokenAddress,
        address identityRegistry,
        address compliance
    );

    // ── State ──────────────────────────────────────────────────────────────

    /// @notice Maps registry assetId → deployed token address.
    mapping(bytes32 => address) public suiteByAssetId;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * @param implementationAuthority  Address of the T-REX ImplementationAuthority contract.
     * @param idFactory                Address of the ONCHAINID IdFactory contract.
     */
    constructor(address implementationAuthority, address idFactory)
        TREXFactory(implementationAuthority, idFactory)
    {}

    // ── Deployment ─────────────────────────────────────────────────────────

    /**
     * @notice Deploy a full T-REX suite (Token, IR, IRS, CTR, TIR, Compliance) and
     *         register the token address against the eWpG registry assetId.
     *
     * @param assetId  Registry DB asset ID (bytes32 keccak of UUID).
     * @param salt     CREATE2 salt — must be unique per chain. Conventionally the
     *                 hex string of assetId, e.g. vm.toString(assetId) in tests.
     * @param details  T-REX TokenDetails: name, symbol, decimals, agents, compliance modules, …
     * @param claims   T-REX ClaimDetails: required claim topics and trusted issuers.
     * @return tokenAddress  Address of the newly deployed EwpgERC3643 token proxy.
     */
    function deployEwpgSuite(
        bytes32 assetId,
        string calldata salt,
        ITREXFactory.TokenDetails calldata details,
        ITREXFactory.ClaimDetails calldata claims
    ) external returns (address tokenAddress) {
        require(suiteByAssetId[assetId] == address(0), "EwpgTREXFactory: assetId already deployed");

        deployTREXSuite(salt, details, claims);
        tokenAddress = getToken(salt);

        suiteByAssetId[assetId] = tokenAddress;

        // Retrieve the ancillary contract addresses for the event.
        // TREXFactory.getContracts() returns (token, ir, irs, tir, ctr, mc).
        (, address ir,, address mc,,) = getContracts(salt);

        emit EwpgSuiteDeployed(assetId, tokenAddress, ir, mc);
    }

    // ── View helpers ───────────────────────────────────────────────────────

    /**
     * @notice Predict the token proxy address before deployment (CREATE2).
     *         Useful for off-chain indexing and configuration before the tx lands.
     * @param salt  The same salt that will be passed to deployEwpgSuite().
     * @return      Predicted token address.
     */
    function predictTokenAddress(string calldata salt) external view returns (address) {
        return getToken(salt);
    }

    /**
     * @notice Return all six suite addresses for a given salt in one call.
     * @return token_      EwpgERC3643 proxy
     * @return ir_         IdentityRegistry proxy
     * @return irs_        IdentityRegistryStorage proxy
     * @return tir_        TrustedIssuersRegistry proxy
     * @return ctr_        ClaimTopicsRegistry proxy
     * @return compliance_ ModularCompliance proxy
     */
    function getSuiteAddresses(string calldata salt)
        external
        view
        returns (
            address token_,
            address ir_,
            address irs_,
            address tir_,
            address ctr_,
            address compliance_
        )
    {
        (token_, ir_, irs_, tir_, ctr_, compliance_) = getContracts(salt);
    }
}
