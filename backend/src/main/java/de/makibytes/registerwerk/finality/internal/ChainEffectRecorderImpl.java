package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.BlockIdentity;
import de.makibytes.registerwerk.finality.api.ChainQuarantinedException;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
class ChainEffectRecorderImpl implements ChainEffectRecorder {

    private static final String INSERT_IGNORING_CONFLICT = """
            INSERT INTO chain_effect (chain_config_id, block_number, block_hash, tx_hash, log_index,
                source_event_key, module_name, effect_type, entity_type, entity_id, asset_id, category,
                before_state, after_state, audit_event_id, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            ON CONFLICT (source_event_key, effect_type, entity_id) DO NOTHING
            RETURNING id
            """;

    private final ChainEffectRepository repository;
    private final CompensationDispatcher dispatcher;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChainQuarantineStore chainQuarantineStore;
    private final BlockFinalityRepository blockFinalityRepository;

    ChainEffectRecorderImpl(ChainEffectRepository repository, CompensationDispatcher dispatcher,
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            ChainQuarantineStore chainQuarantineStore, BlockFinalityRepository blockFinalityRepository) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.chainQuarantineStore = chainQuarantineStore;
        this.blockFinalityRepository = blockFinalityRepository;
    }

    @Override
    @Transactional
    public UUID record(ChainEffectDescriptor descriptor) {
        return record(descriptor, true);
    }

    @Override
    @Transactional
    public UUID recordFinalized(ChainEffectDescriptor descriptor) {
        if (!hasIdentity(descriptor.blockHash())) {
            throw new IllegalArgumentException("A finalized chain effect requires exact blockHash identity");
        }
        UUID id = record(descriptor, true);
        repository.settleAtBlock(descriptor.chainConfigId(), descriptor.blockNumber(),
                BlockIdentity.normalize(descriptor.blockHash()));
        return id;
    }

    private UUID record(ChainEffectDescriptor descriptor, boolean reactivateCompensatedForwardEffect) {
        requireStableSourceIdentity(descriptor);
        // Same database row lock as BlockFinalityServiceImpl's observation/reorg paths. Because
        // callers journal in the transaction that applies their projection, this serializes the
        // entire forward state change with a concurrent sweep across application replicas.
        chainQuarantineStore.lockChain(descriptor.chainConfigId());
        if (chainQuarantineStore.isActive(descriptor.chainConfigId())) {
            throw new ChainQuarantinedException(descriptor.chainConfigId());
        }

        if (reactivateCompensatedForwardEffect) {
            rejectKnownNonCanonicalForwardEffect(descriptor);
        }

        checkCategoryMatchesCompensator(descriptor);
        String sourceEventKey = sourceEventKey(descriptor);

        UUID insertedId = insertIgnoringConflict(descriptor, sourceEventKey);
        if (insertedId != null) {
            if (reactivateCompensatedForwardEffect) {
                settleIfBlockAlreadyFinalized(descriptor);
            }
            return insertedId;
        }

        // ON CONFLICT DO NOTHING means either a concurrent recorder won the exact same race, or
        // (far more commonly) this exact effect was already recorded earlier — either way the row
        // is now safely queryable, unlike the previous catch-DataIntegrityViolationException
        // approach: Postgres aborts the whole transaction on a real constraint violation, so a
        // fallback SELECT run inside that same aborted transaction would itself fail with
        // "current transaction is aborted" rather than return the winning row. This upsert never
        // throws on conflict, so the transaction stays usable.
        ChainEffect existing = repository.findBySourceEventKeyAndEffectTypeAndEntityId(
                        sourceEventKey, descriptor.effectType(), descriptor.entityId())
                .orElseThrow(() -> new IllegalStateException(
                        "chain_effect insert conflicted but no existing row found for sourceEventKey="
                                + sourceEventKey + " effectType=" + descriptor.effectType()
                                + " entityId=" + descriptor.entityId()));

        if (!sameEffect(existing, descriptor, sourceEventKey)) {
            throw new ChainEffectConflictException(
                    "chain_effect idempotency key already belongs to different immutable data: sourceEventKey="
                            + sourceEventKey + " effectType=" + descriptor.effectType()
                            + " entityId=" + descriptor.entityId());
        }

        // record() is called only after the owning module has applied the forward state change.
        // If the exact block/event becomes canonical again (A->B->A), its previously compensated
        // row must therefore become ACTIVE for the new tenure. recordAndCompensate() deliberately
        // disables this transition: replaying the same retraction must stay a total no-op.
        if (reactivateCompensatedForwardEffect && existing.getStatus() == ChainEffect.Status.COMPENSATED) {
            repository.reactivateCompensated(existing.getId());
        }
        if (reactivateCompensatedForwardEffect) {
            settleIfBlockAlreadyFinalized(descriptor);
        }
        return existing.getId();
    }

    @Override
    @Transactional
    public CompensationOutcome recordAndCompensate(ChainEffectDescriptor descriptor) {
        UUID id = record(descriptor, false);
        return dispatcher.compensate(id);
    }

    /** Cheap, fail-fast safety net for the {@link ChainEffectCompensator#category()} contract
     *  ("must match what {@code ChainEffectDescriptor}s for this {@code effectType} were recorded
     *  with"), which nothing previously verified — a mismatch would only have surfaced, if ever,
     *  as confusing behavior at compensation time. Logs loudly but still records the effect: a
     *  wiring bug at the call site must abort the forward transaction. Recording a category the
     *  compensator does not implement creates false assurance that the state can be recovered. */
    private void checkCategoryMatchesCompensator(ChainEffectDescriptor descriptor) {
        ChainEffectCompensator compensator = dispatcher.compensatorFor(descriptor.effectType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No ChainEffectCompensator registered for effectType=" + descriptor.effectType()));
        if (compensator.category() != descriptor.category()) {
            throw new IllegalArgumentException("ChainEffectDescriptor for effectType="
                    + descriptor.effectType() + " uses category=" + descriptor.category()
                    + " but its compensator declares category=" + compensator.category());
        }
    }

    private UUID insertIgnoringConflict(ChainEffectDescriptor d, String sourceEventKey) {
        try {
            return jdbcTemplate.queryForObject(INSERT_IGNORING_CONFLICT, UUID.class,
                    d.chainConfigId(), d.blockNumber(), BlockIdentity.normalize(d.blockHash()),
                    BlockIdentity.normalize(d.txHash()), d.logIndex(),
                    sourceEventKey, d.moduleName(), d.effectType(), d.entityType(), d.entityId(), d.assetId(),
                    d.category().name(), toJson(d.beforeState()), toJson(d.afterState()),
                    d.auditEventId(), d.correlationId());
        } catch (EmptyResultDataAccessException noRowReturned) {
            // ON CONFLICT DO NOTHING suppressed the insert - RETURNING produced no row.
            return null;
        }
    }

    private String toJson(Map<String, Object> value) {
        return value == null ? null : objectMapper.writeValueAsString(value);
    }

    /** Canonical source identity. {@code entityId} remains the separate half of the unique
     * constraint so one source event may legitimately affect several entities. */
    static String sourceEventKey(ChainEffectDescriptor d) {
        Object occurrence = !hasIdentity(d.blockHash()) && !hasIdentity(d.txHash()) ? d.correlationId() : null;
        return d.chainConfigId() + ":" + d.blockNumber()
                + ":block=" + nullToken(BlockIdentity.normalize(d.blockHash()))
                + ":tx=" + nullToken(BlockIdentity.normalize(d.txHash()))
                + ":log=" + nullToken(d.logIndex())
                + ":occurrence=" + nullToken(occurrence);
    }

    private static void requireStableSourceIdentity(ChainEffectDescriptor descriptor) {
        if (descriptor.blockHash() != null && descriptor.blockHash().isBlank()) {
            throw new IllegalArgumentException("blockHash must be null or non-blank");
        }
        if (descriptor.txHash() != null && descriptor.txHash().isBlank()) {
            throw new IllegalArgumentException("txHash must be null or non-blank");
        }
        boolean hasBlock = hasIdentity(descriptor.blockHash());
        boolean hasTransaction = hasIdentity(descriptor.txHash());
        if (!hasBlock && hasTransaction) {
            throw new IllegalArgumentException(
                    "A transaction-backed chain effect requires blockHash as its stable block-incarnation identity");
        }
        if (!hasBlock && descriptor.correlationId() == null) {
            throw new IllegalArgumentException(
                    "A chain effect without blockHash or txHash requires correlationId as its stable occurrence identity");
        }
    }

    private static boolean hasIdentity(String value) {
        return value != null && !value.isBlank();
    }

    private void rejectKnownNonCanonicalForwardEffect(ChainEffectDescriptor descriptor) {
        String canonicalBlockHash = BlockIdentity.normalize(descriptor.blockHash());
        blockFinalityRepository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(
                        descriptor.chainConfigId(), descriptor.blockNumber())
                .filter(canonical -> !BlockIdentity.sameHash(canonical.getBlockHash(), canonicalBlockHash))
                .ifPresent(canonical -> {
                    throw new OrphanedChainEffectException(
                            "Refusing chain effect whose block is not canonical chainConfigId="
                                    + descriptor.chainConfigId() + " block=" + descriptor.blockNumber()
                                    + " expectedHash=" + canonical.getBlockHash()
                                    + " effectHash=" + canonicalBlockHash);
                });
        blockFinalityRepository.findByChainConfigIdAndBlockNumberAndBlockHash(
                        descriptor.chainConfigId(), descriptor.blockNumber(), canonicalBlockHash)
                .filter(incarnation -> !incarnation.isCanonical())
                .ifPresent(incarnation -> {
                    throw new OrphanedChainEffectException(
                            "Refusing chain effect from already-orphaned block chainConfigId="
                                    + descriptor.chainConfigId() + " block=" + descriptor.blockNumber()
                                    + " blockHash=" + canonicalBlockHash);
                });
    }

    private void settleIfBlockAlreadyFinalized(ChainEffectDescriptor descriptor) {
        String hash = BlockIdentity.normalize(descriptor.blockHash());
        blockFinalityRepository.findByChainConfigIdAndBlockNumberAndCanonicalTrue(
                        descriptor.chainConfigId(), descriptor.blockNumber())
                .filter(block -> block.getLevel() == FinalityLevel.FINALIZED)
                .filter(block -> BlockIdentity.sameHash(block.getBlockHash(), hash))
                .ifPresent(block -> repository.settleAtBlock(
                        descriptor.chainConfigId(), descriptor.blockNumber(), hash));
    }

    private static String nullToken(Object value) {
        return value == null ? "~" : value.toString();
    }

    private boolean sameEffect(ChainEffect row, ChainEffectDescriptor d, String sourceEventKey) {
        return Objects.equals(row.getSourceEventKey(), sourceEventKey)
                && Objects.equals(row.getChainConfigId(), d.chainConfigId())
                && row.getBlockNumber() == d.blockNumber()
                && Objects.equals(row.getBlockHash(), BlockIdentity.normalize(d.blockHash()))
                && Objects.equals(row.getTxHash(), BlockIdentity.normalize(d.txHash()))
                && Objects.equals(row.getLogIndex(), d.logIndex())
                && Objects.equals(row.getModuleName(), d.moduleName())
                && Objects.equals(row.getEffectType(), d.effectType())
                && Objects.equals(row.getEntityType(), d.entityType())
                && Objects.equals(row.getEntityId(), d.entityId())
                && Objects.equals(row.getAssetId(), d.assetId())
                && row.getCategory() == d.category()
                // JSONB deliberately forgets Java's in-memory number width.  Comparing the maps
                // directly makes an exact replay depend on whether Jackson materialized `400` as
                // Integer or the original producer supplied it as Long.  Round both values through
                // JSON first so idempotency is based on the persisted JSON value, not JVM types.
                && sameJsonValue(row.getBeforeState(), d.beforeState())
                && sameJsonValue(row.getAfterState(), d.afterState())
                && Objects.equals(row.getAuditEventId(), d.auditEventId())
                && Objects.equals(row.getCorrelationId(), d.correlationId());
    }

    private boolean sameJsonValue(Map<String, Object> persisted, Map<String, Object> proposed) {
        if (persisted == null || proposed == null) {
            return persisted == proposed;
        }
        return Objects.equals(
                objectMapper.readTree(objectMapper.writeValueAsString(persisted)),
                objectMapper.readTree(objectMapper.writeValueAsString(proposed)));
    }
}
