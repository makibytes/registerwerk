package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetCode;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Payment-rail on-chain sanity check (5A-11)")
class PaymentRailOnchainVerifierTest {

    private static final UUID CHAIN = UUID.randomUUID();
    private static final String TOKEN = "0x" + "aa".repeat(20);

    private final EvmContractService evm = mock(EvmContractService.class);
    private final Web3j web3j = mock(Web3j.class, RETURNS_DEEP_STUBS);

    private void code(String code) throws IOException {
        EthGetCode resp = new EthGetCode();
        resp.setResult(code);
        when(web3j.ethGetCode(anyString(), any()).send()).thenReturn(resp);
        when(evm.evmClient(CHAIN)).thenReturn(web3j);
    }

    private void decimals(int value) {
        when(evm.call(any(), anyString(), any())).thenReturn(List.<Type>of(new Uint8(BigInteger.valueOf(value))));
    }

    @Test
    @DisplayName("matching decimals and deployed code pass")
    void passes() throws Exception {
        code("0x6080");
        decimals(6);
        assertThatCode(() -> new PaymentRailOnchainVerifier(evm, true)
                .verify(PaymentRailType.STABLECOIN, 6, Map.of(CHAIN, TOKEN))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("declared decimals differing from decimals() are rejected")
    void decimalsMismatch() throws Exception {
        code("0x6080");
        decimals(18);
        assertThatThrownBy(() -> new PaymentRailOnchainVerifier(evm, true)
                .verify(PaymentRailType.STABLECOIN, 6, Map.of(CHAIN, TOKEN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decimals");
    }

    @Test
    @DisplayName("an address without contract code is rejected")
    void noCode() throws Exception {
        code("0x");
        assertThatThrownBy(() -> new PaymentRailOnchainVerifier(evm, true)
                .verify(PaymentRailType.STABLECOIN, 6, Map.of(CHAIN, TOKEN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no contract code");
    }

    @Test
    @DisplayName("an RPC error fails closed")
    void rpcErrorFailsClosed() throws Exception {
        when(evm.evmClient(CHAIN)).thenReturn(web3j);
        when(web3j.ethGetCode(anyString(), any()).send()).thenThrow(new IOException("boom"));
        assertThatThrownBy(() -> new PaymentRailOnchainVerifier(evm, true)
                .verify(PaymentRailType.STABLECOIN, 6, Map.of(CHAIN, TOKEN)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("off-chain rails, empty address sets and the disabled switch do not touch the chain")
    void skips() {
        new PaymentRailOnchainVerifier(evm, true).verify(PaymentRailType.OFFCHAIN_SEPA, null, Map.of(CHAIN, TOKEN));
        new PaymentRailOnchainVerifier(evm, true).verify(PaymentRailType.STABLECOIN, 6, Map.of());
        new PaymentRailOnchainVerifier(evm, false).verify(PaymentRailType.STABLECOIN, 6, Map.of(CHAIN, TOKEN));
        verifyNoInteractions(evm);
    }
}
