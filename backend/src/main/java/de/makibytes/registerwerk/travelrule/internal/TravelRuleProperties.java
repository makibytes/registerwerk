package de.makibytes.registerwerk.travelrule.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.travel-rule")
public class TravelRuleProperties {

    private String protocol = "NOOP";
    private Notabene notabene = new Notabene();
    private Trp trp = new Trp();
    private OwnVasp ownVasp = new OwnVasp();
    /** Hard cap on how long a gated operation waits for the protocol to accept a message. */
    private int sendTimeoutSeconds = 15;
    /** Inline delivery attempts (with exponential backoff) before the gate refuses. */
    private int sendAttempts = 2;
    private long sendBackoffMillis = 500;
    /** PENDING_SEND rows older than this are crash leftovers: marked FAILED, alerted and re-queued. */
    private int stalePendingMinutes = 5;
    /** Background re-delivery of stale/queued FAILED rows. */
    private int maxRetryAttempts = 5;
    /**
     * Treat a beneficiary wallet that belongs to an onboarded holder of the same register as
     * {@code INTERNAL_REGISTER_TRANSFER} (recorded, no Art. 14(5) block). A legal position (T6-06):
     * off until the operator has signed it off.
     */
    private boolean registerInternalExempt = false;
    /** Accept the pre-V42 shared inbox key (one release of grace; never combined with a trusted X-Vasp-Id). */
    private boolean legacySharedKey = false;
    /** Inbound HMAC freshness window. */
    private int inboundWindowSeconds = 300;
    private int inboundMaxBodyBytes = 262_144;

    public OwnVasp getOwnVasp() { return ownVasp; }
    public void setOwnVasp(OwnVasp ownVasp) { this.ownVasp = ownVasp; }
    public int getSendTimeoutSeconds() { return sendTimeoutSeconds; }
    public void setSendTimeoutSeconds(int v) { this.sendTimeoutSeconds = v; }
    public int getSendAttempts() { return sendAttempts; }
    public void setSendAttempts(int v) { this.sendAttempts = v; }
    public long getSendBackoffMillis() { return sendBackoffMillis; }
    public void setSendBackoffMillis(long v) { this.sendBackoffMillis = v; }
    public int getStalePendingMinutes() { return stalePendingMinutes; }
    public void setStalePendingMinutes(int v) { this.stalePendingMinutes = v; }
    public int getMaxRetryAttempts() { return maxRetryAttempts; }
    public void setMaxRetryAttempts(int v) { this.maxRetryAttempts = v; }
    public boolean isRegisterInternalExempt() { return registerInternalExempt; }
    public void setRegisterInternalExempt(boolean v) { this.registerInternalExempt = v; }
    public boolean isLegacySharedKey() { return legacySharedKey; }
    public void setLegacySharedKey(boolean v) { this.legacySharedKey = v; }
    public int getInboundWindowSeconds() { return inboundWindowSeconds; }
    public void setInboundWindowSeconds(int v) { this.inboundWindowSeconds = v; }
    public int getInboundMaxBodyBytes() { return inboundMaxBodyBytes; }
    public void setInboundMaxBodyBytes(int v) { this.inboundMaxBodyBytes = v; }

    /** Registerwerk operator's own VASP identity: the {@code originatingVasp} of every outbound message. */
    public static class OwnVasp {
        private String did;
        private String lei;
        private String legalName;

        public String getDid() { return did; }
        public void setDid(String did) { this.did = did; }
        public String getLei() { return lei; }
        public void setLei(String lei) { this.lei = lei; }
        public String getLegalName() { return legalName; }
        public void setLegalName(String legalName) { this.legalName = legalName; }

        /** Identifier used in {@code originatingVasp.vaspId}: DID, else LEI. */
        public String identifier() {
            if (did != null && !did.isBlank()) return did.trim();
            return lei == null || lei.isBlank() ? null : lei.trim();
        }
    }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

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
        /** Optional allow-list of delivery hosts; empty = any public https host. */
        private java.util.List<String> allowedHosts = new java.util.ArrayList<>();
        /** Development/test only: permit http and loopback/private delivery hosts. Refused in production. */
        private boolean allowInsecureEndpoints = false;

        public boolean isAllowInsecureEndpoints() { return allowInsecureEndpoints; }
        public void setAllowInsecureEndpoints(boolean v) { this.allowInsecureEndpoints = v; }

        public java.util.List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(java.util.List<String> allowedHosts) { this.allowedHosts = allowedHosts; }

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
