package de.makibytes.registerwerk.blockchain.api;

import org.web3j.abi.datatypes.Function;

import java.util.Map;
import java.util.UUID;

/**
 * Application-facing EVM write boundary: persist signed bytes in the caller's transaction,
 * broadcast only after commit, and retry the identical payload after ambiguous failures.
 */
public interface DurableEvmTransactionGateway {

    String submit(UUID chainConfigId, String contractAddress,
            Function function, Map<String, Object> params);
}
