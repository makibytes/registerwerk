package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.erc3643.api.Erc3643MintPort;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Component
class Erc3643MintPortImpl implements Erc3643MintPort {

    private final Erc3643SuiteRepository suiteRepository;
    private final Erc3643LifecycleService lifecycleService;

    Erc3643MintPortImpl(Erc3643SuiteRepository suiteRepository, Erc3643LifecycleService lifecycleService) {
        this.suiteRepository = suiteRepository;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public UUID mint(UUID deploymentId, String toAddress, BigDecimal amount, UUID actorId, String actorRole) {
        Erc3643Suite suite = suiteRepository.findByAssetDeploymentId(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("Erc3643Suite", deploymentId));
        return lifecycleService.batchMint(suite.getId(), List.of(toAddress), List.of(amount), actorId, actorRole);
    }
}
