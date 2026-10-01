package de.makibytes.registerwerk.repo.internal;

import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.repo.api.RepoTradeRepository;
import de.makibytes.registerwerk.repo.api.RepoTypes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Repo trades in which the entity is borrower or lender and the collateral is still committed (6-23). */
@Component
class RepoOffboardingObligationSource implements OffboardingObligationSource {

    private final RepoTradeRepository trades;

    RepoOffboardingObligationSource(RepoTradeRepository trades) {
        this.trades = trades;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        return trades.findByParty(entityId).stream()
                .filter(t -> RepoTypes.TradeStatus.OPEN_STATES.contains(t.getStatus()))
                .map(t -> new OffboardingObligation("REPO_TRADE_OPEN", t.getId().toString(),
                        "Repo trade " + t.getId() + " is " + t.getStatus()))
                .toList();
    }
}
