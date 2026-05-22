package de.makibytes.registerwerk.customer.web.dto;

public record CompanyIdpSettingsResponse(
    String issuerUrl,
    String clientId,
    String clientSecret,
    boolean hasClientSecret,
    boolean lifecycleManagedExternally
) {}
