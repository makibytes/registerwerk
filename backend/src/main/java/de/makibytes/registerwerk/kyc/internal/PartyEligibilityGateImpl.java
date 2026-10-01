package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Deliberately NOT {@code @Transactional}: {@link #require} throws, and a throwing method on a
 * transactional bean that joins the caller's transaction would mark that transaction
 * rollback-only even when the caller catches the exception (trading turns a failed gate at
 * confirm time into PAYMENT_UNRESOLVED instead of an error). The repositories underneath open
 * their own read transactions.
 */
@Component
class PartyEligibilityGateImpl implements PartyEligibilityGate {

    private final LegalEntityRepository entityRepository;
    private final ScreeningGate screeningGate;
    private final HolderBlockGate holderBlockGate;

    PartyEligibilityGateImpl(LegalEntityRepository entityRepository, ScreeningGate screeningGate,
                             HolderBlockGate holderBlockGate) {
        this.entityRepository = entityRepository;
        this.screeningGate = screeningGate;
        this.holderBlockGate = holderBlockGate;
    }

    @Override
    public void require(UUID entityId, String walletAddress, String purpose) {
        List<String> reasons = check(entityId, walletAddress);
        if (!reasons.isEmpty()) {
            throw new ComplianceGateException("Entity " + entityId + " is not eligible for " + purpose + ": "
                    + String.join("; ", reasons) + ".");
        }
    }

    @Override
    public List<String> check(UUID entityId, String walletAddress) {
        if (entityId == null) {
            return List.of("is unknown");
        }
        LegalEntity entity = entityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            return List.of("is unknown");
        }
        String wallet = walletAddress == null || walletAddress.isBlank() ? null : AddressNormalizer.normalize(walletAddress);
        return PartyEligibility.reasons(entity, wallet, screeningGate, holderBlockGate, LocalDate.now());
    }
}
