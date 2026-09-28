package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.RegisterHandoverInfoPort;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransferRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Answers {@link RegisterHandoverInfoPort} from the completed handover record (T3-07). */
@Component
class RegisterHandoverInfoPortImpl implements RegisterHandoverInfoPort {

    private final RegisterTransferRepository repository;

    RegisterHandoverInfoPortImpl(RegisterTransferRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Handover> completedHandover(UUID assetId) {
        return repository.findFirstByAssetIdAndStatusOrderByCompletedAtDesc(assetId, TransferStatus.COMPLETED)
                .map(t -> new Handover(t.getSuccessorName(), t.getCompletedAt()));
    }
}
