package de.makibytes.registerwerk.orgidentity.web.dto;

import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
import de.makibytes.registerwerk.orgidentity.api.WalletBindChallenge;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records of the org-identity REST API. */
public final class OrgIdentityDtos {

    private OrgIdentityDtos() {}

    private static final String EVM_ADDRESS = "^0x[0-9a-fA-F]{40}$";

    // ── Requests ──────────────────────────────────────────────────────────────

    public record RegisterOrgRequest(
            @NotNull UUID legalEntityId,
            @NotNull UUID chainConfigId,
            Short countryCode) {}

    public record SuspendOrgRequest(@NotBlank String reason) {}

    public record WalletChallengeRequest(
            @NotNull UUID chainConfigId,
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String walletAddress) {}

    public record BindWalletRequest(
            @NotNull UUID chainConfigId,
            @NotBlank @Pattern(regexp = EVM_ADDRESS) String walletAddress,
            @NotBlank String signature,
            @Size(max = 16) List<String> roles,
            @Size(max = 120) String label) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    public record OrgRegistrationResponse(
            UUID id,
            UUID legalEntityId,
            String entityName,
            UUID chainConfigId,
            String chainIdentifier,
            String orgAddress,
            OrgRegistrationStatus status,
            String registeredTx,
            Instant suspendedAt,
            String suspensionReason,
            long activeMemberCount,
            Instant createdAt) {

        public static OrgRegistrationResponse from(OrgRegistration reg, String entityName,
                                                   String chainIdentifier, long activeMemberCount) {
            return new OrgRegistrationResponse(
                    reg.getId(), reg.getLegalEntityId(), entityName,
                    reg.getChainConfigId(), chainIdentifier,
                    reg.getOrgAddress(), reg.getStatus(), reg.getRegisteredTx(),
                    reg.getSuspendedAt(), reg.getSuspensionReason(),
                    activeMemberCount, reg.getCreatedAt());
        }
    }

    public record MemberWalletResponse(
            UUID id,
            String walletAddress,
            String label,
            List<String> roles,
            MemberWalletStatus status,
            String boundTx,
            Instant createdAt,
            Instant removedAt) {

        public static MemberWalletResponse from(OrgMemberWallet wallet) {
            return new MemberWalletResponse(
                    wallet.getId(), wallet.getWalletAddress(), wallet.getLabel(),
                    wallet.getRoles(), wallet.getStatus(), wallet.getBoundTx(),
                    wallet.getCreatedAt(), wallet.getRemovedAt());
        }
    }

    public record WalletChallengeResponse(String nonce, String message, Instant expiresAt) {

        public static WalletChallengeResponse from(WalletBindChallenge challenge, String message) {
            return new WalletChallengeResponse(challenge.getNonce(), message, challenge.getExpiresAt());
        }
    }
}
