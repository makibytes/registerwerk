package de.makibytes.registerwerk.travelrule.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Port for pluggable Travel Rule protocol adapters.
 * Implementations: TrpAdapter (OpenTRP), NotabeneAdapter, SygnaAdapter, OpenVaspAdapter.
 * Wired via @ConditionalOnProperty on registerwerk.travel-rule.protocol.
 */
public interface TravelRuleProtocolPort {

    String protocolName();

    /**
     * Send an outbound IVMS-101 payload to the beneficiary VASP.
     * Returns the protocol-assigned message ID.
     */
    CompletableFuture<String> send(UUID transferId, Ivms101.TravelRuleMessage payload);

    /**
     * Look up a VASP by wallet address.
     * Returns the VASP's LEI/DID + endpoint URL, or empty if unresolved (self-hosted wallet).
     */
    java.util.Optional<VaspInfo> lookupVasp(String walletAddress);

    record VaspInfo(String vaspId, String legalName, String country, String endpoint) {}
}
