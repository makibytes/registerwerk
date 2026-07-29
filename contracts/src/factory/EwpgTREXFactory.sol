// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.27;

import "@openzeppelin/contracts/access/Ownable.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";
import "../interfaces/IERC3643Suite.sol";
import "../documents/EwpgDocumentStore.sol";

// Selective imports: TREXFactory.sol (via roles/AgentRole.sol) and EwpgERC3643.sol's
// T-REX `Token` base (via roles/AgentRoleUpgradeable.sol) both declare file-level
// `AgentAdded`/`AgentRemoved` events with identical signatures. Plain `import "X.sol";`
// re-exports a file's whole transitive import graph, so importing both plainly in the
// same file collides; `{Symbol} from` imports pull in only the named symbol.
import {TREXFactory} from "@erc3643/factory/TREXFactory.sol";
import {EwpgERC3643} from "../tokens/EwpgERC3643.sol";

/**
 * @title EwpgTREXFactory
 * @notice Wraps the T-REX TREXFactory to add eWpG registry linkage.
 *         The backend calls deployEwpgSuite() instead of deployTREXSuite()
 *         so every token deployment is automatically indexed by its assetId.
 *
 * @dev Composition instead of inheritance: T-REX 4.2 marks deployTREXSuite()
 *      external and non-virtual, so it cannot be invoked from a subclass. This
 *      wrapper deploys and owns an inner TREXFactory; the inner factory address
 *      (see {trexFactory}) is the one to register on the ONCHAINID IdFactory.
 *
 * Usage:
 *   EwpgTREXFactory factory = new EwpgTREXFactory(implementationAuthority, idFactory);
 *   factory.deployEwpgSuite(assetId, salt, tokenDetails, claimDetails);
 *
 * The salt is a human-readable string (e.g. the assetId hex string) used for
 * CREATE2 — it must be unique across all deployments on a given chain.
 */
contract EwpgTREXFactory is Ownable {
    // ── Events ─────────────────────────────────────────────────────────────

    event EwpgSuiteDeployed(
        bytes32 indexed assetId,
        address indexed tokenAddress,
        address identityRegistry,
        address compliance
    );

    // ── State ──────────────────────────────────────────────────────────────

    /// @notice The inner T-REX factory performing the actual suite deployments.
    TREXFactory public immutable trexFactory;

    /// @notice Maps registry assetId → deployed token address.
    mapping(bytes32 => address) public suiteByAssetId;

    /// @notice Maps registry assetId → companion EwpgDocumentStore address.
    mapping(bytes32 => address) public docStoreByAssetId;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * @param implementationAuthority  Address of the T-REX ImplementationAuthority contract.
     * @param idFactory                Address of the ONCHAINID IdFactory contract.
     */
    constructor(address implementationAuthority, address idFactory) Ownable(msg.sender) {
        trexFactory = new TREXFactory(implementationAuthority, idFactory);
    }

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
    ) external onlyOwner returns (address tokenAddress) {
        require(suiteByAssetId[assetId] == address(0), "EwpgTREXFactory: assetId already deployed");

        trexFactory.deployTREXSuite(salt, details, claims);
        tokenAddress = trexFactory.tokenDeployed(salt);

        suiteByAssetId[assetId] = tokenAddress;
        // Bind this proxy's own storage to the registry assetId — see the NatSpec on
        // EwpgERC3643.setAssetId for why this cannot be done via the token's constructor.
        EwpgERC3643(tokenAddress).setAssetId(assetId);

        // Deploy a companion document store for term sheet management.
        // EwpgERC3643 is a T-REX upgradeable proxy so document storage is kept separate.
        EwpgDocumentStore docStore = new EwpgDocumentStore(assetId, msg.sender);
        docStoreByAssetId[assetId] = address(docStore);

        IERC3643 token = IERC3643(tokenAddress);
        emit EwpgSuiteDeployed(
            assetId,
            tokenAddress,
            address(token.identityRegistry()),
            address(token.compliance())
        );
    }

    // ── View helpers ───────────────────────────────────────────────────────

    /**
     * @notice Return the token proxy address deployed under a given salt
     *         (address(0) while not yet deployed).
     * @param salt  The same salt that was passed to deployEwpgSuite().
     */
    function deployedTokenAddress(string calldata salt) external view returns (address) {
        return trexFactory.tokenDeployed(salt);
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
        token_ = trexFactory.tokenDeployed(salt);
        if (token_ == address(0)) {
            return (address(0), address(0), address(0), address(0), address(0), address(0));
        }
        IERC3643 token = IERC3643(token_);
        IERC3643IdentityRegistry ir = token.identityRegistry();
        ir_ = address(ir);
        irs_ = address(ir.identityStorage());
        tir_ = address(ir.issuersRegistry());
        ctr_ = address(ir.topicsRegistry());
        compliance_ = address(token.compliance());
    }
}
