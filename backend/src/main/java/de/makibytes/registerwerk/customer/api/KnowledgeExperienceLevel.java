package de.makibytes.registerwerk.customer.api;

/** Investment knowledge/experience self-assessed in a {@link SuitabilityAssessment} and, on the
 *  product side, the minimum an asset's target market requires
 *  ({@code Asset.getTargetMarketMinExperience()}). Ordinal order is significant — it is compared
 *  with {@code compareTo} to check "at least" a required level. */
public enum KnowledgeExperienceLevel {
    NONE,
    BASIC,
    ADVANCED
}
