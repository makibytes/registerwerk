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
    private final UUID chainConfigId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compensator = new OrgRegistrationRevertCompensator(repository);
    }

    private ChainEffectRecord effect() {
        return new ChainEffectRecord(UUID.randomUUID(), chainConfigId, 100L, "0xhash", "0xtxhash", null,
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
        registration.setChainConfigId(chainConfigId);
        registration.setRegisteredTx("0xtxhash");
        registration.setConfirmedBlockNumber(100L);
        registration.setConfirmedBlockHash("0xhash");
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
        registration.setChainConfigId(chainConfigId);
        registration.setRegisteredTx("0xtxhash");
        registration.setConfirmedBlockNumber(100L);
        registration.setConfirmedBlockHash("0xhash");
        registration.setStatusTx("0xsuspend");
        registration.setStatusChainConfigId(chainConfigId);
        registration.setStatusBlockNumber(101L);
        registration.setStatusBlockHash("0xsuspendblock");
        registration.setStatusRequestedAt(Instant.parse("2026-08-23T10:15:30Z"));
        when(repository.findById(id)).thenReturn(Optional.of(registration));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void malformedPendingTransitionCannotClaimLaterIntentOwnership() {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(OrgRegistrationStatus.SUSPEND_PENDING);
        registration.setChainConfigId(chainConfigId);
        registration.setRegisteredTx("0xtxhash");
        registration.setConfirmedBlockNumber(100L);
        registration.setConfirmedBlockHash("0xhash");
        registration.setStatusTx("0xsuspend");
        // statusRequestedAt is deliberately absent: this is not a complete durable intent.
        when(repository.findById(id)).thenReturn(Optional.of(registration));

        CompensationOutcome outcome = compensator.compensate(effect());

        verify(repository, never()).save(any());
        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }

    @Test
    void suspensionThenRegistrationLifoClearsRegistrationButPreservesPendingSuspensionIntent() {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(OrgRegistrationStatus.SUSPENDED);
        registration.setChainConfigId(chainConfigId);
        registration.setRegisteredTx("0xtxhash");
        registration.setConfirmedBlockNumber(100L);
        registration.setConfirmedBlockHash("0xhash");
        registration.setStatusTx("0xsuspend");
        registration.setStatusChainConfigId(chainConfigId);
        registration.setStatusBlockNumber(101L);
        registration.setStatusBlockHash("0xsuspendblock");
        registration.setStatusRequestedAt(Instant.parse("2026-08-23T10:15:30Z"));
        when(repository.findById(id)).thenReturn(Optional.of(registration));

        ChainEffectRecord suspensionEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xsuspendblock", "0xsuspend", null,
                "orgidentity", OrgSuspensionRevertCompensator.EFFECT_TYPE, "OrgRegistration", id,
                null, CompensationCategory.INVERSE_FLIP, null, null, null, null,
                "COMPENSATING", 1, Instant.now());

        CompensationOutcome suspensionOutcome =
                new OrgSuspensionRevertCompensator(repository).compensate(suspensionEffect);
        CompensationOutcome registrationOutcome = compensator.compensate(effect());

        assertThat(suspensionOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registrationOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.SUSPEND_PENDING);
        assertThat(registration.getRegisteredTx()).isEqualTo("0xtxhash");
        assertThat(registration.getConfirmedBlockNumber()).isNull();
        assertThat(registration.getConfirmedBlockHash()).isNull();
        assertThat(registration.getStatusTx()).isEqualTo("0xsuspend");
        assertThat(registration.getStatusChainConfigId()).isNull();
        assertThat(registration.getStatusBlockNumber()).isNull();
        assertThat(registration.getStatusBlockHash()).isNull();
    }

    @Test
    void reinstatementSuspensionRegistrationLifoPreservesLatestIntentAndClearsBaseConfirmation() {
        OrgRegistration registration = new OrgRegistration();
        registration.setStatus(OrgRegistrationStatus.ACTIVE);
        registration.setChainConfigId(chainConfigId);
        registration.setRegisteredTx("0xtxhash");
        registration.setConfirmedBlockNumber(100L);
        registration.setConfirmedBlockHash("0xhash");
        registration.setStatusTx("0xreinstate");
        registration.setStatusChainConfigId(chainConfigId);
        registration.setStatusBlockNumber(102L);
        registration.setStatusBlockHash("0xreinstateblock");
        Instant reinstatementRequestedAt = Instant.parse("2026-08-23T10:16:30Z");
        registration.setStatusRequestedAt(reinstatementRequestedAt);
        when(repository.findById(id)).thenReturn(Optional.of(registration));

        ChainEffectRecord reinstatementEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 102L, "0xreinstateblock", "0xreinstate", null,
                "orgidentity", OrgReinstatementRevertCompensator.EFFECT_TYPE,
                "OrgRegistration", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());
        ChainEffectRecord suspensionEffect = new ChainEffectRecord(
                UUID.randomUUID(), chainConfigId, 101L, "0xsuspendblock", "0xsuspend", null,
                "orgidentity", OrgSuspensionRevertCompensator.EFFECT_TYPE,
                "OrgRegistration", id, null, CompensationCategory.INVERSE_FLIP,
                null, null, null, null, "COMPENSATING", 1, Instant.now());

        CompensationOutcome reinstatementOutcome =
                new OrgReinstatementRevertCompensator(repository).compensate(reinstatementEffect);
        CompensationOutcome suspensionOutcome =
                new OrgSuspensionRevertCompensator(repository).compensate(suspensionEffect);
        CompensationOutcome registrationOutcome = compensator.compensate(effect());

        assertThat(reinstatementOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(suspensionOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registrationOutcome).isInstanceOf(CompensationOutcome.Compensated.class);
        assertThat(registration.getStatus()).isEqualTo(OrgRegistrationStatus.REINSTATE_PENDING);
        assertThat(registration.getRegisteredTx()).isEqualTo("0xtxhash");
        assertThat(registration.getConfirmedBlockNumber()).isNull();
        assertThat(registration.getConfirmedBlockHash()).isNull();
        assertThat(registration.getStatusTx()).isEqualTo("0xreinstate");
        assertThat(registration.getStatusRequestedAt()).isEqualTo(reinstatementRequestedAt);
        assertThat(registration.getStatusChainConfigId()).isNull();
        assertThat(registration.getStatusBlockNumber()).isNull();
        assertThat(registration.getStatusBlockHash()).isNull();
    }

    @Test
    void missingRowIsNotApplicable() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(compensator.compensate(effect())).isInstanceOf(CompensationOutcome.NotApplicable.class);
    }
}
