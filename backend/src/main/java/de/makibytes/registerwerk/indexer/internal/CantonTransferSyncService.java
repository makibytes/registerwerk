package de.makibytes.registerwerk.indexer.internal;

import com.daml.ledger.javaapi.data.*;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.CantonTokenService;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.indexer.api.IndexerState;
import de.makibytes.registerwerk.chain.api.CantonLedgerClient;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.indexer.api.IndexerStateRepository;
import de.makibytes.registerwerk.indexer.api.TokenTransferRepository;
import io.reactivex.disposables.Disposable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indexes Canton token transfers by streaming the Ledger API transaction feed.
 *
 * <p>Mirrors {@link SolanaTransferSyncService} in structure. At startup, opens one
 * {@code TransactionService.getTransactions} gRPC stream per enabled CANTON chain config.
 * Resumes from the last persisted offset stored in {@code indexer_state.last_synced_signature}.
 *
 * <p>The stream emits {@link Transaction} events. We filter for {@code Created} and
 * {@code Archived} events on the Daml Token Standard {@code Holding} template and convert
 * them into {@code token_transfer} rows:
 * <ul>
 *   <li>Holding <em>created</em> → credit (mint or inbound transfer); {@code from} = prior owner or "0x0"</li>
 *   <li>Holding <em>archived</em> → debit (burn or outbound transfer); {@code to} = "0x0"</li>
 * </ul>
 *
 * <p>For reconnect resilience, errors bump {@code consecutive_errors} and set
 * {@code status = ERROR} after {@link #MAX_CONSECUTIVE_ERRORS} failures.
 */
@Service
public class CantonTransferSyncService {

    private static final Logger log = LoggerFactory.getLogger(CantonTransferSyncService.class);

    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    /** Canton offset used to start from the beginning of the ledger. */
    private static final LedgerOffset LEDGER_BEGIN = LedgerOffset.LedgerBegin.getInstance();

    private final BlockchainClientRegistry registry;
    private final ChainConfigRepository chainConfigRepository;
    private final IndexerStateRepository indexerStateRepository;
    private final TokenTransferRepository tokenTransferRepository;

    /** chainConfigId → active stream subscription (for cleanup on shutdown). */
    private final Map<UUID, Disposable> activeSubscriptions = new ConcurrentHashMap<>();

    public CantonTransferSyncService(
            BlockchainClientRegistry registry,
            ChainConfigRepository chainConfigRepository,
            IndexerStateRepository indexerStateRepository,
            TokenTransferRepository tokenTransferRepository) {
        this.registry                 = registry;
        this.chainConfigRepository    = chainConfigRepository;
        this.indexerStateRepository   = indexerStateRepository;
        this.tokenTransferRepository  = tokenTransferRepository;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    @PostConstruct
    public void startStreamSubscriptions() {
        List<ChainConfig> cantonChains = chainConfigRepository.findByEnabledTrue()
                .stream()
                .filter(c -> c.getChainType() == ChainConfig.ChainType.CANTON)
                .toList();

        for (ChainConfig chain : cantonChains) {
            try {
                subscribeToChain(chain);
            } catch (Exception e) {
                log.error("Failed to start Canton stream for chain {}: {}",
                        chain.getIdentifier(), e.getMessage(), e);
            }
        }
        log.info("Canton indexer started: {} chain(s)", cantonChains.size());
    }

    @PreDestroy
    public void stopSubscriptions() {
        activeSubscriptions.values().forEach(Disposable::dispose);
        activeSubscriptions.clear();
        log.info("Canton indexer subscriptions stopped.");
    }

    // ── Per-chain subscription ────────────────────────────────────────────────

    private void subscribeToChain(ChainConfig chain) {
        CantonLedgerClient client = registry.getCantonClientByIdentifier(chain.getIdentifier());

        IndexerState state = getOrCreateIndexerState(chain);
        LedgerOffset beginOffset = resolveBeginOffset(state);

        // Filter for Daml Token Standard Holding template events
        TransactionFilter filter = new FiltersByParty(Map.of()); // empty = all parties visible to admin party

        Disposable sub = client.transactionsClient()
                .getTransactions(beginOffset, Optional.empty(), filter, true)
                .subscribe(
                        tx -> handleTransaction(chain, state, tx),
                        err -> handleStreamError(chain, state, err));

        activeSubscriptions.put(chain.getId(), sub);
        log.info("Canton stream subscription started for chain {}", chain.getIdentifier());
    }

    // ── Transaction processing ────────────────────────────────────────────────

    @Transactional
    protected void handleTransaction(ChainConfig chain, IndexerState state, Transaction tx) {
        for (Event event : tx.getEvents()) {
            if (event instanceof CreatedEvent created) {
                handleHoldingCreated(chain, tx, created);
            } else if (event instanceof ArchivedEvent archived) {
                handleHoldingArchived(chain, tx, archived);
            }
        }

        // Advance the cursor
        state.setLastSyncedSignature(tx.getOffset());
        state.setLastSyncedAt(Instant.now());
        state.setConsecutiveErrors(0);
        state.setStatus(IndexerState.IndexerStatus.ACTIVE);
        indexerStateRepository.save(state);
    }

    private void handleHoldingCreated(ChainConfig chain, Transaction tx, CreatedEvent created) {
        if (!isHoldingTemplate(created.getTemplateId())) return;

        String owner  = extractPartyField(created.getArguments(), "owner");
        BigDecimal amount = extractNumericField(created.getArguments(), "amount");
        String contractId = created.getContractId();
        String instrument = extractInstrumentField(created.getArguments());

        TokenTransfer tt = new TokenTransfer();
        tt.setChainConfigId(chain.getId());
        tt.setContractAddress(instrument);
        tt.setFromAddress("0x0");  // mint or inbound — sender unknown at Created event level
        tt.setToAddress(owner);
        tt.setAmount(amount);
        tt.setTxHash(tx.getUpdateId());

        tokenTransferRepository.save(tt);
        log.debug("Canton Holding created: chain={} owner={} amount={} cid={}",
                chain.getIdentifier(), owner, amount, contractId);
    }

    private void handleHoldingArchived(ChainConfig chain, Transaction tx, ArchivedEvent archived) {
        if (!isHoldingTemplate(archived.getTemplateId())) return;

        // Archive = the holding was consumed (transfer-out or burn).
        // We record a debit row; exact "from" party is stored in the original Created event.
        TokenTransfer tt = new TokenTransfer();
        tt.setChainConfigId(chain.getId());
        tt.setContractAddress(archived.getContractId());
        tt.setFromAddress(archived.getContractId()); // contract ID as proxy for the consumed holding
        tt.setToAddress("0x0");
        tt.setAmount(BigDecimal.ZERO); // amount not available at Archive level; reconcile from Created
        tt.setTxHash(tx.getUpdateId());

        tokenTransferRepository.save(tt);
        log.debug("Canton Holding archived: chain={} cid={}", chain.getIdentifier(), archived.getContractId());
    }

    // ── Error handling ────────────────────────────────────────────────────────

    private void handleStreamError(ChainConfig chain, IndexerState state, Throwable err) {
        log.error("Canton stream error for chain {}: {}", chain.getIdentifier(), err.getMessage(), err);

        int errors = state.getConsecutiveErrors() + 1;
        state.setConsecutiveErrors(errors);
        state.setLastError(err.getMessage());
        if (errors >= MAX_CONSECUTIVE_ERRORS) {
            state.setStatus(IndexerState.IndexerStatus.ERROR);
            log.error("Canton indexer for chain {} set to ERROR after {} consecutive failures",
                    chain.getIdentifier(), errors);
        }
        indexerStateRepository.save(state);

        // Attempt reconnect after a brief delay unless too many errors
        if (errors < MAX_CONSECUTIVE_ERRORS) {
            try {
                Thread.sleep(5_000);
                subscribeToChain(chain);
            } catch (Exception ex) {
                log.error("Failed to reconnect Canton stream for chain {}: {}",
                        chain.getIdentifier(), ex.getMessage());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IndexerState getOrCreateIndexerState(ChainConfig chain) {
        return indexerStateRepository
                .findByChainConfigIdAndIndexerType(chain.getId(), IndexerState.IndexerType.CANTON_STREAM)
                .orElseGet(() -> {
                    IndexerState s = new IndexerState();
                    s.setChainConfigId(chain.getId());
                    s.setIndexerType(IndexerState.IndexerType.CANTON_STREAM);
                    s.setStatus(IndexerState.IndexerStatus.ACTIVE);
                    return indexerStateRepository.save(s);
                });
    }

    private LedgerOffset resolveBeginOffset(IndexerState state) {
        String lastOffset = state.getLastSyncedSignature();
        if (lastOffset == null || lastOffset.isBlank()) return LEDGER_BEGIN;
        return new LedgerOffset.Absolute(lastOffset);
    }

    private boolean isHoldingTemplate(Identifier templateId) {
        return CantonTokenService.TOKEN_STANDARD_PACKAGE.equals(templateId.getPackageId())
                && "Lfdt.Tokenstandard.Holding".equals(templateId.getModuleName() + "." + templateId.getEntityName());
    }

    private String extractPartyField(DamlRecord args, String fieldName) {
        return args.getFields().stream()
                .filter(f -> fieldName.equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> ((Party) f.getValue()).getValue())
                .orElse("unknown");
    }

    private BigDecimal extractNumericField(DamlRecord args, String fieldName) {
        return args.getFields().stream()
                .filter(f -> fieldName.equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> ((Numeric) f.getValue()).getValue())
                .orElse(BigDecimal.ZERO);
    }

    private String extractInstrumentField(DamlRecord args) {
        return args.getFields().stream()
                .filter(f -> "instrument".equals(f.getLabel().orElse("")))
                .findFirst()
                .map(f -> f.getValue().toString())
                .orElse("unknown");
    }
}
