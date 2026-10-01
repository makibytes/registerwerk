package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.api.OutboundDestinationGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import de.makibytes.registerwerk.shared.AddressNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
class OutboundDestinationGateImpl implements OutboundDestinationGate {

    private static final Logger log = LoggerFactory.getLogger(OutboundDestinationGateImpl.class);

    private final AssetHolderRepository holderRepository;
    private final LegalEntityRepository entityRepository;
    private final ScreeningGate screeningGate;
    private final HolderBlockGate holderBlockGate;
    private final boolean enabled;

    OutboundDestinationGateImpl(AssetHolderRepository holderRepository,
                                LegalEntityRepository entityRepository,
                                ScreeningGate screeningGate,
                                HolderBlockGate holderBlockGate,
                                @Value("${registerwerk.chain.destination-gate.enabled:true}") boolean enabled) {
        this.holderRepository = holderRepository;
        this.entityRepository = entityRepository;
        this.screeningGate = screeningGate;
        this.holderBlockGate = holderBlockGate;
        this.enabled = enabled;
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedDestination require(UUID assetId, String address, String purpose) {
        if (!enabled) {
            log.warn("Destination gate DISABLED (registerwerk.chain.destination-gate.enabled=false): {} to {} unchecked",
                    purpose, address);
            return null;
        }
        AddressNormalizer.requireValidChecksum(address);
        String normalized = AddressNormalizer.normalize(address);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("Destination address is required for " + purpose);
        }
        AssetHolder holder = holderRepository.findActiveByAssetIdAndWalletAddress(assetId, normalized)
                .orElseThrow(() -> deny(purpose, address, "is not an active registered holder of this asset — onboard the holder first"));
        LegalEntity entity = entityRepository.findById(holder.getInvestorId())
                .orElseThrow(() -> deny(purpose, address, "belongs to an unknown legal entity"));
        List<String> reasons = PartyEligibility.reasons(entity, normalized, screeningGate, holderBlockGate, LocalDate.now());
        if (!reasons.isEmpty()) {
            throw deny(purpose, address, "belongs to an entity that " + String.join("; ", reasons));
        }
        return new ResolvedDestination(holder.getId(), entity.getId(), entity.getCurrentName(), normalized);
    }

    private static AccessDeniedException deny(String purpose, String address, String reason) {
        return new AccessDeniedException("Destination " + address + " refused for " + purpose + ": address " + reason + ".");
    }
}
