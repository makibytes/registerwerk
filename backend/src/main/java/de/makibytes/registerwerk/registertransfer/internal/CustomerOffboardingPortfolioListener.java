package de.makibytes.registerwerk.registertransfer.internal;

import de.makibytes.registerwerk.customer.events.CustomerOffboardedEvent;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reacts to {@link CustomerOffboardedEvent} by raising a {@code PortfolioMigrationRequest} for
 * every holding the exiting entity has, as investor, across every asset — giving the operator a
 * concrete per-position checklist instead of no signal at all that these holdings need to move
 * somewhere. The destination registrar/wallet must still be supplied by the operator
 * ({@code PortfolioMigrationService.setDestination}) before it can be exported — this listener
 * only raises the request, it does not (and cannot) decide where the holding goes.
 */
@Component
class CustomerOffboardingPortfolioListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerOffboardingPortfolioListener.class);

    private final AssetHolderRepository holderRepository;
    private final PortfolioMigrationService migrationService;

    CustomerOffboardingPortfolioListener(AssetHolderRepository holderRepository,
                                         PortfolioMigrationService migrationService) {
        this.holderRepository = holderRepository;
        this.migrationService = migrationService;
    }

    @ApplicationModuleListener
    void onCustomerOffboarded(CustomerOffboardedEvent event) {
        // A removed holding is already closed out of the register — nothing to migrate.
        List<AssetHolder> holdings = holderRepository.findActiveByInvestorId(event.entityId());
        int raised = 0;
        for (AssetHolder holder : holdings) {
            if (holder.getNominalAmount() == null || holder.getNominalAmount().signum() <= 0) {
                continue;
            }
            try {
                migrationService.initiate(holder.getId(), "Customer offboarded: " + event.reason(), event.actorId());
                raised++;
            } catch (ComplianceGateException blocked) {
                // Caught separately from the plain IllegalStateException below (which
                // ComplianceGateException extends) — a §16 Sperrvermerk block is a
                // compliance-relevant reason the migration was NOT raised, not a benign
                // "already in progress" duplicate, and must not be silently downgraded to
                // a debug log an operator would never see.
                log.warn("Portfolio migration NOT raised for holder={} (offboarded entity={}): {}",
                        holder.getId(), event.entityId(), blocked.getMessage());
            } catch (IllegalStateException alreadyInProgress) {
                log.debug("Portfolio migration already in progress for holder={}", holder.getId());
            }
        }
        if (raised > 0) {
            log.warn("Raised {} portfolio-migration request(s) for offboarded entity={} — operator must "
                            + "supply each destination registrar/wallet before it can be exported.",
                    raised, event.entityId());
        }
    }
}
