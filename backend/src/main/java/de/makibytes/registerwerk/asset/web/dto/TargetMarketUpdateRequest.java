package de.makibytes.registerwerk.asset.web.dto;

import de.makibytes.registerwerk.customer.api.ClientCategory;
import de.makibytes.registerwerk.customer.api.KnowledgeExperienceLevel;

import java.util.Set;
import jakarta.validation.constraints.Size;

/** {@code categories} empty/null means unrestricted (see {@code Asset.isEligibleForTargetMarket}). */
public record TargetMarketUpdateRequest(
        @Size(max = 3) Set<ClientCategory> categories,
        KnowledgeExperienceLevel minExperience) {}
