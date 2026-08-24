package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The INVERSE_FLIP compensator for {@code TRUSTED_ISSUER_ADDED} — see
 *  {@link OrgRegistrationRevertCompensator}'s javadoc for the shared design rationale. */
@Component
class EcosystemTrustedIssuerRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "TRUSTED_ISSUER_ADDED";

    private static final Logger log = LoggerFactory.getLogger(EcosystemTrustedIssuerRevertCompensator.class);

    private final EcosystemTrustedIssuerRepository repository;

    EcosystemTrustedIssuerRevertCompensator(EcosystemTrustedIssuerRepository repository) {
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
        boolean pendingRemovalIntent = issuer.getStatus() == TrustedIssuerStatus.REMOVAL_PENDING
                && issuer.getRemovedAt() != null
                && issuer.getRemovedBlockNumber() == null
                && issuer.getRemovedBlockHash() == null;
        if (issuer.getStatus() != TrustedIssuerStatus.ACTIVE && !pendingRemovalIntent) {
            return new CompensationOutcome.NotApplicable(
                    "EcosystemTrustedIssuer " + id + " is neither ACTIVE nor awaiting removal (status="
                            + issuer.getStatus() + ")");
        }
        if (!ChainEffectCausality.matches(effect, issuer.getChainConfigId(), issuer.getAddedTx(),
                issuer.getAddedBlockNumber(), issuer.getAddedBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "EcosystemTrustedIssuer " + id + " is owned by a different confirmation incarnation");
        }

        log.error("EcosystemTrustedIssuer id={} addition confirmation was retracted by a reorg (status={}) "
                        + "— clearing addition provenance while preserving any fail-closed removal intent.",
                id, issuer.getStatus());
        if (!pendingRemovalIntent) {
            issuer.setStatus(TrustedIssuerStatus.PENDING);
        }
        issuer.setAddedBlockNumber(null);
        issuer.setAddedBlockHash(null);
        repository.save(issuer);

        return new CompensationOutcome.Compensated(
                "Cleared EcosystemTrustedIssuer " + id + " addition confirmation after retraction");
    }
}
