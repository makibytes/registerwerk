package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.wallet.api.ChainBalanceFetcher;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.rpc.RpcClient;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;

@Component
class ChainBalanceFetcherImpl implements ChainBalanceFetcher {
    private static final BigDecimal WEI_PER_ETH = BigDecimal.TEN.pow(18);
    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    private final BlockchainClientRegistry clientRegistry;
    ChainBalanceFetcherImpl(BlockchainClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    @Override
    public BigDecimal getEvmBalance(String address, String chainIdentifier) throws Exception {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        BigInteger wei = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
        return new BigDecimal(wei).divide(WEI_PER_ETH, 8, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public BigDecimal getSolanaBalance(String address, String chainIdentifier) throws Exception {
        RpcClient client = clientRegistry.getSolanaClientByIdentifier(chainIdentifier);
        long lamports = client.getApi().getBalance(new PublicKey(address));
        return BigDecimal.valueOf(lamports).divide(LAMPORTS_PER_SOL, 8, java.math.RoundingMode.HALF_UP);
    }
}
