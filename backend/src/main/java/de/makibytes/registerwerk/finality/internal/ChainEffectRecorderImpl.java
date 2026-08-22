package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@Service
class ChainEffectRecorderImpl implements ChainEffectRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChainEffectRecorderImpl.class);

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

    ChainEffectRecorderImpl(ChainEffectRepository repository, CompensationDispatcher dispatcher,
                            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public UUID record(ChainEffectDescriptor descriptor) {
        checkCategoryMatchesCompensator(descriptor);
        String sourceEventKey = sourceEventKey(descriptor);

        UUID insertedId = insertIgnoringConflict(descriptor, sourceEventKey);
        if (insertedId != null) {
            return insertedId;
        }

        // ON CONFLICT DO NOTHING means either a concurrent recorder won the exact same race, or
        // (far more commonly) this exact effect was already recorded earlier — either way the row
        // is now safely queryable, unlike the previous catch-DataIntegrityViolationException
        // approach: Postgres aborts the whole transaction on a real constraint violation, so a
        // fallback SELECT run inside that same aborted transaction would itself fail with
        // "current transaction is aborted" rather than return the winning row. This upsert never
        // throws on conflict, so the transaction stays usable.
        return repository.findBySourceEventKeyAndEffectTypeAndEntityId(
                        sourceEventKey, descriptor.effectType(), descriptor.entityId())
                .map(ChainEffect::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "chain_effect insert conflicted but no existing row found for sourceEventKey="
                                + sourceEventKey + " effectType=" + descriptor.effectType()
                                + " entityId=" + descriptor.entityId()));
    }

    @Override
    @Transactional
    public CompensationOutcome recordAndCompensate(ChainEffectDescriptor descriptor) {
        UUID id = record(descriptor);
        return dispatcher.compensate(id);
    }

    /** Cheap, fail-fast safety net for the {@link ChainEffectCompensator#category()} contract
     *  ("must match what {@code ChainEffectDescriptor}s for this {@code effectType} were recorded
     *  with"), which nothing previously verified — a mismatch would only have surfaced, if ever,
     *  as confusing behavior at compensation time. Logs loudly but still records the effect: a
     *  wiring bug at the call site is better fixed by a developer reading this log than by
     *  silently dropping a reorg-compensation journal entry. */
    private void checkCategoryMatchesCompensator(ChainEffectDescriptor descriptor) {
        dispatcher.compensatorFor(descriptor.effectType()).ifPresent(compensator -> {
            if (compensator.category() != descriptor.category()) {
                log.error("ChainEffectDescriptor for effectType={} was recorded with category={}, but its "
                        + "registered compensator declares category={} — these must match. Recording anyway "
                        + "(undo-time is a worse place to first discover this), but this is a wiring bug at "
                        + "the call site that journalled this effect.",
                        descriptor.effectType(), descriptor.category(), compensator.category());
            }
        });
    }

    private UUID insertIgnoringConflict(ChainEffectDescriptor d, String sourceEventKey) {
        try {
            return jdbcTemplate.queryForObject(INSERT_IGNORING_CONFLICT, UUID.class,
                    d.chainConfigId(), d.blockNumber(), d.blockHash(), d.txHash(), d.logIndex(),
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

    /** {@code chainConfigId:blockNumber[:txHash][:logIndex]} - deliberately does not include
     *  entityId (that is the other half of the DB unique constraint, kept separate so the same
     *  on-chain event recorded against two different entities is still two rows). */
    private static String sourceEventKey(ChainEffectDescriptor d) {
        StringBuilder key = new StringBuilder();
        key.append(d.chainConfigId()).append(':').append(d.blockNumber());
        if (d.txHash() != null) {
            key.append(':').append(d.txHash());
        }
        if (d.logIndex() != null) {
            key.append(':').append(d.logIndex());
        }
        return key.toString();
    }
}
