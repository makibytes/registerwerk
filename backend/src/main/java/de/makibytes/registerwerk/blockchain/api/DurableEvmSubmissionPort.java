package de.makibytes.registerwerk.blockchain.api;

import org.web3j.abi.datatypes.Function;

import java.util.Map;
import java.util.UUID;

/** Public module boundary for persist-before-broadcast EVM submissions. */
public interface DurableEvmSubmissionPort {

    PreparedSubmission prepare(UUID chainConfigId, String contractAddress,
            Function function, Map<String, Object> params);

    void dispatch(UUID submissionId);

    record PreparedSubmission(UUID id, String txHash) {}
}
