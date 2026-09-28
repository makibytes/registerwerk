package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.events.AssetTermsAmendedEvent;
import de.makibytes.registerwerk.asset.web.dto.TermsAmendmentRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import de.makibytes.registerwerk.shared.IsinValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Amends the economic terms of an asset after approval (T3-10). The controller enforces step-up
 * plus a second REGISTRY_ADMIN approver; this service applies asset and bond-term changes in one
 * transaction, keeps {@code Asset.issueDate/maturityDate} in sync with the bond terms, regenerates
 * the coupon schedule (future, not-yet-raised periods only) and audits before/after values.
 *
 * <p>Which amendments are legally permitted after issuance (SchVG creditor resolutions, issuer
 * corrections) is a parked policy question; the interim control is "locked, except for an
 * operator 4-eyes amendment with a stated legal basis".
 */
@Service
@Transactional
public class TermsAmendmentService {

    private static final Logger log = LoggerFactory.getLogger(TermsAmendmentService.class);

    private final AssetRepository assetRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final AssetDeploymentRepository deploymentRepository;
    private final CouponScheduleService couponScheduleService;
    private final ApplicationEventPublisher events;

    public TermsAmendmentService(AssetRepository assetRepository,
                                 AssetBondTermsRepository bondTermsRepository,
                                 AssetDeploymentRepository deploymentRepository,
                                 CouponScheduleService couponScheduleService,
                                 ApplicationEventPublisher events) {
        this.assetRepository = assetRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.deploymentRepository = deploymentRepository;
        this.couponScheduleService = couponScheduleService;
        this.events = events;
    }

    @CacheEvict(value = "assets", key = "#assetId")
    public Asset amend(UUID assetId, TermsAmendmentRequest request, UUID actorId, String actorRole,
                       UUID dualControlApproverId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (asset.getStatus() == AssetStatus.REDEEMED || asset.getStatus().isRegisterFrozen()) {
            throw new InvalidStateTransitionException("Terms of a " + asset.getStatus() + " asset cannot be amended");
        }
        AssetBondTerms terms = bondTermsRepository.findById(assetId).orElse(null);
        if (terms == null && request.touchesBondTerms()
                && (request.faceValue() != null || request.couponRate() != null || request.referenceRate() != null
                    || request.spread() != null || request.dayCount() != null || request.paymentFrequency() != null
                    || request.businessDayConvention() != null || request.recordDateOffsetBd() != null
                    || request.announcementLeadBd() != null || request.interestGraceDays() != null
                    || request.principalGraceDays() != null)) {
            throw new IllegalArgumentException("Asset has no bond terms to amend");
        }
        refuseLedgerFixedTerms(asset, terms, request);

        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        Change c = new Change(before, after);

        if (request.isin() != null) {
            String isin = request.isin().isBlank() ? null : IsinValidator.validateOrThrow(request.isin());
            c.apply("isin", asset.getIsin(), isin, asset::setIsin);
        }
        if (request.currency() != null) {
            c.apply("currency", asset.getCurrency(), request.currency().toUpperCase(Locale.ROOT), asset::setCurrency);
        }
        c.apply("issueSize", asset.getIssueSize(), request.issueSize(), asset::setIssueSize);
        c.apply("denomination", asset.getDenomination(), request.denomination(), asset::setDenomination);
        c.apply("minInvestmentAmount", asset.getMinInvestmentAmount(), request.minInvestmentAmount(), asset::setMinInvestmentAmount);
        c.apply("maxHoldingAmount", asset.getMaxHoldingAmount(), request.maxHoldingAmount(), asset::setMaxHoldingAmount);
        c.apply("issueDate", asset.getIssueDate(), request.issueDate(), asset::setIssueDate);
        c.apply("maturityDate", asset.getMaturityDate(), request.maturityDate(), asset::setMaturityDate);

        boolean bondChanged = false;
        if (terms != null) {
            int changesBefore = after.size();
            // Dates live on both; the bond terms are authoritative for the schedule and the
            // maturity job, the asset copy is kept in sync (no second source of truth).
            c.apply("bondTerms.issueDate", terms.getIssueDate(), request.issueDate(), terms::setIssueDate);
            c.apply("bondTerms.maturityDate", terms.getMaturityDate(), request.maturityDate(), terms::setMaturityDate);
            c.apply("faceValue", terms.getFaceValue(), request.faceValue(), terms::setFaceValue);
            c.apply("couponRate", terms.getCouponRate(), request.couponRate(), terms::setCouponRate);
            if (request.referenceRate() != null) {
                String ref = request.referenceRate().isBlank() ? null : request.referenceRate().trim();
                c.apply("referenceRate", terms.getReferenceRate(), ref, terms::setReferenceRate);
            }
            c.apply("spread", terms.getSpread(), request.spread(), terms::setSpread);
            c.apply("dayCount", terms.getDayCount(), request.dayCount(), terms::setDayCount);
            c.apply("paymentFrequency", terms.getPaymentFrequency(), request.paymentFrequency(), terms::setPaymentFrequency);
            c.apply("businessDayConvention", terms.getBusinessDayConvention(), request.businessDayConvention(),
                    terms::setBusinessDayConvention);
            c.apply("recordDateOffsetBd", terms.getRecordDateOffsetBd(), request.recordDateOffsetBd(), terms::setRecordDateOffsetBd);
            c.apply("announcementLeadBd", terms.getAnnouncementLeadBd(), request.announcementLeadBd(), terms::setAnnouncementLeadBd);
            c.apply("interestGraceDays", terms.getInterestGraceDays(), request.interestGraceDays(), terms::setInterestGraceDays);
            c.apply("principalGraceDays", terms.getPrincipalGraceDays(), request.principalGraceDays(), terms::setPrincipalGraceDays);
            bondChanged = after.size() > changesBefore;
            if (!terms.getMaturityDate().isAfter(terms.getIssueDate())) {
                throw new IllegalArgumentException("maturityDate must be after issueDate");
            }
        }
        LocalDate assetIssue = asset.getIssueDate();
        LocalDate assetMaturity = asset.getMaturityDate();
        if (assetIssue != null && assetMaturity != null && !assetMaturity.isAfter(assetIssue)) {
            throw new IllegalArgumentException("maturityDate must be after issueDate");
        }
        if (after.isEmpty()) {
            throw new IllegalArgumentException("The amendment changes nothing — every value equals the current term");
        }

        Asset saved = assetRepository.save(asset);
        // Mapped to AssetResponse after this transaction closes (open-in-view is off).
        org.hibernate.Hibernate.initialize(saved.getTargetMarketCategories());
        if (bondChanged) {
            bondTermsRepository.save(terms);
        }
        events.publishEvent(new AssetTermsAmendedEvent(assetId, actorId, actorRole, dualControlApproverId,
                request.legalReference().trim(), before, after));
        if (bondChanged) {
            couponScheduleService.regenerate(assetId, actorId, actorRole, CouponScheduleService.TRIGGER_TERMS_AMENDMENT);
        }
        log.info("Terms amended for asset={} by {} (second approver {}): {}", assetId, actorId,
                dualControlApproverId, after.keySet());
        return saved;
    }

