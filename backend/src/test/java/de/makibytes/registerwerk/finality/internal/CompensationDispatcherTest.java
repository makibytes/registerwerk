package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainEffectCompensator;
import de.makibytes.registerwerk.finality.api.ChainEffectRecord;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.finality.api.CompensationOutcome;
import de.makibytes.registerwerk.finality.events.ChainEffectCompensatedEvent;
import de.makibytes.registerwerk.finality.events.CompensationEscalatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensationDispatcher — claim/compensate/resolve unit tests")
class CompensationDispatcherTest {

    @Mock private ChainEffectRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final UUID effectId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();

    private ChainEffect row(ChainEffect.Status status) {
        ChainEffect row = new ChainEffect();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", effectId);
        row.setChainConfigId(UUID.randomUUID());
        row.setBlockNumber(100L);
        row.setModuleName("indexer");
        row.setEffectType("HOLDER_BALANCE_SYNCED");
        row.setEntityType("Asset");
        row.setEntityId(entityId);
        row.setCategory(CompensationCategory.RECOMPUTE);
        row.setStatus(status);
        row.setAttemptCount(0);
        row.setRecordedAt(Instant.now());
        return row;
    }

    private CompensationDispatcher dispatcherWith(ChainEffectCompensator... compensators) {
        return new CompensationDispatcher(repository, List.of(compensators), eventPublisher, new SimpleMeterRegistry());
    }

    private ChainEffectCompensator compensatorReturning(CompensationOutcome outcome) {
        return new ChainEffectCompensator() {
            public String effectType() { return "HOLDER_BALANCE_SYNCED"; }
            public CompensationCategory category() { return CompensationCategory.RECOMPUTE; }
            public CompensationOutcome compensate(ChainEffectRecord effect) { return outcome; }
        };
    }

    @Test
    @DisplayName("a successful compensation marks the row COMPENSATED and publishes ChainEffectCompensatedEvent")
    void successfulCompensationMarksCompensatedAndPublishes() {
        when(repository.claimForCompensation(effectId)).thenReturn(1);
        when(repository.findById(effectId)).thenReturn(Optional.of(row(ChainEffect.Status.ACTIVE)));
        CompensationDispatcher dispatcher = dispatcherWith(compensatorReturning(new CompensationOutcome.Compensated("done")));

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Compensated.class);
        ArgumentCaptor<ChainEffectCompensatedEvent> captor = ArgumentCaptor.forClass(ChainEffectCompensatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().chainEffectId()).isEqualTo(effectId);
        assertThat(captor.getValue().entityId()).isEqualTo(entityId);
    }

    @Test
    @DisplayName("a row that fails to claim (already resolved / in progress) returns NotApplicable and touches nothing else")
    void unclaimableRowIsNotApplicable() {
        when(repository.claimForCompensation(effectId)).thenReturn(0);
        CompensationDispatcher dispatcher = dispatcherWith(compensatorReturning(new CompensationOutcome.Compensated("done")));

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.NotApplicable.class);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("no registered compensator for the effect type escalates as NO_COMPENSATOR")
    void noCompensatorEscalates() {
        when(repository.claimForCompensation(effectId)).thenReturn(1);
        when(repository.findById(effectId)).thenReturn(Optional.of(row(ChainEffect.Status.ACTIVE)));
        CompensationDispatcher dispatcher = dispatcherWith(); // zero compensators registered

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
        ArgumentCaptor<CompensationEscalatedEvent> captor = ArgumentCaptor.forClass(CompensationEscalatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CompensationEscalatedEvent.Reason.NO_COMPENSATOR);
    }

    @Test
    @DisplayName("a compensator returning Failed marks COMPENSATION_FAILED and escalates as FAILED")
    void failedCompensatorEscalates() {
        when(repository.claimForCompensation(effectId)).thenReturn(1);
        when(repository.findById(effectId)).thenReturn(Optional.of(row(ChainEffect.Status.ACTIVE)));
        CompensationDispatcher dispatcher = dispatcherWith(
                compensatorReturning(new CompensationOutcome.Failed("boom", null)));

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
        ArgumentCaptor<CompensationEscalatedEvent> captor = ArgumentCaptor.forClass(CompensationEscalatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CompensationEscalatedEvent.Reason.FAILED);
    }

    @Test
    @DisplayName("a compensator that throws is treated as Failed, not propagated")
    void throwingCompensatorIsTreatedAsFailed() {
        when(repository.claimForCompensation(effectId)).thenReturn(1);
        when(repository.findById(effectId)).thenReturn(Optional.of(row(ChainEffect.Status.ACTIVE)));
        ChainEffectCompensator throwing = new ChainEffectCompensator() {
            public String effectType() { return "HOLDER_BALANCE_SYNCED"; }
            public CompensationCategory category() { return CompensationCategory.RECOMPUTE; }
            public CompensationOutcome compensate(ChainEffectRecord effect) { throw new RuntimeException("kaboom"); }
        };
        CompensationDispatcher dispatcher = dispatcherWith(throwing);

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Failed.class);
        verify(eventPublisher).publishEvent(any(CompensationEscalatedEvent.class));
    }

    @Test
    @DisplayName("a compensator returning Irreversible marks IRREVERSIBLE_ESCALATED and escalates as IRREVERSIBLE")
    void irreversibleCompensatorEscalates() {
        when(repository.claimForCompensation(effectId)).thenReturn(1);
        when(repository.findById(effectId)).thenReturn(Optional.of(row(ChainEffect.Status.ACTIVE)));
        CompensationDispatcher dispatcher = dispatcherWith(
                compensatorReturning(new CompensationOutcome.Irreversible("issue a corrective statement")));

        CompensationOutcome outcome = dispatcher.compensate(effectId);

        assertThat(outcome).isInstanceOf(CompensationOutcome.Irreversible.class);
        ArgumentCaptor<CompensationEscalatedEvent> captor = ArgumentCaptor.forClass(CompensationEscalatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(CompensationEscalatedEvent.Reason.IRREVERSIBLE);
    }

    @Test
    @DisplayName("two compensators registered for the same effectType fail fast at construction")
    void duplicateEffectTypeCompensatorsRejectedAtConstruction() {
        ChainEffectCompensator a = compensatorReturning(new CompensationOutcome.Compensated("a"));
        ChainEffectCompensator b = compensatorReturning(new CompensationOutcome.Compensated("b"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcherWith(a, b))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("startup self-check logs (does not throw) when an ACTIVE effect type has no compensator")
    void selfCheckDoesNotThrowOnGap() {
        when(repository.findDistinctActiveEffectTypes()).thenReturn(List.of("SOME_UNCOMPENSATED_TYPE"));
        CompensationDispatcher dispatcher = dispatcherWith();

        org.assertj.core.api.Assertions.assertThatCode(dispatcher::verifyEveryActiveEffectTypeHasACompensator)
                .doesNotThrowAnyException();
    }
}
