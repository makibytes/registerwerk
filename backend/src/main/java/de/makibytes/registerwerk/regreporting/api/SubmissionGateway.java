package de.makibytes.registerwerk.regreporting.api;

import java.util.UUID;

/**
 * Port for draft-document transport adapters.
 *
 * <p>A successful result proves only that bytes reached the configured transport endpoint. It
 * does not prove official-schema validity, routing to a competent authority, filing, acceptance,
 * or satisfaction of a reporting obligation.
 * Implementations: SftpSubmissionGateway (generic SFTP), NoopSubmissionGateway (dev fallback).
 * Activate: REGISTERWERK_REPORTING_GATEWAY=SFTP|NOOP
 */
public interface SubmissionGateway {

    /**
     * Attempts to transport a draft/unvalidated XML document.
     *
     * @param submissionId ID of the regreport_submission row (for correlation)
     * @param reportType   e.g. "DAC8_CARF", "MIFIR_RTS22"
     * @param jurisdiction e.g. "DE_BAFIN", "FR_AMF"
     * @param xmlDocument  the encoded XML payload
     * @return transport result with a local transport reference or error
     */
    SubmissionResult submit(UUID submissionId, String reportType, String jurisdiction, byte[] xmlDocument);
}
