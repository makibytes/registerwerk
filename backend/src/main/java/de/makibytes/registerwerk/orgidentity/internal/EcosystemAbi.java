package de.makibytes.registerwerk.orgidentity.internal;

import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Web3j function builders for the ecosystem OrgRegistry contract. */
final class EcosystemAbi {

    private EcosystemAbi() {}

    /** keccak256 of a role/permission code, matching Solidity {@code keccak256(bytes(code))}. */
    static byte[] codeHash(String code) {
        return Hash.sha3(code.getBytes(StandardCharsets.UTF_8));
    }

    static String codeHashHex(String code) {
        return Numeric.toHexString(codeHash(code));
    }

    static Function registerOrg(String orgAddress, int country) {
        return new Function("registerOrg",
                List.of(new Address(orgAddress), new Uint16(country)),
                List.of());
    }

    static Function suspendOrg(String orgAddress, String reason) {
        return new Function("suspendOrg",
                List.of(new Address(orgAddress), new Utf8String(reason)),
                List.of());
    }

    static Function reinstateOrg(String orgAddress) {
        return new Function("reinstateOrg",
                List.of(new Address(orgAddress)),
                List.of());
    }

    /**
     * The operator-relayed {@code addMember} path never needs a wallet-consent signature — the
     * backend only ever broadcasts this after {@code MemberWalletService.bindWallet} already
     * verified the wallet's own nonce-challenge + personal_sign proof off-chain (see {@code
     * OrgRegistry}'s NatSpec on why the org-admin-direct path, not this one, needed the fix).
     */
    static Function addMember(String orgAddress, String walletAddress, List<String> roleCodes) {
        return new Function("addMember",
                List.of(new Address(orgAddress), new Address(walletAddress), roleArray(roleCodes),
                        new DynamicBytes(new byte[0])),
                List.of());
    }

    static Function removeMember(String orgAddress, String walletAddress) {
        return new Function("removeMember",
                List.of(new Address(orgAddress), new Address(walletAddress)),
                List.of());
    }

    static Function setMemberRoles(String orgAddress, String walletAddress, List<String> roleCodes) {
        return new Function("setMemberRoles",
                List.of(new Address(orgAddress), new Address(walletAddress), roleArray(roleCodes)),
                List.of());
    }

    static Function orgOf(String walletAddress) {
        return new Function("orgOf",
                List.of(new Address(walletAddress)),
                List.of(new TypeReference<Address>() {}));
    }

    static Function isOrgActive(String orgAddress) {
        return new Function("isOrgActive",
                List.of(new Address(orgAddress)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
    }

    // ── PermissionRegistry ────────────────────────────────────────────────────

    static Function definePermission(String code) {
        return new Function("definePermission",
                List.of(new Bytes32(codeHash(code)), new Utf8String(code)),
                List.of());
    }

    static Function grantToOrg(String orgAddress, String permissionCode) {
        return new Function("grantToOrg",
                List.of(new Address(orgAddress), new Bytes32(codeHash(permissionCode))),
                List.of());
    }

    static Function revokeFromOrg(String orgAddress, String permissionCode) {
        return new Function("revokeFromOrg",
                List.of(new Address(orgAddress), new Bytes32(codeHash(permissionCode))),
                List.of());
    }

    static Function grantToRole(String orgAddress, String roleCode, String permissionCode) {
        return new Function("grantToRole",
                List.of(new Address(orgAddress), new Bytes32(codeHash(roleCode)), new Bytes32(codeHash(permissionCode))),
                List.of());
    }

    static Function revokeFromRole(String orgAddress, String roleCode, String permissionCode) {
        return new Function("revokeFromRole",
                List.of(new Address(orgAddress), new Bytes32(codeHash(roleCode)), new Bytes32(codeHash(permissionCode))),
                List.of());
    }

    static Function setRoleRestricted(String orgAddress, String permissionCode, boolean restricted) {
        return new Function("setRoleRestricted",
                List.of(new Address(orgAddress), new Bytes32(codeHash(permissionCode)),
                        new org.web3j.abi.datatypes.Bool(restricted)),
                List.of());
    }

    static Function orgGranted(String orgAddress, String permissionCode) {
        return new Function("orgGranted",
                List.of(new Address(orgAddress), new Bytes32(codeHash(permissionCode))),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
    }

    static Function roleGranted(String orgAddress, String roleCode, String permissionCode) {
        return new Function("roleGranted",
                List.of(new Address(orgAddress), new Bytes32(codeHash(roleCode)), new Bytes32(codeHash(permissionCode))),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
    }

    static Function isRoleRestricted(String orgAddress, String permissionCode) {
        return new Function("isRoleRestricted",
                List.of(new Address(orgAddress), new Bytes32(codeHash(permissionCode))),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
    }

    // ── EcosystemTrustedIssuersRegistry ───────────────────────────────────────

    static Function addTrustedIssuer(String issuerAddress, List<Long> claimTopics) {
        List<Uint256> topics = claimTopics.stream()
                .map(topic -> new Uint256(java.math.BigInteger.valueOf(topic)))
                .toList();
        return new Function("addTrustedIssuer",
                List.of(new Address(issuerAddress), new DynamicArray<>(Uint256.class, topics)),
                List.of());
    }

    static Function removeTrustedIssuer(String issuerAddress) {
        return new Function("removeTrustedIssuer",
                List.of(new Address(issuerAddress)),
                List.of());
    }

    private static DynamicArray<Bytes32> roleArray(List<String> roleCodes) {
        List<Bytes32> hashes = roleCodes.stream()
                .map(code -> new Bytes32(codeHash(code)))
                .toList();
        return new DynamicArray<>(Bytes32.class, hashes);
    }
}
