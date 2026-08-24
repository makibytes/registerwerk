package de.makibytes.registerwerk.wallet.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import de.makibytes.registerwerk.wallet.api.OperatorWallet.WalletType;
import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletChainDefault;
import de.makibytes.registerwerk.wallet.api.WalletChainDefaultRepository;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.wallet.events.WalletDefaultChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WalletDefaultService}  — previously untested,
 * including the actor-identity and dual-control-approver threading
 * added to {@code setDefault} this phase.
 */
@ExtendWith(MockitoExtension.class)
class WalletDefaultServiceTest {

    @Mock private WalletChainDefaultRepository defaultRepository;
    @Mock private OperatorWalletRepository walletRepository;
    @Mock private ChainConfigRepository chainConfigRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WalletSigner walletSigner;

    private WalletDefaultService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WalletDefaultService(defaultRepository, walletRepository, chainConfigRepository,
                eventPublisher, walletSigner);
        lenient().when(defaultRepository.save(any(WalletChainDefault.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ChainConfig chain(UUID id, ChainConfig.ChainType type) {
        ChainConfig c = new ChainConfig();
        ReflectionTestUtils.setField(c, "id", id);
        c.setChainType(type);
        return c;
    }

    private static OperatorWallet wallet(UUID id, WalletType type) {
        OperatorWallet w = new OperatorWallet();
        ReflectionTestUtils.setField(w, "id", id);
        w.setName("test-wallet");
        w.setType(type);
        return w;
    }

    @Test
    @DisplayName("setDefault rejects a wallet/chain type mismatch (EVM chain, Solana wallet)")
    void setDefault_rejectsTypeMismatch() {
        UUID chainId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain(chainId, ChainConfig.ChainType.EVM)));
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet(walletId, WalletType.SOLANA)));

        assertThatThrownBy(() -> service.setDefault(chainId, walletId, actorId, "REGISTRY_ADMIN", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type mismatch");
    }

    @Test
    @DisplayName("setDefault saves the new default and publishes an event carrying the actor and dual-control approver")
    void setDefault_publishesEventWithActorAndApprover() {
        UUID chainId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain(chainId, ChainConfig.ChainType.EVM)));
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet(walletId, WalletType.EVM)));
        when(defaultRepository.findByChainConfigId(chainId)).thenReturn(Optional.empty());

        WalletChainDefault result = service.setDefault(chainId, walletId, actorId, "REGISTRY_ADMIN", approverId);

        assertThat(result.getWallet().getId()).isEqualTo(walletId);
        ArgumentCaptor<WalletDefaultChangedEvent> captor = ArgumentCaptor.forClass(WalletDefaultChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().actorId()).isEqualTo(actorId);
        assertThat(captor.getValue().dualControlApproverId()).isEqualTo(approverId);
        assertThat(captor.getValue().chainConfigId()).isEqualTo(chainId);
    }

    @Test
    @DisplayName("setDefault evicts the signer cache for the previous default wallet when it changes")
    void setDefault_evictsPreviousWalletFromSignerCache() {
        UUID chainId = UUID.randomUUID();
        UUID oldWalletId = UUID.randomUUID();
        UUID newWalletId = UUID.randomUUID();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain(chainId, ChainConfig.ChainType.EVM)));
        when(walletRepository.findById(newWalletId)).thenReturn(Optional.of(wallet(newWalletId, WalletType.EVM)));

        WalletChainDefault existing = new WalletChainDefault();
        existing.setChainConfigId(chainId);
        existing.setWallet(wallet(oldWalletId, WalletType.EVM));
        when(defaultRepository.findByChainConfigId(chainId)).thenReturn(Optional.of(existing));

        service.setDefault(chainId, newWalletId, actorId, "REGISTRY_ADMIN", null);

        verify(walletSigner).evict(oldWalletId);
    }

    @Test
    @DisplayName("setDefault does not evict the signer cache when the default wallet is unchanged")
    void setDefault_doesNotEvictWhenWalletUnchanged() {
        UUID chainId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        when(chainConfigRepository.findById(chainId)).thenReturn(Optional.of(chain(chainId, ChainConfig.ChainType.EVM)));
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet(walletId, WalletType.EVM)));

        WalletChainDefault existing = new WalletChainDefault();
        existing.setChainConfigId(chainId);
        existing.setWallet(wallet(walletId, WalletType.EVM));
        when(defaultRepository.findByChainConfigId(chainId)).thenReturn(Optional.of(existing));

        service.setDefault(chainId, walletId, actorId, "REGISTRY_ADMIN", null);

        verify(walletSigner, never()).evict(any());
    }

    @Test
    @DisplayName("autoPromoteIfFirstOfType promotes the wallet only for chains with no existing default")
    void autoPromote_onlyForChainsWithoutADefault() {
        UUID chainWithDefault = UUID.randomUUID();
        UUID chainWithoutDefault = UUID.randomUUID();
        OperatorWallet newWallet = wallet(UUID.randomUUID(), WalletType.EVM);
        when(chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.EVM))
                .thenReturn(java.util.List.of(chain(chainWithDefault, ChainConfig.ChainType.EVM),
                        chain(chainWithoutDefault, ChainConfig.ChainType.EVM)));
        when(defaultRepository.findByChainConfigId(chainWithDefault)).thenReturn(Optional.of(new WalletChainDefault()));
        when(defaultRepository.findByChainConfigId(chainWithoutDefault)).thenReturn(Optional.empty());

        service.autoPromoteIfFirstOfType(newWallet);

        ArgumentCaptor<WalletChainDefault> captor = ArgumentCaptor.forClass(WalletChainDefault.class);
        verify(defaultRepository).save(captor.capture());
        assertThat(captor.getValue().getChainConfigId()).isEqualTo(chainWithoutDefault);
    }

    @Test
    @DisplayName("removeDefaultsForWallet deletes all defaults and evicts the signer cache")
    void removeDefaultsForWallet_deletesAndEvicts() {
        UUID walletId = UUID.randomUUID();
        WalletChainDefault d1 = new WalletChainDefault();
        when(defaultRepository.findByWallet_Id(walletId)).thenReturn(java.util.List.of(d1));

        service.removeDefaultsForWallet(walletId);

        verify(defaultRepository).deleteAll(java.util.List.of(d1));
        verify(walletSigner).evict(walletId);
    }

    @Test
    @DisplayName("removeDefaultsForWallet is a no-op when the wallet has no defaults")
    void removeDefaultsForWallet_noOpWhenNoneExist() {
        UUID walletId = UUID.randomUUID();
        when(defaultRepository.findByWallet_Id(walletId)).thenReturn(java.util.List.of());

        service.removeDefaultsForWallet(walletId);

        verify(defaultRepository, never()).deleteAll(any());
        verify(walletSigner, never()).evict(any());
    }
}
