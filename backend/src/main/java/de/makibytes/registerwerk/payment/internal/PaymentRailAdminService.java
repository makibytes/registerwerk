package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddress;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import de.makibytes.registerwerk.payment.events.PaymentRailEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Operator administration of the payment-rail catalog. Rails are the registry-provided
 * cash-leg options (MiCAR EMT stablecoins, Pontes API, ERC-7573 DvP, SEPA) that dApp
 * manifests may reference by code; disabling a rail stops new manifests from declaring
 * it (and blocks approval of pending ones) without touching already-published listings.
 */
@Service
@Transactional
public class PaymentRailAdminService {

    private final PaymentRailRepository railRepository;
    private final PaymentRailChainAddressRepository chainAddressRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final ApplicationEventPublisher eventPublisher;

    PaymentRailAdminService(
            PaymentRailRepository railRepository,
            PaymentRailChainAddressRepository chainAddressRepository,
            ChainConfigRepository chainConfigRepository,
            ApplicationEventPublisher eventPublisher) {
        this.railRepository = railRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.eventPublisher = eventPublisher;
    }

    public PaymentRail create(String code, String displayName, PaymentRailType railType, String currency,
                              Integer decimals, String description, String issuerName, String issuerLei,
                              String micarAuthorization, boolean emtFlag, String whitePaperUrl, boolean redemptionAtPar,
                              Map<UUID, String> chainAddresses, UUID actorId, String actorRole) {
        if (railRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Payment rail code '" + code + "' already exists");
        }
        PaymentRail rail = new PaymentRail();
        rail.setCode(code);
        applyFields(rail, displayName, railType, currency, decimals, description,
                issuerName, issuerLei, micarAuthorization, emtFlag, whitePaperUrl, redemptionAtPar);
        rail = railRepository.save(rail);
        replaceChainAddresses(rail, chainAddresses);

        eventPublisher.publishEvent(new PaymentRailEvent("CREATED", rail.getId(), actorId, actorRole,
                Map.of("code", code, "railType", railType.name(), "currency", currency)));
        return rail;
    }

    /** Updates everything except the manifest-facing {@code code}, which is immutable. */
    public PaymentRail update(UUID railId, String displayName, PaymentRailType railType, String currency,
                              Integer decimals, String description, String issuerName, String issuerLei,
                              String micarAuthorization, boolean emtFlag, String whitePaperUrl, boolean redemptionAtPar,
                              Map<UUID, String> chainAddresses, UUID actorId, String actorRole, UUID dualControlApproverId) {
        PaymentRail rail = requireRail(railId);
        Map<String, String> oldAddresses = currentChainAddresses(rail.getId());
        if (micarDisclosureChanged(rail, micarAuthorization, emtFlag, whitePaperUrl, redemptionAtPar)
                && rail.isMicarVerified()) {
            // A prior attestation covered the old values — it no longer applies once the
            // disclosed facts themselves change, so require operators to re-attest.
            rail.setMicarVerified(false);
            rail.setMicarVerifiedAt(null);
            rail.setMicarVerifiedBy(null);
        }
        applyFields(rail, displayName, railType, currency, decimals, description,
                issuerName, issuerLei, micarAuthorization, emtFlag, whitePaperUrl, redemptionAtPar);
        rail = railRepository.save(rail);
        replaceChainAddresses(rail, chainAddresses);
        Map<String, String> newAddresses = currentChainAddresses(rail.getId());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", rail.getCode());
        if (!oldAddresses.equals(newAddresses)) {
            details.put("oldChainAddresses", oldAddresses);
            details.put("newChainAddresses", newAddresses);
        }
        eventPublisher.publishEvent(
                new PaymentRailEvent("UPDATED", rail.getId(), actorId, actorRole, details, dualControlApproverId));
        return rail;
    }

    public PaymentRail setEnabled(UUID railId, boolean enabled, UUID actorId, String actorRole) {
        PaymentRail rail = requireRail(railId);
        rail.setEnabled(enabled);
        rail.setUpdatedAt(Instant.now());
        rail = railRepository.save(rail);

        eventPublisher.publishEvent(new PaymentRailEvent(enabled ? "ENABLED" : "DISABLED",
                rail.getId(), actorId, actorRole, Map.of("code", rail.getCode())));
        return rail;
    }

