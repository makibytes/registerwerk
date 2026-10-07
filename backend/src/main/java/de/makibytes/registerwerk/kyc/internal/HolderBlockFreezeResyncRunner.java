package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.events.HolderBlockFreezeResyncRequestedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot follow-up to {@code V10__holder_block_wallet_normalisation.sql} (T3-15). Blocks created
 * with a checksum-cased wallet never froze anything on-chain: the listener's register lookup
 * matched no row. V10 normalised them and queued the ACTIVE ones in
 * {@code holder_block_freeze_resync}; this runner re-emits their freeze propagation once.
 *
 * <p>Rows are claimed with a single {@code UPDATE … RETURNING}, so with several instances
 * starting at once each block is re-emitted by exactly one of them. The freeze itself runs in
 * {@code erc3643.internal.SperrvermerkOnchainSyncListener} after this transaction commits.
 */
@Component
class HolderBlockFreezeResyncRunner {

    private static final Logger log = LoggerFactory.getLogger(HolderBlockFreezeResyncRunner.class);

    @PersistenceContext
    private EntityManager em;

    private final HolderBlockRepository repository;
    private final ApplicationEventPublisher events;

    HolderBlockFreezeResyncRunner(HolderBlockRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        resyncPending();
    }

    /** @return the number of blocks whose freeze propagation was re-emitted */
    @Transactional
    public int resyncPending() {
        @SuppressWarnings("unchecked")
        List<Object> claimed = em.createNativeQuery(
                "UPDATE holder_block_freeze_resync SET processed_at = now() "
                        + "WHERE processed_at IS NULL RETURNING holder_block_id")
                .getResultList();
        int emitted = 0;
        for (Object raw : claimed) {
            UUID blockId = raw instanceof UUID u ? u : UUID.fromString(raw.toString());
            HolderBlock block = repository.findById(blockId).orElse(null);
            // EXPIRY_REVIEW is still a legal block (H5): it keeps blocking until a human lifts it, so its
            // wallet must be frozen on-chain like an ACTIVE one.
            if (block == null || !HolderBlock.BLOCKING.contains(block.getStatus())) {
                continue;
            }
            events.publishEvent(new HolderBlockFreezeResyncRequestedEvent(block.getId(), Map.of(
                    "walletAddress", block.getWalletAddress(),
                    "legalBasis", block.getLegalBasis(),
                    "assetId", block.getAssetId() != null ? block.getAssetId().toString() : "")));
            emitted++;
        }
        if (emitted > 0) {
            log.warn("Re-emitted on-chain freeze for {} Sperrvermerk block(s) normalised by V10.", emitted);
        }
        return emitted;
    }
}
