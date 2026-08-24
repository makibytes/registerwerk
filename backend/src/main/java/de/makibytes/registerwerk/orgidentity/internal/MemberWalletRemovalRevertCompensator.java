package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.springframework.stereotype.Component;

/** Returns an orphaned, confirmed member removal to fail-closed receipt verification. */
@Component
class MemberWalletRemovalRevertCompensator implements ChainEffectCompensator {

    static final String EFFECT_TYPE = "MEMBER_WALLET_REMOVED";
    private final OrgMemberWalletRepository repository;

    MemberWalletRemovalRevertCompensator(OrgMemberWalletRepository repository) {
        this.repository = repository;
    }

    @Override public String effectType() { return EFFECT_TYPE; }
    @Override public CompensationCategory category() { return CompensationCategory.INVERSE_FLIP; }

    @Override
    public CompensationOutcome compensate(ChainEffectRecord effect) {
        var wallet = repository.findById(effect.entityId()).orElse(null);
        if (wallet == null) return new CompensationOutcome.NotApplicable("OrgMemberWallet no longer exists");
        if (wallet.getStatus() != MemberWalletStatus.REMOVED) {
            return new CompensationOutcome.NotApplicable("Removal no longer owns wallet state");
        }
        if (!ChainEffectCausality.matches(effect, wallet.getRemovedChainConfigId(), wallet.getRemovedTx(),
                wallet.getRemovedBlockNumber(), wallet.getRemovedBlockHash())) {
            return new CompensationOutcome.NotApplicable("Removal is owned by a different incarnation");
        }
        wallet.setStatus(MemberWalletStatus.REMOVAL_PENDING);
        wallet.setRemovedChainConfigId(null);
        wallet.setRemovedBlockNumber(null);
        wallet.setRemovedBlockHash(null);
        repository.save(wallet);
        return new CompensationOutcome.Compensated("Returned member removal to fail-closed verification");
    }
}