    /**
     * Canton (DAML_BOND_*) bonds fix face value, coupon and maturity in the ledger instrument at
     * deploy time. Until a ledger amendment flow exists, the register must not diverge from it.
     */
    private void refuseLedgerFixedTerms(Asset asset, AssetBondTerms terms, TermsAmendmentRequest request) {
        if (asset.getTokenStandard() == null || !asset.getTokenStandard().name().startsWith("DAML_BOND_")) {
            return;
        }
        boolean deployed = deploymentRepository.findByAssetId(asset.getId()).stream()
                .anyMatch(d -> d.getDeploymentStatus() != AssetDeployment.DeploymentStatus.FAILED);
        if (!deployed) {
            return;
        }
        boolean touchesLedger = (request.faceValue() != null && (terms == null || differs(request.faceValue(), terms.getFaceValue())))
                || (request.couponRate() != null && (terms == null || differs(request.couponRate(), terms.getCouponRate())))
                || (request.maturityDate() != null && !request.maturityDate().equals(
                        terms != null ? terms.getMaturityDate() : asset.getMaturityDate()));
        if (touchesLedger) {
            throw new InvalidStateTransitionException("Face value, coupon rate and maturity of a deployed Canton bond "
                    + "are fixed in the ledger instrument; amend them on the ledger first");
        }
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return b == null || a.compareTo(b) != 0;
    }

    /** Collects before/after values of fields whose requested value differs from the current one. */
    private record Change(Map<String, String> before, Map<String, String> after) {
        <T> void apply(String field, T current, T requested, Consumer<T> setter) {
            if (requested == null || same(current, requested)) {
                return;
            }
            before.put(field, current == null ? null : render(current));
            after.put(field, render(requested));
            setter.accept(requested);
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
                return x.compareTo(y) == 0;
            }
            return Objects.equals(a, b);
        }

        private static String render(Object v) {
            return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : v.toString();
        }
    }
}
