package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuerRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
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
        if (issuer.getStatus() != MemberWalletStatus.ACTIVE) {
            return new CompensationOutcome.NotApplicable(
                    "EcosystemTrustedIssuer " + id + " is no longer ACTIVE (status=" + issuer.getStatus() + ")");
        }

        log.error("EcosystemTrustedIssuer id={} was ACTIVE but its confirming block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification.", id);
        issuer.setStatus(MemberWalletStatus.PENDING);
        repository.save(issuer);

        return new CompensationOutcome.Compensated("Reverted EcosystemTrustedIssuer " + id + " to PENDING after retraction");
    }
}
