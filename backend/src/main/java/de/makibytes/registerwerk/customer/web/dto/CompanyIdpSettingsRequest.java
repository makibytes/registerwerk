package de.makibytes.registerwerk.customer.web.dto;

public record CompanyIdpSettingsRequest(
    String issuerUrl,
    String clientId,
    String clientSecret
) {}
