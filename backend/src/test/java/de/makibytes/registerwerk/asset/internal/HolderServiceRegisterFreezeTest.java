package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.asset.api.Asset;
import de.makibytes.registerwerk.asset.api.AssetRepository;
import de.makibytes.registerwerk.asset.api.AssetStatus;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T3-07: register entries cannot be added, changed or removed while the register is frozen / transferred out. */
class HolderServiceRegisterFreezeTest {

    private final AssetHolderRepository holders = mock(AssetHolderRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final HolderService service = new HolderService(holders, assets, mock(ApplicationEventPublisher.class),
            mock(de.makibytes.registerwerk.customer.api.LegalEntityRepository.class),
            mock(de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository.class),
            mock(HolderChangeRepository.class),
            new de.makibytes.registerwerk.shared.RegisterClock(java.time.Clock.systemDefaultZone(), java.time.ZoneId.systemDefault()));

    private static final HolderInstruction INSTRUCTION = new HolderInstruction(InstructingParty.COURT, "AZ 1");

    @Test
    void registerEditsRefusedWhileFrozen() {
        for (AssetStatus status : new AssetStatus[] {AssetStatus.TRANSFER_PENDING, AssetStatus.TRANSFERRED_OUT}) {
            UUID assetId = UUID.randomUUID();
            Asset asset = new Asset();
            asset.setId(assetId);
            asset.setStatus(status);
            when(assets.findById(assetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> service.addHolder(assetId, UUID.randomUUID(), "0x" + "1".repeat(40),
                    BigDecimal.TEN, INSTRUCTION, UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> service.addSingleEntryHolder(assetId, UUID.randomUUID(), "0x" + "1".repeat(40),
                    BigDecimal.TEN, true, null, null, null, INSTRUCTION, UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> service.updateSingleEntryAttributes(assetId, UUID.randomUUID(),
                    new HolderService.AttributeChange(true, "x", null, null, false, false), INSTRUCTION,
                    UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> service.removeHolder(assetId, UUID.randomUUID(), UUID.randomUUID(), "REGISTRY_ADMIN"))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
        verify(holders, never()).save(any());
    }
}
