package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.asset.MintControlRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Request payload for creating a new mint control rule.
 */
public record MintControlRuleCreateRequest(
    @NotBlank String targetAddress,
    @NotNull MintControlRule.RuleType ruleType,
    BigDecimal maxAmount
) {}
