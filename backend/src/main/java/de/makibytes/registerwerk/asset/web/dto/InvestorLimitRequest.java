package de.makibytes.registerwerk.asset.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestorLimitRequest(
        BigDecimal minInvestmentOverride,
        BigDecimal maxHoldingOverride,
        LocalDate lockupUntil
) {}
