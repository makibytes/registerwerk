package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.idempotency.api.IdempotencyContext;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.EvmSigner;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Function;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Prepare/persist/dispatch service for exact-byte-idempotent EVM submissions. */
@Service
public class DurableEvmSubmissionService implements DurableEvmSubmissionPort {

    private final EvmSignedSubmissionRepository repository;
    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmContractService evmContractService;
    private final BlockchainTransactionService txService;
    private final OutboxProperties properties;

    public DurableEvmSubmissionService(
            EvmSignedSubmissionRepository repository,
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService,
            OutboxProperties properties) {
        this.repository = repository;
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
        this.properties = properties;
    }

    /** Persists signed bytes and their deterministic hash before any broadcast is possible. */
    @Transactional
    @Override
    public PreparedSubmission prepare(UUID chainConfigId, String contractAddress,
            Function function, Map<String, Object> params) {
        // P4B-7: the same HTTP request replayed with the same Idempotency-Key (after a timeout, a 5xx
        // whose cached response was released, or once the cached response expired) must map to the
        // SAME signed transaction: return the existing row instead of signing a second nonce.
        String idempotencyKey = IdempotencyContext.nextSubmissionKey();
        if (idempotencyKey != null) {
            Optional<EvmSignedSubmission> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new PreparedSubmission(existing.get().getId(), existing.get().getTxHash());
            }
        }
        ChainConfig chain = chainConfigRepository.findById(chainConfigId)
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", chainConfigId));
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        EvmSigner signer = evmContractService.signer(chainConfigId);
        EvmContractService.PreparedRawTransaction prepared = evmContractService.prepareDurable(
                chainConfigId, web3j, signer, contractAddress, function);

