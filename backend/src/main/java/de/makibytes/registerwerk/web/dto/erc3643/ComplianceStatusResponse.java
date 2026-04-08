package de.makibytes.registerwerk.web.dto.erc3643;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated compliance state for a T-REX suite.
 */
public record ComplianceStatusResponse(
    UUID suiteId,
    int investorCount,
    Integer maxInvestors,          // null = unlimited
    BigInteger maxBalance,         // null = unlimited
    Integer transferCooldown,      // seconds; null = disabled
    List<Short> blockedCountries,  // ISO-3166-1 numeric codes
    List<ModuleInfo> modules
) {
    public record ModuleInfo(
        UUID id,
        String moduleAddress,
        String moduleType,
        boolean active
    ) {}
}
