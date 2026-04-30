package de.makibytes.registerwerk.web.dto;

public record CompanyIdpSettingsResponse(
    String issuerUrl,
    String clientId,
    String clientSecret,
    boolean hasClientSecret,
    boolean lifecycleManagedExternally
) {}