        EvmSignedSubmission row = new EvmSignedSubmission();
        row.setChainConfigId(chainConfigId);
        row.setChainId(BigInteger.valueOf(prepared.chainId()));
        row.setSenderAddress(prepared.senderAddress().toLowerCase(java.util.Locale.ROOT));
        row.setNonce(prepared.nonce());
        row.setTxHash(prepared.txHash());
        row.setSignedPayload(prepared.signedPayload());
        row.setChainName(parseChain(chain.getIdentifier()));
        row.setNetwork(chain.getNetworkType().name());
        row.setContractAddress(contractAddress);
        row.setMethodName(function.getName());
        row.setParams(params);
        row.setActorName(resolveActorName());
        row.setActorRole(resolveActorRole());
        row.setIdempotencyKey(idempotencyKey);
        repository.saveAndFlush(row);
        // P4B-4: a PREPARED row is visible in blockchain_transaction from the moment its bytes are
        // durable (same DB transaction). Before, only broadcast created that row, so a payload that
        // could never be broadcast had no timeout, no alert and no console entry at all.
        txService.recordPrepared(row.getTxHash(), row.getMethodName(), row.getChainConfigId(),
                row.getChainName(), row.getNetwork(), row.getContractAddress(), row.getParams(),
                row.getActorName(), row.getActorRole());
        txService.tagIdempotencyKey(row.getTxHash(), idempotencyKey);
        return new PreparedSubmission(row.getId(), row.getTxHash());
    }

    /** Result of one dispatch attempt; the dispatcher escalates {@link #FAILED}. */
    public enum DispatchOutcome { BROADCAST, FAILED, SKIPPED }

    /** A dispatch candidate: the row id plus the signer it belongs to. */
    public record Candidate(UUID id, String signer) {}

    /**
     * Dispatches the persisted bytes in a fresh transaction. A rollback after RPC success only
     * causes the same bytes/hash/nonce to be retried; it cannot produce a second transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void dispatch(UUID submissionId) {
        dispatchWithOutcome(submissionId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispatchOutcome dispatchWithOutcome(UUID submissionId) {
        EvmSignedSubmission row = repository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("EvmSignedSubmission", submissionId));
        if (row.getStatus() != EvmSignedSubmission.Status.PREPARED) {
            return DispatchOutcome.SKIPPED; // already BROADCAST, or superseded / abandoned meanwhile
        }

        ChainConfig chain = chainConfigRepository.findById(row.getChainConfigId())
                .orElseThrow(() -> new EntityNotFoundException("ChainConfig", row.getChainConfigId()));
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chain.getIdentifier());
        row.setAttemptCount(row.getAttemptCount() + 1);
        boolean known;
        try {
            known = isKnown(web3j, row.getTxHash());
            if (!known) {
                try {
                    evmContractService.broadcastPrepared(
                            row.getChainConfigId(), web3j, row.getSignedPayload(), row.getTxHash());
                } catch (RuntimeException ambiguousBroadcastFailure) {
                    // A provider can accept the bytes and still lose the response, or answer
                    // "already known"/"nonce too low" on an exact replay. Only visibility of
                    // this immutable expected hash proves acceptance; otherwise retain PREPARED,
                    // classified so the outbox recovery knows whether a higher fee could help.
                    if (!isKnown(web3j, row.getTxHash())) {
                        recordFailure(row, ambiguousBroadcastFailure);
                        repository.save(row);
                        return DispatchOutcome.FAILED;
                    }
                }
            }
            // Idempotent: prepare() already created the row; this covers pre-V22 PREPARED rows.
            txService.recordPrepared(row.getTxHash(), row.getMethodName(), row.getChainConfigId(),
                    row.getChainName(), row.getNetwork(), row.getContractAddress(), row.getParams(),
                    row.getActorName(), row.getActorRole());
            row.setStatus(EvmSignedSubmission.Status.BROADCAST);
            row.setBroadcastAt(Instant.now());
            row.setLastError(null);
            row.setLastErrorClass(null);
            row.setFirstFailedAt(null);
            row.setNextAttemptAt(null);
            repository.save(row);
            return DispatchOutcome.BROADCAST;
        } catch (Exception e) {
            recordFailure(row, e);
            repository.save(row);
            return DispatchOutcome.FAILED;
        }
    }

    /** Stores the failure, its class, and the exponential back-off before the next attempt. */
    private void recordFailure(EvmSignedSubmission row, Exception failure) {
        Instant now = Instant.now();
        row.setLastError(abbreviate(failure.getMessage()));
        row.setLastErrorClass(BroadcastErrorClassifier.classify(failure.getMessage()));
        if (row.getFirstFailedAt() == null) {
            row.setFirstFailedAt(now);
        }
        row.setNextAttemptAt(now.plus(properties.backoff(row.getAttemptCount())));
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findPreparedId(String txHash) {
        return repository.findByTxHash(txHash).map(EvmSignedSubmission::getId);
    }

    /**
     * Fair dispatch page (P4B-4): per signer the lowest eligible nonces (a row in back-off is
     * skipped, not waited for), interleaved round-robin across signers and capped per signer, so a
     * signer whose rows are poisoned can neither monopolise the page nor starve other signers/chains.
     */
    @Transactional(readOnly = true)
    public java.util.List<Candidate> dispatchCandidates() {
        return repository.findDispatchCandidates(Instant.now(), properties.getPerSignerBatch(),
                properties.getBatchLimit()).stream()
                .map(c -> new Candidate(c.getId(), c.getChainId().toPlainString() + ":" + c.getSenderAddress()))
                .toList();
    }

    private boolean isKnown(Web3j web3j, String txHash) throws Exception {
        if (web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt().isPresent()) {
            return true;
        }
        return web3j.ethGetTransactionByHash(txHash).send().getTransaction().isPresent();
    }

    private static String parseChain(String identifier) {
        int splitIndex = identifier.lastIndexOf('_');
        return splitIndex > 0 ? identifier.substring(0, splitIndex) : identifier;
    }

    private static String resolveActorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "system";
    }

    private static String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority() : "SYSTEM";
    }

    private static String abbreviate(String message) {
        if (message == null) return "Unknown submission failure";
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }
}
