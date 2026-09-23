// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@erc3643/ERC-3643/IERC3643.sol";
import "@erc3643/ERC-3643/IERC3643IdentityRegistry.sol";
import "@erc3643/factory/ITREXFactory.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import "@onchain-id/solidity/contracts/Identity.sol";
import "../helpers/TrexSuiteDeployer.sol";

/// @notice T2-21 regression: claims in the backend's exact format (ClaimSigningService /
///         Erc3643DeploymentService) against real ONCHAINID + T-REX contracts.
///
///         The backend signs with the chain's registry signer on behalf of a per-chain ONCHAINID
///         ClaimIssuer whose MANAGEMENT key is that signer, passes the ClaimIssuer as `_issuer`,
///         and makes it the suite's sole trusted issuer (KYC + AML). Before T2-21 it passed the
///         signer EOA as `_issuer`, which `Identity.addClaim` rejects.
contract ClaimIssuerBackendFormatTest is Test {
    uint256 constant TOPIC_KYC = 1;
    uint256 constant TOPIC_AML = 2;
    uint16 constant COUNTRY_DE = 276;

    // Same fixed test key as backend ClaimSigningServiceTest (0x11 * 32).
    uint256 constant SIGNER_KEY = 0x1111111111111111111111111111111111111111111111111111111111111111;

    // Cross-implementation vector produced by backend ClaimSigningService
    // (ClaimSigningServiceTest.signClaim_onBehalfOfClaimIssuerContract_matchesPinnedOnchainIdVector).
    address constant PINNED_IDENTITY = address(uint160(0x01d1));
    address constant PINNED_CLAIM_ISSUER = address(uint160(0xc1a1));
    bytes constant PINNED_SIGNATURE =
        hex"ed6546e6cbd06f420e71af02067b9479b78e2b087cc7dc74edad75f61cfb883543ba547e89dbbf172d6faf1f50eac9bf4ea2853c8a7e1e1b4d66afa52003da501b";

    address signer;
    ClaimIssuer claimIssuer;
    TrexDeployment trex;
    IERC3643 token;
    IERC3643IdentityRegistry identityRegistry;

    function setUp() public {
        signer = vm.addr(SIGNER_KEY);
        claimIssuer = new ClaimIssuer(signer); // script/DeployClaimIssuer.s.sol equivalent

        // Mirrors Erc3643DeploymentService.buildDeployEwpgSuiteFunction: owner/agents = signer,
        // topics KYC + AML, sole trusted issuer = the ClaimIssuer contract.
        trex = TrexSuiteDeployer.deploy(signer);
        address[] memory agents = new address[](1);
        agents[0] = signer;
        uint256[] memory topics = new uint256[](2);
        topics[0] = TOPIC_KYC;
        topics[1] = TOPIC_AML;
        address[] memory issuers = new address[](1);
        issuers[0] = address(claimIssuer);
        uint256[][] memory issuerClaims = new uint256[][](1);
        issuerClaims[0] = topics;
        vm.prank(signer);
        token = IERC3643(
            trex.factory
                .deployEwpgSuite(
                    keccak256("t2-21"),
                    "registerwerk-t2-21",
                    ITREXFactory.TokenDetails({
                        owner: signer,
                        name: "T2-21",
                        symbol: "T221",
                        decimals: 0,
                        irs: address(0),
                        ONCHAINID: address(0),
                        irAgents: agents,
                        tokenAgents: agents,
                        complianceModules: new address[](0),
                        complianceSettings: new bytes[](0)
                    }),
                    ITREXFactory.ClaimDetails({claimTopics: topics, issuers: issuers, issuerClaims: issuerClaims})
                )
        );
        identityRegistry = token.identityRegistry();
    }

    /// ClaimSigningService claimData = abi.encode(topic, scheme=1, issuer, expiresAt, uri="").
    function _backendClaimData(uint256 topic, address issuer) internal pure returns (bytes memory) {
        return abi.encode(topic, uint256(1), issuer, uint256(0), "");
    }

    /// ClaimSigningService signature: EIP-191 over keccak256(abi.encode(identity, topic, data)).
    function _backendSign(address identity, uint256 topic, bytes memory data) internal pure returns (bytes memory) {
        bytes32 dataHash = keccak256(abi.encode(identity, topic, data));
        (uint8 v, bytes32 r, bytes32 s) =
            vm.sign(SIGNER_KEY, keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", dataHash)));
        return abi.encodePacked(r, s, v);
    }

    /// Investor identity as OnChainIdService deploys it: registry signer = MANAGEMENT key.
    function _investor(string memory label) internal returns (address wallet, Identity identity) {
        wallet = makeAddr(label);
        identity = new Identity(signer, false);
        vm.prank(signer);
        identityRegistry.registerIdentity(wallet, IIdentity(address(identity)), COUNTRY_DE);
    }

    /// Erc3643DeploymentService.issueKycClaim: signer submits addClaim with _issuer = ClaimIssuer.
    function _issue(Identity identity, uint256 topic) internal returns (bytes memory sig) {
        bytes memory data = _backendClaimData(topic, address(claimIssuer));
        sig = _backendSign(address(identity), topic, data);
        vm.prank(signer);
        identity.addClaim(topic, 1, address(claimIssuer), sig, data, "");
    }

    function test_pinnedBackendVector_isValidAtRealClaimIssuer() public {
        deployCodeTo("ClaimIssuer.sol:ClaimIssuer", abi.encode(signer), PINNED_CLAIM_ISSUER);
        bytes memory data = _backendClaimData(TOPIC_KYC, PINNED_CLAIM_ISSUER);
        assertEq(_backendSign(PINNED_IDENTITY, TOPIC_KYC, data), PINNED_SIGNATURE, "Foundry/Java signing diverged");
        assertTrue(
            ClaimIssuer(PINNED_CLAIM_ISSUER).isClaimValid(IIdentity(PINNED_IDENTITY), TOPIC_KYC, PINNED_SIGNATURE, data)
        );
    }

    function test_backendClaims_viaClaimIssuer_makeInvestorVerified() public {
        (address wallet, Identity identity) = _investor("alice");
        assertFalse(identityRegistry.isVerified(wallet));

        _issue(identity, TOPIC_KYC);
        assertFalse(identityRegistry.isVerified(wallet), "AML still missing");
        _issue(identity, TOPIC_AML);
        assertTrue(identityRegistry.isVerified(wallet));

        (,, address issuer,,,) = identity.getClaim(keccak256(abi.encode(address(claimIssuer), TOPIC_KYC)));
        assertEq(issuer, address(claimIssuer));
    }

    /// Pre-T2-21 behaviour: the signer EOA as `_issuer` can never be added.
    function test_eoaIssuer_addClaimReverts() public {
        (, Identity identity) = _investor("bob");
        bytes memory data = _backendClaimData(TOPIC_KYC, signer);
        bytes memory sig = _backendSign(address(identity), TOPIC_KYC, data);
        vm.prank(signer);
        vm.expectRevert();
        identity.addClaim(TOPIC_KYC, 1, signer, sig, data, "");
    }

    /// ClaimIssuanceService.revoke: removeClaim + revokeClaimBySignature. The issuer-level step
    /// makes the removal final: the public signature/data can no longer be re-added.
    function test_revocationAtIssuer_blocksVerificationAndReAdd() public {
        (address wallet, Identity identity) = _investor("carol");
        bytes memory kycSig = _issue(identity, TOPIC_KYC);
        _issue(identity, TOPIC_AML);
        assertTrue(identityRegistry.isVerified(wallet));

        vm.startPrank(signer);
        identity.removeClaim(keccak256(abi.encode(address(claimIssuer), TOPIC_KYC)));
        claimIssuer.revokeClaimBySignature(kycSig);
        vm.stopPrank();

        assertTrue(claimIssuer.isClaimRevoked(kycSig));
        assertFalse(identityRegistry.isVerified(wallet));

        bytes memory data = _backendClaimData(TOPIC_KYC, address(claimIssuer));
        vm.prank(signer);
        vm.expectRevert();
        identity.addClaim(TOPIC_KYC, 1, address(claimIssuer), kycSig, data, "");
    }

    /// Issuer-level revocation alone (removeClaim not yet confirmed) already fails verification.
    function test_revocationAtIssuerAlone_failsVerification() public {
        (address wallet, Identity identity) = _investor("dave");
        bytes memory kycSig = _issue(identity, TOPIC_KYC);
        _issue(identity, TOPIC_AML);

        vm.prank(signer);
        claimIssuer.revokeClaimBySignature(kycSig);
        assertFalse(identityRegistry.isVerified(wallet));
    }

    /// Erc3643DeploymentService pre-flight: keyHasPurpose(keccak256(abi.encode(signer)), 3).
    function test_signerPreflight_keyHasClaimPurpose() public view {
        assertTrue(claimIssuer.keyHasPurpose(keccak256(abi.encode(signer)), 3));
        assertFalse(claimIssuer.keyHasPurpose(keccak256(abi.encode(address(0xBEEF))), 3));
    }
}
