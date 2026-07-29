package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Closes two dead-end {@link BondStatus} values that were declared but never set by any code:
 * {@code MATURED} (a bond reaching its maturity date was never observed anywhere — only
 * {@code CantonBondOperations.redeem}/{@code earlyCall} ever moved a bond to REDEEMED/CALLED,
 * both requiring an operator to have already initiated a corporate action) and {@code DEFAULTED}
 * (no missed-redemption detection existed at all).
 *
 * <p>On maturity: transitions {@code ACTIVE → MATURED} and auto-raises a
 * {@code CorporateAction(REDEMPTION)} at face value — mirroring exactly how
 * {@link CouponPaymentJob} auto-raises {@code COUPON} actions — so the bond's redemption goes
 * through the same dual-control-gated settlement pipeline as everything else (record-date
 * snapshot, COMPUTED, {@code approve-settlement}, settlement, CLOSED) rather than silently
 * skipping approval. The operator still calls the existing {@code POST /assets/{id}/redeem}
 * once satisfied the redemption action settled — this job does not flip {@code Asset.status}
 * itself, only the bond-specific {@code BondStatus} and the triggering corporate action.
 *
 * <p>On an overdue, still-unsettled redemption: transitions {@code MATURED → DEFAULTED} —
 * flagging it for compliance/operator attention rather than leaving no signal at all.
 */
@Component
class BondMaturityJob {

    private static final Logger log = LoggerFactory.getLogger(BondMaturityJob.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetBondTermsRepository bondTermsRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final CorporateActionService corporateActionService;
    private final AssetRepository assetRepository;

    BondMaturityJob(AssetBondTermsRepository bondTermsRepository,
                     CorporateActionRepository corporateActionRepository,
                     CorporateActionService corporateActionService,
                     AssetRepository assetRepository) {
        this.bondTermsRepository = bondTermsRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.corporateActionService = corporateActionService;
        this.assetRepository = assetRepository;
    }

    @Transactional
    @SchedulerLock(name = "bondMaturityJob", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 30 6 * * *")
    public void processMaturitiesAndDefaults() {
        LocalDate today = LocalDate.now();
        List<AssetBondTerms> matured = bondTermsRepository.findMaturedButNotTransitioned(today);

        for (AssetBondTerms terms : matured) {
            try {
                terms.setBondStatus(BondStatus.MATURED);
                bondTermsRepository.save(terms);
                log.info("Bond matured: assetId={} maturityDate={}", terms.getAssetId(), terms.getMaturityDate());

                if (isTransferredOut(terms.getAssetId())) {
                    log.info("Bond assetId={} matured but its register was transferred to a successor operator — "
                            + "not auto-raising a redemption action here.", terms.getAssetId());
                } else if (!corporateActionRepository.existsActiveRedemptionForAsset(terms.getAssetId())) {
                    raiseRedemptionAction(terms, today);
                }
            } catch (Exception e) {
                log.error("Failed to process maturity for bond assetId={}: {}", terms.getAssetId(), e.getMessage());
            }
        }

        // Default detection: any MATURED bond whose redemption action is overdue and still unsettled.
        for (AssetBondTerms terms : bondTermsRepository.findByBondStatus(BondStatus.MATURED)) {
            try {
                List<CorporateAction> overdue = corporateActionRepository.findOverdueRedemptions(terms.getAssetId(), today);
                if (!overdue.isEmpty()) {
                    terms.setBondStatus(BondStatus.DEFAULTED);
                    bondTermsRepository.save(terms);
                    log.warn("Bond defaulted: assetId={} — redemption action(s) {} overdue and unsettled",
                            terms.getAssetId(), overdue.stream().map(CorporateAction::getId).toList());
                }
            } catch (Exception e) {
                log.error("Failed to evaluate default status for bond assetId={}: {}", terms.getAssetId(), e.getMessage());
            }
        }
    }

    private boolean isTransferredOut(UUID assetId) {
        return assetRepository.findById(assetId)
                .map(asset -> asset.getStatus() == AssetStatus.TRANSFERRED_OUT)
                .orElse(false);
    }

    private void raiseRedemptionAction(AssetBondTerms terms, LocalDate today) {
        CorporateAction action = new CorporateAction();
        action.setAssetId(terms.getAssetId());
        action.setActionType(CorporateAction.ActionType.REDEMPTION);
        action.setAnnouncementDate(today);
        action.setRecordDate(terms.getMaturityDate());
        action.setPaymentDate(terms.getMaturityDate());
        action.setAmountPerUnit(terms.getFaceValue());
        action.setCurrency(terms.getCurrencyIso());
        action.setInitiatedBy(SYSTEM_ACTOR);
        action.setNotes("Auto-created on bond maturity (assetId=" + terms.getAssetId() + ")");
        corporateActionService.announce(action);
        log.info("Redemption corporate action raised for matured bond: assetId={}", terms.getAssetId());
    }
}
