package de.makibytes.registerwerk.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RpcNodeCreateRequest(
        @NotBlank String url,
        String label
) {}
