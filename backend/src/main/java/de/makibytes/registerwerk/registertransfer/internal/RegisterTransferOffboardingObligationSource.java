package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.registertransfer.api.PortfolioMigrationRequestRepository;
import de.makibytes.registerwerk.registertransfer.api.RegisterTransferRepository;
import de.makibytes.registerwerk.registertransfer.api.TransferStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** In-flight portfolio migrations of the entity and register transfers of assets it issued (6-23). */
@Component
class RegisterTransferOffboardingObligationSource implements OffboardingObligationSource {

    private static final List<TransferStatus> TERMINAL = List.of(TransferStatus.COMPLETED, TransferStatus.CANCELLED);

    private final PortfolioMigrationRequestRepository migrations;
    private final RegisterTransferRepository transfers;
    private final AssetRepository assets;

    RegisterTransferOffboardingObligationSource(PortfolioMigrationRequestRepository migrations,
                                                RegisterTransferRepository transfers, AssetRepository assets) {
        this.migrations = migrations;
        this.transfers = transfers;
        this.assets = assets;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        List<OffboardingObligation> result = new ArrayList<>();
        migrations.findByInvestorEntityIdOrderByInitiatedAtDesc(entityId).stream()
                .filter(m -> !TERMINAL.contains(m.getStatus()))
                .forEach(m -> result.add(new OffboardingObligation("PORTFOLIO_MIGRATION_OPEN", m.getId().toString(),
                        "Portfolio migration " + m.getId() + " is " + m.getStatus())));
        assets.findByIssuerId(entityId).forEach(a ->
                transfers.findFirstByAssetIdAndStatusNotInOrderByInitiatedAtDesc(a.getId(), TERMINAL)
                        .ifPresent(t -> result.add(new OffboardingObligation("REGISTER_TRANSFER_OPEN",
                                t.getId().toString(), "Register transfer " + t.getId() + " of asset "
                                        + a.getId() + " is " + t.getStatus()))));
        return result;
    }
}
