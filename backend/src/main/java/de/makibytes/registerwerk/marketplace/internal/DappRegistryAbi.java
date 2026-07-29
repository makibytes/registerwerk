package de.makibytes.registerwerk.marketplace.internal;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Web3j function builders for the ecosystem DappRegistry contract. */
final class DappRegistryAbi {

    /** Matches IDappRegistry.DappStatus. */
    static final int STATUS_ACTIVE = 1;
    static final int STATUS_SUSPENDED = 2;
    static final int STATUS_DEPRECATED = 3;

    private DappRegistryAbi() {}

    /** keccak256 of a code/slug, matching Solidity {@code keccak256(bytes(value))}. */
    static byte[] hash(String value) {
        return Hash.sha3(value.getBytes(StandardCharsets.UTF_8));
    }

    static String hashHex(String value) {
        return Numeric.toHexString(hash(value));
    }

    static Function registerDapp(String slug, String publisherOrg, byte[] manifestHash, String version,
                                 List<String> permissionCodes, List<Long> claimTopics) {
        return new Function("registerDapp",
                List.of(
                        new Bytes32(hash(slug)),
                        new Address(publisherOrg),
                        new Bytes32(manifestHash),
                        new Utf8String(version),
                        permissionArray(permissionCodes),
                        topicArray(claimTopics)),
                List.of());
    }

    static Function updateManifest(String slug, byte[] manifestHash, String version) {
        return new Function("updateManifest",
                List.of(new Bytes32(hash(slug)), new Bytes32(manifestHash), new Utf8String(version)),
                List.of());
    }

    static Function setStatus(String slug, int status) {
        return new Function("setStatus",
                List.of(new Bytes32(hash(slug)), new Uint8(BigInteger.valueOf(status))),
                List.of());
    }

    private static DynamicArray<Bytes32> permissionArray(List<String> codes) {
        return new DynamicArray<>(Bytes32.class, codes.stream().map(code -> new Bytes32(hash(code))).toList());
    }

    private static DynamicArray<Uint256> topicArray(List<Long> topics) {
        return new DynamicArray<>(Uint256.class,
                topics.stream().map(topic -> new Uint256(BigInteger.valueOf(topic))).toList());
    }
}
