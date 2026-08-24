package de.makibytes.registerwerk.blockchain.api;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;

import java.io.IOException;

/**
 * The single place every poller resolves "what finality level has this block/receipt reached on
 * this chain" — replaces four independently-duplicated inline {@code FinalityModel} switches
 * ({@code BlockchainTransactionService.pollPendingTransactions}, {@code
 * AssetDeploymentService.syncFromChain}, {@code OrgEcosystemTxPoller.resolveVerdict}, {@code
 * OnchainIdentityReceiptListener.resolveIdentity}) that had drifted into existence one poller at a
 * time despite {@link EvmUtils#finalityOf} already being written to be the single source of
 * truth. {@code GraphNodeSyncService} does not use this class — it already fetches head/safe/
 * finalized once per tick across many blocks and calls {@link EvmUtils#finalityOf} directly to
 * keep that batching; this resolver is for the pollers that check one receipt at a time.
 */
@Component
public class EvmFinalityResolver {

    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainTxProperties txProperties;

    public EvmFinalityResolver(ChainConfigRepository chainConfigRepository, BlockchainTxProperties txProperties) {
        this.chainConfigRepository = chainConfigRepository;
        this.txProperties = txProperties;
    }

    /** {@code chain_config.identifier} ({@code "<CHAIN>_<NETWORK>"}) → its configured
     *  {@link ChainConfig.FinalityModel}, defaulting to {@code DEPTH_BASED} (the pre-existing,
     *  depth-only behavior) when no matching row exists — e.g. a chain configured only via the
     *  legacy static-client tier and never registered in {@code chain_config} — rather than
     *  failing the caller outright. */
    public ChainConfig.FinalityModel resolveModel(String chainConfigIdentifier) {
        return chainConfigRepository.findByIdentifier(chainConfigIdentifier)
                .map(ChainConfig::getFinalityModel)
                .orElse(ChainConfig.FinalityModel.DEPTH_BASED);
    }

    /**
     * The finality level {@code blockNumber} has reached on {@code chainConfigIdentifier},
     * fetching over RPC exactly what that chain's configured model needs — an {@code INSTANT}
     * chain makes no call at all, a {@code TAG_BASED} chain fetches the {@code safe}/{@code
     * finalized} tags but never the head, a {@code DEPTH_BASED} chain fetches only the head.
     *
     * @throws IOException propagated from the underlying {@code eth_blockNumber} call
     *                      ({@code DEPTH_BASED} only) — callers already wrap poll iterations in
     *                      a per-item try/catch, matching every existing call site this replaces.
     */
    public FinalityLevel levelOf(String chainConfigIdentifier, Web3j web3j, long blockNumber) throws IOException {
        ChainConfig chain = chainConfigRepository.findByIdentifier(chainConfigIdentifier).orElse(null);
        return levelOf(chain, chainConfigIdentifier, web3j, blockNumber);
    }

    /** Uses an already-resolved canonical chain row, avoiding a second identifier lookup. */
    public FinalityLevel levelOf(ChainConfig chain, Web3j web3j, long blockNumber) throws IOException {
        if (chain == null || chain.getIdentifier() == null) {
            throw new IllegalArgumentException("Canonical chain configuration is required");
        }
        return levelOf(chain, chain.getIdentifier(), web3j, blockNumber);
    }

    private FinalityLevel levelOf(
            ChainConfig chain, String identifier, Web3j web3j, long blockNumber) throws IOException {
        ChainConfig.FinalityModel model = chain != null && chain.getFinalityModel() != null
                ? chain.getFinalityModel() : ChainConfig.FinalityModel.DEPTH_BASED;
        return switch (model) {
            case INSTANT -> FinalityLevel.FINALIZED;
            case TAG_BASED -> {
                Long finalizedBlockNumber = EvmUtils.finalizedBlockNumber(web3j).orElse(null);
                Long safeBlockNumber = EvmUtils.safeBlockNumber(web3j).orElse(null);
                yield EvmUtils.finalityOf(model, blockNumber, null, safeBlockNumber, finalizedBlockNumber, 0, 0);
            }
            case DEPTH_BASED -> {
                long headBlockNumber = web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
                String chainName = chainNameFrom(identifier);
                yield EvmUtils.finalityOf(model, blockNumber, headBlockNumber, null, null,
                        txProperties.confirmationsFor(chainName), txProperties.safeConfirmationsFor(chainName));
            }
        };
    }

    /** {@code <CHAIN>_<NETWORK>} → {@code CHAIN}, matching {@code Chain} enum names so
     *  {@link BlockchainTxProperties#confirmationsFor}/{@code #safeConfirmationsFor} stay the
     *  single source of confirmation-depth policy. Mirrors {@code
     *  indexer.internal.ReorgGuard#chainNameFrom} — duplicated rather than shared because
     *  {@code indexer.internal} is not visible outside its own module; both derive from the same
     *  {@code chain_config.identifier} convention and must be kept in sync if that convention
     *  ever changes. */
    private static String chainNameFrom(String chainConfigIdentifier) {
        if (chainConfigIdentifier == null) {
            return null;
        }
        int underscore = chainConfigIdentifier.indexOf('_');
        return underscore < 0 ? chainConfigIdentifier : chainConfigIdentifier.substring(0, underscore);
    }
}
