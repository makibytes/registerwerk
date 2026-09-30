package de.makibytes.registerwerk.blockchain.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FailoverWeb3jService (P4B-8)")
class FailoverWeb3jServiceTest {

    private final UUID idA = UUID.randomUUID();
    private final UUID idB = UUID.randomUUID();
    private final UUID idC = UUID.randomUUID();
    private final Web3jService a = mock(Web3jService.class);
    private final Web3jService b = mock(Web3jService.class);
    private final Web3jService c = mock(Web3jService.class);
    private final List<UUID> failed = new ArrayList<>();
    private final List<UUID> accepted = new ArrayList<>();

    private FailoverWeb3jService service() {
        return new FailoverWeb3jService(
                () -> List.of(new FailoverWeb3jService.Target(idA, a), new FailoverWeb3jService.Target(idB, b),
                        new FailoverWeb3jService.Target(idC, c)),
                failed::add, accepted::add);
    }

    private static Request<?, EthBlockNumber> read() {
        return new Request<>("eth_blockNumber", List.of(), null, EthBlockNumber.class);
    }

    private static Request<?, EthSendTransaction> write() {
        return new Request<>("eth_sendRawTransaction", List.of("0x00"), null, EthSendTransaction.class);
    }

    @Test
    @DisplayName("a read that fails on the top node is answered by the next node in the same call")
    void readFailsOverOnTransportError() throws Exception {
        EthBlockNumber ok = new EthBlockNumber();
        when(a.send(any(), any())).thenThrow(new IOException("connection refused"));
        when(b.send(any(), any())).thenReturn(ok);

        EthBlockNumber result = service().send(read(), EthBlockNumber.class);

        assertThat(result).isSameAs(ok);
        assertThat(failed).containsExactly(idA);
        verify(c, never()).send(any(), any());
    }

    @Test
    @DisplayName("failover is bounded: three attempts, then the last transport error surfaces")
    void boundedAttempts() throws Exception {
        when(a.send(any(), any())).thenThrow(new IOException("a"));
        when(b.send(any(), any())).thenThrow(new IOException("b"));
        when(c.send(any(), any())).thenThrow(new IOException("c"));

        assertThatThrownBy(() -> service().send(read(), EthBlockNumber.class))
                .isInstanceOf(IOException.class).hasMessage("c");
        assertThat(failed).containsExactly(idA, idB, idC);
    }

    @Test
    @DisplayName("eth_sendRawTransaction goes to the top node only and is never retried elsewhere")
    void writeNotRetried() throws Exception {
        when(a.send(any(), any())).thenThrow(new IOException("timeout"));

        assertThatThrownBy(() -> service().send(write(), EthSendTransaction.class)).isInstanceOf(IOException.class);
        verify(b, never()).send(any(), any());
        assertThat(accepted).isEmpty();
    }

    @Test
    @DisplayName("an accepted write reports the accepting node (read-your-writes affinity)")
    void acceptedWriteReported() throws Exception {
        EthSendTransaction ok = new EthSendTransaction();
        when(a.send(any(), any())).thenReturn(ok);

        service().send(write(), EthSendTransaction.class);

        assertThat(accepted).containsExactly(idA);
    }
}
