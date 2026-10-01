package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityTask;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockExpiryReviewEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages §16 eWpG Sperrvermerk (legal block) entries at the registry layer.
 * All create/lift operations require 4-eyes approval (via @RequiresStepUp on the controller).
 *
 * <p>6-25 / T6-11: a block past its {@code expires_at} is auto-lifted only when its type is listed in
 * {@code registerwerk.sperrvermerk.auto-expire-types} (default empty = none). Every other block moves to
 * EXPIRY_REVIEW, keeps blocking, and raises an operator task and a compliance notification; it is lifted
 * through the normal step-up + second-approver lift. Expiry dates must lie in the future and, for
 * court/authority order types, are confirmed by the second approver against a reference.
 */
@Service
@Transactional
public class SperrvermerkService {

    private static final Logger log = LoggerFactory.getLogger(SperrvermerkService.class);

    private final HolderBlockRepository repository;
    private final ApplicationEventPublisher events;
    private final AssetHolderRepository holders;
    private final EntityTaskPort tasks;
    private final Set<HolderBlock.BlockType> autoExpireTypes;

    SperrvermerkService(HolderBlockRepository repository, ApplicationEventPublisher events,
                        AssetHolderRepository holders, EntityTaskPort tasks,
                        @Value("${registerwerk.sperrvermerk.auto-expire-types:}") String autoExpireTypes) {
        this.repository = repository;
        this.events = events;
        this.holders = holders;
        this.tasks = tasks;
        this.autoExpireTypes = parseTypes(autoExpireTypes);
    }

