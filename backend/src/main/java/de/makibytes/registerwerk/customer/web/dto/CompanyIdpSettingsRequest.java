package de.makibytes.registerwerk.customer.web.dto;

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
    String issuerUrl,
    String clientId
) {}
