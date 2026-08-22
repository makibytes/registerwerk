package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The INVERSE_FLIP compensator for {@code MEMBER_WALLET_BOUND} — see
 *  {@link OrgRegistrationRevertCompensator}'s javadoc for the shared design rationale. */
@Component
class OrgMemberWalletRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "MEMBER_WALLET_BOUND";

    private static final Logger log = LoggerFactory.getLogger(OrgMemberWalletRevertCompensator.class);

    private final OrgMemberWalletRepository repository;

    OrgMemberWalletRevertCompensator(OrgMemberWalletRepository repository) {
        this.repository = repository;
    }

    @Override
    public String effectType() { return EFFECT_TYPE; }

    @Override
    public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        UUID id = effect.entityId();
        OrgMemberWallet wallet = repository.findById(id).orElse(null);
        if (wallet == null) {
            return new CompensationOutcome.NotApplicable("OrgMemberWallet " + id + " no longer exists");
        }
        if (wallet.getStatus() != MemberWalletStatus.ACTIVE) {
            return new CompensationOutcome.NotApplicable(
                    "OrgMemberWallet " + id + " is no longer ACTIVE (status=" + wallet.getStatus() + ")");
        }

        log.error("OrgMemberWallet id={} was ACTIVE but its confirming block was retracted by a reorg "
                        + "— reverting to PENDING for re-verification.", id);
        wallet.setStatus(MemberWalletStatus.PENDING);
        repository.save(wallet);

        return new CompensationOutcome.Compensated("Reverted OrgMemberWallet " + id + " to PENDING after retraction");
    }
}
