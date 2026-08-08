package de.makibytes.registerwerk.customer.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Identity-provider settings a company administrator may edit.
 *
 * <p>There is deliberately no client secret. Inbound B2B federation is configured
 * tenant-to-tenant in the Entra portal; Registerwerk never runs an authorization-code flow
 * against a customer's tenant, so it has no use for their secret — and the old field stored it
 * in plaintext. V19 nulls the column and nothing writes it any more.
 *
 * <p>Note also what a customer <em>cannot</em> set here: whether MFA performed in their own
 * tenant is trusted. That is {@code legal_entity.idp_mfa_trusted}, operator-controlled, because
 * a customer vouching for their own MFA is a privilege-escalation path.
 */
public record CompanyIdpSettingsRequest(
    @Size(max = 512) @Pattern(regexp = "^$|https://\\S+") String issuerUrl,
    @Size(max = 255) String clientId
) {
    @AssertTrue(message = "issuerUrl and clientId must either both be set or both be empty")
    public boolean isCompletePair() {
        return isBlank(issuerUrl) == isBlank(clientId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
