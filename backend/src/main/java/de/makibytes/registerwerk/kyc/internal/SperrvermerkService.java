package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages §16 eWpG Sperrvermerk (legal block) entries at the registry layer.
 * All create/lift operations require 4-eyes approval (via @RequiresStepUp on the controller).
 * Auto-lifts blocks past their expires_at timestamp daily.
 */
@Service
@Transactional
public class SperrvermerkService {

    private static final Logger log = LoggerFactory.getLogger(SperrvermerkService.class);

    private final HolderBlockRepository repository;
    private final ApplicationEventPublisher events;

    SperrvermerkService(HolderBlockRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    public HolderBlock create(HolderBlock block, UUID createdBy, String actorRole, UUID dualControlApproverId) {
        block.setCreatedBy(createdBy);
        block.setStatus(HolderBlock.Status.ACTIVE);
        if (dualControlApproverId != null) {
            block.setDualControlApproverId(dualControlApproverId);
            block.setDualControlApprovedAt(Instant.now());
        }
        HolderBlock saved = repository.save(block);
        log.warn("Sperrvermerk created: id={} type={} wallet={} legalBasis={}",
                saved.getId(), saved.getBlockType(), saved.getWalletAddress(), saved.getLegalBasis());
        events.publishEvent(new HolderBlockCreatedEvent(saved.getId(), createdBy, actorRole, dualControlApproverId, Map.of(
                "blockType", saved.getBlockType().name(),
                "walletAddress", saved.getWalletAddress(),
                "legalBasis", saved.getLegalBasis(),
                // Scopes SperrvermerkOnchainSyncListener's on-chain freeze to just this asset's
                // deployments when set; a wallet-wide block (assetId null) freezes every asset
                // the wallet holds instead.
                "assetId", saved.getAssetId() != null ? saved.getAssetId().toString() : ""
        )));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<HolderBlock> findActiveByWallet(String walletAddress) {
        return repository.findByWalletAddressAndStatus(walletAddress, HolderBlock.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<HolderBlock> findByEntityId(UUID entityId) {
        return repository.findByEntityIdAndStatus(entityId, HolderBlock.Status.ACTIVE);
    }

    public HolderBlock lift(UUID blockId, UUID liftedBy, String actorRole, String liftReason, UUID dualControlApproverId) {
        HolderBlock block = repository.findById(blockId)
                .orElseThrow(() -> new EntityNotFoundException("HolderBlock", blockId));
        if (block.getStatus() != HolderBlock.Status.ACTIVE) {
            throw new IllegalStateException("Cannot lift block " + blockId + " — status is " + block.getStatus());
        }
        block.setStatus(HolderBlock.Status.LIFTED);
        block.setLiftedAt(Instant.now());
        block.setLiftedBy(liftedBy);
        block.setLiftReason(liftReason);
        block.setDualControlApproverId(dualControlApproverId);
        block.setDualControlApprovedAt(Instant.now());
        HolderBlock saved = repository.save(block);
        log.warn("Sperrvermerk lifted: id={} by={} reason={}", blockId, liftedBy, liftReason);
        events.publishEvent(new HolderBlockLiftedEvent(saved.getId(), liftedBy, actorRole, dualControlApproverId, Map.of(
                "reason", liftReason != null ? liftReason : "",
                "walletAddress", saved.getWalletAddress(),
                "assetId", saved.getAssetId() != null ? saved.getAssetId().toString() : ""
        )));
        return saved;
    }

    /** Daily job auto-lifts blocks past their expires_at (eWpG §16 automatic expiry). */
    @SchedulerLock(name = "sperrvermerkAutoLift", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 3 * * *")
    public void autoExpire() {
        List<HolderBlock> expired = repository.findExpiredActive(Instant.now());
        for (HolderBlock block : expired) {
            block.setStatus(HolderBlock.Status.EXPIRED);
            block.setLiftedAt(Instant.now());
            block.setLiftReason("AUTO_EXPIRED");
            repository.save(block);
            log.info("Sperrvermerk auto-expired: id={} wallet={}", block.getId(), block.getWalletAddress());
            events.publishEvent(new HolderBlockLiftedEvent(block.getId(), null, "SYSTEM", null, Map.of(
                    "reason", "AUTO_EXPIRED",
                    "walletAddress", block.getWalletAddress(),
                    "assetId", block.getAssetId() != null ? block.getAssetId().toString() : ""
            )));
        }
        if (!expired.isEmpty()) {
            log.info("Auto-expired {} Sperrvermerk entries.", expired.size());
        }
    }
}
