package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.customer.api.OffboardingObligation;
import de.makibytes.registerwerk.customer.api.OffboardingObligationSource;
import de.makibytes.registerwerk.lending.api.LendingPositionRepository;
import de.makibytes.registerwerk.lending.api.LendingPositionStatus;
import de.makibytes.registerwerk.lending.api.LendingSupplyPositionRepository;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Open borrower debt and supplied liquidity of the entity's bound wallets, from the cached
 * position rows (the last on-chain refresh; no RPC in the termination path) (6-23).
 */
@Component
class LendingOffboardingObligationSource implements OffboardingObligationSource {

    private final OrgMemberWalletRepository wallets;
    private final LendingPositionRepository positions;
    private final LendingSupplyPositionRepository supplyPositions;

    LendingOffboardingObligationSource(OrgMemberWalletRepository wallets, LendingPositionRepository positions,
                                       LendingSupplyPositionRepository supplyPositions) {
        this.wallets = wallets;
        this.positions = positions;
        this.supplyPositions = supplyPositions;
    }

    @Override
    public List<OffboardingObligation> openObligations(UUID entityId) {
        List<OffboardingObligation> result = new ArrayList<>();
        for (OrgMemberWallet wallet : wallets.findActiveByLegalEntityId(entityId)) {
            if (wallet.getStatus() != MemberWalletStatus.ACTIVE) {
                continue;
            }
            positions.findByWalletAddressIgnoreCase(wallet.getWalletAddress()).stream()
                    .filter(p -> p.getStatus() == LendingPositionStatus.OPEN
                            || (p.getCurrentDebt() != null && p.getCurrentDebt().signum() > 0))
                    .forEach(p -> result.add(new OffboardingObligation("LENDING_POSITION_OPEN", p.getId().toString(),
                            "Borrower position " + p.getId() + " on market " + p.getMarketId() + " is open")));
            supplyPositions.findByWalletAddressIgnoreCase(wallet.getWalletAddress()).stream()
                    .filter(p -> p.getCurrentClaim() != null && p.getCurrentClaim().signum() > 0)
                    .forEach(p -> result.add(new OffboardingObligation("LENDING_POSITION_OPEN", p.getId().toString(),
                            "Supplied liquidity " + p.getId() + " on market " + p.getMarketId() + " is outstanding")));
        }
        return result;
    }
}
