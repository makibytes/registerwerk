package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.Erc3643Api;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class Erc3643ApiImpl implements Erc3643Api {

    private final Erc3643SuiteRepository suiteRepository;
    private final OnChainIdService onChainIdService;
    private final IdentityRegistryService identityRegistryService;

    Erc3643ApiImpl(Erc3643SuiteRepository suiteRepository, OnChainIdService onChainIdService,
                   IdentityRegistryService identityRegistryService) {
        this.suiteRepository = suiteRepository;
        this.onChainIdService = onChainIdService;
        this.identityRegistryService = identityRegistryService;
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
}
