package de.makibytes.registerwerk.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthFeeHistory;
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EvmContractService second-node fee cross-check min(A, B*1.5) (P4B-2 / K5)")
class EvmFeeCrossCheckTest {

    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    private final BlockchainClientRegistry registry = mock(BlockchainClientRegistry.class);
    private final EvmContractService service = new EvmContractService(registry, null, null, null, null);

    private static Web3j nodeWithBaseFee(long baseFeeGwei, long tipGwei) throws Exception {
        Web3j w = mock(Web3j.class, RETURNS_DEEP_STUBS);
        EthFeeHistory.FeeHistory fh = new EthFeeHistory.FeeHistory();
        fh.setBaseFeePerGas(List.of("0x" + BigInteger.valueOf(baseFeeGwei).multiply(GWEI).toString(16)));
        EthFeeHistory hist = mock(EthFeeHistory.class);
        when(hist.hasError()).thenReturn(false);
        when(hist.getFeeHistory()).thenReturn(fh);
        when(w.ethFeeHistory(1, org.web3j.protocol.core.DefaultBlockParameterName.LATEST, List.of()).send()).thenReturn(hist);
        EthMaxPriorityFeePerGas tip = mock(EthMaxPriorityFeePerGas.class);
        when(tip.getMaxPriorityFeePerGas()).thenReturn(BigInteger.valueOf(tipGwei).multiply(GWEI));
        when(w.ethMaxPriorityFeePerGas().send()).thenReturn(tip);
        return w;
    }

    private BigInteger[] cross(Web3j primary, Web3j other, long aMaxGwei, long aTipGwei) throws Exception {
        Class<?> feesCls = Class.forName("de.makibytes.registerwerk.blockchain.api.EvmContractService$Fees");
        Method mk = feesCls.getDeclaredMethod("eip1559", BigInteger.class, BigInteger.class);
        mk.setAccessible(true);
        Object a = mk.invoke(null, BigInteger.valueOf(aTipGwei).multiply(GWEI), BigInteger.valueOf(aMaxGwei).multiply(GWEI));
        when(registry.evmNodeClients("X")).thenReturn(List.of(
                new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), primary, true),
                new BlockchainClientRegistry.EvmNodeClient(UUID.randomUUID(), other, true)));
        Method m = EvmContractService.class.getDeclaredMethod("crossCheckFees", feesCls, Web3j.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(service, a, primary, "X");
        Method tipM = feesCls.getDeclaredMethod("maxPriorityFeePerGas");
        Method maxM = feesCls.getDeclaredMethod("maxFeePerGas");
        tipM.setAccessible(true);
        maxM.setAccessible(true);
        return new BigInteger[] {(BigInteger) tipM.invoke(result), (BigInteger) maxM.invoke(result)};
    }

    @Test
    @DisplayName("an inflated fee from node A is bounded by 1.5x node B's independent view")
    void inflatedPrimaryIsBounded() throws Exception {
        Web3j a = nodeWithBaseFee(400, 2);   // A claims 802 gwei max fee
        Web3j b = nodeWithBaseFee(20, 2);    // B: 2*20+2 = 42 gwei -> cap 63 gwei
        BigInteger[] r = cross(a, b, 802, 2);
        assertThat(r[1]).isEqualTo(BigInteger.valueOf(63).multiply(GWEI));
    }

    @Test
    @DisplayName("a lower fee from A is kept (never raised towards B)")
    void lowerPrimaryKept() throws Exception {
        Web3j a = nodeWithBaseFee(10, 1);
        Web3j b = nodeWithBaseFee(50, 2);
        BigInteger[] r = cross(a, b, 21, 1);
        assertThat(r[1]).isEqualTo(BigInteger.valueOf(21).multiply(GWEI));
    }
}
