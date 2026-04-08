package de.makibytes.registerwerk.web.dto.erc3643;

import java.math.BigInteger;
import java.util.List;

/**
 * Request body for adding or updating a compliance module configuration.
 * All fields are optional; null means "keep current value" on update, or "unlimited/disabled" on create.
 */
public record ModuleConfigRequest(
    /** On-chain address of the deployed compliance module contract. */
    String moduleAddress,
    /** Logical module type identifier (e.g. "MAX_BALANCE", "COUNTRY_RESTRICT"). */
    String moduleType,
    Integer maxInvestors,
    BigInteger maxBalance,
    Integer transferCooldown,
    List<Short> blockedCountries
) {}
