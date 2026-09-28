package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.asset.events.AssetUpdatedEvent;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.deployment.api.DayCountConvention;
import de.makibytes.registerwerk.deployment.api.schedule.BusinessDayConvention;
import de.makibytes.registerwerk.deployment.api.schedule.HolidayCalendar;
import de.makibytes.registerwerk.deployment.api.schedule.StubRule;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class BondTermsService {

    private final AssetRepository assetRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final ApplicationEventPublisher events;
    private final CouponScheduleService couponScheduleService;

    /** Statuses in which terms may still be set wholesale; afterwards only a 4-eyes amendment. */
    static final EnumSet<AssetStatus> TERMS_EDITABLE =
            EnumSet.of(AssetStatus.DRAFT, AssetStatus.PENDING_APPROVAL, AssetStatus.APPROVED);

    public BondTermsService(AssetRepository assetRepository,
                            AssetBondTermsRepository bondTermsRepository,
                            ApplicationEventPublisher events,
                            CouponScheduleService couponScheduleService) {
        this.assetRepository = assetRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.events = events;
        this.couponScheduleService = couponScheduleService;
    }

    /**
     * Creates or replaces the bond terms and regenerates the coupon schedule. Only allowed until
     * the asset is issued (T3-10): after issuance the economic terms of outstanding securities
     * change only through {@code TermsAmendmentService} (step-up + second approver, before/after
     * audit).
     */
    public AssetBondTerms upsert(UUID assetId, BondTermsRequest request, UUID actorId, String actorRole) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));
        if (!TERMS_EDITABLE.contains(asset.getStatus())) {
            throw new InvalidStateTransitionException("Bond terms of a " + asset.getStatus()
                    + " asset are locked; use POST /api/v1/assets/" + assetId + "/terms-amendments");
        }

        AssetBondTerms terms = bondTermsRepository.findById(assetId).orElseGet(() -> {
            AssetBondTerms created = new AssetBondTerms();
            created.setAssetId(assetId);
            created.setBondStatus(BondStatus.ACTIVE);
            return created;
        });
        terms.setFaceValue(request.faceValue());
        terms.setCurrencyIso(request.currencyIso().trim().toUpperCase(Locale.ROOT));
        terms.setIssueDate(request.issueDate());
        terms.setMaturityDate(request.maturityDate());
        terms.setCouponRate(request.couponRate());
        terms.setReferenceRate(trimToNull(request.referenceRate()));
        terms.setSpread(request.spread());
        terms.setDayCount(request.dayCount() != null ? request.dayCount() : DayCountConvention.ACT_ACT_ICMA);
        terms.setPaymentFrequency(request.paymentFrequency());
        terms.setCallable(request.callable());
        terms.setCallSchedule(request.callSchedule() == null ? null : request.callSchedule().stream()
                .map(entry -> Map.<String, Object>of(
                        "callDate", entry.callDate().toString(),
                        "callPrice", entry.callPrice()))
                .toList());
        terms.setBusinessDayConvention(request.businessDayConvention() != null
                ? request.businessDayConvention() : BusinessDayConvention.MODIFIED_FOLLOWING);
        terms.setHolidayCalendar(request.holidayCalendar() != null ? request.holidayCalendar() : HolidayCalendar.TARGET2);
        terms.setRecordDateOffsetBd(request.recordDateOffsetBd() != null ? request.recordDateOffsetBd() : 1);
        terms.setAnnouncementLeadBd(request.announcementLeadBd() != null ? request.announcementLeadBd() : 5);
        terms.setInterestGraceDays(request.interestGraceDays() != null ? request.interestGraceDays() : 30);
        terms.setPrincipalGraceDays(request.principalGraceDays() != null ? request.principalGraceDays() : 7);
        terms.setStubRule(request.stubRule() != null ? request.stubRule() : StubRule.SHORT_FIRST);

        AssetBondTerms saved = bondTermsRepository.save(terms);
        events.publishEvent(new AssetUpdatedEvent(assetId, actorId, actorRole));
        couponScheduleService.regenerate(assetId, actorId, actorRole, CouponScheduleService.TRIGGER_BOND_TERMS);
        return saved;
    }

    @Transactional(readOnly = true)
    public AssetBondTerms get(UUID assetId) {
        return bondTermsRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("AssetBondTerms", assetId));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
