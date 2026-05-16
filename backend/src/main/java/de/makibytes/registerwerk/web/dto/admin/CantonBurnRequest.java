package de.makibytes.registerwerk.web.dto.admin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CantonBurnRequest(
        @NotBlank String holdingContractId,
        @NotNull @DecimalMin("0.000001") BigDecimal amount
) {}
