package de.makibytes.registerwerk.orgidentity.web.dto;

import de.makibytes.registerwerk.orgidentity.api.EcosystemTrustedIssuer;
import de.makibytes.registerwerk.orgidentity.api.TrustedIssuerStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinition;
import de.makibytes.registerwerk.orgidentity.api.PermissionDefinitionStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrant;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantStatus;
import de.makibytes.registerwerk.orgidentity.api.PermissionGrantType;
import de.makibytes.registerwerk.orgidentity.api.RoleRestrictionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records of the permission-framework REST API. */
public final class PermissionDtos {

    private PermissionDtos() {}

    private static final String EVM_ADDRESS = "^0x[0-9a-fA-F]{40}$";

    // ── Requests ──────────────────────────────────────────────────────────────

    public record CreatePermissionRequest(
            @NotBlank @Size(max = 120) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {}

    public record OrgGrantRequest(@NotNull UUID orgRegistrationId) {}

    public record RoleGrantRequest(
            @NotNull UUID chainConfigId,
            @NotBlank @Size(max = 120) String roleCode,
            @NotNull UUID permissionDefinitionId) {}

    public record RoleRestrictionRequest(
            @NotNull UUID chainConfigId,
            @NotNull UUID permissionDefinitionId,
            boolean restricted) {}

    public record TrustedIssuerRequest(
            @NotNull UUID chainConfigId,
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String issuerAddress,
            @NotEmpty List<Long> claimTopics,
            UUID legalEntityId) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    public record PermissionDefinitionResponse(
            UUID id,
            String code,
            String permissionHash,
            String name,
            String description,
            UUID dappListingId,
            PermissionDefinitionStatus status,
            Instant createdAt) {

        public static PermissionDefinitionResponse from(PermissionDefinition definition) {
            return new PermissionDefinitionResponse(
                    definition.getId(), definition.getCode(), definition.getPermissionHash(),
                    definition.getName(), definition.getDescription(), definition.getDappListingId(),
                    definition.getStatus(), definition.getCreatedAt());
        }
    }

    public record PermissionGrantResponse(
            UUID id,
            UUID permissionDefinitionId,
            String permissionCode,
            UUID orgRegistrationId,
            String entityName,
            PermissionGrantType grantType,
            String roleCode,
            boolean roleRestricted,
            RoleRestrictionStatus roleRestrictionStatus,
            Boolean requestedRoleRestricted,
            PermissionGrantStatus status,
            String grantedTx,
            Instant createdAt,
            Instant revokedAt) {

        public static PermissionGrantResponse from(PermissionGrant grant, String permissionCode, String entityName) {
            return new PermissionGrantResponse(
                    grant.getId(), grant.getPermissionDefinitionId(), permissionCode,
                    grant.getOrgRegistrationId(), entityName,
                    grant.getGrantType(), grant.getRoleCode(), grant.isRoleRestricted(),
                    grant.getRoleRestrictionStatus(), grant.getRequestedRoleRestricted(),
                    grant.getStatus(), grant.getGrantedTx(), grant.getCreatedAt(), grant.getRevokedAt());
        }
    }

    public record TrustedIssuerResponse(
            UUID id,
            UUID chainConfigId,
            String issuerAddress,
            List<Long> claimTopics,
            UUID legalEntityId,
            TrustedIssuerStatus status,
            String addedTx,
            Instant createdAt,
            Instant removedAt) {

        public static TrustedIssuerResponse from(EcosystemTrustedIssuer issuer) {
            return new TrustedIssuerResponse(
                    issuer.getId(), issuer.getChainConfigId(), issuer.getIssuerAddress(),
                    issuer.getClaimTopics(), issuer.getLegalEntityId(), issuer.getStatus(),
                    issuer.getAddedTx(), issuer.getCreatedAt(), issuer.getRemovedAt());
        }
    }
}
