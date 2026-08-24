package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetUpdatedEvent;
import de.makibytes.registerwerk.asset.web.dto.BondTermsRequest;
import de.makibytes.registerwerk.deployment.api.AssetBondTerms;
import de.makibytes.registerwerk.deployment.api.AssetBondTermsRepository;
import de.makibytes.registerwerk.deployment.api.BondStatus;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class BondTermsService {

    private final AssetRepository assetRepository;
    private final AssetBondTermsRepository bondTermsRepository;
    private final ApplicationEventPublisher events;

    public BondTermsService(AssetRepository assetRepository,
                            AssetBondTermsRepository bondTermsRepository,
                            ApplicationEventPublisher events) {
        this.assetRepository = assetRepository;
        this.bondTermsRepository = bondTermsRepository;
        this.events = events;
    }

    public AssetBondTerms upsert(UUID assetId, BondTermsRequest request, UUID actorId, String actorRole) {
        assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset", assetId));

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
        terms.setDayCount(request.dayCount());
        terms.setPaymentFrequency(request.paymentFrequency());
        terms.setCallable(request.callable());
        terms.setCallSchedule(request.callSchedule() == null ? null : request.callSchedule().stream()
                .map(entry -> Map.<String, Object>of(
                        "callDate", entry.callDate().toString(),
                        "callPrice", entry.callPrice()))
                .toList());

        AssetBondTerms saved = bondTermsRepository.save(terms);
        events.publishEvent(new AssetUpdatedEvent(assetId, actorId, actorRole));
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
