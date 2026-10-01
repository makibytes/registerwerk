package de.makibytes.registerwerk.payment.web.dto;

import de.makibytes.registerwerk.payment.api.PaymentRail;
import de.makibytes.registerwerk.payment.api.PaymentRailAttestation;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PaymentRailDtos {

    private PaymentRailDtos() {}

    public record ChainAddressRequest(
            @NotNull UUID chainConfigId,
            @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{40}$") String tokenAddress) {}

    public record PaymentRailRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,58}[a-z0-9]$",
                    message = "code must be lowercase kebab-case") String code,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull PaymentRailType railType,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO-4217 code") String currency,
            @Min(0) @Max(255) Integer decimals,
            @Size(max = 1000) String description,
            @Size(max = 200) String issuerName,
            @Size(max = 20) String issuerLei,
            @Size(max = 200) String micarAuthorization,
            boolean emtFlag,
            @Size(max = 500) @Pattern(regexp = "^$|https://\\S+$", message = "must use HTTPS") String whitePaperUrl,
            boolean redemptionAtPar,
            @Size(max = 100) List<@Valid ChainAddressRequest> chainAddresses) {}

    public record ChainAddressResponse(UUID chainConfigId, String chainIdentifier, String tokenAddress) {}

    public record PaymentRailResponse(
            UUID id,
            String code,
            String displayName,
            PaymentRailType railType,
            String currency,
            Integer decimals,
            String description,
            String issuerName,
            String issuerLei,
            String micarAuthorization,
            boolean emtFlag,
            String whitePaperUrl,
            boolean redemptionAtPar,
            boolean enabled,
            // Whether an operator has explicitly attested the MiCAR fields above against a
            // real external source — the fields themselves are
            // operator-entered free text with no cross-check on their own; null verifiedAt/By
            // when not verified.
            // Effective attestation: flag set AND still bound to the current content (token
            // address, issuer, LEI, currency, decimals ...); see PaymentRailAttestation.
            boolean micarVerified,
            Instant micarVerifiedAt,
            UUID micarVerifiedBy,
            // Why the rail was switched off automatically (e.g. MICAR_ATTESTATION_INVALIDATED); null otherwise.
            String disabledReason,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt,
            List<ChainAddressResponse> chainAddresses) {

        public static PaymentRailResponse from(PaymentRail rail, List<ChainAddressResponse> chainAddresses) {
            return build(rail, chainAddresses, true);
        }

        /** Catalog view for any authenticated user: no operator identities. */
        public static PaymentRailResponse forCatalog(PaymentRail rail, List<ChainAddressResponse> chainAddresses) {
            return build(rail, chainAddresses, false);
        }

        private static PaymentRailResponse build(PaymentRail rail, List<ChainAddressResponse> chainAddresses,
                                                 boolean operatorView) {
            Map<UUID, String> byChain = new LinkedHashMap<>();
            chainAddresses.forEach(a -> byChain.put(a.chainConfigId(), a.tokenAddress()));
            boolean attested = PaymentRailAttestation.isEffective(rail, byChain);
            return new PaymentRailResponse(
                    rail.getId(), rail.getCode(), rail.getDisplayName(), rail.getRailType(),
                    rail.getCurrency(), rail.getDecimals(), rail.getDescription(),
                    rail.getIssuerName(), rail.getIssuerLei(), rail.getMicarAuthorization(),
                    rail.isEmtFlag(), rail.getWhitePaperUrl(), rail.isRedemptionAtPar(),
                    rail.isEnabled(), attested,
                    attested ? rail.getMicarVerifiedAt() : null,
                    attested && operatorView ? rail.getMicarVerifiedBy() : null,
                    rail.getDisabledReason(),
                    operatorView ? rail.getCreatedBy() : null,
                    operatorView ? rail.getUpdatedBy() : null,
                    rail.getCreatedAt(), rail.getUpdatedAt(),
                    chainAddresses);
        }
    }
}
