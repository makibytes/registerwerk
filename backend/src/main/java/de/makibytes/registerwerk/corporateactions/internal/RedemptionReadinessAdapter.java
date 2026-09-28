package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.RedemptionReadinessPort;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntry;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionEntryRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers {@link RedemptionReadinessPort} for the {@code asset} module (T3-01) — dependency
 * inversion, since {@code corporateactions} depends on {@code asset} and not the other way round.
 */
@Component
class RedemptionReadinessAdapter implements RedemptionReadinessPort {

    private static final Set<CorporateAction.ActionType> RETIREMENT_TYPES =
            EnumSet.of(CorporateAction.ActionType.REDEMPTION, CorporateAction.ActionType.CALL);
    private static final Set<CorporateAction.Status> SETTLED =
            EnumSet.of(CorporateAction.Status.SETTLED, CorporateAction.Status.CLOSED);
    private static final Set<CorporateAction.Status> OPEN = EnumSet.of(
            CorporateAction.Status.ANNOUNCED, CorporateAction.Status.SNAPSHOT_BLOCKED,
            CorporateAction.Status.RECORD_DATE_SET, CorporateAction.Status.COMPUTED,
            CorporateAction.Status.AWAITING_SETTLEMENT);

    private final CorporateActionRepository actionRepository;
    private final CorporateActionEntryRepository entryRepository;

    RedemptionReadinessAdapter(CorporateActionRepository actionRepository,
                               CorporateActionEntryRepository entryRepository) {
        this.actionRepository = actionRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SettledRetirement> settledRetirementAction(UUID assetId) {
        List<CorporateAction> candidates = actionRepository.findByAssetId(assetId).stream()
                .filter(ca -> RETIREMENT_TYPES.contains(ca.getActionType()))
                .filter(ca -> SETTLED.contains(ca.getStatus()))
                .sorted(Comparator.comparing(
                        (CorporateAction ca) -> ca.getSettledAt() != null ? ca.getSettledAt() : Instant.EPOCH)
                        .reversed())
                .toList();
        for (CorporateAction ca : candidates) {
            List<CorporateActionEntry> entries = entryRepository.findByCorporateActionId(ca.getId());
            if (entries.isEmpty()) {
                continue;
            }
            Set<String> paid = entries.stream()
                    .filter(e -> e.getSettledAt() != null)
                    .filter(e -> e.getPayoutStatus() == CorporateActionEntry.PayoutStatus.PAYABLE)
                    .map(e -> AddressNormalizer.normalize(e.getWalletAddress()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            return Optional.of(new SettledRetirement(ca.getId(), paid));
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenCorporateAction(UUID assetId) {
        return !actionRepository.findByAssetIdAndStatusIn(assetId, OPEN).isEmpty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenAction> openActions(UUID assetId) {
        Set<CorporateAction.Status> statuses = EnumSet.copyOf(OPEN);
        statuses.add(CorporateAction.Status.PROPOSED);
        return actionRepository.findByAssetIdAndStatusIn(assetId, statuses).stream()
                .map(ca -> new OpenAction(ca.getId(), ca.getActionType().name(), ca.getStatus().name(),
                        ca.getRecordDate(), ca.getPaymentDate()))
                .toList();
    }
}
