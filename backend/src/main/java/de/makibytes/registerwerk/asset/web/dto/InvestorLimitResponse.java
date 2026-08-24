package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.api.InvestorLimit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvestorLimitResponse(
        UUID id,
        UUID assetId,
        UUID investorEntityId,
        BigDecimal minInvestmentOverride,
        BigDecimal maxHoldingOverride,
        LocalDate lockupUntil,
        Instant updatedAt,
        UUID updatedBy
) {
    public static InvestorLimitResponse from(InvestorLimit l) {
        return new InvestorLimitResponse(
                l.getId(), l.getAssetId(), l.getInvestorEntityId(), l.getMinInvestmentOverride(),
                l.getMaxHoldingOverride(), l.getLockupUntil(), l.getUpdatedAt(), l.getUpdatedBy());
    }
}
