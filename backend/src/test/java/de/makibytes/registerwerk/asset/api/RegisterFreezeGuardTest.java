package de.makibytes.registerwerk.asset.api;

import de.makibytes.registerwerk.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegisterFreezeGuardTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void statusHelpers() {
        assertThat(AssetStatus.ISSUED.isRegisterFrozen()).isFalse();
        assertThat(AssetStatus.SUSPENDED.isRegisterFrozen()).isFalse();
        assertThat(AssetStatus.TRANSFER_PENDING.isRegisterFrozen()).isTrue();
        assertThat(AssetStatus.TRANSFERRED_OUT.isRegisterFrozen()).isTrue();
        assertThat(AssetStatus.TRANSFER_PENDING.isAdministeredHere()).isTrue();
        assertThat(AssetStatus.TRANSFERRED_OUT.isAdministeredHere()).isFalse();
    }

    @Test
    void requireOpenRefusesFrozenAndTransferredOnly() {
        assertThatCode(() -> RegisterFreezeGuard.requireOpen(AssetStatus.ISSUED, id, "x")).doesNotThrowAnyException();
        assertThatCode(() -> RegisterFreezeGuard.requireOpen((AssetStatus) null, id, "x")).doesNotThrowAnyException();
        assertThatThrownBy(() -> RegisterFreezeGuard.requireOpen(AssetStatus.TRANSFER_PENDING, id, "Trade"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("TRANSFER_PENDING");
        assertThatThrownBy(() -> RegisterFreezeGuard.requireOpen("TRANSFERRED_OUT", id, "Trade"))
                .isInstanceOf(InvalidStateTransitionException.class).hasMessageContaining("TRANSFERRED_OUT");
        assertThatCode(() -> RegisterFreezeGuard.requireOpen("NOT_A_STATUS", id, "x")).doesNotThrowAnyException();
    }

    @Test
    void repositoryLookupAndReadOnlyVariant() {
        AssetRepository repo = mock(AssetRepository.class);
        Asset pending = new Asset();
        pending.setId(id);
        pending.setStatus(AssetStatus.TRANSFER_PENDING);
        when(repo.findById(id)).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> RegisterFreezeGuard.requireOpen(repo, id, "Edit"))
                .isInstanceOf(InvalidStateTransitionException.class);
        // a statement/extract is still possible while merely pending, but not after the handover
        assertThatCode(() -> RegisterFreezeGuard.requireAdministeredHere(pending, "Statement")).doesNotThrowAnyException();
        pending.setStatus(AssetStatus.TRANSFERRED_OUT);
        assertThatThrownBy(() -> RegisterFreezeGuard.requireAdministeredHere(pending, "Statement"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
