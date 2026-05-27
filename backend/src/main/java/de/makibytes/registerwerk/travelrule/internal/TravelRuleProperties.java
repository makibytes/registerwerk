package de.makibytes.registerwerk.travelrule.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.travel-rule")
public class TravelRuleProperties {

    private String protocol = "NOOP";
    private double thresholdEur = 1000.0;
    private Notabene notabene = new Notabene();
    private Trp trp = new Trp();

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public double getThresholdEur() { return thresholdEur; }
    public void setThresholdEur(double thresholdEur) { this.thresholdEur = thresholdEur; }

    public Notabene getNotabene() { return notabene; }
    public void setNotabene(Notabene notabene) { this.notabene = notabene; }

    public Trp getTrp() { return trp; }
    public void setTrp(Trp trp) { this.trp = trp; }

    public static class Notabene {
        private String baseUrl = "https://api.notabene.id";
        private String apiKey;
        private String vaspDid;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getVaspDid() { return vaspDid; }
        public void setVaspDid(String vaspDid) { this.vaspDid = vaspDid; }

        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class Trp {
        private String endpoint;
        private String mtlsCertPath;
        private String mtlsKeyPath;
        private String directoryUrl = "https://trp.notabene.id/directory";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getMtlsCertPath() { return mtlsCertPath; }
        public void setMtlsCertPath(String mtlsCertPath) { this.mtlsCertPath = mtlsCertPath; }

        public String getMtlsKeyPath() { return mtlsKeyPath; }
        public void setMtlsKeyPath(String mtlsKeyPath) { this.mtlsKeyPath = mtlsKeyPath; }

        public String getDirectoryUrl() { return directoryUrl; }
        public void setDirectoryUrl(String directoryUrl) { this.directoryUrl = directoryUrl; }

        public boolean isConfigured() { return endpoint != null && !endpoint.isBlank(); }
    }
}
