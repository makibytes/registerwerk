package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Re-verifies a still-desired issuer removal after its exact confirming block is orphaned. */
@Component
class TrustedIssuerRemovalRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "TRUSTED_ISSUER_REMOVED";

    private final EcosystemTrustedIssuerRepository repository;

    TrustedIssuerRemovalRevertCompensator(EcosystemTrustedIssuerRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        EcosystemTrustedIssuer issuer = repository.findById(id).orElse(null);
        if (issuer == null) {
            return new CompensationOutcome.NotApplicable("EcosystemTrustedIssuer " + id + " no longer exists");
        }
        if (issuer.getStatus() != TrustedIssuerStatus.REMOVED) {
            return new CompensationOutcome.NotApplicable(
                    "EcosystemTrustedIssuer " + id + " is no longer owned by a confirmed removal");
        }
        if (!ChainEffectCausality.matches(effect, issuer.getChainConfigId(), issuer.getRemovedTx(),
                issuer.getRemovedBlockNumber(), issuer.getRemovedBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "EcosystemTrustedIssuer " + id + " is owned by a newer removal incarnation");
        }

        issuer.setStatus(TrustedIssuerStatus.REMOVAL_PENDING);
        issuer.setRemovedBlockNumber(null);
        issuer.setRemovedBlockHash(null);
        repository.save(issuer);
        return new CompensationOutcome.Compensated(
                "Returned EcosystemTrustedIssuer " + id + " removal to fail-closed verification");
    }
}
