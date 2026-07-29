package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.events.AssetRedeemedEvent;
import de.makibytes.registerwerk.blockchain.api.TokenAdminPort;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssetRedemptionListener unit tests")
class AssetRedemptionListenerTest {

    @Mock private AssetRepository assetRepository;
    @Mock private AssetDeploymentRepository deploymentRepository;
    @Mock private AssetHolderRepository holderRepository;
    @Mock private TokenAdminPort tokenAdminPort;

    @InjectMocks
    private AssetRedemptionListener listener;

    private static Asset asset(UUID id, TokenStandard standard) {
        Asset a = new Asset();
        a.setId(id);
        a.setAssetNumber("AST-2026-000042");
        a.setTokenStandard(standard);
        return a;
    }

    private static AssetHolder holder(String wallet, BigDecimal nominal) {
        AssetHolder h = new AssetHolder();
        h.setWalletAddress(wallet);
        h.setNominalAmount(nominal);
        return h;
    }

    @Test
    @DisplayName("dispatches a forceBurn per holder for an ERC-20 redemption")
    void dispatchesBurnForAutomatedStandard() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        Asset a = asset(assetId, TokenStandard.ERC20);
        AssetDeployment dep = new AssetDeployment();
        ReflectionTestUtils.setField(dep, "id", deploymentId);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(
                holder("0xaaa", new BigDecimal("500")),
                holder("0xbbb", BigDecimal.ZERO) // zero balance — must not dispatch a burn
        ));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(tokenAdminPort).forceBurn(eq(deploymentId), eq("0xaaa"), eq(BigInteger.valueOf(500)),
                any(String.class), any(UUID.class), eq("REGISTRY_ADMIN"));
        verify(tokenAdminPort, never()).forceBurn(any(), eq("0xbbb"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("dispatches forceBurnSingle with id=0 (not the now-guarded forceBurn) per holder for an ERC-1155 redemption")
    void dispatchesForceBurnSingleWithIdZeroForErc1155() {
        UUID assetId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        Asset a = asset(assetId, TokenStandard.ERC1155);
        AssetDeployment dep = new AssetDeployment();
        ReflectionTestUtils.setField(dep, "id", deploymentId);

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));
        when(holderRepository.findActiveByAssetId(assetId)).thenReturn(List.of(
                holder("0xccc", new BigDecimal("250"))
        ));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        // TokenAdminPort.forceBurn now rejects ERC-1155 outright (see TokenAdminService fix) —
        // the listener must route through forceBurnSingle with an explicit id 0 instead, which
        // preserves the exact on-chain effect this listener always had for ERC-1155.
        verify(tokenAdminPort).forceBurnSingle(eq(deploymentId), eq("0xccc"), eq(BigInteger.ZERO),
                eq(BigInteger.valueOf(250)), any(String.class), any(UUID.class), eq("REGISTRY_ADMIN"));
        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("does not dispatch any burn for a standard with no automated redemption path")
    void skipsUnautomatedStandard() {
        UUID assetId = UUID.randomUUID();
        Asset a = asset(assetId, TokenStandard.DAML_BOND_FIXED);
        AssetDeployment dep = new AssetDeployment();

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(a));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of(dep));

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
        verify(holderRepository, never()).findActiveByAssetId(any());
    }

    @Test
    @DisplayName("does nothing when the asset has no on-chain deployment")
    void skipsWhenNoDeployment() {
        UUID assetId = UUID.randomUUID();
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset(assetId, TokenStandard.ERC20)));
        when(deploymentRepository.findByAssetId(assetId)).thenReturn(List.of());

        listener.onAssetRedeemed(new AssetRedeemedEvent(assetId, UUID.randomUUID(), "REGISTRY_ADMIN"));

        verify(tokenAdminPort, never()).forceBurn(any(), any(), any(), any(), any(), any());
    }
}
