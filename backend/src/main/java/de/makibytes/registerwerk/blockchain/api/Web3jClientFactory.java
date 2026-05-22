package de.makibytes.registerwerk.blockchain.api;

import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Factory that creates Web3j clients from RPC URLs.
 */
@Component
public class Web3jClientFactory {

    /**
     * Creates a new Web3j instance connected to the given RPC endpoint.
     *
     * @param rpcUrl HTTP(S) URL of the EVM JSON-RPC endpoint
     * @return configured Web3j instance
     */
    public Web3j createClient(String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }
}
