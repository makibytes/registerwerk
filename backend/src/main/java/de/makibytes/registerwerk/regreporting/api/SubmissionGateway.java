package de.makibytes.registerwerk.regreporting.api;

import java.util.UUID;

/**
 * Port for regulatory report filing adapters.
 * Implementations: SftpSubmissionGateway (BaFin/ESMA ARM), NoopSubmissionGateway (dev fallback).
 * Activate: REGISTERWERK_REPORTING_GATEWAY=SFTP|NOOP
 */
public interface SubmissionGateway {

    /**
     * Submits a regulatory XML document to the appropriate authority channel.
     *
     * @param submissionId ID of the regreport_submission row (for correlation)
     * @param reportType   e.g. "DAC8_CARF", "MIFIR_RTS22"
     * @param jurisdiction e.g. "DE_BAFIN", "FR_AMF"
     * @param xmlDocument  the encoded XML payload
     * @return submission result with reference ID or error
     */
    SubmissionResult submit(UUID submissionId, String reportType, String jurisdiction, byte[] xmlDocument);
}
