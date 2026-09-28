package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.customer.api.ActiveHoldingsPort;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Answers {@link ActiveHoldingsPort} from the register (T3-14). */
@Component
class ActiveHoldingsPortImpl implements ActiveHoldingsPort {

    private final AssetHolderRepository holderRepository;

    ActiveHoldingsPortImpl(AssetHolderRepository holderRepository) {
        this.holderRepository = holderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveHoldings(UUID entityId) {
        return holderRepository.findActiveByInvestorId(entityId, PageRequest.of(0, 1)).hasContent();
    }
}
