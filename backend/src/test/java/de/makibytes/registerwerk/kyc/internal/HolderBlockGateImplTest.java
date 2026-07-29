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

    private HolderBlockGateImpl gate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        gate = new HolderBlockGateImpl(holderBlockRepository);
    }

    @Test
    @DisplayName("returns false when neither the entity nor the wallet has an active block")
    void notBlocked_whenNoActiveBlocks() {
        UUID entityId = UUID.randomUUID();
        String wallet = "0x1111111111111111111111111111111111111111";
        when(holderBlockRepository.findByEntityIdAndStatus(entityId, HolderBlock.Status.ACTIVE)).thenReturn(List.of());
        when(holderBlockRepository.findByWalletAddressAndStatus(wallet, HolderBlock.Status.ACTIVE)).thenReturn(List.of());

        assertThat(gate.isBlocked(entityId, wallet)).isFalse();
    }

    @Test
    @DisplayName("returns true when the entity has an active block")
    void blocked_whenEntityHasActiveBlock() {
        UUID entityId = UUID.randomUUID();
        when(holderBlockRepository.findByEntityIdAndStatus(entityId, HolderBlock.Status.ACTIVE))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(entityId, null)).isTrue();
    }

    @Test
    @DisplayName("returns true when the wallet address has an active block, even without a known entity")
    void blocked_whenWalletHasActiveBlock() {
        String wallet = "0x2222222222222222222222222222222222222222";
        when(holderBlockRepository.findByWalletAddressAndStatus(wallet, HolderBlock.Status.ACTIVE))
                .thenReturn(List.of(new HolderBlock()));

        assertThat(gate.isBlocked(null, wallet)).isTrue();
    }

    @Test
    @DisplayName("a lifted (non-ACTIVE) block on the wallet does not block")
    void notBlocked_whenOnlyLiftedBlockExists() {
        String wallet = "0x3333333333333333333333333333333333333333";
        when(holderBlockRepository.findByWalletAddressAndStatus(wallet, HolderBlock.Status.ACTIVE)).thenReturn(List.of());

        assertThat(gate.isBlocked(null, wallet)).isFalse();
    }
}
