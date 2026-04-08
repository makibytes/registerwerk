package de.makibytes.registerwerk.infrastructure.blockchain.solana;

import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.stereotype.Component;

/**
 * Factory that creates Solana RpcClient instances from RPC URLs.
 */
@Component
public class SolanaClientFactory {

    /**
     * Creates a new Solana {@link RpcClient} connected to the given RPC endpoint.
     *
     * @param rpcUrl HTTP(S) URL of the Solana JSON-RPC endpoint
     * @return configured RpcClient instance
     */
    public RpcClient createClient(String rpcUrl) {
        return new RpcClient(rpcUrl);
    }
}
