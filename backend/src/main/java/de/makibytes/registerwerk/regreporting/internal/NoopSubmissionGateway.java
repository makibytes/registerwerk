package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.api.SubmissionGateway;
import de.makibytes.registerwerk.regreporting.api.SubmissionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * No-op gateway: logs transport intent but does not transmit. Active when no real
 * gateway is configured (REGISTERWERK_REPORTING_GATEWAY != SFTP).
 */
@Component
@ConditionalOnMissingBean(name = "sftpSubmissionGateway")
class NoopSubmissionGateway implements SubmissionGateway {

    private static final Logger log = LoggerFactory.getLogger(NoopSubmissionGateway.class);

    @Override
    public SubmissionResult submit(UUID submissionId, String reportType,
                                   String jurisdiction, byte[] xmlDocument) {
        log.warn("NoopSubmissionGateway: draft NOT transported. SFTP, if enabled outside production, " +
                 "would prove transport only. submissionId={} reportType={} jurisdiction={} bytes={}",
                 submissionId, reportType, jurisdiction, xmlDocument.length);
        return SubmissionResult.notTransported("NOOP adapter: no transport attempted");
    }
}
