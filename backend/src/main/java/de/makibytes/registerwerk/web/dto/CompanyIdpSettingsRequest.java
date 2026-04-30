package de.makibytes.registerwerk.web.dto;

public record CompanyIdpSettingsRequest(
    String issuerUrl,
    String clientId,
    String clientSecret
) {}
