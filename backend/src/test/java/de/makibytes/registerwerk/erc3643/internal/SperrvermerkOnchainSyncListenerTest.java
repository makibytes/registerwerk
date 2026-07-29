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

/**
 * Unit tests for the §16 Sperrvermerk → on-chain freeze sync (finding #1, Phase 7): a legally
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

    private SperrvermerkOnchainSyncListener listener;

    private static final String WALLET = "0x" + "aa".repeat(20);

    @BeforeEach
    void setUp() {
        listener = new SperrvermerkOnchainSyncListener(holderRepository, deploymentRepository,
                suiteRepository, erc3643LifecycleService, tokenAdminPort, holderBlockGate);
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
        when(holderBlockGate.isBlocked(null, WALLET)).thenReturn(false);
        when(holderRepository.findByWalletAddressIn(List.of(WALLET))).thenReturn(List.of(holder(assetId)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(deployment(deploymentId, assetId)));
        when(suiteRepository.findByAssetDeploymentId(deploymentId)).thenReturn(Optional.empty());

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Debt settled", "walletAddress", WALLET, "assetId", "")));

        verify(tokenAdminPort).unfreezeAddress(eq(deploymentId), eq(WALLET), any(), eq("SYSTEM"));
    }

    @Test
    @DisplayName("onHolderBlockLifted does NOT unfreeze when another ACTIVE block still covers the wallet")
    void lifted_doesNotUnfreezeWhenAnotherBlockRemainsActive() {
        when(holderBlockGate.isBlocked(null, WALLET)).thenReturn(true);

        listener.onHolderBlockLifted(new HolderBlockLiftedEvent(UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN",
                null, Map.of("reason", "Debt settled", "walletAddress", WALLET, "assetId", "")));

        verify(tokenAdminPort, never()).unfreezeAddress(any(), anyString(), any(), anyString());
        verify(erc3643LifecycleService, never()).unfreezeAddress(any(), anyString(), any(), anyString());
        verify(holderRepository, never()).findByWalletAddressIn(any());
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
