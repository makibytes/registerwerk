package de.makibytes.registerwerk.chain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.chains.canton")
public class CantonProperties {

    private NetworkProps mainnet;
    private NetworkProps devnet;

    public NetworkProps getMainnet() { return mainnet; }
    public void setMainnet(NetworkProps mainnet) { this.mainnet = mainnet; }

    public NetworkProps getDevnet() { return devnet; }
    public void setDevnet(NetworkProps devnet) { this.devnet = devnet; }

    public static class NetworkProps {
        /** gRPC Ledger API endpoint, e.g. "participant.example.com:5001". */
        private String ledgerApiUrl;
        /** Canton synchronizer/domain alias, e.g. "global-synchronizer". */
        private String synchronizerId;
        /** Application ID registered with the participant. */
        private String applicationId = "registerwerk";
        /** JWT bearer token for participant authentication. */
        private String authToken;

        public String getLedgerApiUrl() { return ledgerApiUrl; }
        public void setLedgerApiUrl(String ledgerApiUrl) { this.ledgerApiUrl = ledgerApiUrl; }

        public String getSynchronizerId() { return synchronizerId; }
        public void setSynchronizerId(String synchronizerId) { this.synchronizerId = synchronizerId; }

        public String getApplicationId() { return applicationId; }
        public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
    }
}
