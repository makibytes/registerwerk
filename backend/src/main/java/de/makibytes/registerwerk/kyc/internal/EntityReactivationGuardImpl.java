package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityReactivationGuard;
import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An entity is not reactivated while its screening is unresolved (fail closed: never screened,
 * pending, error, or an unreviewed hit, also on its beneficial owners) or while a Sperrvermerk is
 * active on it (6-21).
 */
@Component
class EntityReactivationGuardImpl implements EntityReactivationGuard {

    private final ScreeningGate screeningGate;
    private final HolderBlockRepository blocks;

    EntityReactivationGuardImpl(ScreeningGate screeningGate, HolderBlockRepository blocks) {
        this.screeningGate = screeningGate;
        this.blocks = blocks;
    }

    @Override
    public List<String> blockers(UUID entityId) {
        List<String> result = new ArrayList<>();
        if (screeningGate.hasUnresolvedHit(entityId)) {
            result.add("unresolved screening hit or no completed screening run");
        }
        if (screeningGate.hasUnresolvedBeneficialOwnerHit(entityId)) {
            result.add("unresolved screening hit on a beneficial owner");
        }
        if (!blocks.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING).isEmpty()) {
            result.add("active Sperrvermerk on the entity");
        }
        return result;
    }
}
