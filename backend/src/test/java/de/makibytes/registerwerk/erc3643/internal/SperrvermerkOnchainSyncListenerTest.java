package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.events.BlockchainTxStatusEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The listener only translates events into {@link SperrvermerkFreezeService} calls (wallet canonicalisation T3-15,
 * entity-scoped wallet lists 6-25, the asset scope); what happens to each deployment is tested in
 * {@code SperrvermerkFreezeServiceTest}.
 */
@DisplayName("SperrvermerkOnchainSyncListener unit tests")
class SperrvermerkOnchainSyncListenerTest {

    private static final String WALLET = "0x" + "aa".repeat(20);
    private static final String REASON = "eWpG §16 Sperrvermerk: Court order";

    private final SperrvermerkFreezeService service = mock(SperrvermerkFreezeService.class);
    private SperrvermerkOnchainSyncListener listener;

    @BeforeEach
    void setUp() {
        listener = new SperrvermerkOnchainSyncListener(service);
    }

    private static HolderBlockCreatedEvent created(UUID blockId, Map<String, Object> payload) {
        return new HolderBlockCreatedEvent(blockId, UUID.randomUUID(), "REGISTRY_ADMIN", null, payload);
    }

    @SuppressWarnings("unchecked")
    private Collection<String> propagatedWallets(UUID blockId, UUID assetId) {
        ArgumentCaptor<Collection<String>> wallets = ArgumentCaptor.forClass(Collection.class);
        if (assetId == null) {
            verify(service).propagate(eq(blockId), isNull(), wallets.capture(), eq(REASON));
        } else {
            verify(service).propagate(eq(blockId), eq(assetId), wallets.capture(), eq(REASON));
        }
        return wallets.getValue();
    }

    @Test
    @DisplayName("a created block propagates its canonical wallet; a blank assetId means every asset the wallet holds")
    void created_propagatesTheCanonicalWallet() {
        UUID blockId = UUID.randomUUID();

        listener.onHolderBlockCreated(created(blockId,
                Map.of("walletAddress", " 0x" + "AA".repeat(20), "legalBasis", "Court order", "assetId", "")));

        assertThat(propagatedWallets(blockId, null)).containsExactly(WALLET);
    }

    @Test
    @DisplayName("an asset-scoped block passes its asset; an unparseable asset id is treated as unscoped")
    void created_passesTheAssetScope() {
        UUID blockId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        listener.onHolderBlockCreated(created(blockId,
                Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", assetId.toString())));
        listener.onHolderBlockCreated(created(UUID.randomUUID(),
                Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "not-a-uuid")));

        assertThat(propagatedWallets(blockId, assetId)).containsExactly(WALLET);
        verify(service).propagate(any(), isNull(), any(), eq(REASON));
    }

    @Test
    @DisplayName("an entity-scoped block propagates every wallet it lists, once each")
    void created_listsAllEntityWallets() {
        String second = "0x" + "bb".repeat(20);
        UUID blockId = UUID.randomUUID();

        listener.onHolderBlockCreated(created(blockId, Map.of("walletAddress", WALLET,
                "walletAddresses", List.of(WALLET, second, "  "), "legalBasis", "Court order", "assetId", "")));

        assertThat(propagatedWallets(blockId, null)).containsExactly(WALLET, second);
    }

    @Test
    @DisplayName("a lifted block releases the wallets it covered, through the service's reconciling release")
    @SuppressWarnings("unchecked")
    void lifted_releasesTheBlocksWallets() {
        UUID blockId = UUID.randomUUID();

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(blockId, UUID.randomUUID(), "REGISTRY_ADMIN", null,
                Map.of("reason", "Debt settled", "walletAddress", WALLET.toUpperCase().replace("0X", "0x"),
                        "walletAddresses", List.of(WALLET), "assetId", "")));

        ArgumentCaptor<Collection<String>> wallets = ArgumentCaptor.forClass(Collection.class);
        verify(service).release(eq(blockId), wallets.capture());
        assertThat(wallets.getValue()).containsExactly(WALLET);
    }

    @Test
    @DisplayName("a transaction status event is forwarded by its hash; one without a hash is ignored")
    void transactionStatus_isForwardedByHash() {
        listener.onTransactionStatus(new BlockchainTxStatusEvent(null, null, "SYSTEM", "FAILED", Map.of("txHash", "0xabc")));
        listener.onTransactionStatus(new BlockchainTxStatusEvent(null, null, "SYSTEM", "FAILED", Map.of()));
        listener.onTransactionStatus(new BlockchainTxStatusEvent(null, null, "SYSTEM", "FAILED", null));

        verify(service).onTransactionStatus("0xabc");
        verify(service, never()).onTransactionStatus(eq(""));
        verify(service, never()).propagate(any(), any(), any(), anyString());
    }
}
