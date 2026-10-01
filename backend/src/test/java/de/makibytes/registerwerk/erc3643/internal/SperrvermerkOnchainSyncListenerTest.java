package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.erc3643.api.Erc3643Suite;
import de.makibytes.registerwerk.erc3643.api.Erc3643SuiteRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.kyc.events.HolderBlockCreatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockLiftedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.erc3643.events.HolderBlockNotPropagatedEvent;
import de.makibytes.registerwerk.kyc.events.HolderBlockFreezeResyncRequestedEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the §16 Sperrvermerk → on-chain freeze sync : a legally
 * blocked holder must not be able to repay/liquidate/withdraw pledged securities directly
 * on-chain, since {@code EwpgRepoMarket}'s lending contracts never consult the register.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SperrvermerkOnchainSyncListener unit tests")
class SperrvermerkOnchainSyncListenerTest {

    @Mock private AssetHolderRepository holderRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private Erc3643SuiteRepository suiteRepository;
    @Mock private Erc3643LifecycleService erc3643LifecycleService;
    @Mock private TokenAdminPort tokenAdminPort;
    @Mock private HolderBlockGate holderBlockGate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private SperrvermerkOnchainSyncListener listener;

    private static final String WALLET = "0x" + "aa".repeat(20);

    @BeforeEach
    void setUp() {
        listener = new SperrvermerkOnchainSyncListener(holderRepository, deploymentRepository,
                suiteRepository, erc3643LifecycleService, tokenAdminPort, holderBlockGate, eventPublisher);
    }

