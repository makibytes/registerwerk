package de.makibytes.registerwerk.web.dto;

import de.makibytes.registerwerk.domain.asset.MintControlRule;

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
