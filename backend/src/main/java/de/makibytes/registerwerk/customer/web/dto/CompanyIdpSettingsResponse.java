package de.makibytes.registerwerk.customer.web.dto;

/**
 * Identity-provider settings shown to a company administrator.
 *
 * @param identityModel              how this customer's users are hosted —
 *                                   {@code WORKFORCE_MEMBER}, {@code WORKFORCE_GUEST} or
 *                                   {@code FEDERATED}. Operator-controlled; read-only here.
 * @param idpMfaTrusted              whether MFA performed in the customer's own tenant is
 *                                   accepted here. Operator-controlled, for the reason given in
 *                                   {@link CompanyIdpSettingsRequest}.
 * @param lifecycleManagedExternally true when user lifecycle is handled by the IdP rather than
 *                                   by Registerwerk's invite / password-reset flows
 */
public record CompanyIdpSettingsResponse(
    String issuerUrl,
    String clientId,
    String identityModel,
    boolean idpMfaTrusted,
    boolean lifecycleManagedExternally
) {}
