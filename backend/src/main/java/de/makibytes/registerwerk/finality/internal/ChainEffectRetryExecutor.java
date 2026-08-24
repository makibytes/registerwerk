package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Gives each scheduled retry its own physical transaction. */
@Component
class ChainEffectRetryExecutor {

    private final CompensationDispatcher dispatcher;

    ChainEffectRetryExecutor(CompensationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    CompensationOutcome retry(UUID chainEffectId) {
        return dispatcher.compensate(chainEffectId);
    }
}
