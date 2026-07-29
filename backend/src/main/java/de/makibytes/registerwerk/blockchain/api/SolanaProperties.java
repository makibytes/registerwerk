package de.makibytes.registerwerk.blockchain.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "registerwerk.chains.solana")
public class SolanaProperties {

    private NetworkProps mainnet;
    private NetworkProps testnet;

    public NetworkProps getMainnet() {
        return mainnet;
    }

    public void setMainnet(NetworkProps mainnet) {
        this.mainnet = mainnet;
    }

    public NetworkProps getTestnet() {
        return testnet;
    }

    public void setTestnet(NetworkProps testnet) {
        this.testnet = testnet;
    }

    /** Base58 program ID of the Registerwerk SPL Token-2022 transfer-hook program (whitelist +
     *  AML checks) — required before any {@code BOND}/{@code CONFIDENTIAL} preset mint can be
     *  created. Null/blank until the hook program is deployed. */
    private String transferHookProgramId;

    public String getTransferHookProgramId() {
        return transferHookProgramId;
    }

    public void setTransferHookProgramId(String transferHookProgramId) {
        this.transferHookProgramId = transferHookProgramId;
    }

    /** Hex-encoded 64-byte ElGamal public key for the registry's confidential-transfer auditor
     *  role — required before any {@code CONFIDENTIAL} preset mint can be created. Null/blank
     *  until the operator has generated and signed off on the real keypair. */
    private String confidentialTransferAuditorElgamalPubkey;

    public String getConfidentialTransferAuditorElgamalPubkey() {
        return confidentialTransferAuditorElgamalPubkey;
    }

    public void setConfidentialTransferAuditorElgamalPubkey(String confidentialTransferAuditorElgamalPubkey) {
        this.confidentialTransferAuditorElgamalPubkey = confidentialTransferAuditorElgamalPubkey;
    }

    public static class NetworkProps {
        private String rpcUrl;

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }
    }
}
