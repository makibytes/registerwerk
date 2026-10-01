package de.makibytes.registerwerk.kyc.internal;

import de.makibytes.registerwerk.kyc.api.HolderBlock;
import de.makibytes.registerwerk.kyc.api.HolderBlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HolderBlockGateImpl §16 eWpG Sperrvermerk gate unit tests")
class HolderBlockGateImplTest {

    @Mock
    private HolderBlockRepository holderBlockRepository;

    @Mock
    private de.makibytes.registerwerk.deployment.api.AssetHolderRepository holderRepository;

    private HolderBlockGateImpl gate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gate = new HolderBlockGateImpl(holderBlockRepository, holderRepository);
    }

    @Test
    @DisplayName("returns false when neither the entity nor the wallet has an active block")
    void notBlocked_whenNoActiveBlocks() {
        UUID entityId = UUID.randomUUID();
        String wallet = "0x1111111111111111111111111111111111111111";
        when(holderBlockRepository.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING)).thenReturn(List.of());
        when(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING)).thenReturn(List.of());

        assertThat(gate.isBlocked(entityId, wallet)).isFalse();
    }

    @Test
    @DisplayName("returns true when the entity has an active block")
    void blocked_whenEntityHasActiveBlock() {
        UUID entityId = UUID.randomUUID();
        when(holderBlockRepository.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(entityId, null)).isTrue();
    }

    @Test
    @DisplayName("returns true when the wallet address has an active block, even without a known entity")
    void blocked_whenWalletHasActiveBlock() {
        String wallet = "0x2222222222222222222222222222222222222222";
        when(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(null, wallet)).isTrue();
    }

    @Test
    @DisplayName("a lifted (non-ACTIVE) block on the wallet does not block")
    void notBlocked_whenOnlyLiftedBlockExists() {
        String wallet = "0x3333333333333333333333333333333333333333";
        when(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING)).thenReturn(List.of());

        assertThat(gate.isBlocked(null, wallet)).isFalse();
    }

    @Test
    @DisplayName("checksum-cased wallet argument matches the normalised stored block (T3-15)")
    void blocked_whenChecksumWalletMatchesNormalisedBlock() {
        when(holderBlockRepository.findByWalletAddressAndStatusIn("0x" + "ab".repeat(20), HolderBlock.BLOCKING))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(null, " 0x" + "AB".repeat(20) + " ")).isTrue();
    }

    @Test
    @DisplayName("6-25: isEntityBlocked sees a wallet-only block on one of the entity's holder wallets (repo gate used to miss it)")
    void entityBlocked_whenWalletOnlyBlockOnHolderWallet() {
        UUID entityId = UUID.randomUUID();
        String wallet = "0x4444444444444444444444444444444444444444";
        de.makibytes.registerwerk.deployment.api.AssetHolder holder = new de.makibytes.registerwerk.deployment.api.AssetHolder();
        holder.setWalletAddress(wallet);
        when(holderBlockRepository.findByEntityIdAndStatusIn(entityId, HolderBlock.BLOCKING)).thenReturn(List.of());
        when(holderRepository.findActiveByInvestorId(entityId)).thenReturn(List.of(holder));
        when(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(entityId, null)).isFalse();
        assertThat(gate.isEntityBlocked(entityId)).isTrue();
    }

    @Test
    @DisplayName("isBlockedForAsset: an asset-scoped block on another asset does not cover this one; a wallet-wide one does")
    void blockedForAsset_respectsScope() {
        String wallet = "0x5555555555555555555555555555555555555555";
        UUID assetA = UUID.randomUUID();
        UUID assetB = UUID.randomUUID();
        HolderBlock onA = new HolderBlock();
        onA.setAssetId(assetA);
        when(holderBlockRepository.findByWalletAddressAndStatusIn(wallet, HolderBlock.BLOCKING)).thenReturn(List.of(onA));
        when(holderRepository.findByWalletAddressIn(List.of(wallet))).thenReturn(List.of());

        assertThat(gate.isBlockedForAsset(wallet, assetA)).isTrue();
        assertThat(gate.isBlockedForAsset(wallet, assetB)).isFalse();
    }
}
