package de.makibytes.registerwerk.endpoint.internal;

import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.endpoint.api.AddressEndpointRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EndpointService.resolveAddresses Phase 7 (7A-10 interim)")
class EndpointResolvePhase7Test {

    private final AddressEndpointRepository endpoints = mock(AddressEndpointRepository.class);
    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final EndpointService service = new EndpointService(endpoints, holders, assets, entities);

    private final UUID issuer = UUID.randomUUID(), investorA = UUID.randomUUID(), investorB = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private static final String WALLET_A = "0x00000000000000000000000000000000000000a1";
    private static final String WALLET_B = "0x00000000000000000000000000000000000000b2";

    private AssetHolder holder(UUID investor, String wallet) {
        AssetHolder h = new AssetHolder();
        h.setAssetId(asset);
        h.setInvestorId(investor);
        h.setWalletAddress(wallet);
        return h;
    }

    private LegalEntity entity(UUID id, String name) {
        LegalEntity e = new LegalEntity();
        e.setId(id);
        e.setCurrentName(name);
        return e;
    }

    private void world(UUID callerIssuedAsset) {
        when(endpoints.findByEntityOwnerAndAddressIn(any(), any(), any())).thenReturn(List.of());
        when(assets.findIdsByIssuerId(issuer)).thenReturn(List.of(asset));
        when(assets.findIdsByIssuerId(investorA)).thenReturn(List.of());
        when(holders.findDistinctActiveAssetIdsByInvestorId(investorA)).thenReturn(List.of(asset));
        when(holders.findDistinctActiveAssetIdsByInvestorId(issuer)).thenReturn(List.of());
        when(holders.findByAssetIdInAndWalletAddressIn(any(), any()))
                .thenReturn(List.of(holder(investorA, WALLET_A), holder(investorB, WALLET_B)));
        when(entities.findAllById(any())).thenReturn(List.of(entity(investorA, "Investor A"), entity(investorB, "Investor B")));
    }

    @Test
    @DisplayName("a co-holder does not learn the identity of another holder, but still resolves its own wallet")
    void coHolderCannotResolveOtherHolder() {
        world(null);
        Map<String, String> result = service.resolveAddresses(List.of(WALLET_A, WALLET_B), false, investorA);
        assertThat(result).containsEntry(WALLET_A, "Investor A").doesNotContainKey(WALLET_B);
    }

    @Test
    @DisplayName("the issuer still resolves the holders of its own asset")
    void issuerResolvesOwnHolders() {
        world(asset);
        Map<String, String> result = service.resolveAddresses(List.of(WALLET_A, WALLET_B), false, issuer);
        assertThat(result).containsEntry(WALLET_A, "Investor A").containsEntry(WALLET_B, "Investor B");
    }
}
