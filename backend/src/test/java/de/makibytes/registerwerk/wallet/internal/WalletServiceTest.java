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
    @Mock private KmsSignerService kmsSignerService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private de.makibytes.registerwerk.wallet.api.WalletUsagePort usagePort;

    private WalletService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletService(walletRepository, walletStorage, defaultService, walletSigner,
                pkcs11HsmService, kmsSignerService, eventPublisher, usagePort);
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
        verify(defaultService).bootstrapDefaultIfFirstWalletEver(result);
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
    @DisplayName("P4C-5: delete is a soft delete — tombstone + event with approver, key material kept")
    void delete_softDeletesAndKeepsKeyMaterial() {
        UUID id = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        OperatorWallet w = wallet(id, WalletType.EVM, id + ".json");
        when(walletRepository.findById(id)).thenReturn(Optional.of(w));
        when(defaultService.findDefaultChainIds(id)).thenReturn(List.of());
        when(usagePort.hasSignedOnChain(w.getAddress())).thenReturn(false);

        service.delete(id, actorId, "REGISTRY_ADMIN", approver);

        verify(walletSigner).evict(id);
        verify(walletRepository).softDelete(id, actorId, approver);
        verify(walletRepository, org.mockito.Mockito.never()).delete(any(OperatorWallet.class));
        verifyNoInteractions(walletStorage);
        ArgumentCaptor<WalletDeletedEvent> captor = ArgumentCaptor.forClass(WalletDeletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approver);
    }

    @Test
    @DisplayName("P4C-5: delete is refused for a chain default")
    void delete_refusedForChainDefault() {
        UUID id = UUID.randomUUID();
        when(walletRepository.findById(id)).thenReturn(Optional.of(wallet(id, WalletType.EVM, id + ".json")));
        when(defaultService.findDefaultChainIds(id)).thenReturn(List.of(UUID.randomUUID()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(id, actorId, "REGISTRY_ADMIN", null))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class);
        verify(walletRepository, org.mockito.Mockito.never()).softDelete(any(), any(), any());
    }

    @Test
    @DisplayName("P4C-5: delete is refused for a key that ever signed on chain (deployer/registry/claim-issuer authority)")
    void delete_refusedForOnChainSigner() {
        UUID id = UUID.randomUUID();
        OperatorWallet w = wallet(id, WalletType.EVM, id + ".json");
        when(walletRepository.findById(id)).thenReturn(Optional.of(w));
        when(defaultService.findDefaultChainIds(id)).thenReturn(List.of());
        when(usagePort.hasSignedOnChain(w.getAddress())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(id, actorId, "REGISTRY_ADMIN", null))
                .isInstanceOf(de.makibytes.registerwerk.shared.InvalidStateTransitionException.class)
                .hasMessageContaining("signer-rotation");
        verify(walletRepository, org.mockito.Mockito.never()).softDelete(any(), any(), any());
    }

    @Test
    @DisplayName("P4C-5: generate goes through the bootstrap-only default promotion")
    void generate_usesBootstrapPromotionOnly() {
        service.generate("fresh", WalletType.EVM, actorId, "REGISTRY_ADMIN");
        verify(defaultService).bootstrapDefaultIfFirstWalletEver(any(OperatorWallet.class));
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

    private OperatorWallet tomb() {
        UUID id = UUID.randomUUID();
        OperatorWallet t = wallet(id, WalletType.EVM, id + ".json");
        t.setCustodyType(OperatorWallet.CustodyType.SOFTWARE);
        return t;
    }

    @Test
    @DisplayName("purgeExpired keeps the keystore when the DELETE hit no row (restore won the race)")
    void purgeExpired_restoredMeanwhile_keepsKeystore() {
        OperatorWallet t = tomb();
        when(walletRepository.findDeletedBefore(any())).thenReturn(List.of(t));
        when(walletRepository.purge(t.getId())).thenReturn(0);

        assertThat(service.purgeExpired(java.time.Instant.now())).isZero();

        verifyNoInteractions(walletStorage);
    }

    @Test
    @DisplayName("purgeExpired destroys the keystore only after the DB delete commits, never on rollback")
    void purgeExpired_keystoreDeletedAfterCommitOnly() {
        OperatorWallet t = tomb();
        when(walletRepository.findDeletedBefore(any())).thenReturn(List.of(t, tomb()));
        when(walletRepository.purge(any())).thenReturn(1);

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.purgeExpired(java.time.Instant.now())).isEqualTo(2);
            verifyNoInteractions(walletStorage); // still inside the transaction
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            // rollback: afterCommit is never invoked
            syncs.forEach(sync -> sync.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(walletStorage);
            // commit path
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
        verify(walletStorage, org.mockito.Mockito.times(2)).delete(any());
    }

    // ── KMS custody (T7-05) ───────────────────────────────────────────────────

    private WalletService serviceWithKms(FakeKmsSigningClient fake, boolean enabled) {
        KmsProperties props = new KmsProperties();
        if (enabled) {
            props.setSigner("kms");
        }
        props.getKms().setRetryBackoff(java.time.Duration.ofMillis(1));
        KmsSignerService kms = new KmsSignerService(props, Optional.of(fake),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        return new WalletService(walletRepository, walletStorage, defaultService, walletSigner,
                pkcs11HsmService, kms, eventPublisher, usagePort);
    }

    @Test
    @DisplayName("attachKms derives the address from the KMS key, proves signing, stores a KMS wallet and never touches keystores")
    void attachKms_persistsOpaqueWallet() throws Exception {
        FakeKmsSigningClient fake = new FakeKmsSigningClient(Keys.createEcKeyPair());
        WalletService kmsService = serviceWithKms(fake, true);
        when(walletRepository.findByName("kms-registry")).thenReturn(Optional.empty());

        OperatorWallet w = kmsService.attachKms("kms-registry", FakeKmsSigningClient.KEY, null, actorId, "REGISTRY_ADMIN");

        assertThat(w.getCustodyType()).isEqualTo(OperatorWallet.CustodyType.KMS);
        assertThat(w.getKeyReference()).isEqualTo(FakeKmsSigningClient.KEY);
        assertThat(w.getKeystorePath()).isNull();
        assertThat(w.getType()).isEqualTo(WalletType.EVM);
        assertThat(w.getAddress()).isEqualTo(Keys.toChecksumAddress(Credentials.create(fake.key).getAddress()));
        assertThat(fake.signCalls.get()).as("enrolment challenge").isEqualTo(1);
        verify(defaultService).bootstrapDefaultIfFirstWalletEver(w);
        ArgumentCaptor<WalletGeneratedEvent> event = ArgumentCaptor.forClass(WalletGeneratedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().actorId()).isEqualTo(actorId);
        verifyNoInteractions(walletStorage);
    }

    @Test
    @DisplayName("attachKms is refused when KMS signing is off, for a malformed reference, or a foreign address")
    void attachKms_refusals() throws Exception {
        FakeKmsSigningClient fake = new FakeKmsSigningClient(Keys.createEcKeyPair());
        lenient().when(walletRepository.findByName(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> serviceWithKms(fake, false)
                .attachKms("n", FakeKmsSigningClient.KEY, null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("registerwerk.wallet.signer=kms");
        WalletService kmsService = serviceWithKms(fake, true);
        assertThatThrownBy(() -> kmsService.attachKms("n", "alias/x", null, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
        String foreign = Credentials.create("0x" + "22".repeat(32)).getAddress();
        assertThatThrownBy(() -> kmsService.attachKms("n", FakeKmsSigningClient.KEY, foreign, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not control");
        verify(walletRepository, org.mockito.Mockito.never()).save(any());
        assertThat(fake.signCalls.get()).isZero();
    }

    @Test
    @DisplayName("KMS wallets cannot export key material or rotate a KEK")
    void kmsWalletsAreOpaque() {
        UUID id = UUID.randomUUID();
        OperatorWallet kmsWallet = wallet(id, WalletType.EVM, null);
        kmsWallet.setCustodyType(OperatorWallet.CustodyType.KMS);
        kmsWallet.setKeyReference(FakeKmsSigningClient.KEY);
        when(walletRepository.findById(id)).thenReturn(Optional.of(kmsWallet));

        assertThatThrownBy(() -> service.exportRaw(id, actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("non-exportable");
        assertThatThrownBy(() -> service.exportKeystore(id, "pw", actorId, "REGISTRY_ADMIN"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("non-exportable");
        assertThat(service.rotateKek(id, actorId, "REGISTRY_ADMIN")).isFalse();
        verifyNoInteractions(walletStorage);
    }
}
