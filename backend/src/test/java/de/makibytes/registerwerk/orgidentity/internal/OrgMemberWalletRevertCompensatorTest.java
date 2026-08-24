package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.MemberWalletStatus;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWallet;
import de.makibytes.registerwerk.orgidentity.api.OrgMemberWalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrgMemberWalletRevertCompensator — INVERSE_FLIP compensator for MEMBER_WALLET_BOUND")
class OrgMemberWalletRevertCompensatorTest {

    @Mock private OrgMemberWalletRepository repository;

    private OrgMemberWalletRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OrgMemberWalletRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "MEMBER_WALLET_BOUND", "OrgMemberWallet", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("MEMBER_WALLET_BOUND");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsActiveWallet() {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setStatus(MemberWalletStatus.ACTIVE);
        wallet.setChainConfigId(chainConfigId);
        wallet.setBoundTx("0xtxhash");
        wallet.setBoundBlockNumber(100L);
        wallet.setBoundBlockHash("0xhash");
        when(repository.findById(id)).thenReturn(Optional.of(wallet));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(wallet);
        assertThat(wallet.getStatus()).isEqualTo(MemberWalletStatus.PENDING);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nonActiveWalletIsNotApplicable() {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setStatus(MemberWalletStatus.REMOVED);
        wallet.setChainConfigId(chainConfigId);
        wallet.setBoundTx("0xtxhash");
        wallet.setBoundBlockNumber(100L);
        wallet.setBoundBlockHash("0xhash");
        wallet.setRemovedTx("0xremove");
        wallet.setRemovedChainConfigId(chainConfigId);
        wallet.setRemovedBlockNumber(101L);
        wallet.setRemovedBlockHash("0xremoveblock");
        when(repository.findById(id)).thenReturn(Optional.of(wallet));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void removalThenBindingLifoClearsBindingButPreservesPendingRemovalIntent() {
        OrgMemberWallet wallet = new OrgMemberWallet();
        wallet.setStatus(MemberWalletStatus.REMOVED);
        wallet.setChainConfigId(chainConfigId);
        wallet.setBoundTx("0xtxhash");
        wallet.setBoundBlockNumber(100L);
        wallet.setBoundBlockHash("0xhash");
        wallet.setRemovedTx("0xremove");
        wallet.setRemovedChainConfigId(chainConfigId);
        wallet.setRemovedBlockNumber(101L);
        wallet.setRemovedBlockHash("0xremoveblock");
        Instant removalRequestedAt = Instant.parse("2026-08-23T10:15:30Z");
        wallet.setRemovedAt(removalRequestedAt);
        when(repository.findById(id)).thenReturn(Optional.of(wallet));

        ChainEffectRecord removalEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xremoveblock", "0xremove", null,
                "orgidentity", MemberWalletRemovalRevertCompensator.EFFECT_TYPE,
                "OrgMemberWallet", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome removalOutcome =
                new MemberWalletRemovalRevertCompensator(repository).compensate(removalEffect);
        CompensationOutcome bindingOutcome = compensator.compensate(effect());

        assertThat(removalOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(bindingOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(wallet.getStatus()).isEqualTo(MemberWalletStatus.REMOVAL_PENDING);
        assertThat(wallet.getBoundTx()).isEqualTo("0xtxhash");
        assertThat(wallet.getBoundBlockNumber()).isNull();
        assertThat(wallet.getBoundBlockHash()).isNull();
        assertThat(wallet.getRemovedAt()).isEqualTo(removalRequestedAt);
        assertThat(wallet.getRemovedTx()).isEqualTo("0xremove");
        assertThat(wallet.getRemovedChainConfigId()).isNull();
        assertThat(wallet.getRemovedBlockNumber()).isNull();
        assertThat(wallet.getRemovedBlockHash()).isNull();
    }

    @Test
    void replacementBindingThenPredecessorRemovalLifoKeepsBothLifecycleGenerationsPending() {
        UUID predecessorId = UUID.randomUUID();
        String walletAddress = "0xsamewallet";

        OrgMemberWallet replacement = new OrgMemberWallet();
        replacement.setStatus(MemberWalletStatus.ACTIVE);
        replacement.setChainConfigId(chainConfigId);
        replacement.setWalletAddress(walletAddress);
        replacement.setBoundTx("0xrebind");
        replacement.setBoundBlockNumber(102L);
        replacement.setBoundBlockHash("0xrebindblock");

        OrgMemberWallet predecessor = new OrgMemberWallet();
        predecessor.setStatus(MemberWalletStatus.REMOVED);
        predecessor.setChainConfigId(chainConfigId);
        predecessor.setWalletAddress(walletAddress);
        predecessor.setRemovedAt(Instant.parse("2026-08-23T10:15:30Z"));
        predecessor.setRemovedTx("0xremove");
        predecessor.setRemovedChainConfigId(chainConfigId);
        predecessor.setRemovedBlockNumber(101L);
        predecessor.setRemovedBlockHash("0xremoveblock");

        when(repository.findById(id)).thenReturn(Optional.of(replacement));
        when(repository.findById(predecessorId)).thenReturn(Optional.of(predecessor));

        ChainEffectRecord replacementEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 102L, "0xrebindblock", "0xrebind", null,
                "orgidentity", OrgMemberWalletRevertCompensator.EFFECT_TYPE,
                "OrgMemberWallet", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
        ChainEffectRecord removalEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xremoveblock", "0xremove", null,
                "orgidentity", MemberWalletRemovalRevertCompensator.EFFECT_TYPE,
                "OrgMemberWallet", predecessorId, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome replacementOutcome = compensator.compensate(replacementEffect);
        CompensationOutcome removalOutcome =
                new MemberWalletRemovalRevertCompensator(repository).compensate(removalEffect);

        assertThat(replacementOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(removalOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(replacement.getStatus()).isEqualTo(MemberWalletStatus.PENDING);
        assertThat(predecessor.getStatus()).isEqualTo(MemberWalletStatus.REMOVAL_PENDING);
        assertThat(replacement.getWalletAddress()).isEqualTo(predecessor.getWalletAddress());
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
