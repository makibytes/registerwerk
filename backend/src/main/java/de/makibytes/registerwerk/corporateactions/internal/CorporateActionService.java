package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionSettlementRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates corporate-action lifecycle.
 * Settlement dispatch is token-standard-specific:
 * ERC-3525 → Erc3525AdminService, ERC-4626/7540 → vault NAV strike,
 * DAML bonds → CantonBondOperations.payCoupon, SPL-2022 → SolanaTokenService.
 */
@Service
@Transactional
public class CorporateActionService {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionService.class);

    private final CorporateActionRepository repository;
    private final ApplicationEventPublisher events;

    CorporateActionService(CorporateActionRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    public CorporateAction announce(CorporateAction action) {
        action.setStatus(CorporateAction.Status.ANNOUNCED);
        CorporateAction saved = repository.save(action);
        log.info("Corporate action announced: id={} type={} assetId={}", saved.getId(), saved.getActionType(), saved.getAssetId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<CorporateAction> findByAsset(UUID assetId) {
        return repository.findByAssetId(assetId);
    }

    /** Daily job: transition ANNOUNCED → RECORD_DATE_SET → COMPUTED when dates are reached. */
    @Scheduled(cron = "0 0 6 * * *")
    public void processDailyTransitions() {
        LocalDate today = LocalDate.now();

        List<CorporateAction> ready = repository.findReadyToCompute(today);
        for (CorporateAction ca : ready) {
            try {
                ca.setStatus(CorporateAction.Status.RECORD_DATE_SET);
                repository.save(ca);
                log.info("Corporate action record date set: id={}", ca.getId());
            } catch (Exception e) {
                log.error("Failed to advance corporate action {}: {}", ca.getId(), e.getMessage());
            }
        }

        List<CorporateAction> due = repository.findDueForSettlement(today);
        for (CorporateAction ca : due) {
            try {
                settle(ca);
            } catch (Exception e) {
                log.error("Settlement failed for corporate action {}: {}", ca.getId(), e.getMessage());
            }
        }
    }

    private void settle(CorporateAction ca) {
        log.info("Settling corporate action: id={} type={} assetId={}", ca.getId(), ca.getActionType(), ca.getAssetId());
        ca.setStatus(CorporateAction.Status.AWAITING_SETTLEMENT);
        repository.save(ca);
        // Dispatch is handled by CorporateActionSettlementListener in the blockchain module,
        // which looks up the asset's token standard and calls the appropriate chain service.
        events.publishEvent(new CorporateActionSettlementRequestedEvent(ca.getId(), ca.getAssetId(), ca.getActionType()));
    }
}
