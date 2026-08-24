package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-closed org-identity gate: only a confirmed (ACTIVE) wallet binding to a
 * confirmed, non-suspended org passes. Pending state never passes.
 */
@Component
class PermissionGateImpl implements PermissionGate {

    private final OrgRegistrationRepository registrationRepository;
    private final OrgMemberWalletRepository walletRepository;
    private final ChainQuarantinePort chainQuarantine;

    PermissionGateImpl(OrgRegistrationRepository registrationRepository,
                       OrgMemberWalletRepository walletRepository,
                       ChainQuarantinePort chainQuarantine) {
        this.registrationRepository = registrationRepository;
        this.walletRepository = walletRepository;
        this.chainQuarantine = chainQuarantine;
    }

    @Override
    public boolean isWalletBoundToEntity(String walletAddress, UUID legalEntityId, UUID chainConfigId) {
        return entityForWallet(walletAddress, chainConfigId)
                .map(legalEntityId::equals)
                .orElse(false);
    }

    @Override
    public boolean isOrgActive(UUID legalEntityId, UUID chainConfigId) {
        if (chainQuarantine.findActive(chainConfigId).isPresent()) return false;
        return registrationRepository.findByLegalEntityIdAndChainConfigId(legalEntityId, chainConfigId)
                .map(reg -> reg.getStatus() == OrgRegistrationStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    public Optional<UUID> entityForWallet(String walletAddress, UUID chainConfigId) {
        if (chainQuarantine.findActive(chainConfigId).isPresent()) return Optional.empty();
        return walletRepository.findLiveBinding(chainConfigId, walletAddress)
                .filter(wallet -> wallet.getStatus() == MemberWalletStatus.ACTIVE)
                .flatMap(wallet -> registrationRepository.findById(wallet.getOrgRegistrationId()))
                .filter(reg -> reg.getStatus() == OrgRegistrationStatus.ACTIVE)
                .map(reg -> reg.getLegalEntityId());
    }
}
