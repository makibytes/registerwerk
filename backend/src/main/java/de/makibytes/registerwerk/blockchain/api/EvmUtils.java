package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.deployment.api.AssetLookupPort;

import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * Shared EVM encoding utilities.
 */
public final class EvmUtils {

    private EvmUtils() {}

    /**
     * Derives the on-chain token symbol for an asset deployment, so every EVM deploy service
     * ({@code Erc20DeploymentService}, {@code Erc721DeploymentService},
     * {@code Erc1155DeploymentService}, {@code Erc3525DeploymentService},
     * {@code Erc4626DeploymentService}, {@code Erc7540DeploymentService},
     * {@code ConfidentialErc20Service}, {@code ConfidentialErc3643Service}) gets a real ticker
     * derived from the asset name, rather than the literal token-standard name (e.g.
     * {@code "ERC20"}) shared identically by every bond/equity/fund on that standard. Mirrors
     * the logic {@code Erc3643DeploymentService} (the non-confidential T-REX path) already used,
     * promoted here so every EVM standard uses it consistently.
     */
    public static String tokenSymbol(AssetLookupPort.AssetInfo asset) {
        return tokenSymbol(asset.name());
    }

    /** @see #tokenSymbol(AssetLookupPort.AssetInfo) */
    public static String tokenSymbol(String assetName) {
        if (assetName == null) {
            return "TOKEN";
        }
        String cleaned = assetName.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return cleaned.isEmpty() ? "TOKEN" : cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    /**
     * Encodes a UUID as a right-aligned 32-byte array (bytes 16-31 = most significant,
     * bytes 24-31 = least significant — matches Solidity's bytes32 ABI encoding of a UUID).
     */
    public static byte[] uuidToBytes32(UUID uuid) {
        byte[] b = new byte[32];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            b[24 + i] = (byte) (lo & 0xFF); lo >>= 8;
        }
        for (int i = 7; i >= 0; i--) {
            b[16 + i] = (byte) (hi & 0xFF); hi >>= 8;
        }
        return b;
    }

    /**
     * Encodes a {@code uint256} (e.g. an {@code euint64} FHE ciphertext handle — a UDVT wrapping
     * {@code uint256}, see {@code lib/fhevm/lib/TFHE.sol}'s {@code type euint64 is uint256;}) as
     * a 0x-prefixed, zero-padded 32-byte hex string, the form
     * {@code ZamaRelayerClient}/{@code zama-relayer} expect for a ciphertext handle.
     */
    public static String uint256ToBytes32Hex(BigInteger value) {
        return Numeric.toHexStringWithPrefixZeroPadded(value, 64);
    }

    /**
     * Finds the first log matching {@code eventTopicSignature} (topic0) in a transaction receipt
     * and extracts an indexed {@code address} parameter from the given topic position.
     *
     * <p>Indexed {@code address} params are left-padded to 32 bytes in the topic itself (not
     * ABI-encoded in the log body), so the value is simply the last 20 bytes of the topic hex
     * string. Topic position depends on the event's own signature — e.g. for
     * {@code TokenDeployed(bytes32 indexed assetId, uint8 indexed tokenType, address indexed
     * tokenAddress)}, topics are {@code [sig, assetId, tokenType, tokenAddress]}, so the address
     * is at index 3, not 2 — getting this wrong silently reads the wrong topic.
     *
     * @param receipt             transaction receipt to scan
     * @param eventTopicSignature {@code keccak256} of the event signature (topic0), 0x-prefixed
     * @param topicIndex          index into {@code log.getTopics()} where the address lives
     * @return the extracted address (0x-prefixed, lowercase), or empty if the event wasn't found
     */
    public static Optional<String> extractIndexedAddress(
            TransactionReceipt receipt, String eventTopicSignature, int topicIndex) {
        for (Log logEntry : receipt.getLogs()) {
            if (logEntry.getTopics() == null || logEntry.getTopics().isEmpty()) {
                continue;
            }
            if (!eventTopicSignature.equalsIgnoreCase(logEntry.getTopics().get(0))) {
                continue;
            }
            if (logEntry.getTopics().size() <= topicIndex) {
                continue;
            }
            String padded = logEntry.getTopics().get(topicIndex);
            return Optional.of("0x" + padded.substring(padded.length() - 40));
        }
        return Optional.empty();
    }

    /**
     * Normalizes an on-chain address for consistent register storage/lookup, so an
     * indexer-persisted (lowercase) address and a UI-entered (possibly checksummed) address
     * for the same account don't silently fail to match as two distinct {@code AssetHolder}
     * rows (see {@code HolderService}/{@code EndpointService}).
     *
     * <p>Only 0x-prefixed hex addresses (EVM, Starknet) are touched — those are
     * case-insensitive on-chain, and lowercasing is the convention the indexer already applies
     * (see {@code indexer.api.HolderDataService}'s {@code toLowerCase(Locale.ROOT)} balance
     * key). Solana (base58) and Stellar (base32) addresses are case-SENSITIVE by construction;
     * lowercasing those would corrupt them, so anything not starting with {@code 0x}/{@code 0X}
     * is returned unchanged (only trimmed).
     */
    public static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        return trimmed.regionMatches(true, 0, "0x", 0, 2)
                ? trimmed.toLowerCase(java.util.Locale.ROOT)
                : trimmed;
    }
}
