package de.makibytes.registerwerk.erc3643.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CustomClaimRequest(
        @Positive long topic,
        @NotBlank @Size(max = 100) String topicLabel,
        @Future Instant expiresAt
) {}
