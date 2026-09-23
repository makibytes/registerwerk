package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class Erc3643ApiImpl implements Erc3643Api {

    private final Erc3643SuiteRepository suiteRepository;
    private final OnChainIdService onChainIdService;
    private final IdentityRegistryService identityRegistryService;
    private final ClaimIssuanceService claimIssuanceService;
    private final OnchainIdentityRepository identityRepository;

    Erc3643ApiImpl(Erc3643SuiteRepository suiteRepository, OnChainIdService onChainIdService,
                   IdentityRegistryService identityRegistryService, ClaimIssuanceService claimIssuanceService,
                   OnchainIdentityRepository identityRepository) {
        this.suiteRepository = suiteRepository;
        this.onChainIdService = onChainIdService;
        this.identityRegistryService = identityRegistryService;
        this.claimIssuanceService = claimIssuanceService;
        this.identityRepository = identityRepository;
    }

    @Override
    public Optional<Erc3643Suite> findSuiteByDeployment(UUID deploymentId) {
        return suiteRepository.findByAssetDeploymentId(deploymentId);
    }

    @Override
    public OnchainIdentity getOrCreateIdentity(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole) {
        return onChainIdService.getOrCreate(legalEntityId, chainConfigId, actorId, actorRole);
    }

    @Override
    public Optional<OnchainIdentity> findIdentity(UUID legalEntityId, UUID chainConfigId) {
        return onChainIdService.findIdentity(legalEntityId, chainConfigId);
    }

    @Override
    public boolean isWalletVerified(UUID suiteId, String walletAddress) {
        return identityRegistryService.isVerified(suiteId, walletAddress);
    }

    @Override
    public List<UUID> identityChainIds(UUID legalEntityId) {
        return identityRepository.findByLegalEntityId(legalEntityId).stream()
                .map(OnchainIdentity::getChainConfigId)
                .distinct()
                .toList();
    }

    @Override
    public int revokeComplianceClaims(UUID legalEntityId, UUID chainConfigId, UUID actorId, String actorRole,
                                      Map<String, Object> auditDetails) {
        return claimIssuanceService.revokeComplianceClaims(legalEntityId, chainConfigId, actorId, actorRole,
                auditDetails);
    }
}