    public PaymentRail requireRail(UUID railId) {
        return railRepository.findById(railId)
                .orElseThrow(() -> new EntityNotFoundException("PaymentRail", railId));
    }

    /**
     * Records (or clears) an operator's explicit attestation that this rail's MiCAR
     * disclosure fields were checked against a real external source — e.g. the EBA Art. 109
     * authorized-issuer register. This is deliberately a separate, auditable action from
     * {@link #update}, not a side effect of editing the fields: a full cross-check against a
     * live public register is out of scope for this codebase (no such API is reachable
     * here), so this only ever records the operator's own attestation, never a live result.
     */
    public PaymentRail setMicarVerified(UUID railId, boolean verified, UUID actorId, String actorRole) {
        PaymentRail rail = requireRail(railId);
        rail.setMicarVerified(verified);
        rail.setMicarVerifiedAt(verified ? Instant.now() : null);
        rail.setMicarVerifiedBy(verified ? actorId : null);
        rail = railRepository.save(rail);

        eventPublisher.publishEvent(new PaymentRailEvent(verified ? "MICAR_VERIFIED" : "MICAR_VERIFICATION_CLEARED",
                rail.getId(), actorId, actorRole, Map.of("code", rail.getCode())));
        return rail;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private boolean micarDisclosureChanged(PaymentRail rail, String micarAuthorization, boolean emtFlag,
                                           String whitePaperUrl, boolean redemptionAtPar) {
        return !Objects.equals(rail.getMicarAuthorization(), micarAuthorization)
                || rail.isEmtFlag() != emtFlag
                || !Objects.equals(rail.getWhitePaperUrl(), whitePaperUrl)
                || rail.isRedemptionAtPar() != redemptionAtPar;
    }

    private void applyFields(PaymentRail rail, String displayName, PaymentRailType railType, String currency,
                             Integer decimals, String description, String issuerName, String issuerLei,
                             String micarAuthorization, boolean emtFlag, String whitePaperUrl, boolean redemptionAtPar) {
        if (railType == PaymentRailType.STABLECOIN && decimals == null) {
            throw new IllegalArgumentException("decimals is required for stablecoin payment rails");
        }
        if (decimals != null && (decimals < 0 || decimals > 255)) {
            throw new IllegalArgumentException("decimals must be between 0 and 255");
        }
        rail.setDisplayName(displayName);
        rail.setRailType(railType);
        rail.setCurrency(currency);
        rail.setDecimals(decimals);
        rail.setDescription(description);
        rail.setIssuerName(issuerName);
        rail.setIssuerLei(issuerLei);
        rail.setMicarAuthorization(micarAuthorization);
        rail.setEmtFlag(emtFlag);
        rail.setWhitePaperUrl(whitePaperUrl);
        rail.setRedemptionAtPar(redemptionAtPar);
        rail.setUpdatedAt(Instant.now());
    }

    private Map<String, String> currentChainAddresses(UUID railId) {
        return chainAddressRepository.findByPaymentRailId(railId).stream()
                .collect(Collectors.toMap(
                        address -> address.getChainConfigId().toString(),
                        PaymentRailChainAddress::getTokenAddress));
    }

    private void replaceChainAddresses(PaymentRail rail, Map<UUID, String> chainAddresses) {
        if (!rail.getRailType().isChainBound() && chainAddresses != null && !chainAddresses.isEmpty()) {
            throw new IllegalArgumentException(
                    "Chain addresses are not allowed for off-chain payment rails");
        }
        chainAddressRepository.deleteByPaymentRailId(rail.getId());
        if (chainAddresses == null || chainAddresses.isEmpty()) {
            return;
        }
        Map<UUID, String> normalized = new LinkedHashMap<>(chainAddresses);
        for (Map.Entry<UUID, String> entry : normalized.entrySet()) {
            if (!chainConfigRepository.existsById(entry.getKey())) {
                throw new EntityNotFoundException("ChainConfig", entry.getKey());
            }
            PaymentRailChainAddress address = new PaymentRailChainAddress();
            address.setPaymentRailId(rail.getId());
            address.setChainConfigId(entry.getKey());
            address.setTokenAddress(entry.getValue());
            chainAddressRepository.save(address);
        }
    }
}
