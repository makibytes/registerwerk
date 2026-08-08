package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.deployment.api.MintControlRule;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Nullable fields give this DTO true PATCH semantics while still validating supplied values. */
public record MintControlRuleUpdateRequest(
        @Size(min = 1, max = 128) String targetAddress,
        MintControlRule.RuleType ruleType,
        @Positive BigDecimal maxAmount
) {}
