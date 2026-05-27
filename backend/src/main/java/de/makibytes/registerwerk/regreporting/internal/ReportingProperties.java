package de.makibytes.registerwerk.regreporting.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "registerwerk.reporting")
public class ReportingProperties {

    private String operatorTin;
    private String operatorLei;
    private String documentBucket = "registerwerk-reports";
    private String gateway = "NOOP";
    private Map<String, SftpEndpoint> sftp = new HashMap<>();

    public String getOperatorTin() { return operatorTin; }
    public void setOperatorTin(String operatorTin) { this.operatorTin = operatorTin; }

    public String getOperatorLei() { return operatorLei; }
    public void setOperatorLei(String operatorLei) { this.operatorLei = operatorLei; }

    public String getDocumentBucket() { return documentBucket; }
    public void setDocumentBucket(String documentBucket) { this.documentBucket = documentBucket; }

    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }

    public Map<String, SftpEndpoint> getSftp() { return sftp; }
    public void setSftp(Map<String, SftpEndpoint> sftp) { this.sftp = sftp; }

    public SftpEndpoint sftpForJurisdiction(String jurisdiction) {
        return sftp.get(jurisdiction);
    }

    public static class SftpEndpoint {
        private String host;
        private int port = 22;
        private String username;
        private String privateKeyPath;
        private String remoteDir = "/incoming/";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPrivateKeyPath() { return privateKeyPath; }
        public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

        public String getRemoteDir() { return remoteDir; }
        public void setRemoteDir(String remoteDir) { this.remoteDir = remoteDir; }

        public boolean isConfigured() { return host != null && !host.isBlank(); }
    }
}
