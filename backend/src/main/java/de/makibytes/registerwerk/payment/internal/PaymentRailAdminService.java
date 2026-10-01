package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailAttestation;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddress;
import de.makibytes.registerwerk.payment.api.PaymentRailChainAddressRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailRepository;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import de.makibytes.registerwerk.payment.events.PaymentRailEvent;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
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
    private final PaymentRailOnchainVerifier onchainVerifier;

    /** Reason code stored on a rail that was switched off because its attestation was voided. */
    public static final String REASON_ATTESTATION_INVALIDATED = "MICAR_ATTESTATION_INVALIDATED";

    PaymentRailAdminService(
            PaymentRailRepository railRepository,
            PaymentRailChainAddressRepository chainAddressRepository,
            ChainConfigRepository chainConfigRepository,
            ApplicationEventPublisher eventPublisher,
            PaymentRailOnchainVerifier onchainVerifier) {
        this.onchainVerifier = onchainVerifier;
        this.railRepository = railRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.eventPublisher = eventPublisher;
    }

    public PaymentRail create(String code, String displayName, PaymentRailType railType, String currency,
                              Integer decimals, String description, String issuerName, String issuerLei,
                              String micarAuthorization, boolean emtFlag, String whitePaperUrl, boolean redemptionAtPar,
                              Map<UUID, String> chainAddresses, UUID actorId, String actorRole,
                              UUID dualControlApproverId) {
        if (railRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Payment rail code '" + code + "' already exists");
        }
        PaymentRail rail = new PaymentRail();
        rail.setCode(code);
        applyFields(rail, displayName, railType, currency, decimals, description,
                issuerName, issuerLei, micarAuthorization, emtFlag, whitePaperUrl, redemptionAtPar);
        // New rails start disabled: enabling is a separate 4-eyes step (and, for EMT rails,
        // requires an effective attestation), so creation alone never exposes a rail.
        rail.setEnabled(false);
        rail.setCreatedBy(actorId);
        rail.setUpdatedBy(actorId);
        onchainVerifier.verify(railType, decimals, chainAddresses);
        rail = railRepository.save(rail);
        replaceChainAddresses(rail, chainAddresses);

        eventPublisher.publishEvent(new PaymentRailEvent("CREATED", rail.getId(), actorId, actorRole,
                Map.of("code", code, "railType", railType.name(), "currency", currency),
                dualControlApproverId));
        return rail;
    }

    /** Updates everything except the manifest-facing {@code code}, which is immutable. */
    public PaymentRail update(UUID railId, String displayName, PaymentRailType railType, String currency,
                              Integer decimals, String description, String issuerName, String issuerLei,
                              String micarAuthorization, boolean emtFlag, String whitePaperUrl, boolean redemptionAtPar,
                              Map<UUID, String> chainAddresses, UUID actorId, String actorRole, UUID dualControlApproverId) {
        PaymentRail rail = requireRail(railId);
        Map<String, String> oldAddresses = currentChainAddresses(rail.getId());
        boolean wasAttested = rail.isMicarVerified();
        boolean wasEmt = rail.isEmtFlag();
        Integer oldDecimals = rail.getDecimals();
        Map<UUID, String> requested = chainAddresses == null ? Map.of() : chainAddresses;
        applyFields(rail, displayName, railType, currency, decimals, description,
                issuerName, issuerLei, micarAuthorization, emtFlag, whitePaperUrl, redemptionAtPar);
        rail.setUpdatedBy(actorId);
        boolean addressesChanged = !oldAddresses.equals(stringKeyed(requested));
        if (addressesChanged || !Objects.equals(oldDecimals, decimals)) {
            onchainVerifier.verify(railType, decimals, requested);
        }
        rail = railRepository.save(rail);
        replaceChainAddresses(rail, chainAddresses);
        Map<UUID, String> currentAddresses = PaymentRailAttestation.addressMap(
                chainAddressRepository.findByPaymentRailId(rail.getId()));
        boolean autoDisabled = false;
        if (wasAttested && !PaymentRailAttestation.isEffective(rail, currentAddresses)) {
            // The attestation covered the old content (incl. token address, issuer, LEI,
            // currency, decimals) - it does not apply to the new one; require re-attestation.
            autoDisabled = voidAttestation(rail, "MICAR_ATTESTATION_INVALIDATED", "content changed by update", actorId, actorRole,
                    dualControlApproverId);
        } else if (emtFlag && !wasEmt && rail.isEnabled() && requiresAttestation(rail)) {
            autoDisabled = disableForAttestation(rail, "MICAR_ATTESTATION_MISSING", actorId, actorRole,
                    dualControlApproverId);
        }
        rail = railRepository.save(rail);
        Map<String, String> newAddresses = currentChainAddresses(rail.getId());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", rail.getCode());
        if (!oldAddresses.equals(newAddresses)) {
            details.put("oldChainAddresses", oldAddresses);
            details.put("newChainAddresses", newAddresses);
        }
        if (autoDisabled) {
            details.put("autoDisabled", true);
        }
        eventPublisher.publishEvent(
                new PaymentRailEvent("UPDATED", rail.getId(), actorId, actorRole, details, dualControlApproverId));
        return rail;
    }

    public PaymentRail setEnabled(UUID railId, boolean enabled, UUID actorId, String actorRole,
                                  UUID dualControlApproverId) {
        PaymentRail rail = requireRail(railId);
        if (enabled && requiresAttestation(rail) && !isAttested(rail)) {
            throw new IllegalStateException("EMT stablecoin rail '" + rail.getCode()
                    + "' cannot be enabled without an effective operator MiCAR attestation "
                    + "(attest it first; a change of token address, issuer, LEI, currency or decimals voids it)");
        }
        rail.setEnabled(enabled);
        rail.setDisabledReason(null);
        rail.setUpdatedAt(Instant.now());
        rail = railRepository.save(rail);

        eventPublisher.publishEvent(new PaymentRailEvent(enabled ? "ENABLED" : "DISABLED",
                rail.getId(), actorId, actorRole, Map.of("code", rail.getCode()), dualControlApproverId));
        return rail;
    }

    /** Whether the rail's operator attestation is set and still matches its current content. */
    public boolean isAttested(PaymentRail rail) {
        return PaymentRailAttestation.isEffective(rail, PaymentRailAttestation.addressMap(
                chainAddressRepository.findByPaymentRailId(rail.getId())));
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
    public PaymentRail setMicarVerified(UUID railId, boolean verified, UUID actorId, String actorRole,
                                        UUID dualControlApproverId) {
        PaymentRail rail = requireRail(railId);
        if (!verified) {
            if (rail.isMicarVerified()) {
                voidAttestation(rail, "MICAR_VERIFICATION_CLEARED", "attestation cleared by operator", actorId, actorRole, dualControlApproverId);
                rail = railRepository.save(rail);
            }
            return rail;
        }
        // Separation of duties: whoever entered or last changed the facts cannot attest them.
        if (actorId != null && (actorId.equals(rail.getCreatedBy()) || actorId.equals(rail.getUpdatedBy()))) {
            throw new AccessDeniedException(
                    "The operator who created or last changed a payment rail cannot attest its MiCAR facts");
        }
        rail.setMicarVerified(true);
        rail.setMicarVerifiedAt(Instant.now());
        rail.setMicarVerifiedBy(actorId);
        rail.setMicarAttestedFingerprint(PaymentRailAttestation.fingerprint(rail, PaymentRailAttestation.addressMap(
                chainAddressRepository.findByPaymentRailId(rail.getId()))));
        rail = railRepository.save(rail);

        eventPublisher.publishEvent(new PaymentRailEvent("MICAR_VERIFIED", rail.getId(), actorId, actorRole,
                Map.of("code", rail.getCode(), "fingerprint", rail.getMicarAttestedFingerprint()),
                dualControlApproverId));
        return rail;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static boolean requiresAttestation(PaymentRail rail) {
        return rail.getRailType() == PaymentRailType.STABLECOIN && rail.isEmtFlag();
    }

    /**
     * Clears the attestation and, for an enabled EMT stablecoin rail, switches the rail off with
     * a visible reason so investors are never shown an unattested EMT as ready to use.
     *
     * @return whether the rail was auto-disabled
     */
    private boolean voidAttestation(PaymentRail rail, String action, String why, UUID actorId, String actorRole,
                                    UUID dualControlApproverId) {
        rail.setMicarVerified(false);
        rail.setMicarVerifiedAt(null);
        rail.setMicarVerifiedBy(null);
        rail.setMicarAttestedFingerprint(null);
        eventPublisher.publishEvent(new PaymentRailEvent(action, rail.getId(), actorId,
                actorRole, Map.of("code", rail.getCode(), "reason", why), dualControlApproverId));
        if (rail.isEnabled() && requiresAttestation(rail)) {
            return disableForAttestation(rail, REASON_ATTESTATION_INVALIDATED, actorId, actorRole,
                    dualControlApproverId);
        }
        return false;
    }

    private boolean disableForAttestation(PaymentRail rail, String reason, UUID actorId, String actorRole,
                                          UUID dualControlApproverId) {
        rail.setEnabled(false);
        rail.setDisabledReason(reason);
        eventPublisher.publishEvent(new PaymentRailEvent("DISABLED", rail.getId(), actorId, actorRole,
                Map.of("code", rail.getCode(), "reason", reason, "automatic", true), dualControlApproverId));
        return true;
    }

    private static Map<String, String> stringKeyed(Map<UUID, String> byId) {
        return byId.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
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
        // Hibernate flushes inserts before deletes: without this an update that keeps a
        // chain violates uq_payment_rail_chain.
        chainAddressRepository.flush();
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
