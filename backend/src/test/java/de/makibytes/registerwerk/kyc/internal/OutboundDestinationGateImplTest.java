package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.kyc.api.HolderBlockGate;
import de.makibytes.registerwerk.screening.api.ScreeningGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("P4C-2 OutboundDestinationGate")
class OutboundDestinationGateImplTest {

    private static final String ADDR = "0x52908400098527886e0f7030069857d2e4169ee7"; // EIP-55 test vector, lowercased
    private static final String CHECKSUM = "0x52908400098527886E0F7030069857D2E4169EE7";

    private final UUID assetId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();
    private AssetHolderRepository holders;
    private LegalEntityRepository entities;
    private ScreeningGate screening;
    private HolderBlockGate blocks;
    private OutboundDestinationGateImpl gate;
    private LegalEntity entity;

    @BeforeEach
    void setUp() {
        holders = mock(AssetHolderRepository.class);
        entities = mock(LegalEntityRepository.class);
        screening = mock(ScreeningGate.class);
        blocks = mock(HolderBlockGate.class);
        gate = new OutboundDestinationGateImpl(holders, entities, screening, blocks, true);

        AssetHolder holder = new AssetHolder();
        ReflectionTestUtils.setField(holder, "id", UUID.randomUUID());
        holder.setInvestorId(entityId);
        holder.setWalletAddress(ADDR);
        when(holders.findActiveByAssetIdAndWalletAddress(assetId, ADDR)).thenReturn(Optional.of(holder));
        entity = new LegalEntity();
        ReflectionTestUtils.setField(entity, "id", entityId);
        entity.setStatus(EntityStatus.ACTIVE);
        entity.setKycStatus(KycStatus.APPROVED);
        when(entities.findById(entityId)).thenReturn(Optional.of(entity));
    }

    @Test
    @DisplayName("an ACTIVE, KYC-approved, screened holder resolves (checksum case accepted)")
    void resolvesApprovedHolder() {
        assertThat(gate.require(assetId, CHECKSUM, "mint").entityId()).isEqualTo(entityId);
        assertThat(gate.require(assetId, ADDR, "mint").address()).isEqualTo(ADDR);
    }

    @Test
    @DisplayName("an address that is not a holder of the asset is refused")
    void unknownAddressRefused() {
        assertThatThrownBy(() -> gate.require(assetId, "0x" + "9".repeat(40), "mint"))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("onboard");
    }

    @Test
    @DisplayName("a rejected or expired KYC, an unresolved screening hit or a block refuses the destination")
    void nonCompliantHolderRefused() {
        entity.setKycStatus(KycStatus.REJECTED);
        assertThatThrownBy(() -> gate.require(assetId, ADDR, "mint")).isInstanceOf(AccessDeniedException.class);
        entity.setKycStatus(KycStatus.APPROVED);

        when(screening.hasUnresolvedHit(entityId)).thenReturn(true);
        assertThatThrownBy(() -> gate.require(assetId, ADDR, "mint")).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("screening");
        when(screening.hasUnresolvedHit(entityId)).thenReturn(false);

        when(blocks.isBlocked(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> gate.require(assetId, ADDR, "mint")).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Sperrvermerk");
    }

    @Test
    @DisplayName("a mixed-case address with a wrong EIP-55 checksum is rejected (400) before any lookup")
    void badChecksumRejected() {
        String bad = CHECKSUM.replace("E0F", "e0F");
        assertThatThrownBy(() -> gate.require(assetId, bad, "mint"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("the feature flag off skips the gate (demo profile only)")
    void disabledFlagSkips() {
        var off = new OutboundDestinationGateImpl(holders, entities, screening, blocks, false);
        assertThat(off.require(assetId, "0x" + "9".repeat(40), "mint")).isNull();
        verifyNoInteractions(entities);
    }

    @Test
    @DisplayName("5C-03: an unresolved BENEFICIAL OWNER hit and an expired KYC date refuse the destination too (shared helper)")
    void uboHitAndExpiredKycRefused() {
        when(screening.hasUnresolvedBeneficialOwnerHit(entityId)).thenReturn(true);
        assertThatThrownBy(() -> gate.require(assetId, ADDR, "mint")).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("screening");
        when(screening.hasUnresolvedBeneficialOwnerHit(entityId)).thenReturn(false);

        entity.setKycExpiryDate(java.time.LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> gate.require(assetId, ADDR, "mint")).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("expired KYC");
    }
}
