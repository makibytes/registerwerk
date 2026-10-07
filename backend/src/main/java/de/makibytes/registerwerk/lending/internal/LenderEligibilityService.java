package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.kyc.api.PartyEligibilityGate;
import de.makibytes.registerwerk.shared.ComplianceGateException;
import de.makibytes.registerwerk.shared.ProductionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Lender-side (Supply &amp; Earn: supply, withdraw, vault shares) gate, T2-20 / T5-13. In production mode a lender
 * must pass the same tests as a borrower: the entity is ACTIVE with an APPROVED, unexpired KYC and no unresolved
 * sanctions-screening result ({@link PartyEligibilityGate}, fail-closed), and is classified PROFESSIONAL or
 * ELIGIBLE_COUNTERPARTY. Demo mode applies no gate. The contracts themselves are permissionless; this gate protects
 * the product surface (positions API, lender preflight, Supply &amp; Earn page), not the chain.
 */
@Service
public class LenderEligibilityService {

    private final LegalEntityRepository entities;
    private final PartyEligibilityGate gate;
    private final ProductionMode productionMode;

    LenderEligibilityService(LegalEntityRepository entities, PartyEligibilityGate gate, Environment environment) {
        this.entities = entities;
        this.gate = gate;
        this.productionMode = ProductionMode.of(environment);
    }

    /** True when the gate is applied (production mode). */
    public boolean enforced() {
        return productionMode.enabled();
    }

    /** Every reason the entity may not act as a lender (empty = eligible). Production semantics, regardless of mode. */
    public List<String> reasons(UUID entityId) {
        if (entityId == null) {
            return List.of("no customer entity is attached to this session");
        }
        LegalEntity entity = entities.findById(entityId).orElse(null);
        if (entity == null) {
            return List.of("the entity is unknown");
        }
        List<String> reasons = new ArrayList<>();
        ClientCategory category = entity.getClientCategory();
        if (category == null || category == ClientCategory.RETAIL) {
            reasons.add("the entity is not classified as a PROFESSIONAL client or ELIGIBLE_COUNTERPARTY");
        }
        reasons.addAll(gate.check(entityId, null));
        return reasons;
    }

    /** Refuses (409, fail closed) a lender action in production mode; a no-op in demo mode. */
    public void requireLender(UUID entityId, String purpose) {
        if (!enforced()) {
            return;
        }
        List<String> reasons = reasons(entityId);
        if (!reasons.isEmpty()) {
            throw new ComplianceGateException("Entity " + entityId + " is not eligible for " + purpose
                    + " (lender-side access requires an approved, screened professional client): "
                    + String.join("; ", reasons) + ".");
        }
    }

    /** What the Supply &amp; Earn page needs: whether the gate is enforced and whether this entity passes it. */
    public record Status(boolean productionMode, boolean eligible, List<String> reasons) {}

    public Status status(UUID entityId) {
        if (!enforced()) {
            return new Status(false, true, List.of());
        }
        List<String> reasons = reasons(entityId);
        return new Status(true, reasons.isEmpty(), reasons);
    }
}
