package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.BlockFinalityPort;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.api.ReorgObservation;
import de.makibytes.registerwerk.finality.api.QuarantineTrigger;
import de.makibytes.registerwerk.finality.api.ReorgEnvelopeConflictException;
import de.makibytes.registerwerk.finality.events.BlockFinalityChangedEvent;
import de.makibytes.registerwerk.finality.events.BlockRetractedEvent;
import de.makibytes.registerwerk.finality.events.ChainQuarantinedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class BlockFinalityServiceImpl implements BlockFinalityFeed, BlockFinalityPort {

    private static final Logger log = LoggerFactory.getLogger(BlockFinalityServiceImpl.class);
    private final BlockFinalityRepository repository;
    private final ChainEffectRepository chainEffectRepository;
    private final CompensationDispatcher compensationDispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final ReorgEpisodeStore reorgEpisodeStore;
    private final ChainQuarantineStore chainQuarantineStore;

    BlockFinalityServiceImpl(BlockFinalityRepository repository, ChainEffectRepository chainEffectRepository,
            CompensationDispatcher compensationDispatcher, ApplicationEventPublisher eventPublisher,
            ReorgEpisodeStore reorgEpisodeStore, ChainQuarantineStore chainQuarantineStore) {
        this.repository = repository;
        this.chainEffectRepository = chainEffectRepository;
        this.compensationDispatcher = compensationDispatcher;
        this.eventPublisher = eventPublisher;
        this.reorgEpisodeStore = reorgEpisodeStore;
        this.chainQuarantineStore = chainQuarantineStore;
    }

    @Override
    @Transactional(noRollbackFor = ReorgEnvelopeConflictException.class)
    public boolean isReorgRecorded(UUID chainConfigId, ReorgObservation observation) {
        ReorgEpisodeStore.ReplayStatus status = reorgEpisodeStore.replayStatus(chainConfigId, observation);
        if (status != ReorgEpisodeStore.ReplayStatus.CONFLICT) {
            return status == ReorgEpisodeStore.ReplayStatus.EXACT;
        }
        chainQuarantineStore.lockChain(chainConfigId);
        String detail = "Durable producer reused reorgId for a different immutable envelope";
        chainQuarantineStore.activate(
                chainConfigId, observation, QuarantineTrigger.REORG_ID_COLLISION, detail);
        eventPublisher.publishEvent(new ChainQuarantinedEvent(
                chainConfigId, observation.reorgId(), observation.severity(),
                QuarantineTrigger.REORG_ID_COLLISION, detail, Instant.now()));
        throw new ReorgEnvelopeConflictException(detail + ": " + observation.reorgId());
    }

    @Override
    @Transactional
    public boolean hasLocalFinalityConflict(UUID chainConfigId, ReorgObservation observation) {
        chainQuarantineStore.lockChain(chainConfigId);
        List<String> hashes = orphanedHashes(observation);
        return repository.existsByChainConfigIdAndCanonicalTrueAndLevelAndBlockHashIn(
                        chainConfigId, FinalityLevel.FINALIZED, hashes)
                || chainEffectRepository.existsByChainConfigIdAndStatusAndBlockHashIn(
                        chainConfigId, ChainEffect.Status.SETTLED, hashes);
    }

    @Override
    @Transactional
    public boolean quarantineUnverifiableFinalizedRetraction(
            UUID chainConfigId, long forkBlockNumber, List<String> storedBlockHashes,
            String replacementBlockHash) {
        chainQuarantineStore.lockChain(chainConfigId);
        if (chainQuarantineStore.isActive(chainConfigId)) {
            throw new ChainQuarantinedException(chainConfigId);
        }
        boolean crossesFinalizedBlock = repository
                .existsByChainConfigIdAndBlockNumberGreaterThanEqualAndCanonicalTrueAndLevel(
                        chainConfigId, forkBlockNumber, FinalityLevel.FINALIZED);
        boolean crossesSettledEffect = chainEffectRepository
                .existsByChainConfigIdAndBlockNumberGreaterThanEqualAndStatus(
                        chainConfigId, forkBlockNumber, ChainEffect.Status.SETTLED);
        if (!crossesFinalizedBlock && !crossesSettledEffect) {
            return false;
        }

        String replacement = replacementBlockHash == null || replacementBlockHash.isBlank()
                ? "unresolved-replacement-at-" + forkBlockNumber
                : BlockIdentity.normalize(replacementBlockHash);
        Instant observedAt = Instant.now();
        ReorgObservation observation = new ReorgObservation(
                ReorgObservation.SUPPORTED_SCHEMA_VERSION,
                "registerwerk-self-probe-" + UUID.randomUUID(),
                ReorgObservation.ReorgSeverity.UNRESOLVED_ANCESTRY,
                null,
                List.of(),
                List.of(new ReorgObservation.BlockReference(
                        forkBlockNumber, replacement, "unresolved-parent", FinalityLevel.PROVISIONAL)),
                observedAt);
        reorgEpisodeStore.claim(chainConfigId, observation);
        String detail = "Self-probe mismatch intersects locally finalized state; forkBlock="
                + forkBlockNumber + ", storedHashes=" + storedBlockHashes
                + ", replacementHash=" + replacement;
        chainQuarantineStore.activate(chainConfigId, observation,
                QuarantineTrigger.LOCAL_FINALITY_CONFLICT, detail);
        eventPublisher.publishEvent(new ChainQuarantinedEvent(
                chainConfigId, observation.reorgId(), observation.severity(),
                QuarantineTrigger.LOCAL_FINALITY_CONFLICT, detail, observedAt));
        log.error("Quarantined unverifiable self-probe retraction chainConfigId={} forkBlock={}",
                chainConfigId, forkBlockNumber);
        return true;
    }

    @Override
    @Transactional
    public void recordObservation(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level) {
        chainQuarantineStore.lockChain(chainConfigId);
        if (chainQuarantineStore.isActive(chainConfigId)) {
            throw new ChainQuarantinedException(chainConfigId);
        }
        if (level == FinalityLevel.ORPHANED) {
            throw new IllegalArgumentException(
                    "recordObservation must not be called with ORPHANED; use recordRetraction instead");
        }
        blockHash = BlockIdentity.normalize(blockHash);

        BlockFinality canonical = repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(
                chainConfigId, blockNumber).orElse(null);
        if (canonical != null && !Objects.equals(canonical.getBlockHash(), blockHash)) {
            throw new IllegalStateException("Cannot replace canonical block at chainConfigId=" + chainConfigId
                    + ", block=" + blockNumber + " without recording its retraction first");
        }

        if (canonical != null) {
            promoteCanonical(canonical, chainConfigId, blockNumber, blockHash, level);
            return;
        }

        BlockFinality existingIncarnation = repository.findByChainConfigIdAndBlockNumberAndBlockHash(
                chainConfigId, blockNumber, blockHash).orElse(null);
        if (existingIncarnation == null) {
            BlockFinality row = new BlockFinality();
            row.setChainConfigId(chainConfigId);
            row.setBlockNumber(blockNumber);
            row.setBlockHash(blockHash);
            row.setLevel(level);
            row.setObservedAt(Instant.now());
            repository.save(row);
            publishChanged(chainConfigId, blockNumber, blockHash, level);
            settleIfFinalized(chainConfigId, blockNumber, blockHash, level);
            return;
        }

        // A previously orphaned hash can genuinely become canonical again (A->B->A). This is a
        // new canonical tenure, so its finality restarts at the incoming observation rather than
        // inheriting the former tenure's SAFE/FINALIZED level. orphanedAt is intentionally kept.
        existingIncarnation.setCanonical(true);
        existingIncarnation.setLevel(level);
        repository.save(existingIncarnation);
        publishChanged(chainConfigId, blockNumber, blockHash, level);
        settleIfFinalized(chainConfigId, blockNumber, blockHash, level);
    }

    private void promoteCanonical(BlockFinality existing, UUID chainConfigId, long blockNumber,
            String blockHash, FinalityLevel level) {
        // Promotion is monotonic during one canonical tenure: a transient lagging-provider
        // regression must never demote an already-stronger observation.
        if (!rank(level).isHigherThan(rank(existing.getLevel()))) {
            log.debug("BlockFinalityService: ignoring non-promoting observation chainConfigId={} block={} "
                    + "existing={} incoming={}", chainConfigId, blockNumber, existing.getLevel(), level);
            return;
        }

        existing.setLevel(level);
        repository.save(existing);
        publishChanged(chainConfigId, blockNumber, blockHash, level);
        settleIfFinalized(chainConfigId, blockNumber, blockHash, level);
    }

    @Override
    @Transactional
    public void recordRetraction(UUID chainConfigId, long forkBlockNumber, String replacementBlockHash,
            int orphanedTransferCount) {
        chainQuarantineStore.lockChain(chainConfigId);
        if (chainQuarantineStore.isActive(chainConfigId)) {
            throw new ChainQuarantinedException(chainConfigId);
        }
        boolean crossesFinalizedBlock = repository
                .existsByChainConfigIdAndBlockNumberGreaterThanEqualAndCanonicalTrueAndLevel(
                        chainConfigId, forkBlockNumber, FinalityLevel.FINALIZED);
        boolean crossesSettledEffect = chainEffectRepository
                .existsByChainConfigIdAndBlockNumberGreaterThanEqualAndStatus(
                        chainConfigId, forkBlockNumber, ChainEffect.Status.SETTLED);
        if (crossesFinalizedBlock || crossesSettledEffect) {
            // Legacy height-only envelopes cannot prove which finalized incarnation was orphaned
            // and carry no occurrence id with which to persist an exact quarantine episode. Abort
            // the whole caller transaction before mutating canonical/business state; the durable
            // source remains unacknowledged. Typed recordReorg persists the stronger quarantine.
            throw new IllegalStateException("Legacy retraction at chainConfigId=" + chainConfigId
                    + " forkBlock=" + forkBlockNumber
                    + " intersects finalized state; refusing compensation without a typed finality-violation episode");
        }
        int rowsOrphaned = repository.markOrphanedFromBlock(chainConfigId, forkBlockNumber);
        log.debug("BlockFinalityService: retraction chainConfigId={} forkBlockNumber={} "
                + "block_finality rows orphaned={} (token_transfer rows orphaned={})",
                chainConfigId, forkBlockNumber, rowsOrphaned, orphanedTransferCount);
        eventPublisher.publishEvent(new BlockRetractedEvent(
                chainConfigId, forkBlockNumber, replacementBlockHash, orphanedTransferCount, Instant.now()));

        // The generic compensation sweep: every claimable chain_effect recorded at or after the
        // fork block gets a compensation attempt. The SETTLED guard above means this legacy path
        // is restricted to effects below finality; deep/finality-violating reorgs require the
        // typed episode path and quarantine instead. Safe by construction even for
        // an effect a module-specific reorg guard (e.g. indexer's ReorgGuard, for
        // HOLDER_BALANCE_SYNCED) already compensated synchronously moments earlier: the
        // dispatcher's atomic claim makes re-compensating an already-COMPENSATED row a no-op.
        boolean compensationFailed = false;
        for (UUID chainEffectId : chainEffectRepository.findIdsAtOrAfter(chainConfigId, forkBlockNumber)) {
            compensationFailed |= unresolved(compensationDispatcher.compensate(chainEffectId));
        }
        if (compensationFailed) {
            // A height-only envelope has no durable episode identity to which chain_quarantine can
            // safely refer. Roll the transaction back and keep the source/cursor unacknowledged;
            // a later retry must not observe a half-compensated local state.
            throw new IllegalStateException("One or more reorg compensations failed for legacy retraction "
                    + "chainConfigId=" + chainConfigId + ", forkBlock=" + forkBlockNumber);
        }
    }

    @Override
    @Transactional(noRollbackFor = ReorgEnvelopeConflictException.class)
    public void recordReorg(UUID chainConfigId, ReorgObservation observation, int orphanedTransferCount,
            QuarantineTrigger safetyConflict) {
        chainQuarantineStore.lockChain(chainConfigId);
        boolean claimed;
        try {
            claimed = reorgEpisodeStore.claim(chainConfigId, observation);
        } catch (ReorgEnvelopeConflictException conflict) {
            chainQuarantineStore.activate(chainConfigId, observation,
                    QuarantineTrigger.REORG_ID_COLLISION, conflict.getMessage());
            eventPublisher.publishEvent(new ChainQuarantinedEvent(
                    chainConfigId, observation.reorgId(), observation.severity(),
                    QuarantineTrigger.REORG_ID_COLLISION, conflict.getMessage(), Instant.now()));
            throw conflict;
        }
        if (!claimed) {
            log.debug("Ignoring replayed reorg episode chainConfigId={} reorgId={}",
                    chainConfigId, observation.reorgId());
            return;
        }

        if (observation.severity() != ReorgObservation.ReorgSeverity.ROUTINE) {
            QuarantineTrigger trigger = observation.severity() == ReorgObservation.ReorgSeverity.FINALITY_VIOLATION
                    ? QuarantineTrigger.CONSENSUS_FINALITY_VIOLATION
                    : QuarantineTrigger.UNRESOLVED_ANCESTRY;
            chainQuarantineStore.activate(chainConfigId, observation, trigger, null);
            eventPublisher.publishEvent(new ChainQuarantinedEvent(
                    chainConfigId, observation.reorgId(), observation.severity(), trigger, null, Instant.now()));
            log.error("Quarantined chain without applying reorg episode chainConfigId={} reorgId={} severity={}",
                    chainConfigId, observation.reorgId(), observation.severity());
            return;
        }
        if (chainQuarantineStore.isActive(chainConfigId)) {
            throw new ChainQuarantinedException(chainConfigId);
        }

        List<String> orphanedHashes = orphanedHashes(observation);

        boolean localFinalityConflict = safetyConflict != null
                || repository.existsByChainConfigIdAndCanonicalTrueAndLevelAndBlockHashIn(
                        chainConfigId, FinalityLevel.FINALIZED, orphanedHashes)
                || chainEffectRepository.existsByChainConfigIdAndStatusAndBlockHashIn(
                        chainConfigId, ChainEffect.Status.SETTLED, orphanedHashes);
        if (localFinalityConflict) {
            // Never trust an upstream ROUTINE label over stronger state already reached locally.
            // This can happen under finality-policy drift. Preserve all canonical/business state
            // and persist the claimed episode as the quarantine incident before ACK.
            QuarantineTrigger trigger = safetyConflict == null
                    ? QuarantineTrigger.LOCAL_FINALITY_CONFLICT : safetyConflict;
            String detail = "Routine reorg intersects locally finalized/settled state";
            chainQuarantineStore.activate(chainConfigId, observation, trigger, detail);
            eventPublisher.publishEvent(new ChainQuarantinedEvent(
                    chainConfigId, observation.reorgId(), observation.severity(), trigger, detail, Instant.now()));
            log.error("Quarantined routine reorg that intersects locally finalized state "
                            + "chainConfigId={} reorgId={}", chainConfigId, observation.reorgId());
            return;
        }

        int rowsOrphaned = orphanedHashes.isEmpty()
                ? 0
                : repository.markCanonicalOrphanedByHashes(chainConfigId, orphanedHashes);
        Long forkBlock = observation.forkBlockNumber();
        if (!orphanedHashes.isEmpty() && forkBlock != null) {
            eventPublisher.publishEvent(new BlockRetractedEvent(
                    chainConfigId, forkBlock, observation.replacementHashAtFork(),
                    orphanedTransferCount, Instant.now()));
            boolean compensationFailed = false;
            for (UUID chainEffectId : chainEffectRepository.findIdsByBlockHashes(chainConfigId, orphanedHashes)) {
                compensationFailed |= unresolved(compensationDispatcher.compensate(chainEffectId));
            }
            if (compensationFailed) {
                // The durable episode has been applied as far as safely possible, but at least one
                // business effect remains stale. Persist a chain-wide quarantine in the same
                // transaction before the Chaincache envelope can be ACKed. This avoids both a
                // deterministic redelivery hot-loop and a fail-open non-asset state.
                String detail = "One or more domain chain-effect compensations failed or were irreversible";
                chainQuarantineStore.activate(chainConfigId, observation,
                        QuarantineTrigger.DOMAIN_COMPENSATION_FAILED, detail);
                eventPublisher.publishEvent(new ChainQuarantinedEvent(
                        chainConfigId, observation.reorgId(), observation.severity(),
                        QuarantineTrigger.DOMAIN_COMPENSATION_FAILED, detail, Instant.now()));
                log.error("Quarantined chain after incomplete routine-reorg compensation "
                                + "chainConfigId={} reorgId={}",
                        chainConfigId, observation.reorgId());
            }
        }

        log.info("Applied reorg episode chainConfigId={} reorgId={} severity={} orphanedRows={}",
                chainConfigId, observation.reorgId(), observation.severity(), rowsOrphaned);
    }

    @Override
    public Optional<BlockFinalityRecord> find(UUID chainConfigId, long blockNumber) {
        return repository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(chainConfigId, blockNumber)
                .map(BlockFinalityServiceImpl::toRecord);
    }

    @Override
    public List<BlockFinalityRecord> findIncarnations(UUID chainConfigId, long blockNumber) {
        return repository.findByChainConfigIdAndBlockNumberOrderByObservedAtAscIdAsc(chainConfigId, blockNumber)
                .stream().map(BlockFinalityServiceImpl::toRecord).toList();
    }

    @Override
    public List<Long> findBlocksWithUnresolvedEffects(UUID chainConfigId) {
        return chainEffectRepository.findDistinctUnresolvedBlockNumbers(chainConfigId);
    }

    /** Once a block reaches FINALIZED, any chain_effect still ACTIVE for that exact incarnation is settled —
     *  mirroring token_transfer's own trust model (see this class's ORPHANED-is-terminal comment
     *  above) so the unresolved-effects set {@link #findBlocksWithUnresolvedEffects} exposes does
     *  not grow without bound. */
    private void settleIfFinalized(
            UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level) {
        if (level == FinalityLevel.FINALIZED) {
            chainEffectRepository.settleAtBlock(chainConfigId, blockNumber, BlockIdentity.normalize(blockHash));
        }
    }

    private void publishChanged(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level) {
        eventPublisher.publishEvent(new BlockFinalityChangedEvent(chainConfigId, blockNumber, blockHash, level, Instant.now()));
    }

    private static boolean unresolved(CompensationOutcome outcome) {
        return outcome instanceof CompensationOutcome.Failed
                || outcome instanceof CompensationOutcome.Irreversible;
    }

    private static List<String> orphanedHashes(ReorgObservation observation) {
        return observation.orphanedLineage().stream()
                .map(ReorgObservation.BlockReference::blockHash)
                .map(BlockIdentity::normalize)
                .filter(Objects::nonNull)
                .toList();
    }

    private static BlockFinalityRecord toRecord(BlockFinality row) {
        return new BlockFinalityRecord(
                row.getChainConfigId(), row.getBlockNumber(), row.getBlockHash(), row.getLevel(),
                row.isCanonical(), row.getObservedAt(), row.getUpdatedAt(), row.getOrphanedAt());
    }

    /** Local rank, deliberately not exposed on {@link FinalityLevel} itself - only this class
     *  needs to compare promotion strength, and ORPHANED is treated as "never a valid promotion
     *  target here" (guarded above) rather than needing a rank of its own. */
    private static Rank rank(FinalityLevel level) {
        return switch (level) {
            case PROVISIONAL -> Rank.of(0);
            case SAFE -> Rank.of(1);
            case FINALIZED -> Rank.of(2);
            case ORPHANED -> Rank.of(-1);
        };
    }

    private record Rank(int value) {
        static Rank of(int value) { return new Rank(value); }
        boolean isHigherThan(Rank other) { return this.value > other.value; }
    }
}
