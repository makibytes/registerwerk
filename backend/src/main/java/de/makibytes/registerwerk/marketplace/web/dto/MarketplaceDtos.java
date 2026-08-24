package de.makibytes.registerwerk.marketplace.web.dto;

import de.makibytes.registerwerk.marketplace.api.DappListing;
import de.makibytes.registerwerk.marketplace.api.DappListingStatus;
import de.makibytes.registerwerk.marketplace.api.DappPaymentMethod;
import de.makibytes.registerwerk.marketplace.api.DappRequiredPermission;
import de.makibytes.registerwerk.marketplace.api.DappVersion;
import de.makibytes.registerwerk.marketplace.api.DappVersionStatus;
import de.makibytes.registerwerk.payment.api.PaymentRail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records of the marketplace REST API. */
public final class MarketplaceDtos {

    private MarketplaceDtos() {}

    // ── Requests ──────────────────────────────────────────────────────────────

    public record CreateListingRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$") String slug,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 60) String category,
            @NotNull UUID chainConfigId) {}

    public record SignManifestRequest(
            @NotBlank String signature,
            @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{40}$") String signerWallet) {}

    public record ReviewNotesRequest(@Size(max = 5000) String notes) {}

    public record RejectRequest(@NotBlank @Size(max = 5000) String reason) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    public record ListingResponse(
            UUID id,
            String slug,
            String dappIdHash,
            String name,
            String category,
            DappListingStatus status,
            UUID chainConfigId,
            String chainIdentifier,
            UUID publisherEntityId,
            String publisherName,
            UUID currentVersionId,
            String contactEmail,
            String docsUrl,
            String pricingNote,
            Instant createdAt,
            Instant updatedAt) {

        public static ListingResponse from(DappListing listing, String chainIdentifier, String publisherName) {
            return new ListingResponse(
                    listing.getId(), listing.getSlug(), listing.getDappIdHash(), listing.getName(),
                    listing.getCategory(), listing.getStatus(), listing.getChainConfigId(), chainIdentifier,
                    listing.getPublisherEntityId(), publisherName, listing.getCurrentVersionId(),
                    listing.getContactEmail(), listing.getDocsUrl(), listing.getPricingNote(),
                    listing.getCreatedAt(), listing.getUpdatedAt());
        }
    }

    public record VersionResponse(
            UUID id,
            UUID listingId,
            String version,
            DappVersionStatus status,
            String manifestHash,
            String signerWallet,
            boolean signed,
            String reviewNotes,
            Instant submittedAt,
            Instant reviewedAt,
            String onchainTx,
            Instant createdAt) {

        public static VersionResponse from(DappVersion version) {
            return new VersionResponse(
                    version.getId(), version.getListingId(), version.getVersion(), version.getStatus(),
                    version.getManifestHash(), version.getSignerWallet(),
                    version.getManifestSignature() != null,
                    version.getReviewNotes(), version.getSubmittedAt(), version.getReviewedAt(),
                    version.getOnchainTx(), version.getCreatedAt());
        }
    }

    public record RequiredPermissionResponse(
            String permissionCode,
            String permissionHash,
            List<Long> claimTopics,
            String rationale) {

        public static RequiredPermissionResponse from(DappRequiredPermission permission) {
            return new RequiredPermissionResponse(
                    permission.getPermissionCode(), permission.getPermissionHash(),
                    permission.getClaimTopics(), permission.getRationale());
        }
    }

    public record ManifestValidationResponse(boolean valid, List<String> errors, String manifestHash) {}

    /** Exact manifest bytes for an owner resuming an existing publication draft. */
    public record ManifestContentResponse(String manifestRaw) {}

    public record ReviewQueueItem(
            UUID versionId,
            UUID listingId,
            String slug,
            String name,
            String publisherName,
            String version,
            DappVersionStatus status,
            Instant submittedAt) {}

    public record ReviewDetailResponse(
            ListingResponse listing,
            VersionResponse version,
            String manifestRaw,
            String previousManifestRaw,
            List<RequiredPermissionResponse> requiredPermissions,
            List<PaymentMethodResponse> paymentMethods) {}

    public record CatalogCard(
            String slug,
            String name,
            String category,
            String publisherName,
            String version,
            int requiredPermissionCount,
            String pricingNote,
            List<PaymentMethodResponse> paymentMethods,
            Instant updatedAt) {}

    public record CatalogDetail(
            ListingResponse listing,
            VersionResponse version,
            String manifestRaw,
            String manifestSignature,
            List<RequiredPermissionResponse> requiredPermissions,
            List<PaymentMethodResponse> paymentMethods) {}

    /**
     * A payment method resolved for display: {@code RAIL} entries carry the operator
     * rail's display metadata (including MiCAR fields for stablecoins), {@code CUSTOM}
     * entries carry the publisher's own descriptor.
     */
    public record PaymentMethodResponse(
            String methodType,
            String railCode,
            String displayName,
            String railType,
            String currency,
            Boolean emtFlag,
            String issuerName,
            String issuerLei,
            String whitePaperUrl,
            Boolean redemptionAtPar,
            boolean railEnabled,
            String customName,
            String customDescription,
            String note) {

        public static PaymentMethodResponse forRail(DappPaymentMethod method, PaymentRail rail) {
            if (rail == null) {
                return new PaymentMethodResponse("RAIL", method.getRailCode(), method.getRailCode(),
                        null, null, null, null, null, null, null, false, null, null, method.getNote());
            }
            return new PaymentMethodResponse("RAIL", rail.getCode(), rail.getDisplayName(),
                    rail.getRailType().name(), rail.getCurrency(), rail.isEmtFlag(),
                    rail.getIssuerName(), rail.getIssuerLei(), rail.getWhitePaperUrl(), rail.isRedemptionAtPar(),
                    rail.isEnabled(), null, null, method.getNote());
        }

        public static PaymentMethodResponse forCustom(DappPaymentMethod method) {
            return new PaymentMethodResponse("CUSTOM", null, method.getCustomName(), null,
                    method.getCurrency(), null, null, null, null, null, false,
                    method.getCustomName(), method.getCustomDescription(), method.getNote());
        }
    }
}
