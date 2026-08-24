package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.SolanaFinalityReader;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.p2p.solanaj.rpc.RpcApi;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.types.Block;
import org.p2p.solanaj.rpc.types.ConfirmedTransaction;
import org.p2p.solanaj.rpc.types.SignatureStatuses;
import org.p2p.solanaj.rpc.types.config.Commitment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SolanaFinalityReaderImplTest {

    @Mock ChainConfigRepository chains;
    @Mock BlockchainClientRegistry clients;
    @Mock RpcClient client;
    @Mock RpcApi api;

    private SolanaFinalityReaderImpl reader;
    private UUID chainId;

    @BeforeEach
    void setUp() {
        reader = new SolanaFinalityReaderImpl(chains, clients);
        chainId = UUID.randomUUID();
        ChainConfig chain = new ChainConfig();
        chain.setId(chainId);
        chain.setChainType(ChainConfig.ChainType.SOLANA);
        chain.setIdentifier("SOLANA_DEVNET");
        when(chains.findById(chainId)).thenReturn(Optional.of(chain));
        when(clients.getSolanaClientByIdentifier("SOLANA_DEVNET")).thenReturn(client);
        when(client.getApi()).thenReturn(api);
    }

    @Test
    void finalizedSuccessfulSignatureCarriesExactSlotAndBlockHash() throws Exception {
        long slot = 401_234_567L;
        when(api.getSignatureStatuses(List.of("signature"), true))
                .thenReturn(statuses("finalized", slot));
        ConfirmedTransaction transaction = transactionWithError(null);
        when(api.getTransaction("signature", Commitment.FINALIZED, 0)).thenReturn(transaction);
        Block block = new Block();
        ReflectionTestUtils.setField(block, "blockHash", "FinalizedBlockHash");
        when(client.call(eq("getBlock"), anyList(), eq(Block.class))).thenReturn(block);

        SolanaFinalityReader.Result result = reader.read(chainId, "signature");

        assertThat(result.state()).isEqualTo(SolanaFinalityReader.State.FINALIZED);
        assertThat(result.slot()).isEqualTo(slot);
        assertThat(result.blockHash()).isEqualTo("FinalizedBlockHash");
    }

    @Test
    void finalizedFailedSignatureIsNotReportedAsSuccessful() throws Exception {
        when(api.getSignatureStatuses(List.of("signature"), true))
                .thenReturn(statuses("finalized", 42L));
        when(api.getTransaction("signature", Commitment.FINALIZED, 0))
                .thenReturn(transactionWithError("InstructionError"));

        SolanaFinalityReader.Result result = reader.read(chainId, "signature");

        assertThat(result.state()).isEqualTo(SolanaFinalityReader.State.FAILED);
        assertThat(result.failure()).contains("InstructionError");
        verify(client, never()).call(eq("getBlock"), anyList(), eq(Block.class));
    }

    private static SignatureStatuses statuses(String confirmation, long slot) {
        SignatureStatuses.Value value = new SignatureStatuses.Value();
        ReflectionTestUtils.setField(value, "confirmationStatus", confirmation);
        ReflectionTestUtils.setField(value, "slot", slot);
        SignatureStatuses statuses = new SignatureStatuses();
        ReflectionTestUtils.setField(statuses, "value", List.of(value));
        return statuses;
    }

    private static ConfirmedTransaction transactionWithError(Object error) {
        ConfirmedTransaction.Meta meta = new ConfirmedTransaction.Meta();
        ReflectionTestUtils.setField(meta, "err", error);
        ConfirmedTransaction transaction = new ConfirmedTransaction();
        ReflectionTestUtils.setField(transaction, "meta", meta);
        return transaction;
    }
}
