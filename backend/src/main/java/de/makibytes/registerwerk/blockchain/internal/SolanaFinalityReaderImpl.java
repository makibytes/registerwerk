package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.SolanaFinalityReader;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.p2p.solanaj.rpc.types.Block;
import org.p2p.solanaj.rpc.types.ConfirmedTransaction;
import org.p2p.solanaj.rpc.types.SignatureStatuses;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Solana RPC implementation that accepts only the protocol's {@code finalized} commitment. */
@Service
class SolanaFinalityReaderImpl implements SolanaFinalityReader {

    private final ChainConfigRepository chains;
    private final BlockchainClientRegistry clients;

    SolanaFinalityReaderImpl(ChainConfigRepository chains, BlockchainClientRegistry clients) {
        this.chains = chains;
        this.clients = clients;
    }

    @Override
    public Result read(UUID chainConfigId, String signature) {
        ChainConfig chain = chains.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        if (chain.getChainType() != ChainConfig.ChainType.SOLANA) {
            throw new IllegalArgumentException("ChainConfig " + chainConfigId + " is not Solana");
        }
        RpcClient client = clients.getSolanaClientByIdentifier(chain.getIdentifier());
        try {
            SignatureStatuses statuses = client.getApi()
                    .getSignatureStatuses(List.of(signature), true);
            if (statuses == null || statuses.getValue() == null
                    || statuses.getValue().isEmpty() || statuses.getValue().getFirst() == null) {
                return Result.pending();
            }
            SignatureStatuses.Value status = statuses.getValue().getFirst();
            if (!"finalized".equalsIgnoreCase(status.getConfirmationStatus())) {
                return Result.pending();
            }

            long slot = status.getSlot();
            ConfirmedTransaction transaction = client.getApi()
                    .getTransaction(signature, Commitment.FINALIZED, 0);
            if (transaction == null || transaction.getMeta() == null) {
                return Result.pending();
            }
            if (transaction.getMeta().getErr() != null) {
                return Result.failed(slot, transaction.getMeta().getErr().toString());
            }

            // A signature status alone identifies a slot, not its exact incarnation. Retain the
            // finalized block hash so the shared effect journal can prove ownership if a finality
            // violation is ever reported.
            // Solanaj 1.28 exposes getBlock(int, ...), even though Solana slots are uint64. Call
            // the same RPC directly with a Long to avoid an eventual year-scale integer overflow.
            Block block = client.call("getBlock", List.of(slot, Map.of(
                    "commitment", Commitment.FINALIZED.getValue(),
                    "encoding", "json",
                    "transactionDetails", "none",
                    "rewards", false,
                    "maxSupportedTransactionVersion", 0)), Block.class);
            if (block == null || block.getBlockHash() == null || block.getBlockHash().isBlank()) {
                return Result.pending();
            }
            return Result.finalized(slot, block.getBlockHash());
        } catch (RpcException e) {
            throw new IllegalStateException(
                    "Unable to read finalized Solana transaction " + signature, e);
        }
    }
}
