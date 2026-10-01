package de.makibytes.registerwerk.corporateactions.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.corporateactions.api.CorporateAction;
import de.makibytes.registerwerk.corporateactions.api.CorporateActionRepository;
import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Corporate actions on assets the entity issued that are not yet settled, closed or cancelled (6-23). */
@Component
class CorporateActionOffboardingObligationSource implements OffboardingObligationSource {

    private static final List<CorporateAction.Status> PENDING = List.of(
            CorporateAction.Status.PROPOSED, CorporateAction.Status.ANNOUNCED, CorporateAction.Status.SNAPSHOT_BLOCKED,
            CorporateAction.Status.RECORD_DATE_SET, CorporateAction.Status.COMPUTED,
            CorporateAction.Status.AWAITING_SETTLEMENT);

    private final AssetRepository assets;
    private final CorporateActionRepository actions;

    CorporateActionOffboardingObligationSource(AssetRepository assets, CorporateActionRepository actions) {
        this.assets = assets;
        this.actions = actions;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        List<OffboardingObligation> result = new ArrayList<>();
        assets.findByIssuerId(entityId).forEach(a -> actions.findByAssetId(a.getId()).stream()
                .filter(ca -> PENDING.contains(ca.getStatus()))
                .forEach(ca -> result.add(new OffboardingObligation("CORPORATE_ACTION_PENDING", ca.getId().toString(),
                        ca.getActionType() + " " + ca.getId() + " on asset " + a.getId() + " is " + ca.getStatus()))));
        return result;
    }
}
