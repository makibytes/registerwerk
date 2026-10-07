package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.customer.api.EntityStatus;
import de.makibytes.registerwerk.customer.api.EntityType;
import de.makibytes.registerwerk.customer.api.KycStatus;
import de.makibytes.registerwerk.customer.api.LegalEntity;
import de.makibytes.registerwerk.customer.api.LegalEntityRepository;
import de.makibytes.registerwerk.indexer.api.DemoNomineePoolEntity;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApproval;
import de.makibytes.registerwerk.kyc.api.KycJurisdictionApprovalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DemoNomineePoolSeeder")
class DemoNomineePoolSeederTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final KycJurisdictionApprovalRepository approvals = mock(KycJurisdictionApprovalRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DemoNomineePoolEntity demoEntity = new DemoNomineePoolEntity();
    private final DemoNomineePoolSeeder seeder = new DemoNomineePoolSeeder(entities, approvals, users, demoEntity);

    @Test
    @DisplayName("creates an ACTIVE, KYC-approved nominee-pool entity with a DE_EWPG approval and publishes its id")
    void createsEntityAndPublishesId() throws Exception {
        UUID id = UUID.randomUUID();
        when(entities.findByEntityNumber(DemoNomineePoolSeeder.ENTITY_NUMBER)).thenReturn(Optional.empty());
        when(entities.save(any(LegalEntity.class))).thenAnswer(inv -> {
            LegalEntity e = inv.getArgument(0);
            e.setId(id);
            return e;
        });
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<LegalEntity> saved = ArgumentCaptor.forClass(LegalEntity.class);
        verify(entities).save(saved.capture());
        assertThat(saved.getValue().getEntityNumber()).isEqualTo("DEMO-NP-001");
        assertThat(saved.getValue().getType()).isEqualTo(EntityType.INVESTOR);
        assertThat(saved.getValue().getStatus()).isEqualTo(EntityStatus.ACTIVE);
        assertThat(saved.getValue().getKycStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(saved.getValue().getKycExpiryDate()).isAfter(java.time.LocalDate.now());
        ArgumentCaptor<KycJurisdictionApproval> approval = ArgumentCaptor.forClass(KycJurisdictionApproval.class);
        verify(approvals).save(approval.capture());
        assertThat(approval.getValue().getEntityId()).isEqualTo(id);
        assertThat(approval.getValue().getStatus()).isEqualTo(KycJurisdictionApproval.Status.APPROVED);
        assertThat(demoEntity.get()).contains(id);
    }

    @Test
    @DisplayName("is idempotent: an existing entity is reused untouched and its id is published again")
    void reusesExistingEntity() throws Exception {
        UUID id = UUID.randomUUID();
        LegalEntity existing = new LegalEntity();
        existing.setId(id);
        when(entities.findByEntityNumber("DEMO-NP-001")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        verify(entities, never()).save(any());
        verify(approvals, never()).save(any());
        assertThat(demoEntity.get()).contains(id);
    }
}
