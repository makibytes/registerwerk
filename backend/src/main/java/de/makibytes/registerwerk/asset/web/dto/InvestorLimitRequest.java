package de.makibytes.registerwerk.asset.web.dto;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestorLimitRequest(
        @DecimalMin(value = "0", inclusive = false) BigDecimal minInvestmentOverride,
        @DecimalMin(value = "0", inclusive = false) BigDecimal maxHoldingOverride,
        LocalDate lockupUntil
) {}
