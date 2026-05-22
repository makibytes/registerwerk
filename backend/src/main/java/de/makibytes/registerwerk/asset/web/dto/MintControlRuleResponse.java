package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.asset.api.MintControlRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a mint control rule.
 */
public record MintControlRuleResponse(
    UUID id,
    String targetAddress,
    MintControlRule.RuleType ruleType,
    BigDecimal maxAmount,
    Boolean active,
    Instant createdAt
) {}
