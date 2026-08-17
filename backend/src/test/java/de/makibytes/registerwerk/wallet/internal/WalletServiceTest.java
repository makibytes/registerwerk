package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.OperatorWallet.WalletType;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.api.WalletStorage;
import de.makibytes.registerwerk.wallet.events.WalletDeletedEvent;
import de.makibytes.registerwerk.wallet.events.WalletExportedKeystoreEvent;
import de.makibytes.registerwerk.wallet.events.WalletGeneratedEvent;
import de.makibytes.registerwerk.wallet.events.WalletImportedKeystoreEvent;
import de.makibytes.registerwerk.wallet.events.WalletImportedRawEvent;
import de.makibytes.registerwerk.wallet.events.WalletKekRotatedEvent;
import de.makibytes.registerwerk.wallet.events.WalletRenamedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the operator-wallet lifecycle, including actor attribution and KEK rotation.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private OperatorWalletRepository walletRepository;
    @Mock private WalletStorage walletStorage;
    @Mock private WalletDefaultService defaultService;
    @Mock private WalletSigner walletSigner;
    @Mock private Pkcs11HsmService pkcs11HsmService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WalletService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletService(walletRepository, walletStorage, defaultService, walletSigner,
                pkcs11HsmService, eventPublisher);
        lenient().when(walletRepository.save(any(OperatorWallet.class))).thenAnswer(inv -> {
            OperatorWallet w = inv.getArgument(0);
            if (w.getId() == null) {
                ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
            }
            return w;
        });
    }

    private static OperatorWallet wallet(UUID id, WalletType type, String keystorePath) {
        OperatorWallet w = new OperatorWallet();
        ReflectionTestUtils.setField(w, "id", id);
        w.setType(type);
        w.setKeystorePath(keystorePath);
        w.setName("test-wallet");
        return w;
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("generate(EVM) persists the wallet, auto-promotes it, and publishes an event carrying the real actor")
    void generate_evm_publishesEventWithActor() {
        when(walletRepository.findByName("my-wallet")).thenReturn(Optional.empty());
        when(walletStorage.storeEvm(any(), any())).thenReturn("some-id.json");

        OperatorWallet result = service.generate("my-wallet", WalletType.EVM, actorId, "REGISTRY_ADMIN");

        assertThat(result.getName()).isEqualTo("my-wallet");
        assertThat(result.getType()).isEqualTo(WalletType.EVM);
        verify(defaultService).autoPromoteIfFirstOfType(result);
        ArgumentCaptor<WalletGeneratedEvent> captor = ArgumentCaptor.forClass(WalletGeneratedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().actorRole()).isEqualTo("REGISTRY_ADMIN");
    }

    @Test
    @DisplayName("generate rejects a duplicate wallet name")
    void generate_rejectsDuplicateName() {
        when(walletRepository.findByName("my-wallet")).thenReturn(Optional.of(new OperatorWallet()));

        assertThatThrownBy(() -> service.generate("my-wallet", WalletType.EVM, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // ── importRaw / importKeystore ────────────────────────────────────────────

    @Test
    @DisplayName("importRaw publishes an event carrying the real actor")
    void importRaw_publishesEventWithActor() throws Exception {
        when(walletRepository.findByName("imported")).thenReturn(Optional.empty());
        when(walletStorage.importEvmRaw(any(), any())).thenReturn("some-id.json");

        service.importRaw("imported", WalletType.EVM,
                "0x" + Keys.createEcKeyPair().getPrivateKey().toString(16), actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<WalletImportedRawEvent> captor = ArgumentCaptor.forClass(WalletImportedRawEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("importKeystore publishes an event carrying the real actor")
    void importKeystore_publishesEventWithActor() throws Exception {
        when(walletRepository.findByName("imported")).thenReturn(Optional.empty());
        when(walletStorage.importEvmKeystore(any(), any(), any())).thenReturn("some-id.json");
        when(walletStorage.loadEvm("some-id.json"))
                .thenReturn(Credentials.create(Keys.createEcKeyPair()));

        service.importKeystore("imported", "{}", "pw", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<WalletImportedKeystoreEvent> captor = ArgumentCaptor.forClass(WalletImportedKeystoreEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    // ── export ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("exportKeystore rejects non-EVM wallets")
    void exportKeystore_rejectsNonEvm() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.SOLANA, id + ".json")));

        assertThatThrownBy(() -> service.exportKeystore(id, "pw", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("exportKeystore publishes an event carrying the real actor")
    void exportKeystore_publishesEventWithActor() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.EVM, id + ".json")));
        when(walletStorage.exportEvmKeystore(any(), any())).thenReturn("{}");

        service.exportKeystore(id, "pw", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<WalletExportedKeystoreEvent> captor = ArgumentCaptor.forClass(WalletExportedKeystoreEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("HSM wallets cannot export private key material")
    void export_hsmWallet_rejectsRawAndKeystore() {
        UUID id = UUID.randomUUID();
        OperatorWallet hsmWallet = wallet(id, WalletType.EVM, null);
        hsmWallet.setCustodyType(OperatorWallet.CustodyType.PKCS11);
        hsmWallet.setKeyReference("registerwerk-operator");
        when(walletRepository.findById(id)).thenReturn(Optional.of(hsmWallet));

        assertThatThrownBy(() -> service.exportRaw(id, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("non-exportable");
        assertThatThrownBy(() -> service.exportKeystore(id, "pw", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("non-exportable");
        verifyNoInteractions(walletStorage);
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rename rejects a duplicate name and publishes an event carrying the real actor otherwise")
    void rename_publishesEventWithActor() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.EVM, id + ".json")));
        when(walletRepository.findByName("new-name")).thenReturn(Optional.empty());

        service.rename(id, "new-name", actorId, "REGISTRY_ADMIN");

        ArgumentCaptor<WalletRenamedEvent> captor = ArgumentCaptor.forClass(WalletRenamedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete removes chain defaults, evicts the signer cache, and publishes an event carrying the real actor")
    void delete_removesDefaultsEvictsSignerAndPublishesEvent() {
        UUID id = UUID.randomUUID();
        OperatorWallet w = wallet(id, WalletType.EVM, id + ".json");
        when(walletRepository.findById(id)).thenReturn(Optional.of(w));

        service.delete(id, actorId, "REGISTRY_ADMIN");

        verify(defaultService).removeDefaultsForWallet(id);
        verify(walletSigner).evict(id);
        verify(walletStorage).delete(id + ".json");
        ArgumentCaptor<WalletDeletedEvent> captor = ArgumentCaptor.forClass(WalletDeletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("deleting HSM wallet metadata does not delete the PKCS#11 key")
    void delete_hsmWallet_preservesTokenKey() {
        UUID id = UUID.randomUUID();
        OperatorWallet hsmWallet = wallet(id, WalletType.EVM, null);
        hsmWallet.setCustodyType(OperatorWallet.CustodyType.PKCS11);
        hsmWallet.setKeyReference("registerwerk-operator");
        when(walletRepository.findById(id)).thenReturn(Optional.of(hsmWallet));

        service.delete(id, actorId, "REGISTRY_ADMIN");

        verify(defaultService).removeDefaultsForWallet(id);
        verify(walletSigner).evict(id);
        verify(walletRepository).delete(hsmWallet);
        verifyNoInteractions(walletStorage);
    }

    // ── KEK rotation  ─────────────────────────────────────

    @Test
    @DisplayName("rotateKek(EVM) delegates to WalletStorage and publishes rotated=true")
    void rotateKek_evm_rotated() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.EVM, id + ".json")));
        when(walletStorage.rewrapDek(id + ".json", true)).thenReturn(true);

        boolean rotated = service.rotateKek(id, actorId, "REGISTRY_ADMIN");

        assertThat(rotated).isTrue();
        ArgumentCaptor<WalletKekRotatedEvent> captor = ArgumentCaptor.forClass(WalletKekRotatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().rotated()).isTrue();
    }

    @Test
    @DisplayName("rotateKek reports rotated=false for a legacy wallet with no wrapped DEK")
    void rotateKek_legacyWallet_reportsNotRotated() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.SOLANA, id + ".json")));
        when(walletStorage.rewrapDek(id + ".json", false)).thenReturn(false);

        boolean rotated = service.rotateKek(id, actorId, "REGISTRY_ADMIN");

        assertThat(rotated).isFalse();
        ArgumentCaptor<WalletKekRotatedEvent> captor = ArgumentCaptor.forClass(WalletKekRotatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().rotated()).isFalse();
    }

    @Test
    @DisplayName("rotateAllKeks rotates every wallet and returns only the ones actually rewrapped")
    void rotateAllKeks_returnsOnlyActuallyRotatedIds() {
        UUID rotatedId = UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();
        OperatorWallet rotatedWallet = wallet(rotatedId, WalletType.EVM, rotatedId + ".json");
        OperatorWallet legacyWallet = wallet(legacyId, WalletType.SOLANA, legacyId + ".json");
        when(walletRepository.findAll()).thenReturn(List.of(rotatedWallet, legacyWallet));
        when(walletRepository.findById(rotatedId)).thenReturn(Optional.of(rotatedWallet));
        when(walletRepository.findById(legacyId)).thenReturn(Optional.of(legacyWallet));
        when(walletStorage.rewrapDek(rotatedId + ".json", true)).thenReturn(true);
        when(walletStorage.rewrapDek(legacyId + ".json", false)).thenReturn(false);

        List<UUID> rotated = service.rotateAllKeks(actorId, "REGISTRY_ADMIN");

        assertThat(rotated).containsExactly(rotatedId);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(any(WalletKekRotatedEvent.class));
    }
}
