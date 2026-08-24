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
        boolean pendingRemovalIntent = wallet.getStatus() == MemberWalletStatus.REMOVAL_PENDING
                && wallet.getRemovedAt() != null
                && wallet.getRemovedChainConfigId() == null
                && wallet.getRemovedBlockNumber() == null
                && wallet.getRemovedBlockHash() == null;
        if (wallet.getStatus() != MemberWalletStatus.ACTIVE && !pendingRemovalIntent) {
            return new CompensationOutcome.NotApplicable(
                    "OrgMemberWallet " + id + " is neither ACTIVE nor awaiting removal (status="
                            + wallet.getStatus() + ")");
        }
        if (!ChainEffectCausality.matches(effect, wallet.getChainConfigId(), wallet.getBoundTx(),
                wallet.getBoundBlockNumber(), wallet.getBoundBlockHash())) {
            return new CompensationOutcome.NotApplicable(
                    "OrgMemberWallet " + id + " is owned by a different confirmation incarnation");
        }

        log.error("OrgMemberWallet id={} binding confirmation was retracted by a reorg (status={}) "
                        + "— clearing binding provenance while preserving any fail-closed removal intent.",
                id, wallet.getStatus());
        if (!pendingRemovalIntent) {
            wallet.setStatus(MemberWalletStatus.PENDING);
        }
        wallet.setBoundBlockNumber(null);
        wallet.setBoundBlockHash(null);
        repository.save(wallet);

        return new CompensationOutcome.Compensated(
                "Cleared OrgMemberWallet " + id + " binding confirmation after retraction");
    }
}
