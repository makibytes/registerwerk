package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.finality.api.FinalityLevel;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

/**
 * Shared EVM encoding utilities.
 */
public final class EvmUtils {

    private EvmUtils() {}

    /**
     * Number of blocks mined on top of (and including) the receipt's own block — the standard
     * "confirmation depth" a reorg-safety policy gates on. Shared by every EVM confirmation path
     * ({@code BlockchainTransactionService.pollPendingTransactions},
     * {@code AssetDeploymentService.syncFromChain}) so the depth calculation itself never drifts
     * between the tx-submission and deployment-confirmation call sites.
     */
    public static long confirmations(Web3j web3j, TransactionReceipt receipt) throws IOException {
        if (receipt.getBlockNumber() == null) {
            return 0;
        }
        BigInteger current = web3j.ethBlockNumber().send().getBlockNumber();
        return depthOf(current.longValueExact(), receipt.getBlockNumber().longValueExact());
    }

    /** Blocks mined on top of (and including) {@code blockNumber}, given the chain's current
     *  head — never negative. Shared arithmetic behind {@link #confirmations} and
     *  {@link #isFinal}'s {@code DEPTH_BASED} branch, so the two never drift. */
    private static long depthOf(long headBlockNumber, long blockNumber) {
        long depth = headBlockNumber - blockNumber + 1;
        return Math.max(depth, 0);
    }

    /**
     * The chain's current {@code finalized} block number, per {@link ChainConfig.FinalityModel#TAG_BASED}.
     * Empty if the node doesn't support the tag (permissioned chains, some L2s, anvil) or the
     * call errors — callers must treat that as "not yet known to be final", never as final,
     * since misreading an unsupported/erroring tag as finality would be worse than an extra poll
     * cycle of waiting.
     */
    public static Optional<Long> finalizedBlockNumber(Web3j web3j) {
        try {
            EthBlock response = web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send();
            if (response.hasError() || response.getBlock() == null) {
                return Optional.empty();
            }
            return Optional.of(response.getBlock().getNumber().longValueExact());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The chain's current {@code safe} block number, per {@link ChainConfig.FinalityModel#TAG_BASED}.
     * Empty if the node doesn't support the tag (permissioned chains, some L2s, anvil) or the
     * call errors — callers must treat that as "not yet known to be safe", never as safe, exactly
     * mirroring {@link #finalizedBlockNumber}'s contract.
     */
    public static Optional<Long> safeBlockNumber(Web3j web3j) {
        try {
            EthBlock response = web3j.ethGetBlockByNumber(DefaultBlockParameterName.SAFE, false).send();
            if (response.hasError() || response.getBlock() == null) {
                return Optional.empty();
            }
            return Optional.of(response.getBlock().getNumber().longValueExact());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The finality level {@code blockNumber} has reached under the chain's configured
     * {@link ChainConfig.FinalityModel} — the single decision every EVM confirmation gate in
     * this registry ({@code BlockchainTransactionService.pollPendingTransactions},
     * {@code AssetDeploymentService.syncFromChain}, {@code GraphNodeSyncService},
     * {@code OrgEcosystemTxPoller}, {@code OnchainIdentityReceiptListener}) evaluates against —
     * via {@link de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver}, not by calling
     * this method with inline duplicated switches — so "what level is this chain at" cannot drift
     * between call sites.
     *
     * <p>Pure: callers fetch only what their model needs (a {@code TAG_BASED} check needs
     * {@code safeBlockNumber}/{@code finalizedBlockNumber} but not {@code headBlockNumber}, an
     * {@code INSTANT} check needs neither) via {@link #safeBlockNumber} / {@link #finalizedBlockNumber}
     * / a live {@code eth_blockNumber} call, and pass the results in — this method makes no RPC
     * calls itself.
     *
     * <p>Chains that don't support the {@code safe}/{@code finalized} tags see no behaviour
     * change from before this method existed: a null {@code safeBlockNumber}/{@code
     * finalizedBlockNumber} under {@code TAG_BASED} degrades straight to {@code PROVISIONAL},
     * and a {@code DEPTH_BASED} chain whose {@code safeConfirmations} equals its {@code
     * requiredConfirmations} collapses {@code SAFE} into {@code FINALIZED}.
     *
     * @param headBlockNumber       current chain head; required only for {@code DEPTH_BASED},
     *                              null otherwise
     * @param safeBlockNumber       the node's {@code safe} tag; required only for
     *                              {@code TAG_BASED} (see {@link #safeBlockNumber}), null
     *                              otherwise — a null here for a {@code TAG_BASED} chain is
     *                              treated as "not yet safe", not an error
     * @param finalizedBlockNumber  the node's {@code finalized} tag; required only for
     *                              {@code TAG_BASED} (see {@link #finalizedBlockNumber}), null
     *                              otherwise — a null here for a {@code TAG_BASED} chain is
     *                              treated as "not yet final", not an error
     * @param requiredConfirmations FINALIZED-level confirmation depth for {@code DEPTH_BASED},
     *                              e.g. {@code BlockchainTxProperties#confirmationsFor}
     * @param safeConfirmations     SAFE-level confirmation depth for {@code DEPTH_BASED}, e.g.
     *                              {@code BlockchainTxProperties#safeConfirmationsFor}
     */
    public static FinalityLevel finalityOf(ChainConfig.FinalityModel model, long blockNumber,
            Long headBlockNumber, Long safeBlockNumber, Long finalizedBlockNumber,
            int requiredConfirmations, int safeConfirmations) {
        return switch (model) {
            case INSTANT -> FinalityLevel.FINALIZED;
            case TAG_BASED -> {
                if (finalizedBlockNumber != null && finalizedBlockNumber >= blockNumber) {
                    yield FinalityLevel.FINALIZED;
                }
                if (safeBlockNumber != null && safeBlockNumber >= blockNumber) {
                    yield FinalityLevel.SAFE;
                }
                yield FinalityLevel.PROVISIONAL;
            }
            case DEPTH_BASED -> {
                if (headBlockNumber == null) {
                    yield FinalityLevel.PROVISIONAL;
                }
                long depth = depthOf(headBlockNumber, blockNumber);
                if (depth >= requiredConfirmations) {
                    yield FinalityLevel.FINALIZED;
                }
                if (depth >= safeConfirmations) {
                    yield FinalityLevel.SAFE;
                }
                yield FinalityLevel.PROVISIONAL;
            }
        };
    }

    /**
     * @deprecated superseded by {@link #finalityOf}, which distinguishes SAFE from FINALIZED
     * instead of collapsing both into a boolean. Kept only for any caller not yet migrated;
     * behaviourally identical to {@code finalityOf(...) == FinalityLevel.FINALIZED}.
     */
    @Deprecated(forRemoval = true)
    public static boolean isFinal(ChainConfig.FinalityModel model, long blockNumber,
            Long headBlockNumber, Long finalizedBlockNumber, int requiredConfirmations) {
        return finalityOf(model, blockNumber, headBlockNumber, null, finalizedBlockNumber,
                requiredConfirmations, requiredConfirmations) == FinalityLevel.FINALIZED;
    }

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
