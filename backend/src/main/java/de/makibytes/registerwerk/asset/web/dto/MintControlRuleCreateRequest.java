package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.MintControlRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for creating a new mint control rule.
 */
public record MintControlRuleCreateRequest(
    @NotBlank @Size(max = 128) String targetAddress,
    @NotNull MintControlRule.RuleType ruleType,
    @Positive BigDecimal maxAmount
) {}
