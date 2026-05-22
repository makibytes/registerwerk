package de.makibytes.registerwerk.auth.web.dto;

import java.util.List;

public record LoginResponse(
    String token,
    String tokenType,
    String userId,
    List<String> roles,
    long expiresAt
) {}
