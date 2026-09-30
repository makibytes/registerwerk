package de.makibytes.registerwerk.blockchain.web.dto;

import de.makibytes.registerwerk.shared.api.StrictAmount;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CantonBurnRequest(
        @NotBlank String holdingContractId,
        @JsonDeserialize(using = StrictAmount.BigDecimalAmount.class) @NotNull @DecimalMin("0.000001") BigDecimal amount
) {}
