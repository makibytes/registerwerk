package de.makibytes.registerwerk.stepup.web.dto;

/** {@code secret} is shown once for manual entry; {@code otpauthUri} encodes it as a QR code. */
public record TotpEnrollmentResponse(String secret, String otpauthUri) {}
