package de.makibytes.registerwerk.kyc;

import de.makibytes.registerwerk.kyc.api.KycComplianceService;
import de.makibytes.registerwerk.customer.api.Jurisdiction;

import java.util.UUID;

/**
 * Public API for KYC compliance checks.
 * Use {@link KycComplianceService.ComplianceResult} for the return type.
 */
public interface KycApi {

    KycComplianceService.ComplianceResult checkCompliance(UUID entityId, Jurisdiction jurisdiction);
}
