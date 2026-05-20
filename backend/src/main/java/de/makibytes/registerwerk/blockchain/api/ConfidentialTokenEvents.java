package de.makibytes.registerwerk.blockchain.api;

import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * Shared parsing for the {@code EwpgConfidentialFactory.ConfidentialTokenDeployed} event.
 */
public final class ConfidentialTokenEvents {

    public static final String TOKEN_DEPLOYED_TOPIC =
            "0x" + org.web3j.crypto.Hash.sha3String("ConfidentialTokenDeployed(bytes32,uint8,address)");

    private ConfidentialTokenEvents() {}

    public static String extractTokenAddress(TransactionReceipt receipt) {
        for (Log entry : receipt.getLogs()) {
            if (entry.getTopics() != null
                    && !entry.getTopics().isEmpty()
                    && TOKEN_DEPLOYED_TOPIC.equalsIgnoreCase(entry.getTopics().get(0))
                    && entry.getTopics().size() >= 4) {
                String padded = entry.getTopics().get(3);
                return "0x" + padded.substring(padded.length() - 40);
            }
        }
        throw new RuntimeException(
                "ConfidentialTokenDeployed event not found in receipt: "
                        + receipt.getTransactionHash());
    }
}
