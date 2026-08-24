package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmission;
import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
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

    public DurableEvmSubmissionService(
            EvmSignedSubmissionRepository repository,
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmContractService evmContractService,
            BlockchainTransactionService txService) {
        this.repository = repository;
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.evmContractService = evmContractService;
        this.txService = txService;
    }

    /** Persists signed bytes and their deterministic hash before any broadcast is possible. */
    @Transactional
    @Override
    public PreparedSubmission prepare(UUID chainConfigId, String contractAddress,
            Function function, Map<String, Object> params) {
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
        repository.saveAndFlush(row);
        return new PreparedSubmission(row.getId(), row.getTxHash());
    }

    /**
     * Dispatches the persisted bytes in a fresh transaction. A rollback after RPC success only
     * causes the same bytes/hash/nonce to be retried; it cannot produce a second transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public void dispatch(UUID submissionId) {
        EvmSignedSubmission row = repository.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new EntityNotFoundException("EvmSignedSubmission", submissionId));
        if (row.getStatus() == EvmSignedSubmission.Status.BROADCAST) {
            return;
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
                    // this immutable expected hash proves acceptance; otherwise retain PREPARED.
                    if (!isKnown(web3j, row.getTxHash())) {
                        row.setLastError(abbreviate(ambiguousBroadcastFailure.getMessage()));
                        repository.save(row);
                        return;
                    }
                }
            }
            txService.recordPrepared(row.getTxHash(), row.getMethodName(), row.getChainConfigId(),
                    row.getChainName(), row.getNetwork(), row.getContractAddress(), row.getParams(),
                    row.getActorName(), row.getActorRole());
            row.setStatus(EvmSignedSubmission.Status.BROADCAST);
            row.setBroadcastAt(Instant.now());
            row.setLastError(null);
            repository.save(row);
        } catch (Exception e) {
            row.setLastError(abbreviate(e.getMessage()));
            repository.save(row);
            return;
        }
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findPreparedId(String txHash) {
        return repository.findByTxHash(txHash).map(EvmSignedSubmission::getId);
    }

    @Transactional(readOnly = true)
    public java.util.List<UUID> pendingIds() {
        return repository.findTop100ByStatusOrderByCreatedAtAsc(EvmSignedSubmission.Status.PREPARED)
                .stream().map(EvmSignedSubmission::getId).toList();
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
