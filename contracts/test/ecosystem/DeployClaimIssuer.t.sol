// SPDX-License-Identifier: MIT
pragma solidity ^0.8.27;

import "forge-std/Test.sol";
import "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import "../../script/DeployClaimIssuer.s.sol";

/// @notice The per-chain ClaimIssuer deployed by `DeployClaimIssuer`: by default the MANAGEMENT key is
///         the registry signer that broadcasts the deployment (the backend signs claims AND revokes
///         them with that key — `revokeClaimBySignature` is `onlyManager` in ONCHAINID), and
///         `CLAIM_ISSUER_MANAGEMENT_KEY` is an optional override for operators that run a separate
///         (cold / multisig) management key.
/// @dev One test function on purpose: `vm.setEnv` is process-wide and cannot be unset, so the cases
///      run in a fixed order (default first, then the override) and must not race with a sibling test.
contract DeployClaimIssuerTest is Test {
    uint256 constant TOPIC_KYC = 1;
    uint256 constant REGISTRY_PK = 0xA11CE;

    /// @dev The backend's claim signature: EIP-191 over keccak256(abi.encode(identity, topic, data)).
    function _sign(address identity, bytes memory data) internal returns (bytes memory) {
        bytes32 dataHash = keccak256(abi.encode(identity, TOPIC_KYC, data));
        (uint8 v, bytes32 r, bytes32 s) =
            vm.sign(REGISTRY_PK, keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", dataHash)));
        return abi.encodePacked(r, s, v);
    }

    function test_registrySignerIsTheManagerByDefault_andMayRevokeClaims_overrideIsOptional() public {
        address registrySigner = vm.addr(REGISTRY_PK);
        address coldManagement = address(0xC01D);
        bytes32 registryKeyHash = keccak256(abi.encode(registrySigner));
        bytes32 coldKeyHash = keccak256(abi.encode(coldManagement));
        address identity = address(0x1D);
        vm.setEnv("REGISTRY_WALLET_PRIVATE_KEY", vm.toString(bytes32(REGISTRY_PK)));
        DeployClaimIssuer script = new DeployClaimIssuer();

        // 1. No CLAIM_ISSUER_MANAGEMENT_KEY: the deployment succeeds and the registry signer is the
        //    MANAGEMENT key — the backend's claim signing and revocation both depend on it.
        ClaimIssuer issuer = script.run();
        assertTrue(issuer.keyHasPurpose(registryKeyHash, 1), "registry signer must be the manager by default");
        assertTrue(issuer.keyHasPurpose(registryKeyHash, 3), "and may sign claims");

        // 2. The revoke path: a claim signed by the registry signer is valid, and the registry signer
        //    (as manager) can revoke it by signature — which makes it invalid.
        bytes memory data = abi.encode(TOPIC_KYC, uint256(1), address(issuer), uint256(0), "");
        bytes memory sig = _sign(identity, data);
        assertTrue(issuer.isClaimValid(IIdentity(identity), TOPIC_KYC, sig, data));
        vm.prank(registrySigner);
        issuer.revokeClaimBySignature(sig);
        assertTrue(issuer.isClaimRevoked(sig));
        assertFalse(issuer.isClaimValid(IIdentity(identity), TOPIC_KYC, sig, data));

        // 3. Optional override: a separate management key is installed and the signer gets no
        //    management rights until that key grants them. A signer holding only CLAIM_SIGNER can sign
        //    but cannot revoke — the documented consequence of choosing a separate key.
        vm.setEnv("CLAIM_ISSUER_MANAGEMENT_KEY", vm.toString(coldManagement));
        ClaimIssuer separate = script.run();
        assertTrue(separate.keyHasPurpose(coldKeyHash, 1));
        assertFalse(separate.keyHasPurpose(registryKeyHash, 1));
        vm.prank(coldManagement);
        separate.addKey(registryKeyHash, 3, 1);
        bytes memory data2 = abi.encode(TOPIC_KYC, uint256(1), address(separate), uint256(0), "");
        bytes memory sig2 = _sign(identity, data2);
        assertTrue(separate.isClaimValid(IIdentity(identity), TOPIC_KYC, sig2, data2));
        vm.prank(registrySigner);
        vm.expectRevert();
        separate.revokeClaimBySignature(sig2);
        vm.prank(coldManagement);
        separate.revokeClaimBySignature(sig2);
        assertTrue(separate.isClaimRevoked(sig2));
    }
}
