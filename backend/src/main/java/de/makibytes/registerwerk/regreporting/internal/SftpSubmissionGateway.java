package de.makibytes.registerwerk.regreporting.internal;

import de.makibytes.registerwerk.regreporting.api.SubmissionGateway;
import de.makibytes.registerwerk.regreporting.api.SubmissionResult;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Generic SFTP transport for draft/unvalidated reporting documents.
 * Activate: REGISTERWERK_REPORTING_GATEWAY=SFTP
 *
 * Supports per-jurisdiction SFTP endpoints configured by an operator.
 * Uses Apache MINA SSHD for SFTP transport with private-key authentication.
 * A successful write is not an authenticated authority receipt or filing acceptance.
 */
@Component("sftpSubmissionGateway")
@ConditionalOnProperty(name = "registerwerk.reporting.gateway", havingValue = "SFTP")
class SftpSubmissionGateway implements SubmissionGateway {

    private static final Logger log = LoggerFactory.getLogger(SftpSubmissionGateway.class);
    private static final long CONNECT_TIMEOUT_SECONDS = 30;

    private final ReportingProperties props;

    SftpSubmissionGateway(ReportingProperties props) {
        this.props = props;
        log.info("SftpSubmissionGateway initialized");
    }

    @Override
    public SubmissionResult submit(UUID submissionId, String reportType,
                                   String jurisdiction, byte[] xmlDocument) {
        ReportingProperties.SftpEndpoint endpoint = props.sftpForJurisdiction(jurisdiction);
        if (endpoint == null || !endpoint.isConfigured()) {
            log.warn("SFTP not configured for jurisdiction={}; draft was not transported", jurisdiction);
            return SubmissionResult.notTransported("SFTP endpoint is not configured for " + jurisdiction);
        }

        String remoteFile = endpoint.getRemoteDir()
                + buildFilename(reportType, jurisdiction, submissionId);

        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try (ClientSession session = client.connect(endpoint.getUsername(),
                endpoint.getHost(), endpoint.getPort())
                .verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getSession()) {

            if (endpoint.getPrivateKeyPath() != null && !endpoint.getPrivateKeyPath().isBlank()) {
                KeyPair kp = loadKeyPair(endpoint.getPrivateKeyPath());
                session.addPublicKeyIdentity(kp);
            }

            session.auth().verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                try (OutputStream out = sftp.write(remoteFile,
                        SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                    out.write(xmlDocument);
                }
                log.info("Transported draft reporting document via SFTP (acceptance unverified): submissionId={} host={} file={}",
                        submissionId, endpoint.getHost(), remoteFile);
            }

            String ref = "SFTP-" + Instant.now().toEpochMilli() + "-" + submissionId;
            return SubmissionResult.transportedUnverified(ref);

        } catch (Exception e) {
            log.error("SFTP transport failed for submissionId={} jurisdiction={}: {}",
                    submissionId, jurisdiction, e.getMessage());
            return SubmissionResult.transportFailed("SFTP error: " + e.getMessage());
        } finally {
            client.stop();
        }
    }

    private static String buildFilename(String reportType, String jurisdiction, UUID submissionId) {
        return String.format("%s_%s_%s.xml",
                reportType.toLowerCase(),
                jurisdiction,
                submissionId.toString().replace("-", "").substring(0, 8));
    }

    private static KeyPair loadKeyPair(String privateKeyPath) throws Exception {
        org.apache.sshd.common.config.keys.loader.KeyPairResourceLoader loader =
                org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE;
        var pairs = loader.loadKeyPairs(null, Paths.get(privateKeyPath), null);
        return pairs.iterator().next();
    }
}
