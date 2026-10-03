package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.BondRedemptionBlockedEvent;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.schedule.BusinessDayCalendar;
import de.makibytes.registerwerk.deployment.api.schedule.BusinessDayConvention;
import de.makibytes.registerwerk.deployment.api.schedule.HolidayCalendar;
import de.makibytes.registerwerk.customer.api.EntityTaskPort;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.shared.RegisterClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives a bond's principal lifecycle: the REDEMPTION corporate action, the {@code MATURED}
 * transition, and OVERDUE/DEFAULTED detection.
 *
 * <p>Redemption (T3-05): the {@code CorporateAction(REDEMPTION)} at face value is raised at its
 * <em>announcement date</em> — payment date = maturity adjusted by the bond's business-day
 * convention, record date = payment date − {@code recordDateOffsetBd}, announcement = record date −
 * {@code announcementLeadBd} (business days on the bond's calendar) — so the snapshot, the issuer
 * attestation and the operator confirmation can all happen before the payment date. Raising it on
 * the maturity day itself, as before, made every non-Canton bond DEFAULTED the next morning. The
 * redemption then goes through the same dual-control-gated settlement pipeline as everything else.
 * The operator still calls {@code POST /assets/{id}/redeem} once the redemption settled — this job
 * never flips {@code Asset.status}.
 *
 * <p>{@code ACTIVE → MATURED} stays on the (unadjusted) maturity date. A bond whose CALL settled is
 * retired ({@code CALLED}, set by the settlement writer) and gets no maturity redemption.
 *
 * <p>Default detection: an unsettled redemption past its payment date makes the bond
 * {@code OVERDUE} (operator-visible; customers see "payment pending"); only after the principal
 * grace period ({@code principalGraceDays}) does it become {@code DEFAULTED}. Settlement moves
 * either straight to {@code REDEEMED}.
 *
 * <p>H6: a redemption the REGISTRY side blocks (record-date snapshot blocked, register frozen or handed over,
 * settlement held by the system - see {@link CorporateActionBlocks}) is not the issuer's non-payment: it never
 * counts toward OVERDUE / DEFAULTED. The bond is left as it is, the cause is audited once
 * ({@link BondRedemptionBlockedEvent}) and an operator task is raised on the issuer.
 *
 * <p>H8: the job holds no transaction of its own; every bond is handled in its own {@code REQUIRES_NEW}
 * transaction, so one failing bond cannot roll back the others' transitions.
 */
@Component
class BondMaturityJob {

    private static final Logger log = LoggerFactory.getLogger(BondMaturityJob.class);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssetBondTermsRepository bondTermsRepository;
    private final CorporateActionRepository corporateActionRepository;
    private final CorporateActionService corporateActionService;
    private final AssetRepository assetRepository;
    private final RegisterClock registerClock;
    private final IsolatedTransactionExecutor isolated;
    private final EntityTaskPort entityTasks;
    private final ApplicationEventPublisher events;

    BondMaturityJob(AssetBondTermsRepository bondTermsRepository,
                     CorporateActionRepository corporateActionRepository,
                     CorporateActionService corporateActionService,
                     AssetRepository assetRepository,
                     RegisterClock registerClock,
                     IsolatedTransactionExecutor isolated,
                     EntityTaskPort entityTasks,
                     ApplicationEventPublisher events) {
        this.bondTermsRepository = bondTermsRepository;
        this.corporateActionRepository = corporateActionRepository;
        this.corporateActionService = corporateActionService;
        this.assetRepository = assetRepository;
        this.registerClock = registerClock;
        this.isolated = isolated;
        this.entityTasks = entityTasks;
        this.events = events;
    }

    /** The redemption's dates, derived from the bond's conventions. */
    record RedemptionDates(LocalDate announcementDate, LocalDate recordDate, LocalDate paymentDate) {
    }

    static RedemptionDates redemptionDates(AssetBondTerms terms) {
        BusinessDayCalendar calendar = (terms.getHolidayCalendar() != null
                ? terms.getHolidayCalendar() : HolidayCalendar.TARGET2).calendar();
        BusinessDayConvention bdc = terms.getBusinessDayConvention() != null
                ? terms.getBusinessDayConvention() : BusinessDayConvention.MODIFIED_FOLLOWING;
        LocalDate payment = calendar.adjust(terms.getMaturityDate(), bdc);
        LocalDate record = calendar.addBusinessDays(payment, -terms.getRecordDateOffsetBd());
        LocalDate announce = calendar.addBusinessDays(record, -terms.getAnnouncementLeadBd());
        return new RedemptionDates(announce, record, payment);
    }

    /** 05:45 register time: after CouponPaymentJob (05:30), before the daily transitions (06:00). */
    @SchedulerLock(name = "bondMaturityJob", lockAtMostFor = "PT14M")
    @Scheduled(cron = "0 45 5 * * *", zone = "${registerwerk.register.time-zone:Europe/Berlin}")
    public void processMaturitiesAndDefaults() {
        LocalDate today = registerClock.today();

        for (AssetBondTerms scanned : bondTermsRepository.findMaturedButNotTransitioned(today)) {
            UUID assetId = scanned.getAssetId();
            runItem("maturity", assetId, () -> transitionToMatured(assetId));
        }

        for (BondStatus status : List.of(BondStatus.ACTIVE, BondStatus.MATURED)) {
            for (AssetBondTerms scanned : bondTermsRepository.findByBondStatus(status)) {
                UUID assetId = scanned.getAssetId();
                runItem("raise-redemption", assetId, () -> bondTermsRepository.findById(assetId)
                        .ifPresent(terms -> raiseRedemptionIfAnnounceable(terms, today)));
            }
        }

        for (BondStatus status : List.of(BondStatus.ACTIVE, BondStatus.MATURED, BondStatus.OVERDUE)) {
            for (AssetBondTerms scanned : bondTermsRepository.findByBondStatus(status)) {
                UUID assetId = scanned.getAssetId();
                runItem("default-evaluation", assetId, () -> bondTermsRepository.findById(assetId)
                        .ifPresent(terms -> evaluateOverdue(terms, today)));
            }
        }
    }

    private void runItem(String step, UUID assetId, IsolatedTransactionExecutor.Work work) {
        try {
            isolated.run(work);
        } catch (Exception e) {
            log.error("BondMaturityJob step '{}' failed for bond assetId={} (rolled back on its own; other bonds "
                    + "are unaffected): {}", step, assetId, e.getMessage(), e);
        }
    }

    private void transitionToMatured(UUID assetId) {
        AssetBondTerms terms = bondTermsRepository.findById(assetId).orElse(null);
        if (terms == null) {
            return;
        }
        if (corporateActionRepository.existsSettledCallForAsset(terms.getAssetId())) {
            terms.setBondStatus(BondStatus.CALLED);
            bondTermsRepository.save(terms);
            log.info("Bond assetId={} reached maturity but was already called — marked CALLED.", terms.getAssetId());
            return;
        }
        terms.setBondStatus(BondStatus.MATURED);
        bondTermsRepository.save(terms);
        log.info("Bond matured: assetId={} maturityDate={}", terms.getAssetId(), terms.getMaturityDate());
    }

    private void raiseRedemptionIfAnnounceable(AssetBondTerms terms, LocalDate today) {
        RedemptionDates dates = redemptionDates(terms);
        if (today.isBefore(dates.announcementDate())
                || corporateActionRepository.existsActiveRedemptionForAsset(terms.getAssetId())) {
            return;
        }
        if (corporateActionRepository.existsSettledCallForAsset(terms.getAssetId())) {
            log.info("Bond assetId={} was called — no maturity redemption raised.", terms.getAssetId());
            return;
        }
        if (isTransferredOut(terms.getAssetId())) {
            log.info("Bond assetId={} is due for redemption but its register was transferred to a successor "
                    + "operator — not auto-raising a redemption action here.", terms.getAssetId());
            return;
        }
        CorporateAction action = new CorporateAction();
        action.setAssetId(terms.getAssetId());
        action.setActionType(CorporateAction.ActionType.REDEMPTION);
        action.setAnnouncementDate(today);
        action.setRecordDate(dates.recordDate());
        action.setPaymentDate(dates.paymentDate());
        action.setAmountPerUnit(terms.getFaceValue());
        action.setCurrency(terms.getCurrencyIso());
        action.setInitiatedBy(SYSTEM_ACTOR);
        action.setNotes("Auto-created for bond maturity " + terms.getMaturityDate() + " (assetId=" + terms.getAssetId() + ")");
        corporateActionService.announce(action);
        log.info("Redemption corporate action raised: assetId={} record={} payment={}",
                terms.getAssetId(), dates.recordDate(), dates.paymentDate());
    }

    /**
     * OVERDUE once the payment date has passed unsettled; DEFAULTED only after the grace period. Only redemptions
     * that wait for the ISSUER count (H6): registry-side blocks are reported, not escalated.
     */
    private void evaluateOverdue(AssetBondTerms terms, LocalDate today) {
        List<CorporateAction> overdue = corporateActionRepository.findOverdueRedemptions(terms.getAssetId(), today);
        if (overdue.isEmpty()) {
            return;
        }
        boolean frozen = isTransferredOut(terms.getAssetId());
        List<CorporateAction> issuerSide = new ArrayList<>();
        for (CorporateAction ca : overdue) {
            Optional<String> cause = CorporateActionBlocks.systemBlockCause(ca, frozen);
            if (cause.isPresent()) {
                reportBlocked(terms, ca, cause.get());
            } else {
                issuerSide.add(ca);
            }
        }
        if (issuerSide.isEmpty()) {
            log.warn("Bond assetId={} redemption is past its payment date but blocked on the registry side — status "
                    + "left unchanged ({}), not escalated.", terms.getAssetId(), terms.getBondStatus());
            return;
        }
        LocalDate earliestPayment = issuerSide.stream().map(CorporateAction::getPaymentDate)
                .min(Comparator.naturalOrder()).orElseThrow();
        List<UUID> ids = issuerSide.stream().map(CorporateAction::getId).toList();
        if (today.isAfter(earliestPayment.plusDays(terms.getPrincipalGraceDays()))) {
            terms.setBondStatus(BondStatus.DEFAULTED);
            bondTermsRepository.save(terms);
            log.warn("Bond defaulted: assetId={} — redemption action(s) {} unsettled {} day(s) past payment date {} "
                            + "(principal grace {} day(s))", terms.getAssetId(), ids,
                    today.toEpochDay() - earliestPayment.toEpochDay(), earliestPayment, terms.getPrincipalGraceDays());
        } else if (terms.getBondStatus() != BondStatus.OVERDUE) {
            terms.setBondStatus(BondStatus.OVERDUE);
            bondTermsRepository.save(terms);
            log.warn("Bond redemption overdue: assetId={} — action(s) {} unsettled after payment date {} "
                    + "(inside the {}-day principal grace period)", terms.getAssetId(), ids, earliestPayment,
                    terms.getPrincipalGraceDays());
        }
    }

    /** One operator task per (issuer, action) while it is open; the audit event only when the task is new. */
    private void reportBlocked(AssetBondTerms terms, CorporateAction ca, String cause) {
        UUID issuerId = assetRepository.findById(terms.getAssetId()).map(Asset::getIssuerId).orElse(null);
        boolean fresh = true;
        if (issuerId != null) {
            fresh = entityTasks.open(issuerId, CorporateActionBlocks.TASK_REDEMPTION_BLOCKED, ca.getId().toString(),
                    "The redemption " + ca.getId() + " of asset " + terms.getAssetId() + " (payment date "
                            + ca.getPaymentDate() + ") is blocked on the registry side — " + cause
                            + ". The bond is NOT marked overdue or defaulted.", null);
        }
        if (fresh) {
            events.publishEvent(new BondRedemptionBlockedEvent(terms.getAssetId(), ca.getId(), cause));
        }
    }

    private boolean isTransferredOut(UUID assetId) {
        return assetRepository.findById(assetId)
                .map(asset -> asset.getStatus() != null && asset.getStatus().isRegisterFrozen())
                .orElse(false);
    }
}
