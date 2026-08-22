package de.makibytes.registerwerk.orgidentity.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistration;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationRepository;
import de.makibytes.registerwerk.orgidentity.api.OrgRegistrationStatus;
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
@DisplayName("OrgRegistrationRevertCompensator — INVERSE_FLIP compensator for ORG_REGISTRATION_CONFIRMED")
class OrgRegistrationRevertCompensatorTest {

    @Mock private OrgRegistrationRepository repository;

    private OrgRegistrationRevertCompensator compensator;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OrgRegistrationRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), UUID.randomUUID(), 100L, "0xhash", "0xtxhash", null,
                "orgidentity", "ORG_REGISTRATION_CONFIRMED", "OrgRegistration", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
    }

    @Test
    void advertisesIdentity() {
        assertThat(compensator.effectType()).isEqualTo("ORG_REGISTRATION_CONFIRMED");
        assertThat(compensator.category()).isEqualTo(CompensationCategory.INVERSE_FLIP);
    }

    @Test
    void compensateRevertsActiveRegistration() {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(OrgRegistrationStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(registration));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository).save(registration);
        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.PENDING);
        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
    }

    @Test
    void nonActiveRegistrationIsNotApplicable() {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(OrgRegistrationStatus.SUSPENDED);
        when(repository.findById(id)).thenReturn(Optional.of(registration));

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
