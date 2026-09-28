package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.CouponPaymentActionLookup;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Backs the asset module's {@link CouponPaymentActionLookup} with the corporate-action table. */
@Component
class CouponPaymentActionLookupAdapter implements CouponPaymentActionLookup {

    private final CorporateActionRepository corporateActionRepository;

    CouponPaymentActionLookupAdapter(CorporateActionRepository corporateActionRepository) {
        this.corporateActionRepository = corporateActionRepository;
    }

    @Override
    public boolean hasCorporateAction(UUID couponPaymentId) {
        return corporateActionRepository.existsByCouponPaymentId(couponPaymentId);
    }
}
