package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class ChainEffectRecorderImpl implements ChainEffectRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChainEffectRecorderImpl.class);

    private final ChainEffectRepository repository;
    private final CompensationDispatcher dispatcher;

    ChainEffectRecorderImpl(ChainEffectRepository repository, CompensationDispatcher dispatcher) {
        this.repository = repository;
        this.dispatcher = dispatcher;
    }

    @Override
    @Transactional
    public UUID record(ChainEffectDescriptor descriptor) {
        String sourceEventKey = sourceEventKey(descriptor);
        ChainEffect existing = repository.findBySourceEventKeyAndEffectTypeAndEntityId(
                sourceEventKey, descriptor.effectType(), descriptor.entityId()).orElse(null);
        if (existing != null) {
            return existing.getId();
        }

        ChainEffect row = new ChainEffect();
        row.setChainConfigId(descriptor.chainConfigId());
        row.setBlockNumber(descriptor.blockNumber());
        row.setBlockHash(descriptor.blockHash());
        row.setTxHash(descriptor.txHash());
        row.setLogIndex(descriptor.logIndex());
        row.setSourceEventKey(sourceEventKey);
        row.setModuleName(descriptor.moduleName());
        row.setEffectType(descriptor.effectType());
        row.setEntityType(descriptor.entityType());
        row.setEntityId(descriptor.entityId());
        row.setCategory(descriptor.category());
        row.setBeforeState(descriptor.beforeState());
        row.setAfterState(descriptor.afterState());
        row.setAuditEventId(descriptor.auditEventId());
        row.setCorrelationId(descriptor.correlationId());

        try {
            return repository.save(row).getId();
        } catch (DataIntegrityViolationException e) {
            // Concurrent recorder won the unique-constraint race between our lookup and our save.
            log.debug("ChainEffectRecorder: lost race recording sourceEventKey={} effectType={} entityId={}, "
                    + "falling back to the winning row", sourceEventKey, descriptor.effectType(), descriptor.entityId());
            return repository.findBySourceEventKeyAndEffectTypeAndEntityId(
                            sourceEventKey, descriptor.effectType(), descriptor.entityId())
                    .map(ChainEffect::getId)
                    .orElseThrow(() -> e);
        }
    }

    @Override
    @Transactional
    public CompensationOutcome recordAndCompensate(ChainEffectDescriptor descriptor) {
        UUID id = record(descriptor);
        return dispatcher.compensate(id);
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