    private static Set<HolderBlock.BlockType> parseTypes(String csv) {
        Set<HolderBlock.BlockType> out = EnumSet.noneOf(HolderBlock.BlockType.class);
        if (csv != null) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(t -> !t.isEmpty())
                    .forEach(t -> out.add(HolderBlock.BlockType.valueOf(t.toUpperCase(java.util.Locale.ROOT))));
        }
        return out;
    }

    public HolderBlock create(HolderBlock block, UUID createdBy, String actorRole, UUID dualControlApproverId) {
        // Canonical form regardless of caller (T3-15): an un-normalised checksum address never
        // matched asset_holder, so no deployment was frozen and the gates failed open.
        block.setWalletAddress(AddressNormalizer.normalize(block.getWalletAddress()));
        validateExpiry(block, dualControlApproverId);
        resolveEntity(block);
        block.setCreatedBy(createdBy);
        block.setStatus(HolderBlock.Status.ACTIVE);
        if (dualControlApproverId != null) {
            block.setDualControlApproverId(dualControlApproverId);
            block.setDualControlApprovedAt(Instant.now());
        }
        HolderBlock saved = repository.save(block);
        Set<String> wallets = walletsOf(saved);
        log.warn("Sperrvermerk created: id={} type={} wallet={} legalBasis={}",
                saved.getId(), saved.getBlockType(), saved.getWalletAddress(), saved.getLegalBasis());
        events.publishEvent(new HolderBlockCreatedEvent(saved.getId(), createdBy, actorRole, dualControlApproverId, Map.of(
                "blockType", saved.getBlockType().name(),
                "walletAddress", saved.getWalletAddress(),
                // Entity-scoped blocks freeze every wallet the entity holds units on (6-25).
                "walletAddresses", new ArrayList<>(wallets),
                "entityId", saved.getEntityId() != null ? saved.getEntityId().toString() : "",
                "expiresAt", saved.getExpiresAt() != null ? saved.getExpiresAt().toString() : "",
                "expiryConfirmedByApprover", saved.isExpiryConfirmedByApprover(),
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
        return repository.findByWalletAddressAndStatusIn(AddressNormalizer.normalize(walletAddress), HolderBlock.BLOCKING);
    }

    @Transactional(readOnly = true)
    public List<HolderBlock> findByEntityId(UUID entityId) {
        return repository.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING);
    }

    public HolderBlock lift(UUID blockId, UUID liftedBy, String actorRole, String liftReason, UUID dualControlApproverId) {
        HolderBlock block = repository.findById(blockId)
                .orElseThrow(() -> new EntityNotFoundException("HolderBlock", blockId));
        if (!HolderBlock.BLOCKING.contains(block.getStatus())) {
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
                "walletAddresses", new ArrayList<>(walletsOf(saved)),
                "assetId", saved.getAssetId() != null ? saved.getAssetId().toString() : ""
        )));
        return saved;
    }

    /**
     * Daily job: blocks past their expires_at are auto-lifted only for types configured in
     * {@code registerwerk.sperrvermerk.auto-expire-types}; all others go to EXPIRY_REVIEW and stay blocking.
     */
    @SchedulerLock(name = "sperrvermerkAutoLift", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 3 * * *")
    public void autoExpire() {
        List<HolderBlock> expired = repository.findExpiredActive(Instant.now());
        int lifted = 0;
        for (HolderBlock block : expired) {
            if (autoExpireTypes.contains(block.getBlockType())) {
                block.setStatus(HolderBlock.Status.EXPIRED);
                block.setLiftedAt(Instant.now());
                block.setLiftReason("AUTO_EXPIRED");
                repository.save(block);
                lifted++;
                log.info("Sperrvermerk auto-expired: id={} wallet={}", block.getId(), block.getWalletAddress());
                events.publishEvent(new HolderBlockLiftedEvent(block.getId(), null, "SYSTEM", null, Map.of(
                        "reason", "AUTO_EXPIRED",
                        "walletAddress", block.getWalletAddress(),
                        "walletAddresses", new ArrayList<>(walletsOf(block)),
                        "assetId", block.getAssetId() != null ? block.getAssetId().toString() : ""
                )));
            } else {
                moveToExpiryReview(block);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Sperrvermerk expiry: {} auto-expired, {} moved to EXPIRY_REVIEW.", lifted, expired.size() - lifted);
        }
    }

    private void moveToExpiryReview(HolderBlock block) {
        block.setStatus(HolderBlock.Status.EXPIRY_REVIEW);
        block.setExpiryReviewAt(Instant.now());
        repository.save(block);
        log.warn("Sperrvermerk past expiry, kept blocking pending review: id={} type={} wallet={}",
                block.getId(), block.getBlockType(), block.getWalletAddress());
        Map<String, Object> details = new HashMap<>();
        details.put("blockType", block.getBlockType().name());
        details.put("walletAddress", block.getWalletAddress());
        details.put("expiresAt", block.getExpiresAt() != null ? block.getExpiresAt().toString() : "");
        details.put("assetId", block.getAssetId() != null ? block.getAssetId().toString() : "");
        events.publishEvent(new HolderBlockExpiryReviewEvent(block.getId(), details));
        if (block.getEntityId() != null) {
            tasks.open(block.getEntityId(), EntityTask.SPERRVERMERK_EXPIRY_REVIEW, block.getId().toString(),
                    "Sperrvermerk " + block.getId() + " (" + block.getBlockType() + ") passed its expiry date; "
                            + "confirm against the order and lift or extend it.", null);
        }
    }

    /** A past/typo'd expiry used to lift the block at the next 03:00; legal-order types need a reference and the approver's confirmation. */
    private static void validateExpiry(HolderBlock block, UUID approverId) {
        if (block.getExpiresAt() == null) {
            return;
        }
        if (!block.getExpiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        if (HolderBlock.LEGAL_ORDER_TYPES.contains(block.getBlockType())) {
            boolean hasRef = (block.getCourtRef() != null && !block.getCourtRef().isBlank()) || block.getDocumentId() != null;
            if (!hasRef) {
                throw new IllegalArgumentException(
                        "A " + block.getBlockType() + " block with an expiry date needs courtRef or documentId");
            }
            if (approverId == null) {
                throw new IllegalArgumentException(
                        "The expiry of a " + block.getBlockType() + " block must be confirmed by a second approver");
            }
            block.setExpiryConfirmedByApprover(true);
        }
    }

    /** A wallet-only block takes the entity of the wallet's holder row when exactly one entity holds it. */
    private void resolveEntity(HolderBlock block) {
        if (block.getEntityId() != null || block.getWalletAddress() == null) {
            return;
        }
        Set<UUID> owners = new LinkedHashSet<>();
        for (AssetHolder h : holders.findByWalletAddressIn(List.of(block.getWalletAddress()))) {
            if (h.getInvestorId() != null && h.getRemovedAt() == null) {
                owners.add(h.getInvestorId());
            }
        }
        if (owners.size() == 1) {
            block.setEntityId(owners.iterator().next());
        } else if (owners.size() > 1) {
            log.warn("Sperrvermerk wallet {} is held by {} entities; entityId left empty (wallet-only block).",
                    block.getWalletAddress(), owners.size());
        }
    }

    /** The block's own wallet plus, for an entity-scoped block, every wallet the entity holds units on. */
    private Set<String> walletsOf(HolderBlock block) {
        Set<String> wallets = new LinkedHashSet<>();
        if (block.getWalletAddress() != null) {
            wallets.add(block.getWalletAddress());
        }
        if (block.getEntityId() != null) {
            for (AssetHolder h : holders.findActiveByInvestorId(block.getEntityId())) {
                String w = AddressNormalizer.normalize(h.getWalletAddress());
                if (w != null && !w.isBlank()) {
                    wallets.add(w);
                }
            }
        }
        return wallets;
    }
}
