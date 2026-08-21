package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.BlockFinalityFeed;
import de.makibytes.registerwerk.finality.api.BlockFinalityPort;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import de.makibytes.registerwerk.finality.events.BlockFinalityChangedEvent;
import de.makibytes.registerwerk.finality.events.BlockRetractedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class BlockFinalityServiceImpl implements BlockFinalityFeed, BlockFinalityPort {

    private static final Logger log = LoggerFactory.getLogger(BlockFinalityServiceImpl.class);

    private final BlockFinalityRepository repository;
    private final ChainEffectRepository chainEffectRepository;
    private final CompensationDispatcher compensationDispatcher;
    private final ApplicationEventPublisher eventPublisher;

    BlockFinalityServiceImpl(BlockFinalityRepository repository, ChainEffectRepository chainEffectRepository,
            CompensationDispatcher compensationDispatcher, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.chainEffectRepository = chainEffectRepository;
        this.compensationDispatcher = compensationDispatcher;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void recordObservation(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level) {
        if (level == FinalityLevel.ORPHANED) {
            throw new IllegalArgumentException(
                    "recordObservation must not be called with ORPHANED; use recordRetraction instead");
        }

        BlockFinality existing = repository.findByChainConfigIdAndBlockNumber(chainConfigId, blockNumber).orElse(null);
        if (existing == null) {
            BlockFinality row = new BlockFinality();
            row.setChainConfigId(chainConfigId);
            row.setBlockNumber(blockNumber);
            row.setBlockHash(blockHash);
            row.setLevel(level);
            row.setObservedAt(Instant.now());
            repository.save(row);
            publishChanged(chainConfigId, blockNumber, blockHash, level);
            settleIfFinalized(chainConfigId, blockNumber, level);
            return;
        }

        // ORPHANED is terminal in this model (matches token_transfer.finality_status - see
        // FinalityLevel's javadoc): a normal observation never reverses it. And promotion is
        // monotonic - a transient probe regression (e.g. a lagging node briefly reporting an
        // older safe/finalized tag) must never demote an already-stronger recorded level.
        if (existing.getLevel() == FinalityLevel.ORPHANED || !rank(level).isHigherThan(rank(existing.getLevel()))) {
            log.debug("BlockFinalityService: ignoring non-promoting observation chainConfigId={} block={} "
                    + "existing={} incoming={}", chainConfigId, blockNumber, existing.getLevel(), level);
            return;
        }

        existing.setBlockHash(blockHash);
        existing.setLevel(level);
        repository.save(existing);
        publishChanged(chainConfigId, blockNumber, blockHash, level);
        settleIfFinalized(chainConfigId, blockNumber, level);
    }

    @Override
    @Transactional
    public void recordRetraction(UUID chainConfigId, long forkBlockNumber, String replacementBlockHash,
            int orphanedTransferCount) {
        int rowsOrphaned = repository.markOrphanedFromBlock(chainConfigId, forkBlockNumber);
        log.debug("BlockFinalityService: retraction chainConfigId={} forkBlockNumber={} "
                + "block_finality rows orphaned={} (token_transfer rows orphaned={})",
                chainConfigId, forkBlockNumber, rowsOrphaned, orphanedTransferCount);
        eventPublisher.publishEvent(new BlockRetractedEvent(
                chainConfigId, forkBlockNumber, replacementBlockHash, orphanedTransferCount, Instant.now()));

        // The generic compensation sweep: every chain_effect recorded at or after the fork block,
        // in any status (a reorg deep enough to retract a FINALIZED block un-settles an
        // already-SETTLED effect too), gets a compensation attempt. Safe by construction even for
        // an effect a module-specific reorg guard (e.g. indexer's ReorgGuard, for
        // HOLDER_BALANCE_SYNCED) already compensated synchronously moments earlier: the
        // dispatcher's atomic claim makes re-compensating an already-COMPENSATED row a no-op.
        for (UUID chainEffectId : chainEffectRepository.findIdsAtOrAfter(chainConfigId, forkBlockNumber)) {
            compensationDispatcher.compensate(chainEffectId);
        }
    }

    @Override
    public Optional<BlockFinalityRecord> find(UUID chainConfigId, long blockNumber) {
        return repository.findByChainConfigIdAndBlockNumber(chainConfigId, blockNumber)
                .map(row -> new BlockFinalityRecord(
                        row.getChainConfigId(), row.getBlockNumber(), row.getBlockHash(), row.getLevel(),
                        row.getObservedAt(), row.getUpdatedAt()));
    }

    @Override
    public List<Long> findBlocksWithUnresolvedEffects(UUID chainConfigId) {
        return chainEffectRepository.findDistinctUnresolvedBlockNumbers(chainConfigId);
    }

    /** Once a block reaches FINALIZED, any chain_effect still ACTIVE at that height is settled —
     *  mirroring token_transfer's own trust model (see this class's ORPHANED-is-terminal comment
     *  above) so the unresolved-effects set {@link #findBlocksWithUnresolvedEffects} exposes does
     *  not grow without bound. */
    private void settleIfFinalized(UUID chainConfigId, long blockNumber, FinalityLevel level) {
        if (level == FinalityLevel.FINALIZED) {
            chainEffectRepository.settleAtBlock(chainConfigId, blockNumber);
        }
    }

    private void publishChanged(UUID chainConfigId, long blockNumber, String blockHash, FinalityLevel level) {
        eventPublisher.publishEvent(new BlockFinalityChangedEvent(chainConfigId, blockNumber, blockHash, level, Instant.now()));
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