    private static AssetHolder holder(UUID assetId) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(assetId);
        h.setWalletAddress(WALLET);
        return h;
    }

    private static AssetDeployment deployment(UUID id, UUID assetId) {
        AssetDeployment d = new AssetDeployment();
        d.setId(id);
        d.setAssetId(assetId);
        return d;
    }

    @Test
    @DisplayName("onHolderBlockCreated freezes via Erc3643LifecycleService for an ERC-3643 deployment")
    void created_freezesViaErc3643ForKnownSuite() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.of(suite));

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "")));

        verify(erc3643LifecycleService).freezeAddress(eq(suiteId), eq(WALLET), any(), eq("SYSTEM"));
        verify(tokenAdminPort, never()).freezeAddress(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("onHolderBlockCreated falls back to TokenAdminPort for a non-ERC-3643 deployment")
    void created_freezesViaTokenAdminPortWhenNoSuite() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "")));

        verify(tokenAdminPort).freezeAddress(eq(deploymentId), eq(WALLET), anyString(), anyString(), any(), eq("SYSTEM"));
        verify(erc3643LifecycleService, never()).freezeAddress(any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("onHolderBlockCreated scopes to the specific asset when assetId is set (asset-specific block)")
    void created_scopesToAssetIdWhenSet() {
        UUID blockedAsset = UUID.randomUUID();
        UUID otherAsset = UUID.randomUUID();
        UUID blockedDeployment = UUID.randomUUID();
        UUID otherDeployment = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET)))
                .thenReturn(List.of(holder(blockedAsset), holder(otherAsset)));
        when(deploymentRepository.findByAssetId(blockedAsset)).thenReturn(List.of(deployment(blockedDeployment, blockedAsset)));
        when(suiteRepository.findByAssetDeploymentId(blockedDeployment)).thenReturn(Optional.empty());

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", blockedAsset.toString())));

        verify(tokenAdminPort).freezeAddress(eq(blockedDeployment), eq(WALLET), anyString(), anyString(), any(), eq("SYSTEM"));
        verify(deploymentRepository, never()).findByAssetId(otherAsset);
    }

    @Test
    @DisplayName("onHolderBlockLifted unfreezes when no other ACTIVE block remains on the wallet")
    void lifted_unfreezesWhenNoOtherActiveBlock() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderBlockGate.isBlockedForAsset(WALLET, assetId)).thenReturn(false);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Debt settled", "walletAddress", WALLET, "assetId", "")));

        verify(tokenAdminPort).unfreezeAfterBlockLift(deploymentId, WALLET);
    }

    @Test
    @DisplayName("blockLiftListenerStillUnfreezes: ERC-3643 lift uses the block-lift variant, not the guarded manual unfreeze")
    void blockLiftListenerStillUnfreezes() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        when(holderBlockGate.isBlockedForAsset(WALLET, assetId)).thenReturn(false);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        Erc3643Suite suite = new Erc3643Suite();
        suite.setId(suiteId);
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.of(suite));

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Court order lifted", "walletAddress", WALLET.toUpperCase().replace("0X", "0x"),
                        "assetId", "")));

        verify(erc3643LifecycleService).unfreezeAddressForBlockLift(suiteId, WALLET);
        verify(erc3643LifecycleService, never()).unfreezeAddress(any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("checksumAddressBlockFreezes: a checksum-cased block wallet is matched against the lowercase register")
    void created_checksumWalletIsNormalisedBeforeLookup() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", " 0x" + "AA".repeat(20), "legalBasis", "Court order", "assetId", "")));

        verify(tokenAdminPort).freezeAddress(eq(deploymentId), eq(WALLET), anyString(), anyString(), any(), eq("SYSTEM"));
    }

    @Test
    @DisplayName("listenerAlertsOnZeroDeployments: asset-scoped block with no register match on an EVM asset → ERROR event")
    void listenerAlertsOnZeroDeployments() {
        UUID assetId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        AssetDeployment evm = deployment(UUID.randomUUID(), assetId);
        evm.setChain(Chain.ETHEREUM);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of());
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(evm));

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(blockId, UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", assetId.toString())));

        ArgumentCaptor<HolderBlockNotPropagatedEvent> captor = ArgumentCaptor.forClass(HolderBlockNotPropagatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().holderBlockId()).isEqualTo(blockId);
        verify(tokenAdminPort, never()).freezeAddress(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("no alert for a wallet-wide block on a wallet that holds nothing")
    void noAlertForWalletWideBlockWithoutHoldings() {
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of());

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "")));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("V10 resync request re-applies the freeze like a newly created block")
    void resyncRequestFreezes() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockFreezeResyncRequested(new HolderBlockFreezeResyncRequestedEvent(UUID.randomUUID(),
                Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "")));

        verify(tokenAdminPort).freezeAddress(eq(deploymentId), eq(WALLET), anyString(), anyString(), any(), eq("SYSTEM"));
    }

    @Test
    @DisplayName("onHolderBlockLifted keeps the freeze of an asset another block still covers")
    void lifted_doesNotUnfreezeWhenAnotherBlockRemainsActive() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderBlockGate.isBlockedForAsset(WALLET, assetId)).thenReturn(true);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Debt settled", "walletAddress", WALLET, "assetId", "")));

        verify(tokenAdminPort, never()).unfreezeAfterBlockLift(any(), anyString());
        verify(erc3643LifecycleService, never()).unfreezeAddress(any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("6-25: lifting the last block releases deployments of ALL the wallet's assets, not only the lifted block's asset")
    void lifted_unfreezesEveryAssetNoRemainingBlockCovers() {
        UUID assetA = UUID.randomUUID();
        UUID assetB = UUID.randomUUID();
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        // Block X covered asset A, block Y covered asset B; Y is lifted last and its payload names asset B.
        when(holderBlockGate.isBlockedForAsset(eq(WALLET), any())).thenReturn(false);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetA), holder(assetB)));
        when(deploymentRepository.findByAssetId(assetA)).thenReturn(List.of(deployment(depA, assetA)));
        when(deploymentRepository.findByAssetId(assetB)).thenReturn(List.of(deployment(depB, assetB)));
        when(suiteRepository.findByAssetDeploymentId(any())).thenReturn(Optional.empty());

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Order lifted", "walletAddress", WALLET, "assetId", assetB.toString())));

        verify(tokenAdminPort).unfreezeAfterBlockLift(depA, WALLET);
        verify(tokenAdminPort).unfreezeAfterBlockLift(depB, WALLET);
    }

    @Test
    @DisplayName("an entity-scoped block freezes every wallet listed in walletAddresses")
    void created_freezesAllEntityWallets() {
        String second = "0x" + "bb".repeat(20);
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        AssetHolder h2 = holder(assetId);
        h2.setWalletAddress(second);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(holderRepository.findByWalletAddressIn(List.of(second))).thenReturn(List.of(h2));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "walletAddresses", List.of(WALLET, second),
                        "legalBasis", "Court order", "assetId", "")));

        verify(tokenAdminPort).freezeAddress(eq(deploymentId), eq(WALLET), anyString(), anyString(), any(), eq("SYSTEM"));
        verify(tokenAdminPort).freezeAddress(eq(deploymentId), eq(second), anyString(), anyString(), any(), eq("SYSTEM"));
    }

    @Test
    @DisplayName("a freeze failure is caught and logged, not propagated")
    void created_freezeFailureIsCaughtNotPropagated() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());
        when(tokenAdminPort.freezeAddress(any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("RPC unavailable"));

        listener.onHolderBlockCreated(new HolderBlockCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("walletAddress", WALLET, "legalBasis", "Court order", "assetId", "")));
        // No exception propagated — the Sperrvermerk DB record remains the authoritative source
        // of truth even if the on-chain freeze call fails.
    }
}
