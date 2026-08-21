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

    @BeforeEach
    void setUp() {
        compensator = new OrgMemberWalletRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "MEMBER_WALLET_BOUND", "OrgMemberWallet", id, CompensationCategory.INVERSE_FLIP,
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
        when(repository.findById(id)).thenReturn(Optional.of(wallet));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
